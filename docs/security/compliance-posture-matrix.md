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

## Change log

| Date       | Change                                                                  | Driver                                       |
|------------|--------------------------------------------------------------------------|----------------------------------------------|
| 2026-04-17 | Initial matrix — audit-row = committed-event contract documented.       | Phase B warroom W-AUDIT-DOC-1 (Casey).       |
| 2026-04-17 | BED_HOLDS_RECONCILED audit attribution — per-tenant, not platform-wide. | Phase B warroom W-B-FIXB-1.                  |
