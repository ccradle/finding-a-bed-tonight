# Platform Admin Monitoring (G-4.5 §6.19)

**Audience:** Operator / SOC engineer running observability for the
platform-operator surface. Documents what metrics + log markers v0.53
emits, what alerts they drive, and what Grafana panels are worth
building when an observability pass lands (Phase H+).

**Companion artifacts:**
- `deploy/prometheus/phase-g-platform-admin.rules.yml` — alert rules
- `docs/security/dv-incident-response.md` — DV-side runbook (parallel pattern)
- `openspec/changes/platform-admin-split-and-access-log/design.md` F28
  (deferred FabtPlatformUserDelayedActivation gauge)

---

## What v0.53 emits

### Counters (incremented at the platform-auth surface)

| Metric | Tags | Source | When it fires |
|---|---|---|---|
| `fabt.platform.login.failures` | `reason` ∈ {bad_email, locked, bad_password, mfa_disabled} | `PlatformAuthService.login` + `verifyMfa` | Any REJECTED login outcome. Rejected paths run a decoy bcrypt verify so timing is constant — but the counter carries the actual reason for forensic / alert use. |
| `fabt.platform.user.locked_out` | (none) | `PlatformAuthService.recordFailureAndMaybeLock` | Lockout TRANSITION only (not per failed MFA attempt). 5 fails / 15 min auto-locks per V88 policy. |
| `fabt.platform.action.without_justification` | `action` (the `AuditEventType` of the would-be platform action) | `PlatformAdminLogger.aroundPlatformAdminOnly` | Defense-in-depth: aspect saw a request without `X-Platform-Justification`. Should always be 0 — non-zero means `JustificationValidationFilter` is offline / mis-ordered. |
| `fabt.platform.admin.action` | `action`, `outcome` ∈ {committed, method_failed_after_audit, aspect_failed} | `PlatformAdminLogger.emitMetric` (G-4.3) | Per-action audit-write outcome; existing G-4.3 metric, listed here for completeness. |

### Counters added in v0.54 (F11 §5.1)

| Metric | Tags | Source | When it fires |
|---|---|---|---|
| `fabt.platform.mfa.verify` | `outcome` ∈ {success, failure} | `PlatformAuthService.verifyMfaWithState` | Every MFA-verify attempt: increments success on TOTP-or-backup-code accept; increments failure on any rejection (no enrollment, replay, bad TOTP, bad backup code, including the just-now-locked-out branch). Drives `FabtPlatformMfaFailureSpike` warning alert. |

### Log MDC marker

All log lines emitted during a `@PlatformAdminOnly` invocation carry
the SLF4J MDC entry `platform_action=true`. This is set at aspect
entry (`PlatformAdminLogger.aroundPlatformAdminOnly`) and removed in
the `finally` so child log statements within the wrapped business
method also inherit it.

The `PlatformAdminAccessLogger.logLockout` path (V88 lockout audit,
which fires from `PlatformAuthService` outside the aspect) sets the
same marker explicitly so SOC log filters catch the full
platform-action surface.

**SOC filter expression** (Loki / OpenSearch / etc.):
```
{platform_action="true"}
```

### Audit-events rows (forensics, not alerting)

Every `@PlatformAdminOnly` method commits one `audit_events` row +
one `platform_admin_access_log` row before the business method runs
(G-4.3 D5). These are NOT exposed as Prometheus metrics — they are
the queryable forensic surface. To pull them, use the owner-role
psql pattern from `docs/security/dv-incident-response.md` step 3
(set `app.tenant_id` to the SYSTEM tenant + `app.dv_access='true'`).

---

## Active alert rules

### `phase-g-platform-admin.rules.yml` (G-4.5)

| Alert | Severity | Threshold | Runbook anchor |
|---|---|---|---|
| `FabtPlatformLoginFailureBurst` | warning | rate > 5/min sustained 2 min | This file → "Login failure burst" |
| `FabtPlatformUserLockedOut` | info | rate > 0 over 5 min | This file → "Account lockout" |
| `FabtPlatformActionWithoutJustification` | critical | rate > 0 over 5 min | This file → "Filter chain broken" |

### `f11-platform-operator-ui.rules.yml` (F11 §5.4 v0.54)

