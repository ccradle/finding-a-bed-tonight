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

Oracle VM runs backend via docker compose (NOT systemd). Use the docker log path first:

```bash
# On the Oracle VM
docker logs --since 1h fabt-backend 2>&1 | \
    grep "Failed to persist audit event" | tail -50
```

Fallback for systemd-deployed environments (stage/dev VM where ops swapped):

```bash
sudo journalctl -u fabt-backend --since "1 hour ago" | \
    grep "Failed to persist audit event" | tail -50
```

If the line contains `ERROR: new row violates row-level security policy`, then RLS rejected the INSERT. Go to step 2.

If the line is empty but `fabt.audit.rls_rejected.count` is non-zero in Prometheus, the ERROR log was rotated out — read the metric directly. Retrieve the actuator credentials from 1Password entry `fabt-actuator-basic-auth`:

```bash
# ACTUATOR_USER / ACTUATOR_PASSWORD sourced from 1Password, never hardcoded
curl -s -u "$ACTUATOR_USER:$ACTUATOR_PASSWORD" http://localhost:9091/actuator/prometheus | \
    grep fabt_audit_rls_rejected_count
```

**Baseline expectations (first 2 weeks post-v0.43 deploy):**
- `fabt_audit_system_insert_count` — expect 0/hour once task 3.27 sweep completes. During the rollout window (first 7-10 days), non-zero rates identify publishers that still haven't been swept; file a ticket per unique `action` tag.
- `fabt_audit_rls_rejected_count` — expect strictly 0. Any non-zero rate is a page-worthy event (either a code regression or an active intrusion attempt).

### 2. Verify the Spring proxy is engaged on `AuditEventPersister`

```bash
# ACTUATOR_USER / ACTUATOR_PASSWORD from 1Password entry 'fabt-actuator-basic-auth'
curl -s -u "$ACTUATOR_USER:$ACTUATOR_PASSWORD" http://localhost:9091/actuator/beans | \
    jq '.contexts.application.beans.auditEventPersister'
```

The `resource` field must reference a CGLIB proxy class (e.g., `AuditEventPersister$$SpringCGLIB$$0`). If it reports the plain class, Spring's AOP didn't proxy it — `@Transactional` annotations are inert and every audit insert will fail under FORCE RLS.

Mitigation: redeploy the previous known-good release. Do NOT disable FORCE RLS — that re-opens the cross-tenant audit leak from VAWA/Issue #117.

### 3. Verify the SYSTEM_TENANT_ID sentinel is being used when expected

```bash
curl -s -u "$ACTUATOR_USER:$ACTUATOR_PASSWORD" http://localhost:9091/actuator/prometheus | \
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

## Page vs ticket decision tree (Sam checkpoint W-B-N9)

| Signal | Action |
|--------|--------|
| `fabt_audit_rls_rejected_count` rate > 0 for >5 min OR any `42501` in logs | **PAGE** — RLS rejection means either regression or intrusion |
| `fabt_audit_system_insert_count` rate > 10/hour tagged by scheduled-job action | **TICKET** — unswept publisher; fix in next release |
| `fabt_audit_system_insert_count` rate > 10/hour tagged by request-path action (e.g. ACCESS_CODE_USED) | **PAGE** — pre-auth endpoint missing TenantContext wrap |
| AuditEventPersister not proxied (step 2 fail) | **PAGE** — audit path fully broken |
| Multiple users report "my action didn't audit" | **PAGE** |
| Expected-row-count-vs-actual drift > 5% over 1h | **PAGE** — rollback candidate |

Rollback threshold measurement query (the >5% test at 3 AM):

```sql
-- Run as fabt owner. Compare expected publish count vs audit row count
-- for a known audit-generating endpoint over the last hour.
-- Requires access-log correlation; if no easy correlator, rollback based
-- on step 1 grep + step 3 counter delta instead.
SELECT
    date_trunc('hour', timestamp) AS hr,
    COUNT(*) AS audit_rows
FROM audit_events
WHERE timestamp > now() - interval '1 hour'
  AND action IN ('LOGIN_SUCCESS', 'ACCESS_CODE_USED', 'JWT_KEY_GENERATION_BUMPED')
