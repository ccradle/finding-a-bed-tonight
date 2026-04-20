# Runbook: Phase C cache-isolation alert triage

**Applies to:** v0.47.0 and later (Phase C — `TenantScopedCacheService` wrapper live across all application call sites)
**Owner:** SRE on-call + application security (escalate CRITICAL)
**Last reviewed:** 2026-04-19

## Alerts covered

| Alert | Severity | Rule file | Meaning |
|---|---|---|---|
| `FabtPhaseCCrossTenantCacheRead` | **CRITICAL** | `deploy/prometheus/phase-c-cache-isolation.rules.yml` | Wrapper rejected a cache read because the stored envelope's tenant UUID did not match the reader's |
| `FabtPhaseCMalformedCacheEntry` | WARN | same | Wrapper fetched a non-envelope payload from a cache — a caller wrote through `CacheService` bypassing the wrapper |
| `FabtPhaseCCacheHitRateCollapse` | WARN | same | Per-cache hit rate dropped more than 50% below its 7-day average |
| `FabtPhaseCDetachedAuditPersistFailure` | WARN | same | `DetachedAuditPersister` swallowed a persistence exception writing a security-evidence audit row (cross-tenant-read or cross-tenant-policy-read evidence lost) |

## Why these alerts exist

Phase C installs a `TenantScopedCacheService` wrapper that prefixes every cache key with the caller's tenant UUID AND stamps every stored value with a `TenantScopedValue(UUID tenantId, T value)` envelope. On read, the wrapper verifies both the prefix (caller can't read another tenant's slot) and the envelope's tenant (caller can't consume a value stamped by a different tenant, even if they reach the same underlying cache slot by some bug or attack).

- **Prefix alone** catches honest reader mistakes (tenant B trying tenant A's key).
- **Envelope alone** catches writer mistakes (tenant A's code path runs with stale `TenantContext` and writes into a slot that another tenant's reader later reaches).

Both have to fail in the same direction for a leak. The three alerts page when the wrapper says either check fired.

---

## Alert 1 — `FabtPhaseCCrossTenantCacheRead` (CRITICAL)

**PromQL**: `sum by (tenant, cache) (rate(fabt_cache_get_total{result="cross_tenant_reject",env="prod"}[5m])) > 0`
**For**: 0m (immediate page)

### What just happened

Someone's read path hit `TenantScopedCacheService.get(cacheName, key, ...)`. The wrapper fetched a value from the underlying cache; the value was a `TenantScopedValue` envelope; the envelope's `tenantId` did NOT match `TenantContext.getTenantId()` at read time. Wrapper threw `IllegalStateException("CROSS_TENANT_CACHE_READ")`, incremented the counter that triggered this alert, and persisted an `audit_events` row with `action='CROSS_TENANT_CACHE_READ'` via `DetachedAuditPersister` REQUIRES_NEW (survives caller rollback).

This counter fires only on the wrapper's envelope-verify path. It should physically never fire in correct production operation.

### Possible causes

1. **Async continuation context drift** — code crossed a thread boundary (virtual thread hand-off, `CompletableFuture`, SSE loop) without re-binding `TenantContext.runWithContext(...)`. New thread carries a stale or null context; wrapper reads a value stamped by the prior tenant on the old thread.
2. **Scheduled-job-forgot-to-bind** — a new `@Scheduled` method that accesses a cache skipped the `TenantContext.runWithContext` wrap. ArchUnit `EscalationPolicyBatchOnlyArchitectureTest` covers the known batch path; a novel scheduled caller won't be on that allowlist.
3. **Attacker-triggered** — an HTTP endpoint that races a tenant-switch or manipulates session state to read a cached value stamped by a different tenant. Rare — the wrapper's prefix would need to match AND the envelope tenant would need to mismatch, which requires a very specific pattern.

### Triage — within 5 minutes

1. **Confirm the alert labels**:
   ```bash
   # Alertmanager UI or:
   curl -s 'http://prometheus:9090/api/v1/alerts' | jq '.data.alerts[] | select(.labels.alertname=="FabtPhaseCCrossTenantCacheRead")'
   ```
   Note the `tenant` and `cache` labels — these are the READER's tenant and the cache name.

2. **Read the audit row** — it has actor + observed vs expected tenant:
   ```sql
   -- Connect as fabt owner (NOT fabt_app) to bypass RLS during diagnosis.
   -- Wrap count query in TenantContext of the reader's tenant if connecting as fabt_app.
   SELECT id, timestamp, actor_user_id, details
   FROM audit_events
   WHERE action = 'CROSS_TENANT_CACHE_READ'
     AND timestamp > NOW() - INTERVAL '15 minutes'
   ORDER BY timestamp DESC
   LIMIT 20;
   ```
   `details` is JSONB with `{cache_name, reader_tenant, stamped_tenant, caller_key}`. The `caller_key` (logical key, no tenant prefix) is the most useful triage signal.

3. **Cross-reference with application logs**:
   ```bash
   docker logs --since 15m fabt-backend 2>&1 | \
       grep -E "CROSS_TENANT_CACHE_READ|TenantContext.runWithContext" | tail -100
   ```
   Look for the stack trace at the throw site. The caller method is the suspect.

4. **Decide blast radius**:
   - If the `caller_key` suggests a specific resource (e.g., `shelter-UUID-abc`) AND the reader's tenant is NOT the owner of that UUID → **scope a per-tenant cache invalidation** as a precaution:
     ```bash
     # Platform-admin API call
     curl -X POST -H "Authorization: Bearer $PLATFORM_ADMIN_TOKEN" \
         "https://findabed.org/api/v1/admin/cache/invalidate?tenantId=${READER_TENANT}"
     ```
   - If multiple audit rows with different `caller_key` but same `reader_tenant` → the reader's context was broken across multiple reads. Full tenant-invalidate.
   - If multiple `reader_tenant` values in the 15-min window → a systemic bug or active attack. **Escalate to security lead; do NOT attempt resolution alone**.

### Rollback criterion

If the alert fires on a release day within 2 hours of deploy AND the suspect code path is in the release, ROLL BACK. Phase C's value-stamp-and-verify is correctness-critical; false positives at this level do not exist by design.

---

## Alert 2 — `FabtPhaseCMalformedCacheEntry` (WARN)

**PromQL**: `sum by (cache) (rate(fabt_cache_get_total{result="malformed_entry",env="prod"}[15m])) > 0`
**For**: 15m

### What happened

Wrapper `get()` fetched an entry from the underlying cache that was NOT a `TenantScopedValue` envelope OR whose inner value's runtime type did not match the caller's requested type. Wrapper threw `IllegalStateException("MALFORMED_CACHE_ENTRY")`.

This means someone put a raw non-envelope value into the cache via `CacheService.put(...)` directly, bypassing the wrapper's stamp step. After the v0.47.0 release + Task 4.b drain, this counter should be zero — every production put goes through the wrapper.

### Possible causes

1. **Wrapper-bypass on write** — a new `CacheService.put(...)` call site landed without the Family C ArchUnit rule catching it (scope-package typo, new package not in the rule's scope list). ArchUnit rules C1+C3 should catch this at build time; a non-zero rate means one slipped through.
2. **Test-path leakage** — an integration test that directly writes via raw `CacheService` polluted a shared cache instance. Shouldn't be possible with Testcontainers but has happened historically.
3. **Deserialization skew** — Caffeine's `asMap()` held a value from a prior release before the envelope existed; envelope shape changed without a cache invalidation on deploy. v0.47.0 deploy on a fresh JVM eliminates this; subsequent hot deploys need cache flush.

### Triage — within 15 minutes

1. **Identify the cache name** from the alert `cache` label.
2. **Grep for raw put sites** on that cache name:
   ```bash
   cd finding-a-bed-tonight
   grep -rn "cacheService.put.*${CACHE_NAME}\|cacheService.put(.*\"${CACHE_NAME}\"" backend/src/main/java/
   ```
   Every hit must be either through `TenantScopedCacheService` OR annotated `@TenantUnscopedCache`.
3. **Confirm via Family C**: run `mvn -Dtest=FamilyCArchitectureTest test` locally against the current main. If it passes but the alert still fires, there's a bypass the rule doesn't catch — open an issue and add a negative-test fixture mirroring `UnannotatedCaffeineFixture.java`.
4. **Flush the cache** to stop the bleed while you find the bypass:
   ```bash
   curl -X POST -H "Authorization: Bearer $PLATFORM_ADMIN_TOKEN" \
       "https://findabed.org/api/v1/admin/cache/invalidate?tenantId=${AFFECTED_TENANT}"
   ```
   Repeat per affected tenant. If multiple tenants or all — restart backend (cache is in-memory Caffeine; restart clears it).

### When to rollback

Not immediate. Malformed entries don't corrupt data — they throw on read and fail the individual request. Investigate in-hours; rollback only if the bypass is active on every write and users see sustained 500s.

---

## Alert 3 — `FabtPhaseCCacheHitRateCollapse` (WARN)

**PromQL**: compares current-1h hit-rate against 7-day moving avg; fires if current < avg × 0.5
**For**: 30m

### What happened

Cache is being hit (`put` and `get` counters both incrementing) but hit-rate dropped by more than half vs. the 7-day baseline for 30+ minutes. The wrapper is running; callers are reaching it; but the `get` keys don't match the `put` keys — so every read is a miss, every miss goes to DB, every DB result is re-put, cycle repeats with no hits.

### Possible causes

1. **Migration drift** — a recent PR changed a caller's cache-key computation (composite-key `toString()` reordering, accidentally stripped a caller-side prefix on one side only). `Task4bCacheHitRateTest` is the first line of defence here; if it didn't cover the changed caller, this alert is the fallback.
2. **TTL too short** — a TTL was shortened to less than the typical re-fetch interval; entries expire before they're re-read. Check recent commits to `CACHE_TTL` constants.
3. **Cache eviction under pressure** — Caffeine's bounded size (`maximumSize`) is exceeding capacity and LRU-evicting before re-read. Check JVM heap + the specific cache's `.maximumSize(...)` builder call.

### Triage — within 30 minutes

1. **Identify the cache name** from the alert `cache` label.
2. **Compare put counter to get-miss counter rates**:
   ```
   rate(fabt_cache_put_total{cache="${CACHE_NAME}",env="prod"}[10m])
   rate(fabt_cache_get_total{cache="${CACHE_NAME}",result="miss",env="prod"}[10m])
   ```
   Similar rates = normal (cache warming). Miss rate >> put rate = the put path stopped working. Miss rate >> get-hit rate + put rate similar to pre-regression = key-drift.
3. **Check recent commits** to the caller class of that cache name:
   ```bash
   git log --since="7 days ago" --name-only | \
       grep -iE "$(echo $CACHE_NAME | tr '_-' ' ')" | head -10
   ```
4. **Run hit-rate test locally** against main:
   ```bash
   cd backend && mvn -Dtest=Task4bCacheHitRateTest test
   ```
   If it passes and the alert still fires, the caller-to-wrapper path is OK; investigate TTL + eviction.

### When to rollback

Not immediate. Hit-rate collapse degrades performance but doesn't corrupt data or leak across tenants. Fix forward; rollback only if p95 regresses more than 30% vs. pre-deploy baseline.

---

## Alert 4 — `FabtPhaseCDetachedAuditPersistFailure` (WARN)

**PromQL**: `sum by (action) (rate(fabt_audit_detached_failed_count_total{env="prod"}[15m])) > 0`
**For**: 15m

### What happened

`DetachedAuditPersister.persistDetached(...)` caught a persistence exception while writing a security-evidence audit row and swallowed it (correct posture — re-throwing would mask the original `IllegalStateException` that fired the detached persist in the first place; the caller's user would see a 500 for the WRONG reason). The counter fires so the swallow is observable. Each counter increment = one lost audit row; the `action` tag identifies which kind of security event was being logged.

### Why we care

`DetachedAuditPersister` exists because the Phase C wrapper throws on cross-tenant reads inside `@Transactional` endpoints, and the classic attacker pattern is: trigger the cross-tenant read, let the `@Transactional` rollback, hope the audit row rolls back too. `REQUIRES_NEW` propagation keeps the audit row committed even if the caller's transaction rolls back. When the wrapping fails for ANY reason — DB down, RLS rejection, FK constraint, constraint violation — we lose the very audit row that proves the attack happened.

This alert's severity is WARN (not CRITICAL) because the underlying security event — the `CROSS_TENANT_CACHE_READ` or `CROSS_TENANT_POLICY_READ` exception — already fired at its own CRITICAL level (Alert 1). This alert is the signal that we LOST the evidence; the event itself was surfaced elsewhere.

### Possible causes

1. **RLS rejection (SQLState 42501)** — the connection's `app.tenant_id` GUC wasn't bound at persist time. `REQUIRES_NEW` opens a fresh connection; if `AuditEventPersister` (the chained persister inside `DetachedAuditPersister`) doesn't re-bind the tenant, FORCE RLS on `audit_events` blocks the INSERT. Phase B guard territory.
2. **FK constraint violation** — `actor_user_id` references `app_user`; if the acting user was deleted between the event and the persist, the INSERT fails. Rare but happens in tenant-lifecycle paths.
3. **DB connectivity drop** — Hikari connection timeout, long-running txn exhausted pool. Correlated with `hikari_pool_connections_pending` spikes.

### Triage — within 30 minutes

1. **Check which `action` tag is firing**:
   ```bash
   # In Grafana or:
   curl -sf http://localhost:9091/actuator/prometheus | \
       grep '^fabt_audit_detached_failed_count_total'
   ```
   `action=CROSS_TENANT_CACHE_READ` vs `CROSS_TENANT_POLICY_READ` localizes which security surface is affected.
2. **Find the swallow stack trace**:
   ```bash
   docker logs --since 1h fabt-backend 2>&1 | \
       grep -A 20 "DetachedAuditPersister failed for action=" | tail -100
   ```
   Root cause is in the stack trace; SQLState is printed with the exception.
3. **Cross-check Alert 1** — if `FabtPhaseCCrossTenantCacheRead` is firing at the same rate, the security events are happening AND being unlogged; this is a dual-incident (page on-call security + SRE).
4. **Count actual audit rows** to compare with fired-but-unpersisted signal:
   ```sql
   SELECT action, COUNT(*) FROM audit_events
   WHERE timestamp > NOW() - INTERVAL '1 hour'
     AND action IN ('CROSS_TENANT_CACHE_READ','CROSS_TENANT_POLICY_READ')
   GROUP BY action;
   ```
   Compare against `fabt_cache_get_total{result="cross_tenant_reject"}` aggregate. Delta = lost rows.

### When to rollback

Not usually. This alert is a signal that something else is broken (DB, RLS, connection pool). Fix the root cause; audit rows persist once it's resolved. Rollback v0.47.0 only if a NEW code path in v0.47.0 is provably the cause of the persist failure AND on-call can't mitigate via the obvious paths (DB restart, connection pool recycle).

---

## Cross-cutting signals

### `fabt_cache_registered_cache_names` gauge

Expected steady-state value: **11** (one per `CacheNames` constant). Deviation means the wrapper's eager-seed `@PostConstruct` found a different number of constants — almost certainly a broken deploy (classloader skew, partial JAR). Paired with the CI check `CacheIsolationDiscoveryTest.discoveryMatchesExpectedCount` which enforces exact-equals 11 at build time.

### `fabt.cache.get{result=hit|miss|cross_tenant_reject|malformed_entry}`

All four result values should be observable. A flat-line `hit=0` at steady-state traffic (no new deploys) is a silent-wrapper signal (wrapper is running but every op is a miss — handled by Alert 3).

---

## Escalation

- **CRITICAL unresolved 15 min → security lead.**
- **Multiple tenants in cross-tenant-reject audit rows → all-hands security bridge.**
- **Alert on post-deploy window → consider rollback before continuing triage**.

Refer to `docs/oracle-update-notes-v0.47.0.md` for the v0.47.0 deploy + rollback commands.
