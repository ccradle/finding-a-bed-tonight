# Compliance posture matrix

This file documents the load-bearing data-integrity and trust contracts
that compliance auditors (HIPAA, VAWA-FVPSA, state CoC procurement) need
to see written down in one place. The matrix is narrow on purpose —
only the claims that would cost us a finding if they turned out to be
different from what the code does.

Living document. When a contract changes, this file MUST change in the
same PR; CODEOWNERS includes Casey Drummond on this path.

## audit_events — what a row means

**Contract.** A row in `audit_events` represents an action that
successfully COMMITTED to the database. Attempts that rolled back are
NOT captured in this table.

**Why this is not obvious.** Several audit writers publish via
`ApplicationEventPublisher`. That call is handled synchronously on the
publisher thread by `AuditEventService` which delegates the INSERT to
`AuditEventPersister` — a separate Spring bean carrying
`@Transactional(propagation = REQUIRED)`. When the publisher is inside
a caller-owned transaction, REQUIRED joins the caller's transaction, so
the audit row COMMITS/ROLLS BACK WITH the caller's own work. If the
caller throws and the transaction rolls back, the audit row is
discarded along with it.

This is a DELIBERATE design choice (Phase B, design-b-rls-hardening.md
D55 + rollback-coupling Javadoc on `AuditEventService`):

- Rolled-back actions did not produce the side effects they claimed to
  produce, so a surviving audit row describing them is misleading to
  investigators.
- Consumers needing always-commits audit trails (e.g., login-failure
  counters, hostile-access tripwires) use a different mechanism
  (REQUIRES_NEW-scoped service OR a structured log file + retention
  policy). `audit_events` is not a universal event log.

**What auditors can rely on:**

1. Every row in `audit_events` reflects a real, committed state change.
   There is no "attempted but failed" noise in this table.
2. Because FORCE ROW LEVEL SECURITY (V69) enforces
   `tenant_id = fabt_current_tenant_id()` on every row, a tenant cannot
   see another tenant's audit rows even if the operator session would
   otherwise have permission.
3. Because `REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES ON audit_events
   FROM fabt_app` (V70/V72), the application role cannot mutate or
   destroy prior rows; only the Flyway-owner role (session-scoped,
   audited by pgaudit once V73 lands) can. `AuditTableAppendOnlyTest`
   regression-guards this invariant.

**What auditors CANNOT rely on in this table:**

1. Exhaustive capture of attempted actions. Log files OR separate
   counters cover rolled-back attempts; see
   `docs/security/phase-b-silent-audit-write-failures-runbook.md`
   for the WARN-log fallback path and the Micrometer counters
   (`fabt.audit.system_insert.count`, `fabt.audit.rls_rejected.count`)
   that surface audit-write failures.
2. Exhaustive capture of read access. Reads do not produce
   `audit_events` rows except where a dedicated read-audit path exists
   (not all reads — by design, cardinality budget G6). Casey-
   specific reads (cross-tenant platform-admin access) are captured
   in `platform_admin_access_log` (Phase G) not here.

## BED_HOLDS_RECONCILED audit attribution

**Contract.** `BED_HOLDS_RECONCILED` audit rows are per-tenant. Each
drifted-row correction produces one audit row attributed to the owning
tenant (`tenant_id = row.tenantId()`).

**Why this is not obvious.** Historically the reconciliation job
attributed these audit rows to `tenant_id = null` on the framing that
"reconciliation is platform-wide." That framing is SUPERSEDED by Phase
B D55. A drifted `bed_availability` row belongs to exactly one tenant;
the reconciliation corrects it in that tenant's scope; the audit
attribution follows. (The scheduler job itself runs platform-wide; the
WRITES it produces are per-tenant and bound via
`TenantContext.runWithContext(row.tenantId(), …)` before the audit
INSERT.)

