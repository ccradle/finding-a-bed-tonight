# Redis pooling ADR — single-tenant Redis default, ACL-per-tenant for future shared-Redis

**Status:** ADR (architecture decision record). Authoritative. Update with amendments; do not delete.
**Authors:** multi-tenant-production-readiness Phase C warroom (Alex Chen + Marcus Webb + Jordan Reyes + Sam Okafor), 2026-04-19.
**Amendments:** 2026-04-19 PM — extended warroom review (Casey Drummond + Riley Cho + Elena Vasquez) + external-standards pass against OWASP ASVS 5.0, Redis Inc. Feb 2026, Azure Apr 2026, HIPAA Dec 2025. Six additive clarifications: Phase-A3 DEK compensating control on shape 2 blast-radius; `invalidateTenant` idempotency guarantee; concrete Redis-backed bleed harness pattern; new "Cached-value tenant verification" section; shape-2 Redis-ACL-not-automatic rationale; shape-3 HIPAA/VAWA encryption scope; external-standards cross-references.
**Scope:** The Redis deployment posture FABT offers alongside its pooled (default) and silo (regulated) tenancy tiers. Complements `docs/architecture/tenancy-model.md` row "Cache (Redis L2)". Governs when Redis is provisioned, whether it is shared across FABT tenants, and what isolation controls it requires.

## Context

FABT currently caches at **L1 only** (Caffeine, in-process). `TieredCacheService` (activated via `SPRING_PROFILES_ACTIVE=standard,full`) and `RedisReservationExpiryService` carry TODO-marked stubs for L2 Redis wiring; no L2 code path is live. The memory file `project_standard_tier_untested.md` records this as the prior stance.

Phase C of multi-tenant-production-readiness introduces `TenantScopedCacheService` — a Spring-bean decorator that prepends `TenantContext.getTenantId()` to every cache key and exposes `invalidateTenant(UUID)`. The wrapper is cache-backend-agnostic: it works identically whether the underlying cache is Caffeine-in-process or a future Redis L2. The wrapper alone, however, is an **application-layer** isolation control — one wrapper bypass leaks cross-tenant. Redis, with RDB/AOF persistence, can turn a transient in-memory bleed into a durable data leak.

This ADR decides **when Redis enters the deployment and how it is isolated** if/when it does.

## Decision

FABT operates Redis under a **single-tenant-by-default** posture with three authorised deployment shapes:

1. **Pooled + L1-only (default today, default tomorrow).** The pooled tier runs Caffeine L1 only. No Redis is deployed. `TenantScopedCacheService` enforces per-tenant key prefixing in-process; ArchUnit Family C (per spec `tenant-scoped-cache`) blocks tenant-unscoped Caffeine fields in `*.service` + `*.api` + `*.security` + `*.auth.*`. This is what findabed.org runs.

2. **Pooled + L2-single-tenant (authorised, not deployed today).** A pooled FABT deployment MAY provision one Redis instance dedicated to that deployment (single FABT backend → single Redis). Multiple FABT tenants share the one Redis instance, relying on `TenantScopedCacheService` key prefix for isolation. Authorisation gate before this lands: a reflection-driven Redis-backed bleed test analogous to the Caffeine one (spec Requirement `cache-bleed-regression-test`), run against a Testcontainers Redis, proving the prefix holds under concurrent writes.

3. **Silo + L2-silo (authorised for regulated tier).** A silo-tier tenant gets its own Redis container alongside its dedicated Postgres + backend VM per `tenancy-model.md`. Since the silo hosts exactly one FABT tenant, Redis has no cross-tenant blast radius by construction. `TenantScopedCacheService` prefix still applies (defence in depth against any future change that pools a silo), but is not the load-bearing control. **Encryption scope**: silo-tier Redis MUST run with AES-256 at-rest encryption and TLS 1.3 in-transit to meet the HIPAA Security Rule December 2025 update (AES-256 / TLS 1.3 mandatory for ePHI); `requirepass` uses a per-silo-generated password rotated on tenant FSM lifecycle transitions. The shape 3 deployment runbook (separate change, not this ADR) hard-codes these settings; this ADR states them as load-bearing for the regulated-tier BAA claim.

**Shared Redis without Redis-level per-tenant isolation is NOT supported.** This includes shared Redis + key-prefix-only (shape 2 extended across multiple backend deployments), shared Redis + logical-DB-per-tenant, and shared Redis with a single flat ACL.