| Alert | Severity | Threshold | Runbook anchor |
|---|---|---|---|
| `FabtPlatformMfaFailureSpike` | warning | mfa-verify failure rate > 1/min sustained 5 min | Below → "MFA failure spike" |
| `FabtPlatformBackend5xx` | critical | any 5xx on `/api/v1/auth/platform/*` over 2 min | Below → "Backend 5xx storm" |

F11 §5.4 originally listed a third alert `PlatformLockoutTriggered`
at severity=critical/page. The existing G-4.5 rule
`FabtPlatformUserLockedOut` (above) ALREADY fires on the same
counter at severity=info, with a documented rationale that a single
lockout is not page-grade (auto-clears via cron, manual unlock
available via `platform_user_reset_to_bootstrap`). F11 adopts the
existing rule rather than duplicating with critical severity. See
the comment block at the top of `f11-platform-operator-ui.rules.yml`
for the trade-off discussion.

The fourth spec'd alert — `FabtPlatformUserDelayedActivation` — is
deferred to F28 because it requires a new SECURITY DEFINER function
(`platform_user_count_unactivated_older_than(seconds)`) and a
scheduled gauge. The operational gap is partially covered by
`PlatformUser.isLoginAllowed()` enforcement (an unactivated user
cannot log in) and the `oracle-update-notes-v0.53.0.md §5.10`
activation checklist.

---

## Grafana panel ideas (Phase H+)

These are sketches for when an observability pass lands. Not built
in v0.53; the metrics are emitted so the panels can be added without
backend changes.

### Panel 1 — Platform login funnel
**Query stack** (single panel, multiple time-series):
```promql
# Successful logins (no per-success counter today; derive from
# failures + total — until then, just plot the failure rate)
sum by (reason) (rate(fabt_platform_login_failures_total[5m])) * 60
```
**Visualization:** stacked area chart, by `reason`. Heavy `bad_email`
is enumeration; heavy `bad_password` is brute force; heavy `locked`
is post-attack (someone is hitting locked accounts hoping for a
race-condition unlock).

**Future enhancement:** add `fabt.platform.login.success` counter so
the panel can plot conversion ratio.

### Panel 2 — Lockouts over time
```promql
sum(rate(fabt_platform_user_locked_out_total[1h])) * 3600
```
**Visualization:** single-series line chart with annotations for
incident-response timeline. Hover should show the timestamp of each
spike for cross-reference with `audit_events.timestamp` queries.

### Panel 3 — Platform action volume by operator action
```promql
sum by (action) (rate(fabt_platform_admin_action_total{outcome="committed"}[15m])) * 60
```
**Visualization:** stacked bar chart, grouped by `action`
(`PLATFORM_TENANT_UPDATED`, `PLATFORM_TENANT_OBSERVABILITY_UPDATED`,
`HMIS_EXPORT_TRIGGERED`, etc.). Useful baseline for "what does
normal platform-operator activity look like?" — answers questions
like "is anyone running tenant-update at 3am?"

### Panel 4 — Audit chain coverage
```promql
sum by (outcome) (rate(fabt_platform_admin_action_total[5m])) * 300
```
**Visualization:** four-line chart showing committed / method_failed_after_audit
/ aspect_failed counts. Healthy: only `committed` is non-zero. Either
of the failure modes warrants investigation (G-4.3 D5 contract: the
audit row commits BEFORE the business method runs, so a
`method_failed_after_audit` means the operator-facing 5xx came from
the business method, not the audit layer).

### Panel 5 — Defense-in-depth canary
```promql
sum by (action) (rate(fabt_platform_action_without_justification_total[1h])) * 3600
```
**Visualization:** single-series sparkline pinned at 0. Any non-zero
value means `JustificationValidationFilter` is offline — pair with
the `FabtPlatformActionWithoutJustification` critical alert.

### Panel 6a — Platform MFA-verify outcome rate (F11 §5.2)
```promql
sum by (outcome) (rate(fabt_platform_mfa_verify_total[5m])) * 60
```
**Visualization:** dual-line (success / failure) over a 24h window.
Sustained failure rate above 1/min triggers the new
`FabtPlatformMfaFailureSpike` warning alert (see
`f11-platform-operator-ui.rules.yml`). Healthy baseline: near-zero
failure with occasional success spikes when an operator logs in.