GROUP BY 1;
```

Cross-reference with nginx access logs for matching request counts. Ratio < 0.95 → trigger rollback.

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

## Deploy-time anomaly: Flyway hang on CREATE INDEX CONCURRENTLY

**Symptom:** Flyway `migrate` hangs >60s on a migration that contains `CREATE INDEX CONCURRENTLY`. No progress in logs; `docker logs --follow fabt-backend` idle; backend container up but not serving traffic.

**Root cause:** `CREATE INDEX CONCURRENTLY` runs a two-scan pattern and must wait at scan-2-start for every concurrent transaction to terminate. Flyway + Spring Data JDBC's connection pool can leave an `idle in transaction` connection on `flyway_schema_history` reads — the client returned the connection to the pool without committing, so Postgres still sees the transaction as alive. `CREATE INDEX CONCURRENTLY` waits on `virtualxid` for that zombie transaction to release, forever.

**Diagnostic query (run as fabt owner or postgres, NOT fabt_app — per `feedback_rls_hides_dv_data.md`):**

```sql
SELECT pid, state, wait_event, age(query_start) AS age, query
FROM pg_stat_activity
WHERE (state != 'idle' AND backend_type = 'client backend')
   OR (state = 'idle in transaction' AND age(query_start) > interval '1 minute')
ORDER BY query_start;
```

Expected signature of the bug:
- One row with `state='active'`, `wait_event='virtualxid'`, query starting with `CREATE INDEX CONCURRENTLY`
- One row with `state='idle in transaction'`, `wait_event='ClientRead'`, query `SELECT ... FROM flyway_schema_history` or `pg_namespace`

**Remediation (in order of preference):**

1. **Set `idle_in_transaction_session_timeout` before V71-class migrations.** The safest option is to configure Postgres to auto-terminate idle-in-transaction connections before they hang the migration:
   ```sql
   ALTER DATABASE fabt SET idle_in_transaction_session_timeout = '60s';
   ```
   This must be done ONCE on the target cluster before any CONCURRENTLY migration runs. The setting applies to new connections; existing connections in the pool need to be recycled.

2. **If already hanging: terminate the idle transaction.**
   ```sql
   -- As fabt owner (NOT fabt_app):
   SELECT pg_terminate_backend(pid)
   FROM pg_stat_activity
   WHERE state = 'idle in transaction'
     AND age(query_start) > interval '30 seconds'
     AND datname = 'fabt';
   ```
   This releases the virtualxid; CONCURRENTLY completes within seconds after. Safe because idle-in-transaction connections aren't doing useful work — they're sitting parked in Hikari awaiting a reader.

3. **Abort the deploy.** If >5 minutes have elapsed and neither (1) nor (2) cleared the hang, abort:
   - `docker stop fabt-backend` (kills the Flyway process)
   - Revert to the previous image tag
   - Note: the partially-applied CONCURRENTLY creates an INVALID index that must be dropped before retry. Run as fabt owner:
     ```sql
     SELECT indexname FROM pg_indexes WHERE indexdef ILIKE 'CREATE INDEX%' AND schemaname = 'public';
     SELECT relname, indisvalid FROM pg_class c JOIN pg_index i ON c.oid = i.indexrelid WHERE NOT indisvalid;
     DROP INDEX IF EXISTS <invalid_index_name>;
     ```

**For migration authors:** at year-1 scale (>1M rows), CONCURRENTLY will become necessary on `audit_events` and `hmis_audit_log` indexes. Per Jordan's DBA guidance, **do NOT issue those via Flyway** — run them as ad-hoc operator SQL outside the deploy path, with `idle_in_transaction_session_timeout` set session-level, and track the index existence via a no-op guard migration that asserts `SELECT 1 FROM pg_indexes WHERE indexname = ?`. See Phase E task cards.

**Why Phase B V71 is safe as-shipped:** V71 dropped CONCURRENTLY to plain `CREATE INDEX` after the test-harness hang. At pilot scale, `password_reset_token` and `one_time_access_code` hold dozens of rows max; the ACCESS EXCLUSIVE lock during CREATE INDEX resolves in single-digit milliseconds, imperceptible to users. Phase B deploy already declares a maintenance window (pgaudit image swap) so the write-block is subsumed.

## Related

- `project_multi_tenant_phase_b_resume.md` (memory) — Phase B implementation history
- `openspec/changes/multi-tenant-production-readiness/design-b-rls-hardening.md` — D55 SYSTEM_TENANT_ID rationale, D61 panic script
- `docs/security/pg-policies-snapshot.md` — git-tracked RLS policy shape
- `feedback_rls_hides_dv_data.md` — always diagnose as owner, not fabt_app
- `feedback_transactional_eventlistener.md` — proxy-self-invocation lesson