This came up in the Phase B CI-failure warroom (W-B-FIXB-1). Prior
inline comment in `BedHoldsReconciliationJobConfig.writeAuditRowDirect`
is updated to reflect the current framing.

## Rollback-coupled audit rows — downstream implications

**For forensic analysis.** When investigating a Sev-1 incident,
queries against `audit_events` return the committed history. If the
investigation needs to know about rolled-back attempts (e.g., did a
hostile actor try an impossible state transition and get rejected?), the
investigator MUST cross-reference:

- Application log files (`kubectl logs` / `docker logs fabt-backend`)
  — retained per the log-retention matrix.
- `fabt.audit.rls_rejected.count{action, sqlstate}` Prometheus counter
  — captures every audit-INSERT that was itself blocked by FORCE RLS
  (SQLState 42501) or other SQL error. Non-zero rate = investigation
  trigger.
- WARN-log lines emitted by `AuditEventService` when a publisher
  reaches `SYSTEM_TENANT_ID` fallback (D55). Rate-limited by
  `DuplicateMessageFilter`.

**For right-to-be-forgotten (VAWA-FVPSA, GDPR Art. 17, CCPA §1798.105, etc.).** Under crypto-shred (Phase F hard-delete), a tenant's `tenant_key_material` is deleted; the tenant's existing audit rows remain but their encrypted-JSONB details fields become undecryptable, effectively redacted. The row itself is preserved for aggregate-query purposes (e.g., platform-wide deletion rate). The full deployment-owner posture — automated retention vs ad-hoc deletion distinction, jurisdictional-scope deferrals, and a step-by-step deletion-request runbook with the SQL queries that enumerate a subject's data — is documented at `docs/legal/right-to-be-forgotten.md` (authored 2026-04-30 as part of `reentry-release-readiness`).

## `fabt.rls.tenant_context.empty.count` — noise-floor characterization

**Contract.** This counter is a **tripwire**, not a control. Increments when a
JDBC connection is borrowed while `TenantContext.getTenantId()` is null. The
control signal (whether a query then actually *used* the connection against
a regulated table without a tenant binding) is
`fabt.audit.rls_rejected.count{sqlstate="42501"}` — that counter staying at
zero is what proves FORCE RLS is enforcing correctly. `tenant_context.empty`
can fire and be harmless if the subsequent query either (a) doesn't touch a
regulated table, or (b) binds `set_config('app.tenant_id', …)` just-in-time
inside the connection's first statement.

**Observed noise floor (v0.43.1 live characterization, 2026-04-18).**

| Phase | Rate | Source attribution (hypothesized) |
|---|---|---|
| Idle (backend up, no user traffic) | ~0.1/s (88 in 15 min) | `ForceRlsHealthGauge` 60 s poll + actuator health/Prometheus scrape |
| Active user flow (1 user: DV referral round-trip + admin nav + forgot-password + SSE) | ~0.83/s (993 in 20 min) | + SSE reconnect churn (~10 `AsyncRequestNotUsableException: Broken pipe` in window) + notification heartbeat polling + request-path borrows that don't touch regulated tables |

The ~10× amplification under single-user load is characterized, not fixed.
Attribution is hypothetical pending Phase C label enrichment (task #154 —
expands W-GAUGE-2 to add `{source}` tag and a synthetic load regression test).

**Alert threshold sizing (v0.43) AND wiring gap.** The alert
`FabtPhaseBTenantContextEmpty` is DEFINED in `deploy/prometheus/phase-b-rls.rules.yml`
at `rate > 1/s for 15 m`. Single-user rehearsal traffic produced 0.83/s
sustained — just under that threshold. **However**, on verification
2026-04-18 20:55 UTC the rules file is NOT loaded into the running
Prometheus instance on the v0.43.1 demo VM (0 rule groups active via
`/api/v1/rules`). So the alert is defined in source, not watching in prod.
This is the same operational gap flagged in
`docs/oracle-update-notes-v0.43.1-amendments.md` as "Alertmanager Slack
wiring deferred to backlog." **RESOLVED in v0.49.0** — Alertmanager routing
is live (email + ntfy push), 9 Prometheus rules (5 Phase B + 4 Phase C)
now deliver notifications per the "Alerting tier posture" section below.
Threshold sizing with multi-user load data is a separate follow-up
(Phase C task #154 — label-enrich `tenant_context_empty` counter + load
regression test).

