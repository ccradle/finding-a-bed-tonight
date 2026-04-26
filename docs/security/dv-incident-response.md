# DV Incident Response Runbook (G-4.5 §6.9)

**Scope:** Operator triage when the
`FabtDvReferralBurstFromSingleIp` Prometheus alert fires (rule file
`deploy/prometheus/dv-defenses.rules.yml`), or when a coordinator
reports DV referral abuse out-of-band.

**Audience:** Operator with read access to the production database via
the `fabt` (owner) role and shell access to the Oracle VM.

**Companion alerts:** None of the v0.53 DV-defense layers are
authoritative on their own. Each is documented in this runbook because
each has a different evidence surface and a different response.

---

## Defense layers (what fired and why)

```
   POST /api/v1/dv-referrals
        │
        ▼
   ┌─────────────────────────────────────────────────────────┐
   │ Layer 1 — DvReferralCrossSiteFilter (G-4.5 §6.11)       │
   │ Rejects 403 on Sec-Fetch-Site=cross-site                │
   │ Cost-raiser only; trivially bypassed by non-browser     │
   │ clients. Evidence: WARN log line in backend.log carrying │
   │ source ip + Origin + Referer.                           │
   └─────────────────────────────────────────────────────────┘
        │ pass
        ▼
   ┌─────────────────────────────────────────────────────────┐
   │ Layer 2 — bucket4j rate-limit-dv-referral-create        │
   │ 5 creates per source_ip per hour (G-4.5 §6.7)           │
   │ Returns 429 once exceeded; logged by RateLimitLogging-  │
   │ Filter at WARN with source ip, method, path.            │
   └─────────────────────────────────────────────────────────┘
        │ pass
        ▼
   ┌─────────────────────────────────────────────────────────┐
   │ Layer 3 — ReferralTokenService.createToken              │
   │ Creates token; per-account-per-shelter dedupe blocks    │
   │ retries with same shelter_id. Counter                   │
   │ fabt_dv_referrals_created_total{source_ip} increments.  │
   │ DV_REFERRAL_REQUESTED audit row inserted.               │
   └─────────────────────────────────────────────────────────┘
        │ sustained burst breaks throttle
        ▼
   ┌─────────────────────────────────────────────────────────┐
   │ Layer 4 — Prometheus alert (G-4.5 §6.8)                 │
   │ FabtDvReferralBurstFromSingleIp fires when a single     │
   │ source_ip exceeds 10 creates/min over 2 minutes.        │
   │ Routes to operator via Alertmanager (severity=warning). │
   └─────────────────────────────────────────────────────────┘
```

---

## Triage flow when `FabtDvReferralBurstFromSingleIp` fires

The alert annotation carries the offending `source_ip`. Work the
evidence layers in order: nginx access log → bucket4j WARN lines →
audit_events → tenant context.

### Step 1 — Confirm the alert

The alert evaluates over a 2-minute window with a `for: 2m` hold; it
will not fire on a single-spike. Re-check Prometheus to make sure the
rate is still elevated, not just a stale series:

```promql
sum by (source_ip) (rate(fabt_dv_referrals_created_total[2m])) * 60
```

If the value has fallen back below 10/min, the burst already ended.
Continue to evidence collection so the audit trail is captured even
when the live rate has subsided.

### Step 2 — Pull the nginx access log slice

The source IP from the alert label maps to the value nginx logs as
`$remote_addr`. Pull the last 5 minutes of POST traffic from that IP:

```bash
ssh fabt-prod
sudo journalctl -u nginx --since '5 minutes ago' \
    | grep -E "POST /api/v1/dv-referrals" \
    | grep '<source_ip>'
```

Replace `<source_ip>` with the alert label literal. Look for:
- High request rate (consistent with the 10/min trigger),
- Mixed POST + GET traffic (suggests a logged-in session not just
  abuse-script POSTs),
- User-Agent string (a single value across all POSTs is a script
  fingerprint; rotating UAs suggest browser-driven abuse).

### Step 3 — Pull recent DV referral audit rows

Connect with the `fabt` owner role (NOT `fabt_app`) so RLS does not
hide DV-tenant rows. The owner bypass is documented in
`feedback_rls_hides_dv_data.md`.

```bash
PGPASSWORD=<fabt-owner-password> psql -h localhost -U fabt -d fabt
```

Then in psql:

```sql
-- Recent DV referral creates with the firing source_ip.
-- audit_events has FORCE RLS; SET LOCAL inside a tx for the duration
-- of the lookup. tenant_id is still scoped per row, so you'll see
-- whichever tenants the burst targeted.
BEGIN;
SET LOCAL app.dv_access = 'true';

SELECT
    created_at,
    tenant_id,
    actor_user_id,
    details->>'shelter_id'  AS shelter_id,
    details->>'shelter_name' AS shelter_name,
    details->>'urgency'      AS urgency
FROM audit_events
WHERE event_type = 'DV_REFERRAL_REQUESTED'
  AND created_at > NOW() - INTERVAL '15 minutes'
ORDER BY created_at DESC;

COMMIT;
```

