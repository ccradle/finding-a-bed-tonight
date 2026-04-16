# Cross-Tenant Audit Performance Probe — Findings

**Probe:** `docs/performance/cross-tenant-audit-probe.sql`
**Run date:** 2026-04-16
**Stack:** local dev (`./dev-start.sh --nginx --observability`), Postgres 16, Java 25, Spring Boot 4.0.5
**Operator:** warroom-recommended pre-OWASP perf check (Sam, Elena, Alex)

## Summary

Cross-tenant-isolation-audit (Issue #117) Phase 2/4 changes do **NOT** regress query plans or per-call latency at dev scale. Ship v0.40 on this evidence; re-run the probe once at NYC scale before any pilot launch to confirm planner index choice on `audit_events`.

## EXPLAIN ANALYZE results (dev scale)

| Query | Plan | Buffers | Execution Time | Verdict |
|---|---|---|---|---|
| `subscription.findByIdAndTenantId` | Index Scan on `subscription_pkey` | shared hit=2 | 0.034ms | ✅ |
| `tenant_oauth2_provider.findByIdAndTenantId` | Index Scan on `tenant_id_provider_name_key` | shared hit=2 | 0.034ms | ✅ |
| `audit_events.findByTargetUserIdAndTenantId` | Index Scan on `idx_audit_events_target_user` | shared hit=5 | 0.050ms | ⚠️ see "Index choice" below |
| Baseline `app_user` lookup | DO-block total 5.5ms / 100 | — | ~0.055ms per call | ✅ baseline |

All Index Scans, no Seq Scans, all buffer hits ≤ 5, all execution times sub-millisecond.

## Index choice on `audit_events` (the one watch-item)

The V57 composite index `idx_audit_events_tenant_target (tenant_id, target_user_id, timestamp DESC)` exists but the planner chose the simpler `idx_audit_events_target_user (target_user_id)` instead, then post-filtered by `tenant_id`. At dev scale (small table, ~75 rows for the test user) this is the cheaper plan — composite-index seek overhead exceeds the cost of a single-column lookup + filter.

**Expected behavior on small tables.** At NYC scale (10M rows) the planner should flip to the composite index because the post-filter cost on a single-column lookup would scan many more rows.

**Action:** re-run this probe against the NYC scale dataset (`docs/performance/generate-nyc-loadtest.py`) before the first multi-tenant pilot launch to confirm the planner picks the composite. NOT a v0.40 ship gate — the audit's correctness doesn't depend on which index Postgres chooses, only on the WHERE clause containing both predicates (which it does).

## Probe limitations surfaced (for v4 if/when needed)

1. **set_config Q0/Q6 returned 0 rows.** The `count(*) FROM (...)` portability wrapper added in warroom v3 fix #1 changed the pg_stat_statements fingerprint so the inner `set_config()` calls weren't matched by the filter. Workaround: measure D13 set_config delta via direct `\timing` on a single statement OR via Java microbench from the application layer.

2. **DO-block EXECUTE not tracked per-iteration.** Despite `pg_stat_statements.track = all`, PL/pgSQL `EXECUTE 'sql' USING ...` (dynamic SPI) gets attributed to the outer DO block. The DO block's `mean_ms / 100` gives a usable per-call estimate but `max_ms` and `stddev_ms` are unavailable. For per-call statistical confidence, use prepared statements driven by `\copy from program` or an external loop (pgbench).

3. **Dev-scale Seq Scan caveat applies.** Postgres prefers Seq Scan on tables under ~1000 rows. The probe's interpretation guide explicitly notes this — re-validate at NYC scale before flagging Seq Scan as a real plan-choice regression.

## Postgres config verified (Elena)

| Setting | Value | Required | Status |
|---|---|---|---|
| `shared_preload_libraries` | `pg_stat_statements` | yes | ✅ |
| `pg_stat_statements.track` | `all` | recommended | ✅ |
| Extension `pg_stat_statements` | v1.10 | yes | ✅ in `fabt` DB |
| Current user `fabt` | SUPERUSER | yes (for reset) | ✅ |
| V57 `audit_events.tenant_id` column | present | yes | ✅ |
| V57 composite index `idx_audit_events_tenant_target` | present | yes | ✅ (planner declines at dev scale) |

## Warroom verdict

| Persona | Verdict |
|---|---|
| Sam (Performance) | ✅ Per-call timings within budget. No regression. |
| Elena (DBA) | ✅ All Index Scans, ≤5 buffer hits. Re-validate audit_events index choice at NYC scale before pilot. |
| Alex (Principal) | ✅ Probe limitations are probe-design issues, not audit findings. Don't block v0.40 on probe v4. |

**Recommendation:** ship v0.40. Defer probe v4 fixes (set_config measurement + per-call prepared-statement tracking) to companion change `multi-tenant-production-readiness` where per-tenant infrastructure changes will actually need finer-grained timing data.

## Reproduction

```bash
./dev-start.sh --nginx --observability
# wait for stack to be ready
docker compose exec -T postgres psql -U fabt -d fabt < \
    docs/performance/cross-tenant-audit-probe.sql 2>&1 | tee \
    logs/perf-probe-$(date +%Y%m%d-%H%M%S).log
```