**What auditors can rely on:**
1. The control for cross-tenant data isolation is FORCE ROW LEVEL SECURITY
   (V69). `relforcerowsecurity = t` on all 7 regulated tables, observable
   via `fabt_rls_force_rls_enabled{table} = 1.0`.
2. The control-failure signal is `fabt.audit.rls_rejected.count{sqlstate="42501"}`.
   Zero rate = no enforcement failure.
3. `tenant_context.empty` is a *secondary* indicator — it surfaces hot paths
   that should but don't bind `TenantContext`, upstream of any actual data
   exposure. Non-zero during normal operation is characterized noise floor,
   not a control breach.

**What auditors CANNOT rely on:**
- `tenant_context.empty` does NOT attribute increments to a source. Until
  Phase C label enrichment lands, we cannot say "SSE caused X%, scheduled
  jobs caused Y%." The characterization above is forensic reasoning from
  log correlation, not code instrumentation.

## Alerting tier posture (v0.49.0+)

**Contract.** The Prometheus rules that gate data-integrity signals
(FORCE RLS drop, cross-tenant cache read, malformed audit row,
detached-audit-persist failure) are paging signals — they notify an
operator who is then expected to triage. The posture of that notification
path differs between demo-tier (findabed.org today) and regulated-tier
(future HIPAA / VAWA / CJIS deployment).

### Demo-tier (current, findabed.org)

- **Delivery:** Prometheus → Alertmanager → (a) Gmail SMTP for WARN +
  CRITICAL, (b) ntfy.sh webhook push for CRITICAL only.
- **Auth:**
  - Gmail SMTP uses an app password (manual rotation via Google account UI).
  - ntfy.sh uses a public topic with a long-random name as a shared
    secret — treat as the weakest link. Alert bodies include tenant
    UUIDs and metric names but NO PII; acceptable for demo.
- **Availability:** Single-instance Alertmanager on a single Oracle
  VM. If the VM dies, alerts die with it.
- **Audit:** Alert routing is NOT logged to `audit_events` (pgaudit
  covers the underlying DB events; alert delivery is ops-visibility,
  not audit-trail).
- **What an auditor can rely on:** the *existence* of the alerting
  path is demonstrable (template file version-controlled, rendered
  config `0600` on disk, rule files auditable). What they *cannot*
  rely on: that a specific alert reached a specific person within a
  specific SLA — demo-tier has no delivery-confirmation audit.

### Regulated-tier upgrade path

Before a HIPAA / VAWA / CJIS deployment carries real PII:

1. **Replace ntfy.sh public topic** with authenticated ntfy (self-
   hosted on VPC + access-token auth), PagerDuty / Opsgenie with BAA,
   OR private Slack workspace with webhook verification.
2. **SMTP hardening:** Gmail app-password → dedicated SMTP relay
   (SES / SendGrid / in-house Postfix) with TLS + DKIM + SPF, audit
   logs exported to SIEM.
3. **HA Alertmanager** — 3-node cluster across availability zones.
   Prometheus federation / remote_write to a reliable store.
4. **Delivery-confirmation audit trail** — every routed alert
   recorded to `audit_events` via `DetachedAuditPersister`; weekly
   roll-up signed + exported to compliance bucket.
5. **Alert content PII scrub** — filter tenant_id UUIDs and any
   label that could identify a household / individual before webhook
   send. Current demo-tier templates are unfiltered.

Tracked for Phase F in `openspec/changes/multi-tenant-production-readiness/tasks.md`.