### Panel 6b — Platform-operator backend HTTP status (F11 §5.2)
```promql
sum by (uri, status) (
  rate(http_server_requests_seconds_count{
    uri=~"/api/v1/auth/platform/.*"
  }[5m])
) * 60
```
**Visualization:** stacked area chart by `(uri, status)`. The 5xx
band should be flat-zero in steady state; any non-zero band drives
the `FabtPlatformBackend5xx` critical alert. The 401 / 403 bands are
expected non-zero (auth-flow rejection signals) but should NOT
correlate with operator-reported login failures — if 4xx spikes
without operator reports, suspect a route mis-config or filter chain
issue.

### Panel 7 — DV-defense bridge (cross-reference)
```promql
sum by (source_ip) (rate(fabt_dv_referrals_created_total[5m])) * 60
```
Same data as the DV-defense panel in `dv-incident-response.md`,
pinned next to platform-admin panels because operators triaging
either kind of attack often want to see both surfaces at once
(coordinated attack on tenant data + platform credentials is the
"both layers compromised" scenario).

---

## Grafana dashboard JSON skeleton (when ready to build)

Save as `infra/grafana/dashboards/platform-admin.json`. Structure:
```json
{
  "title": "Platform Admin (G-4.5)",
  "tags": ["fabt", "platform", "auth", "g-4.5"],
  "panels": [
    { "title": "Login funnel by reason", "datasource": "Prometheus", "targets": [...] },
    { "title": "Lockouts (hourly)", ... },
    { "title": "Platform action volume", ... },
    { "title": "Audit chain coverage", ... },
    { "title": "Defense-in-depth canary", ... },
    { "title": "DV-defense bridge", ... }
  ],
  "time": { "from": "now-24h", "to": "now" },
  "refresh": "30s"
}
```

The skeleton is intentionally not committed yet — Grafana JSON is
verbose, version-controlled separately when we have a Grafana
instance to source-of-truth against. F11 panel additions (6a, 6b)
follow the same convention — the PromQL above is the source of
truth; a JSON commit will land alongside the first Grafana
provisioning pass.

---

## Runbook anchors — F11 alerts

### MFA failure spike (`FabtPlatformMfaFailureSpike`, warning)

**What it means:** Platform-operator MFA-verify failures running
above 1/min averaged over 5 min. Could be brute-force against an
operator whose password is already compromised (MFA is the last
gate), an operator cycling backup codes incorrectly during
lost-phone, or device-clock skew pushing TOTP windows out of sync.

**Triage:**
1. Pull operator's current backup-code count via /me or psql:
   ```sql
   SELECT id, email, backup_code_remaining_count FROM platform_user;
   ```
2. If the operator confirms lost-phone: walk them through backup-
   code use (8-char codes; one-shot; switching to backup mode in
   `/platform/mfa-verify` is the same form input).
3. If clock skew suspected: check NTP on operator's device. TOTP
   tolerates ±30s skew per `TotpService` config.
4. If brute-force suspected: rotate the operator's password
   out-of-band. Per existing `FabtPlatformUserLockedOut`, if the
   bucket fills, that info-level alert will fire separately and
   confirm.

### Backend 5xx storm (`FabtPlatformBackend5xx`, critical)

**What it means:** One or more `/api/v1/auth/platform/*` endpoints
returned a 5xx in the last 2 minutes. Platform operators are
typically 1-3 people company-wide and CANNOT self-recover from a
backend error — this is page-grade.

**Common causes:**
- (a) Postgres unreachable / connection pool exhausted. Check
  `fabt_db_connections_active` gauge.
- (b) Flyway migration in flight blocking a SECURITY DEFINER
  function. Check `/actuator/flyway`.
- (c) PlatformAuthService bean failed to wire (NPE on startup
  cascading to a 500). Check backend.log for "PlatformAuthService"
  + Spring Boot startup banner.

**Triage:**
1. Pull the affected URI:
   ```promql
   sum by (uri) (
     rate(http_server_requests_seconds_count{
       uri=~"/api/v1/auth/platform/.*",status=~"5.."
     }[5m])
   )
   ```
2. Pull the corresponding stack trace from backend.log filtered on
   `platform_action=true`.
3. Operator-visible UX is a generic "Couldn't reach server" toast on
   the SPA. They may retry; if persistent, escalate to on-call
   backend engineer.