Cross-reference the source IP from the nginx log against the
`actor_user_id` column to find which authenticated user filed the
referrals. (The current `audit_events` schema does not store the
source IP directly — that pivot lives in nginx logs.)

### Step 4 — Decide between the two failure modes

The rows you just pulled separate the two distinct attack shapes:

**(a) Same actor_user_id across all rows.** A single authenticated
worker is filing referrals at an anomalous rate. Treat this as a
**compromised account** until proven otherwise:
1. Force-revoke the user's sessions: bump `app_user.token_version`
   for that user (every existing JWT becomes invalid on next request).
2. Notify the tenant's CoC admin via the same channel as a normal
   account-compromise incident.
3. Escalate to the user's coordinator if the worker is on a managed
   account and a session reset is not enough.

**(b) Many distinct actor_user_id values, all targeting one tenant.**
A single source IP is driving multiple compromised accounts at the
same tenant. Treat as a **coordinated attack on the tenant**:
1. Apply nginx ACL deny on the source IP (manual edit to
   `/etc/nginx/conf.d/<config>.conf` `deny <ip>;` then `nginx -s
   reload`). Document the ACL change in the audit incident log.
2. Notify the tenant's CoC admin AND the platform operator.
3. Pull a 24-hour audit_events export for the affected tenant to
   confirm the burst was contained and no other event types were
   driven from the same IP.

### Step 5 — Record the incident

Append to the audit incident log file (currently maintained out-of-
band by the platform operator). Record:

- Alert name + firing time + source IP
- Step 4 classification (a / b)
- Action taken (token-version bump, nginx ACL, etc.)
- Confirmation timestamp the burst rate returned to baseline
- Any tenant CoC admin who was notified

---

## Tabletop exercise: synthetic burst (operator dry-run)

Run this exercise before any tenant goes live with active outreach
workers. Sample personas drawn from the war-room contributor pool
(Marcus Webb / Casey Drummond / Sam Okafor) — these are documentation
personas, not real stakeholders.

### Scenario

Marcus, the platform operator on call, receives the
`FabtDvReferralBurstFromSingleIp` alert at 14:32 local time. The label
shows `source_ip="203.0.113.42"`. Casey, the compliance reviewer, is
shadowing for the audit trail.

### Walkthrough

1. **14:32** — Alert fires. Marcus opens the Prometheus dashboard and
   confirms the rate is `12.4/min` sustained over the last two
   minutes. _Outcome:_ confirms not stale.
2. **14:33** — Marcus runs Step 2 (nginx log slice). The slice shows
   ~25 POSTs from `203.0.113.42` in the last 4 minutes, all with
   User-Agent `python-requests/2.31`. _Outcome:_ script signature, not
   browser.
3. **14:34** — Marcus runs Step 3 (audit query). 22 rows return, all
   with `actor_user_id = <single uuid>` and `tenant_id =
   <dev-coc-east>`. _Outcome:_ classification (a), single compromised
   account.
4. **14:35** — Marcus runs the token-version bump:
   ```sql
   UPDATE app_user
       SET token_version = token_version + 1
       WHERE id = '<the uuid>';
   ```
   The compromised session is revoked at the JWT layer; subsequent
   POSTs from `203.0.113.42` carrying the now-stale JWT return 401.
5. **14:36** — Marcus pages the tenant's CoC admin via the channel
   listed in the customer-comms registry. Shares the user_id and the
   approximate window of compromised activity.
6. **14:38** — Sam (out-of-band) reviews the
   `fabt_dv_referrals_created_total` graph in Grafana. The rate has
   fallen to zero. _Outcome:_ burst contained; no nginx ACL needed.
7. **14:40** — Casey appends to the audit incident log: alert name,
   firing time, source IP, classification, action taken, CoC admin
   notification timestamp. Marks incident closed pending the affected
   user's password reset on next login.

### What this exercise validates

- The Prometheus → Alertmanager → operator channel works end-to-end.
- The owner-role psql access path is current (passwords not rotated
  out, host reachable from operator workstation).
- The `app_user.token_version` bump revokes the session within seconds
  (next JWT validation rejects).
- The audit_events row carries enough fields (actor_user_id +
  tenant_id + shelter_id) to make the classification decision without
  pulling additional tables.

If any step fails (alert delivery, owner-role auth, JWT revocation
latency >5s), file a ticket BEFORE the first paying tenant goes live;
a real incident at scale is not the time to discover the runbook is
stale.