### What auditors can rely on today

1. The 9 Prometheus rules (`deploy/prometheus/phase-b-rls.rules.yml` +
   `phase-c-cache-isolation.rules.yml`) are version-controlled and
   loaded in prod. `promtool check rules` in CI on every PR.
2. Alertmanager template (`deploy/alertmanager.yml.tmpl`) version-
   controlled; `amtool check-config` runs on the rendered form
   pre-deploy (documented in the v0.49 runbook).
3. Secrets live in `~/fabt-secrets/.env.prod` on the VM with `0600`
   perms; `feedback_no_ip_in_repo.md` discipline keeps no secrets
   in git.

## Hold-attribution PII (v0.55+, transitional-reentry-support)

### What the columns are

v0.55 adds three nullable encrypted columns to `reservation` (V93):

- `held_for_client_name_encrypted` — base64 v1 envelope of the client name a navigator entered for shelter check-in coordination
- `held_for_client_dob_encrypted` — base64 v1 envelope of the client DOB (ISO-8601)
- `hold_notes_encrypted` — base64 v1 envelope of operator-supplied free-text notes (server max 1000 chars; UI cap 500)

Encryption uses `SecretEncryptionService.encryptForTenant(KeyPurpose.RESERVATION_PII)` — a per-tenant DEK separate from the existing per-purpose DEKs (JWT_SIGN, TOTP, WEBHOOK_SECRET, OAUTH2_CLIENT_SECRET, HMIS_API_KEY). V93 extends `tenant_dek.purpose` with the new value.

### Path scoping (structural separation from DV)

The optional hold-attribution fields are available only on the **non-DV navigator-hold path**. The V91 CHECK constraint `shelter_dv_implies_dv_type` (`dv_shelter = false OR shelter_type = 'DV'`) ensures DV-flagged inventory cannot be reached via the navigator-hold flow. The DV referral path continues to use the opaque-token zero-PII model unchanged.

### Purge contract

Hold-attribution ciphertext SHALL be erased **no later than 25 hours** after a reservation reaches a terminal status (CANCELLED / CONFIRMED / EXPIRED / CANCELLED_SHELTER_DEACTIVATED) or 25 hours past `expires_at`. Implementation: `ReservationService.purgeExpiredHoldAttribution(Instant)` invoked by `ReferralTokenPurgeService.purgeExpiredHoldAttribution()` on a `@Scheduled(fixedDelay=900_000)` (15 minutes) — worst-case PII lifetime is 24h+15m. Two-layer crypto-shred posture: (a) at-rest defense via `tenant_dek` rotation/hard-delete, (b) row-level null on the bounded UPDATE.

### What survives the purge

- The reservation row itself (sans ciphertext) — operational and analytical metadata is preserved
- Audit trail: the row's creation, status transitions, and decryption-on-read events live in `audit_events` with the reservation_id as a join key (no plaintext in audit payloads — see `RESERVATION_HELD_FOR_CLIENT_RECORDED` / `RESERVATION_PII_DECRYPTED_ON_READ` / `RESERVATION_PII_PURGED` audit event types under "audit_events" above)
- `tenant_dek` row for the `RESERVATION_PII` purpose remains until the tenant is hard-deleted (Phase F-6)

### What does NOT leave the server

Hold-attribution PII is **NEVER** published to HMIS, AsyncAPI, OAuth2 webhooks, or any external system by design. The only consumer is the authenticated REST detail-view endpoint (`GET /api/v1/reservations/{id}/detail`, per design D12) which decrypts under audit. List-view endpoints return PII fields as `null` regardless of underlying ciphertext.

### Purge SLA — operational signal (v0.55 honest gap)

The 25-hour purge claim is the contract. The operational signal that proves the claim is **not yet wired in v0.55**:

