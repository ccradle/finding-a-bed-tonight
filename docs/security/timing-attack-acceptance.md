# Timing-attack acceptance for `findByIdAndTenantId` (UUID-not-secret)

**Status:** ADR. Authoritative acceptance of the residual risk.
**Context:** multi-tenant-production-readiness change, decisions D10 and I1.
**Review date:** 2026-04-16 warroom (Marcus Webb lead; Alex Chen + Riley Cho concurring).

## Statement

FABT accepts that `findByIdAndTenantId(UUID, tenantId)` may exhibit marginally different response timings for:

- a UUID that exists in the caller's tenant but the row is filtered by some other invariant (e.g., wrong role) → slow 404 (full authorization path)
- a UUID that exists in a different tenant → slow 404 (DB lookup with tenant filter returns empty)
- a UUID that does not exist anywhere → slow 404 (DB lookup returns empty)
- a UUID shape that fails early validation → fast 400/404 (no DB hit)

The difference is typically sub-millisecond. A side-channel attacker with precise timing measurements could theoretically distinguish these cases and thereby learn facts about UUID existence without authorization.

**FABT does not consider resource UUIDs to be secrets**, and accepts this theoretical side-channel as a documented non-finding.

## Rationale

1. **UUIDs are random 128-bit values.** Enumeration requires on the order of 2^64 queries before a collision becomes likely (birthday bound). At any achievable query rate (rate limit: 10–100 req/sec per tenant), enumeration is computationally infeasible within the lifetime of the platform, the universe, or any realistic legal proceedings.

2. **UUID existence is not a data leak.** The timing distinguishes "some UUID X exists somewhere" from "X does not exist." It does NOT distinguish:
   - which tenant owns X (cross-tenant attribution)
   - what type of resource X is (shelter? referral? user?)
   - any content of the row
   - whether the caller would be authorized to read X if they were in its tenant

3. **The 404 response body is already public information.** Every endpoint that follows the D3 envelope contract returns a standard `{"error":"not_found","status":404,...}` payload regardless of why the row was filtered. The body leaks no information; timing is the only channel.

4. **Mitigation cost is user-facing.** Two mitigations were considered:
   - Constant-time 404 via fixed `Thread.sleep(floor_ms)` at the end of the request path. Adds 20–100ms of latency to every 404 (which are the common case for cross-tenant probes). Unambiguously worse UX for honest users; also fights against Sam's p95 < 500ms global SLO.
   - Random jitter on 404 response. Obscures timing somewhat but does not eliminate it; adds non-determinism to response time metrics, which operators read for capacity planning.
   Both were rejected as over-engineering for a non-exploitable theoretical concern.

5. **Industry precedent.** GitHub, GitLab, Stripe, Auth0, and other high-volume APIs all return 404-on-missing-id with response-time characteristics distinguishable from 200-with-authz-failure. None of these platforms treat the timing channel as a live finding requiring mitigation.

## What this decision does NOT accept

- **Cross-tenant data leakage** via 404 timing. If an attacker could determine "this UUID belongs to Tenant X specifically," that WOULD be a finding. The current design does not leak this — a 404 looks the same regardless of which tenant owns (or doesn't own) the UUID.

- **Authenticated 404 timing.** For an attacker WITH a valid JWT for Tenant A, a 404 on their own tenant vs. a cross-tenant UUID may differ. We accept this too — by design, Tenant A's authenticated view of cross-tenant-owned UUIDs is "doesn't exist" (D3 existence-leak prevention), and timing cannot distinguish that from a truly non-existent UUID. The D3 guarantee is at the status-code level, not the nanosecond level.

- **Side-channels other than 404 timing.** CPU-cache, memory-pressure, network-latency, heap-growth, GC-pause-pattern, log-volume, and similar cross-signal channels are not in scope of this ADR. Each would need separate analysis if a real-world exploit path surfaced.

## Revisit conditions

This ADR should be revisited if any of the following:

1. A credible exploit demonstration against another multi-tenant SaaS with comparable authorization architecture (not just academic theoretical work)
2. A regulated-tier pilot whose compliance team requires mitigation as a contractual obligation
3. Platform growth to volumes where rate-limit caps allow enumeration to become tractable
4. An NIST or OWASP guidance update explicitly calling this pattern a finding

On revisit, the mitigations above (constant-time floor, random jitter) are the canonical options; a third option (per-request synthetic delay budget) would need independent analysis.

## Sign-offs

- Marcus Webb (AppSec): "Accept. UUID-is-not-a-secret is the right posture; fixed-sleep cost outweighs theoretical benefit."
- Alex Chen (Principal): "Architecturally consistent with D3 existence-leak prevention; don't add latency to honest 404s."
- Riley Cho (QA): "Accept; document so next auditor sees it's a deliberate non-finding, not an oversight."
