# Runbook: Silent audit-write failures under Phase B RLS

**Applies to:** v0.43 and later (Phase B FORCE RLS on audit_events + 6 other regulated tables)
**Owner:** SRE on-call
**Last reviewed:** 2026-04-17

## Symptom

A user action clearly happened (key rotation, access-code login, password reset issued, role changed) but `audit_events` has no corresponding row. Users do not see errors. Logs may or may not show the failure depending on log-retention.

## Why this is a Phase B thing

Phase B (V67–V69) enables `FORCE ROW LEVEL SECURITY` on `audit_events` and 6 other regulated tables. The RLS policy requires every INSERT/SELECT/UPDATE/DELETE to satisfy `tenant_id = fabt_current_tenant_id()`. If the session's `app.tenant_id` GUC is empty (pool-borrow with no `TenantContext` bound), `fabt_current_tenant_id()` returns `NULL` and the policy blocks the operation.

Pre-Phase-B the INSERT would succeed with `tenant_id = NULL` (orphan row). Post-Phase-B the INSERT is rejected with SQLState `42501`. The audit path's `try/catch` around `onAuditEvent` (intentional, preserves request completion) swallows the exception; only an `ERROR` log line marks it.

The v0.43 codebase has two defensive mechanisms:

1. **`AuditEventPersister`** — separate Spring bean that `AuditEventService` delegates to. `@Transactional(REQUIRED)` opens a real tx when there's no outer publisher tx, so `set_config('app.tenant_id', ?, true)` binds correctly before the INSERT. If this bean were ever accidentally re-inlined back into `AuditEventService` (self-invocation), the proxy bypass returns. ArchUnit family-G rule guards against it (task 3.x).
2. **`SYSTEM_TENANT_ID` sentinel** — `AuditEventService.onAuditEvent` falls back to `00000000-0000-0000-0000-000000000001` when `TenantContext` is unbound AND the session GUC is empty. Row lands with that sentinel, `fabt.audit.system_insert.count` counter increments, WARN log fires (rate-limited per-JVM via `DuplicateMessageFilter`).

## Diagnostic steps

**Always run these as the `fabt` owner role, NOT as `fabt_app`.** Per `feedback_rls_hides_dv_data.md`, RLS hides the data you're trying to diagnose — connect directly with the owner credentials.

### 1. Check for swallowed errors in the application log

```bash
# On the Oracle VM
sudo journalctl -u fabt-backend --since "1 hour ago" | \
    grep "Failed to persist audit event" | tail -50
```

If the line contains `ERROR: new row violates row-level security policy`, then RLS rejected the INSERT. Go to step 2.

If the line is empty but `fabt.audit.rls_rejected.count` is non-zero in Prometheus, the ERROR log was rotated out — read the metric directly:

```bash
curl -s http://localhost:9091/actuator/prometheus | \
    grep fabt_audit_rls_rejected_count
```

### 2. Verify the Spring proxy is engaged on `AuditEventPersister`

```bash
curl -s -u 'actuator:***' http://localhost:9091/actuator/beans | \
    jq '.contexts.application.beans.auditEventPersister'
```

The `resource` field must reference a CGLIB proxy class (e.g., `AuditEventPersister$$SpringCGLIB$$0`). If it reports the plain class, Spring's AOP didn't proxy it — `@Transactional` annotations are inert and every audit insert will fail under FORCE RLS.

Mitigation: redeploy the previous known-good release. Do NOT disable FORCE RLS — that re-opens the cross-tenant audit leak from VAWA/Issue #117.

### 3. Verify the SYSTEM_TENANT_ID sentinel is being used when expected

```bash
curl -s http://localhost:9091/actuator/prometheus | \
    grep fabt_audit_system_insert_count
```