**Shared Redis + ACL-per-tenant is reserved as a future escape valve, not a current option.** If cost-optimisation pressure ever forces multiple pooled FABT backends to share one Redis, ACL-per-tenant (Redis 6+ `+@read +@write ~<tenant-prefix>:*` per user) is the only acceptable pattern. Provisioning that requires a new ADR addendum with warroom sign-off + per-tenant ACL lifecycle wiring (create on tenant-activate, disable on suspend, drop on hard-delete).

**Why per-tenant Redis ACLs are not automatic within shape 2.** Redis ACLs enforce boundaries between Redis *connection principals* — distinct AUTH credentials on distinct connections. In shape 2, all Redis connections originate from a single FABT backend process (one Lettuce or Jedis pool); Redis sees one client application, not N tenants. Enforcing per-tenant ACL inside shape 2 would require the backend to maintain N per-tenant Redis connection pools tied to the tenant-lifecycle FSM — feasible but adds operational complexity disproportionate to the threat model, because the trust boundary inside shape 2 is already the single backend's code quality (same boundary Phase C's ArchUnit Family C + reflection bleed test + cached-value tenant verification defend). Current external guidance (Azure Apr 2026 Managed Redis Multitenancy, OWASP ASVS 5.0 May 2025, Redis Inc. Feb 2026) flags prefix-without-ACL as below-the-line when (a) prefix is the *sole* isolation control and (b) regulated data is cached. FABT shape 2 carries neither: cached-value tenant verification is a second application-layer control, and regulated tenants route to shape 3 (silo) by tenancy-model.md design. Monitored at the Phase C+1 `NoisyNeighborSimulation` gate; escalated to a new ADR addendum if a pooled tenant later signs a BAA or if per-tenant resource quotas become enforceable only at the Redis connection layer.

## Per-shape isolation spectrum

| Control | Pooled + L1-only (today) | Pooled + L2-single-tenant | Silo + L2-silo | Shared Redis + ACL (deferred) |
|---|---|---|---|---|
| Tenant-prefix in cache key | yes (`TenantScopedCacheService`) | yes | yes | yes |
| Reflection-driven bleed test | Caffeine only (Family C) | Caffeine + Redis | Caffeine + Redis | Caffeine + Redis |
| ArchUnit Family C enforcement | active | active | active | active |
| Redis-level AUTH | N/A | single password, full access | single password, full access | per-tenant user, per-tenant ACL pattern |
| Persistence (RDB/AOF) blast radius if wrapper bypass | N/A | cross-tenant durable leak possible | N/A (one tenant in the store) | blocked at Redis layer |
| `invalidateTenant(UUID)` procedure | L1 evict by prefix | L1 evict + `SCAN MATCH <cache>:<uuid>:* UNLINK` | same as pooled + L2 | L1 evict + tenant's ACL user runs the UNLINK |
| Suspend-hook enforcement | `TenantScopedCacheService.invalidateTenant` | same + Redis SCAN/UNLINK | same | same + ACL `user disable` |
| Hard-delete crypto-shred adequacy (Phase F F6) | tenant DEK shred ⇒ any residual ciphertext is undecipherable | same | same | same |

A pooled + L1-only deployment is the only shape exercised on findabed.org today. The other three shapes are authorised in code (wrapper + Family C + invalidateTenant all work cache-backend-agnostically) but gated on specific deployment triggers.

## Cached-value tenant verification (defence in depth)

Per OWASP Multi-Tenant Security Cheat Sheet (ASVS 5.0, May 2025) and Redis Inc.'s Feb 2026 multi-tenant-SaaS guidance, the highest-frequency cache-leak pattern in 2025-2026 post-mortems is **wrong tenant context on write** — not prefix collision. A caller with the wrong `TenantContext` bound (scheduled-job context drift, async continuation losing ScopedValue inheritance, a manually-constructed `CacheService` direct call slipping past the wrapper) writes Tenant A's data under Tenant B's prefix; subsequent Tenant B reads return Tenant A's data with no ArchUnit or wrapper warning because the key structure is well-formed from the wrapper's perspective.

FABT layers a second defence: entries written through `TenantScopedCacheService` MUST embed the `tenant_id` inside the value payload (record field or Jackson envelope), and the wrapper verifies stamped-tenant against `TenantContext.getTenantId()` on read; mismatches throw `IllegalStateException` tagged `CROSS_TENANT_CACHE_READ`, emit an `audit_events` row, and increment `fabt.cache.get{cache,tenant,result=cross_tenant_reject}`. Prefix defends the read side (Tenant B cannot read Tenant A's prefixed keys); stamp+verify defends the write side (mis-contexted callers cannot silently poison another tenant's keyspace). Both layers must fail in the same direction for a leak.

