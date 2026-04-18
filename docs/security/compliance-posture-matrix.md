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

**For right-to-be-forgotten (VAWA-FVPSA).** Under crypto-shred
(Phase F hard-delete), a tenant's `tenant_key_material` is deleted;
the tenant's existing audit rows remain but their encrypted-JSONB
details fields become undecryptable, effectively redacted. The row
itself is preserved for aggregate-query purposes (e.g., platform-wide
deletion rate). This is documented in design `docs/legal/right-to-be-forgotten.md`.

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
wiring deferred to backlog," broadened here: rule loading itself is
incomplete. Control signals remain visible via direct actuator scrape
(the compliance position does not depend on the alerting path), but
operator-pager early-warning is not active. Phase C MUST complete rule
loading + Alertmanager routing alongside label enrichment, and revisit
threshold sizing with multi-user load data.

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

## Change log

| Date       | Change                                                                  | Driver                                       |
|------------|--------------------------------------------------------------------------|----------------------------------------------|
| 2026-04-17 | Initial matrix — audit-row = committed-event contract documented.       | Phase B warroom W-AUDIT-DOC-1 (Casey).       |
| 2026-04-17 | BED_HOLDS_RECONCILED audit attribution — per-tenant, not platform-wide. | Phase B warroom W-B-FIXB-1.                  |
| 2026-04-18 | `tenant_context.empty` noise-floor characterized; alert threshold flap risk documented; Phase C task filed. | v0.43.1 live rehearsal warroom (Riley + Marcus + Casey). |