A non-zero rate on actions other than scheduled-job names (e.g., `daily_prune`, `kid_cleanup`) indicates a publisher that forgot to bind `TenantContext`. Grep the application logs for the specific action name to find the publisher code path — then wrap in `TenantContext.runWithContext(user.getTenantId(), …)` per `AuthController.accessCodeLogin` (see code comment at the `eventPublisher.publishEvent` call).

### 4. Verify pool-borrow is setting `app.tenant_id`

Connect as `fabt_app` and issue:

```sql
BEGIN;
SELECT current_setting('app.tenant_id', true) AS gud;
COMMIT;
```

Expected result: empty string. (Pool borrow sets it to empty when `TenantContext` is unbound. `RlsDataSourceConfig.applyRlsContext` is the only code path that writes this GUC at session level.)

If the result is the previous borrower's UUID, pool-borrow reset has broken — a connection is being reused without `applyRlsContext` running. This is a serious isolation bug; roll back the release.

### 5. Verify the RLS policy is the expected shape

```sql
SELECT policyname, cmd, qual, with_check, permissive
FROM pg_policies
WHERE tablename = 'audit_events'
ORDER BY policyname;
```

Expected single row:

| policyname | cmd | qual | with_check | permissive |
|------------|-----|------|------------|-----------|
| `tenant_isolation_audit_events` | `ALL` | `(tenant_id = fabt_current_tenant_id())` | `(tenant_id = fabt_current_tenant_id())` | `PERMISSIVE` |

If the shape differs (missing `with_check`, extra policies, permissive→restrictive drift) compare against the git-tracked `docs/security/pg-policies-snapshot.md` (Phase B B5).

## Mitigation — never do these without the incident commander's call

- **Do NOT disable `FORCE ROW LEVEL SECURITY` on `audit_events`** — that re-opens the VAWA cross-tenant-audit leak (Issue #117). The D61 panic script only reverts the full RLS policy stack atomically; it's a last resort.
- **Do NOT bypass the sentinel by nullifying tenant_id on the INSERT path** — a row with `tenant_id = NULL` would be invisible to every tenant's query (including forensic review), which is the exact forensic-evasion class of bug D55 was designed to close.
- **DO** roll back the release if >5% of expected audit rows are missing. Previous release (v0.42) does not have FORCE RLS on `audit_events` (V67–V69 are Phase B only), so rollback restores the pre-Phase-B audit-visibility contract.

## Short-term (within hour)

If the issue is a specific publisher missing `TenantContext.runWithContext`:

1. Identify the publisher via `fabt.audit.system_insert.count{action=...}` + log grep.
2. Hot-patch the publisher to wrap `eventPublisher.publishEvent(...)` in `TenantContext.runWithContext(ownerTenantId, userId, dvAccess, () -> ...)`.
3. Deploy patch; verify counter flat-lines.

If the issue is a Spring proxy regression (step 2 failed):

1. Redeploy the previous release (v0.42) immediately.
2. File regression issue against the release candidate — the ArchUnit rule should have caught this in CI.

## Long-term follow-ups

- Every `@Scheduled`, `@EventListener`, `ApplicationRunner`, `CommandLineRunner`, and `@Async` method that publishes an audit event MUST wrap in `TenantContext.runWithContext(…)` — task 3.27 sweep, scheduled as part of v0.44.
- Prometheus alerting rules (not yet deployed):
  - `rate(fabt_audit_rls_rejected_count[5m]) > 0` → PagerDuty page
  - `rate(fabt_audit_system_insert_count[1h]) > baseline × 2` → ticket for publisher sweep

## Related

- `project_multi_tenant_phase_b_resume.md` (memory) — Phase B implementation history
- `openspec/changes/multi-tenant-production-readiness/design-b-rls-hardening.md` — D55 SYSTEM_TENANT_ID rationale, D61 panic script
- `docs/security/pg-policies-snapshot.md` — git-tracked RLS policy shape
- `feedback_rls_hides_dv_data.md` — always diagnose as owner, not fabt_app
- `feedback_transactional_eventlistener.md` — proxy-self-invocation lesson