Implementation contract lands with task 4.1 (the wrapper's write+read methods); test coverage is task 4.9c (cross-tenant poisoning regression test: write with context A, read with context B, expect reject).

## Rejected alternatives

- **Shared Redis + logical-DB-per-tenant** (Redis `SELECT N`). Rejected: the default 16-DB cap makes this unscalable past 16 tenants; `FLUSHALL`, `CONFIG`, `DEBUG` cross DB boundaries without restriction; there is no Redis-level audit of cross-DB access; the escape valve (removing or aliasing those commands) is fragile and version-dependent. Not a meaningful isolation boundary over prefix-only.

- **Shared Redis + key-prefix only** (pooled FABT backends sharing one Redis with `TenantScopedCacheService` as the only isolation control). Rejected as the default: app-layer prefix is a single line of defence; one wrapper bypass or one direct `redisTemplate` call leaks cross-tenant; persistence amplifies the blast to durable storage. Acceptable only within shape 2 (one backend → one Redis), where the trust boundary reduces to the single backend's code quality.

- **Shared Redis + flat password** (one AUTH, multiple FABT backends). Rejected: same failure modes as key-prefix-only with the added risk that any compromised backend exfiltrates every tenant's cache in one SCAN.

- **Start with shared-Redis + ACL as a standard-tier feature now.** Rejected: Redis ACL operationalisation (per-tenant user, per-tenant ACL pattern, create/disable/drop tied to tenant-lifecycle FSM from Phase F) is substantial work with zero current traffic to justify it. Ship the posture as written; escalate when scale warrants.

- **Kubernetes-managed Redis sharded per tenant.** Rejected: FABT does not run on Kubernetes; Oracle Always Free has no managed K8s; regulated tenants get their own VMs (silo tier) which is a cleaner isolation story at the deployment level than a shard label.

## Invalidation + tenant-lifecycle procedures

Per spec Requirement `tenant-scoped-cache-invalidate-tenant`, `TenantScopedCacheService.invalidateTenant(UUID tenantId)` MUST evict every cache entry whose key begins with the tenant's prefix across every registered cache name. Behaviour per shape:

- **L1-only (today).** Iterate registered Caffeine cache names; for each, iterate the `Cache<String, Object>.asMap()` keyset and invalidate keys starting with `<tenantId>:`. Emit `audit_events` row `TENANT_CACHE_INVALIDATED` with cache-name list + eviction count.
- **L2-single-tenant or silo + L2.** L1 evict as above. In parallel, issue `SCAN 0 MATCH "<cacheName>:<tenantId>:*" COUNT 1000` + `UNLINK` per batch per cache name. `UNLINK` over `DEL` avoids blocking the Redis main thread on large tenants. Audit row includes both L1 and L2 counts.
- **Shared Redis + ACL (deferred).** Same as L2-single-tenant plus `ACL SETUSER <tenantId> off` to prevent the tenant's keys from being repopulated after eviction during suspension. Hard-delete additionally runs `ACL DELUSER <tenantId>`.

**Idempotency guarantee.** `invalidateTenant(UUID)` is idempotent: a failed SCAN/UNLINK pass (network blip, Redis restart mid-batch, SIGTERM mid-iteration, backend crash) is safe to retry. Partial completion is reflected in the emitted `audit_events` row's `TENANT_CACHE_INVALIDATED` eviction count; the tenant-lifecycle FSM re-invokes `invalidateTenant` on state-transition replay (Phase F F4) so an operator paged at 3am does not have to reason about "did the last call finish." Caffeine L1 invalidation is synchronous and cannot partial-fail within a single JVM; Redis L2 `SCAN` + `UNLINK` per batch is the only surface where retries matter, and `UNLINK` is naturally idempotent on already-deleted keys.

**Hard-delete crypto-shred interaction (Phase F F6).** Tenant hard-delete crypto-shreds the tenant's DEK. Any residual bytes in Redis persistence (RDB snapshot, AOF replay log) that survive the UNLINK are rendered undecipherable because cache values encrypted at rest use the tenant DEK. This is the primary defence against "but what if UNLINK misses a key due to a SCAN-during-write race"; the secondary defence is that caches hold short-TTL'd operational data, not durable records.

## Upgrade path: pooled + L1 → pooled + L2-single-tenant

Operator-driven, zero-downtime:

1. Provision a Redis container (single-tenant from Redis's perspective; one FABT backend is its only client).
2. Deploy the Redis-L2 wiring change for `TieredCacheService` (removes today's TODO markers; implements SCAN + UNLINK in `invalidateTenant`; integration tests against Testcontainers Redis).
3. Ship the Redis-backed reflection bleed test harness (spec scenario "Reflection-driven cache-bleed fixture"): extends the existing `ReflectionDrivenCacheBleedTest` with a Testcontainers Redis profile; `RedisTemplate` / `ReactiveRedisTemplate` bean-usage scan replaces the Caffeine-field scan; the same `EXPECTED_MIN_SITES` silent-empty guard (design-c D-C-7) applies. Shape 2 deployment does not proceed until the harness is green against a multi-tenant concurrent-write load on Testcontainers Redis.
4. Flip `SPRING_PROFILES_ACTIVE` to include `standard` + set `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` env vars.
5. Boot; observe `fabt.cache.{get,put}{cache,tenant,result}` Micrometer counters (spec Requirement `tenant-scoped-cache-observability`) reflecting L2 hits.
6. Per-tenant cache statistics Grafana dashboard (deferred to Phase G) surfaces cross-tenant key-prefix drift if any occurs.

Migration of existing tenants' cache state: **none required.** Caches are short-TTL'd operational data; the pooled-with-L2 deploy cold-starts L1 and L2; there is no tenant data migration.

## Upgrade path: pooled → silo (Redis aspect)

Per `tenancy-model.md` "Upgrade path: pooled → silo", a CoC migrating to the silo tier gets a new Redis container provisioned alongside their dedicated Postgres + backend VM. The pooled deployment's Redis (if any) keeps running for the remaining pooled tenants; the migrated tenant's keys are evicted via `invalidateTenant(UUID)` at the JSON-export-and-hard-delete step. No Redis-level data migration between pooled and silo — cache cold-starts on the silo backend.

## Blast-radius statement (Redis-specific)

Bounding the failure modes this ADR is intended to prevent:

- **Pooled + L1-only (today).** Worst case: one tenant reads another tenant's cached bed-search result via a `TenantScopedCacheService` wrapper bypass or a caller forgetting to route through the wrapper. Bounded to the JVM's in-memory state; resolved by container restart or explicit `evictAll`. Detection: reflection-driven Family C test catches drift; Micrometer `fabt.cache.get{tenant,result}` counters surface unexpected tenant-labelled traffic.
- **Pooled + L2-single-tenant (authorised, not deployed).** Worst case: same wrapper-bypass leak becomes durable in Redis RDB/AOF. Bounded to the one Redis instance; resolved by `FLUSHALL` or container restart + key-scan audit. Gated on Redis-backed bleed test landing before deployment. **Pre-remediation compensating control**: cache values for sensitive entities (session claims, auth tokens, tenant-specific policy snapshots) are encrypted at rest under the tenant's Phase-A3 DEK; a leaked RDB fragment carrying Tenant A's ciphertext is undecipherable to a Tenant B principal even if scanned from the raw file, because Tenant B does not possess Tenant A's DEK.
- **Silo + L2-silo.** Worst case: wrapper bypass persists one tenant's data to its own Redis — no cross-tenant blast possible because only one tenant is present. Bounded trivially.
- **Shared Redis + ACL (deferred future).** Worst case: ACL pattern misconfiguration during a tenant-lifecycle event (e.g., a SETUSER typo allowing `~*` instead of `~<prefix>:*`) leaks across tenants until the ACL is corrected. Detection: mandatory post-provisioning ACL-pattern audit log + per-tenant SCAN probe that asserts the tenant user cannot read foreign keys. This is why this shape requires a future ADR addendum.

## Anti-goals (explicitly NOT in this ADR)

- **Specific Redis deployment syntax** (`docker-compose.yml` entries, `redis.conf` values, ACL command text). Out of scope — this ADR documents the posture. Syntax lands in the separate wiring change when a deployment needs it.
- **Pub/Sub for cross-region cache invalidation.** Out of scope — FABT does not run multi-region within one instance (`tenancy-model.md` anti-goal).
- **Redis as a primary data store.** Out of scope — Postgres remains primary; Redis is only a cache (and a reservation-expiry keyspace-notification sink once `RedisReservationExpiryService` is implemented).
- **A second caching library to run alongside Caffeine or Redis** (Guava, Ehcache, Hazelcast). Out of scope — spec Family C ArchUnit rule effectively locks the caching layer to Caffeine (+ future Redis via the wrapper).

## Stakeholder sign-offs

The single-tenant-default + ACL-reserved model has been reviewed and approved by the Phase C warroom (2026-04-19):

- **Alex Chen (architecture lens)** — three-shape taxonomy maps cleanly onto the pooled/silo model in `tenancy-model.md`; no schema / service-layer change needed to enable shapes 2 and 3 when they land; the ADR is the only artefact required to ship Phase C.
- **Marcus Webb (security lens)** — rejecting shared Redis without ACL as the default is correct; app-layer prefix as single-line-of-defence durably in RDB/AOF is the exact failure mode to avoid. ACL-per-tenant being deferred behind a future-addendum requirement is the right forcing function.
- **Jordan Reyes (SRE lens)** — L1-only is cheapest to operate (one fewer stateful component); L2-single-tenant is the lowest-overhead upgrade; Redis-per-silo matches the one-VM-per-silo deployment model already documented. `invalidateTenant` SCAN/UNLINK procedure is non-blocking on the Redis main thread; audit-row emission gives ops a post-incident trail.
- **Sam Okafor (performance lens)** — no-Redis today does not cost measurable latency at current scale (bed-search p99 ~180ms L1-backed); Redis wiring + prefix adds one network hop + serialisation cost that requires a fresh perf gate (Gatling `NoisyNeighborSimulation` extension) when shapes 2 or 3 land — tracked as a Phase C+1 item, not a blocker for this ADR.

Extended review (2026-04-19 PM), on the amendments above:

- **Casey Drummond (legal lens)** — shape 3 HIPAA/VAWA encryption scope makes the regulated-tier BAA claim defensible against the December 2025 Security Rule update; silo+L2-silo interaction with `data_residency_region` (Phase F7) keeps DV survivor data on one VM in one jurisdiction.
- **Riley Cho (QA lens)** — concrete Redis-backed bleed harness pattern (extends `ReflectionDrivenCacheBleedTest` with Testcontainers Redis) converts a verbal commitment into a QA-gate-able artifact; task 4.9c covers the cross-tenant cache-poisoning regression.
- **Elena Vasquez (data/privacy lens)** — Phase-A3 DEK-at-rest as shape 2 compensating control + cached-value tenant verification as second app-layer defence close the write-side leak class called out in Redis Inc. Feb 2026 guidance; short-TTL operational-cache stance remains adequate against durable-PII exposure.

Future changes to this model (in particular, activating the shared-Redis + ACL shape) require a new ADR addendum reviewed by the same extended lens set.

## Cross-references

### Internal
- `docs/architecture/tenancy-model.md` — row "Cache (Redis L2)"
- `openspec/changes/multi-tenant-production-readiness/specs/tenant-scoped-cache/spec.md` — Requirements `redis-pooling-adr`, `tenant-scoped-cache-key-prefix`, `tenant-scoped-cache-invalidate-tenant`, `cache-bleed-regression-test`
- `openspec/changes/multi-tenant-production-readiness/design-c-cache-isolation.md` — non-decision "Redis ACL syntax"
- `project_standard_tier_untested.md` — the prior stance this ADR codifies
- `backend/src/main/java/org/fabt/shared/cache/TieredCacheService.java` — current L2 TODOs
- `backend/src/main/java/org/fabt/reservation/service/RedisReservationExpiryService.java` — current L2 TODOs

### External standards (2025-2026)
- [OWASP Multi-Tenant Security Cheat Sheet (ASVS 5.0, May 2025)](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html) — cross-tenant controls requirement; prefix + ACL + cached-value verification pattern
- [Redis Inc. — Data Isolation in Multi-Tenant SaaS (Feb 6, 2026)](https://redis.io/blog/data-isolation-multi-tenant-saas/) — phased pooled→silo posture; "prefix is necessary, not sufficient"; write-side poisoning as leading failure mode
- [Azure Architecture Center — Managed Redis Multitenancy (updated Apr 15, 2026)](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/service/managed-redis) — shared-vs-per-tenant trade-off; CMK-per-instance scope; regulated-tier silo guidance
- [HIPAA Encryption Requirements — December 2025 Security Rule update](https://www.hipaajournal.com/hipaa-encryption-requirements/) — AES-256 at rest + TLS 1.3 in transit mandatory for ePHI (load-bearing for shape 3)
- [AWS SaaS Lens — Tenant Isolation](https://docs.aws.amazon.com/wellarchitected/latest/saas-lens/tenant-isolation.html) — silo/pool/bridge model; canonical reference in `tenancy-model.md`
- [NIST SP 800-190 — Application Container Security Guide](https://csrc.nist.gov/pubs/sp/800/190/final) — still-canonical container-isolation reference relevant to shape 3 silo VM deployments