| Signal | Status (v0.55) | Tracked for |
|---|---|---|
| `fabt.reservation.pii_purge.success.count` (Prometheus counter) | NOT WIRED | v0.56, target Q2-2026 |
| `fabt.reservation.pii_purge.lag_seconds` (Prometheus histogram) | NOT WIRED | v0.56, target Q2-2026 |
| `fabt.reservation.pii_purge.failure.count > 0 for 5m` (Alertmanager rule) | NOT WIRED | v0.56, target Q2-2026 |

This is a known `feedback_truthfulness_above_all` disclosure: the public 25h claim is contractually stronger than the production observability evidence. v0.55 closes the gap structurally (the purge job runs, emits an `INFO` log line on success, and writes a `RESERVATION_PII_PURGED` audit row per scheduled invocation) but does NOT yet emit the metric. Operators can verify the claim manually via `audit_events` queries and the operator runbook §6.5 (`docs/oracle-update-notes-v0.55.0.md`).

**Operator log-parse fallback (v0.55):** the purge service writes one `INFO` log line per scheduled invocation with `purgedCount={n}` (`ReferralTokenPurgeService.purgeExpiredHoldAttribution`). On the live VM:

```bash
docker logs fabt-backend --since 26h 2>&1 | grep "purgeExpiredHoldAttribution: purged="
```

A line every ~15 minutes confirms the schedule fired. A 25h+ gap with no line is the exception condition the future alert will catch.

When the alert wires up, it MUST include a traffic-floor guard per `feedback_demotier_alert_traffic_floor.md` to avoid false positives on demo-tier sparse-traffic windows.

### Companion docs deferred to v0.56

Three additional docs the public PII posture would normally reference are also deferred:

| Doc | Status | Tracked for |
|---|---|---|
| `docs/security/dek-rotation-policy.md` | NOT AUTHORED | v0.56, target Q2-2026 |
| `docs/security/threat-model.md` (STRIDE-lite, ~1500 words) | NOT AUTHORED | v0.56, target Q2-2026 |
| `docs/security/zap-v0.55-baseline.md` | NOT RUN | v0.56, target Q2-2026 (depends on dev-stack uptime) |
| `docs/security/audit-event-catalog.md` (full enum enumeration) | NOT AUTHORED | v0.56, target Q3-2026 |

This matrix is the canonical landing zone for a city-attorney pre-procurement review; honest disclosure of these gaps is the v0.55 posture. None of the gaps falsify the 25h purge claim — they document the surrounding evidence framework that's still being built.

## Change log

| Date       | Change                                                                  | Driver                                       |
|------------|--------------------------------------------------------------------------|----------------------------------------------|
| 2026-04-17 | Initial matrix — audit-row = committed-event contract documented.       | Phase B warroom W-AUDIT-DOC-1 (Casey).       |
| 2026-04-17 | BED_HOLDS_RECONCILED audit attribution — per-tenant, not platform-wide. | Phase B warroom W-B-FIXB-1.                  |
| 2026-04-18 | `tenant_context.empty` noise-floor characterized; alert threshold flap risk documented; Phase C task filed. | v0.43.1 live rehearsal warroom (Riley + Marcus + Casey). |
| 2026-04-21 | Alerting-tier posture section added (demo-tier vs regulated-tier upgrade path). Retires the prior "Phase C MUST complete Alertmanager routing" future-work flag — v0.49 ships Alertmanager wiring. | v0.49 release-prep audit (Jordan + Marcus + Casey). |
| 2026-04-30 | Hold-attribution PII (v0.55) section added: V93 `_encrypted` columns, RESERVATION_PII per-tenant DEK, 25-hour purge contract, structural DV separation via V91 CHECK, audit-event types, purge-SLA operational-signal honest-gap disclosure (metric not yet wired — tracked v0.56 Q2-2026), companion docs deferral table (dek-rotation, threat-model, ZAP, audit-event-catalog). | reentry-release-readiness §4.1+§4.2 (Round 3 PII warroom + Round 4 hardening). |
