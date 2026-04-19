package org.fabt.notification.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.security.TenantUnscoped;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.repository.EscalationPolicyRepository;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service over the {@code escalation_policy} table.
 *
 * <p>Two responsibilities:</p>
 *
 * <ol>
 *   <li><b>Lookup with cache</b> for the escalation batch job
 *       ({@link #findById}, {@link #getCurrentForTenant}). The batch job runs
 *       every 5 minutes and reads the policy for each pending referral; without
 *       a cache that's N queries per run.</li>
 *   <li><b>Validated update</b> ({@link #update}) for the admin
 *       {@code PATCH /api/v1/admin/escalation-policy/{eventType}} endpoint
 *       (Session 4). Validates monotonic thresholds, valid roles, and valid
 *       severities BEFORE inserting a new version row, so invalid policies
 *       never reach the append-only table.</li>
 * </ol>
 *
 * <p><b>Two Caffeine caches:</b></p>
 *
 * <ul>
 *   <li>{@code policyById} — keyed by policy UUID. Used by the batch job to
 *       resolve a referral's frozen policy. Hit rate: very high (only changes
 *       when an admin PATCHes the policy, which inserts a new id). Bounded
 *       size 500, TTL 10 min.</li>
 *   <li>{@code currentPolicyByTenant} — keyed by {@code (tenantId, eventType)}.
 *       Used by {@code ReferralTokenService.create()} to snapshot the current
 *       policy onto a new referral. Hit rate: high but updates must be visible
 *       quickly because admin PATCHes shift "what new referrals will use."
 *       Bounded size 200, TTL 5 min, programmatically invalidated on
 *       {@link #update}.</li>
 * </ul>
 *
 * <p><b>Append-only is enforced at three layers:</b></p>
 *
 * <ol>
 *   <li>The Flyway V40 migration declares no UPDATE or DELETE policy on the
 *       table — PostgreSQL rejects mutations at the catalog level.</li>
 *   <li>{@link EscalationPolicyRepository} exposes only read methods plus
 *       {@code insertNewVersion(...)}. There is no {@code update(...)} or
 *       {@code delete(...)} method.</li>
 *   <li>This service's {@link #update} method is named "update" to match the
 *       admin endpoint contract, but its implementation calls
 *       {@code repository.insertNewVersion(...)} — semantically a "publish a
 *       new version" operation, not a row mutation.</li>
 * </ol>
 */
@Service
public class EscalationPolicyService {

    private static final Logger log = LoggerFactory.getLogger(EscalationPolicyService.class);

    /** Valid recipient roles. Synced with the role enum in auth module. */
    private static final Set<String> VALID_ROLES = Set.of(
            "COORDINATOR", "COC_ADMIN", "OUTREACH_WORKER", "PLATFORM_ADMIN");

    /** Valid notification severities. Synced with notification.severity column convention. */
    private static final Set<String> VALID_SEVERITIES = Set.of(
            "INFO", "ACTION_REQUIRED", "CRITICAL");

    /**
     * Allowed shape for {@code threshold.id}: lowercase letters, digits, and
     * underscore. The id is concatenated into the notification type column
     * (e.g. {@code escalation.1h}) which is keyed on by the frontend
     * NotificationBell switch — keeping the character set tight prevents
     * accidental delimiter collisions and odd Unicode shenanigans.
     */
    private static final Pattern THRESHOLD_ID_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private final EscalationPolicyRepository repository;
    private final DetachedAuditPersister detachedAuditPersister;
    private final MeterRegistry meterRegistry;

    /**
     * Cache for findById lookups (batch job hot path).
     * Policies are immutable once inserted (append-only), so this cache never
     * needs invalidation — entries simply expire after 10 minutes of idle.
     *
     * <p><b>.recordStats() is REQUIRED</b> for Micrometer's
     * {@link CaffeineCacheMetrics} to emit non-zero hit/miss/put counters.
     * Without it, the observability stack silently reports all zeros —
     * {@code EscalationPolicyServiceCacheMetricsTest} pins this against
     * regression (T-53, Alex Chen review 2026-04-12).</p>
     */
    private final Cache<UUID, EscalationPolicy> policyById = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofMinutes(10))
            .recordStats()
            .build();

    /**
     * Cache for "current policy for this tenant + event type" lookups
     * (referral creation hot path). Programmatically invalidated on update().
     *
     * <p><b>.recordStats() is REQUIRED</b> — see {@link #policyById} comment.</p>
     */
    private final Cache<CurrentKey, EscalationPolicy> currentPolicyByTenant = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(5))
            .recordStats()
            .build();

    /**
     * Cache for request-path lookups by {@code (tenantId, policyId)} — introduced
     * in Phase C task 4.4 per design-c D-C-2. The caller passes their current
     * {@code TenantContext.getTenantId()} explicitly; the service verifies on
     * miss that the stored policy's {@code tenantId} matches (or is the
     * platform-default NULL) and rejects otherwise. Prevents the
     * cross-tenant-policy-leak class that would exist if a request path
     * called {@link #findByIdForBatch} (which is intentionally {@code @TenantUnscoped}).
     *
     * <p><b>TTL shape matches {@link #policyById}</b> ({@code expireAfterAccess(10 min)})
     * because {@code (tenantId, policyId)} identifies an immutable version —
     * {@code expireAfterWrite} would be semantically wrong (the entry never
     * goes stale, only cold). This is the same reasoning that applies to
     * {@link #policyById}. Note that {@link #currentPolicyByTenant} uses
     * {@code expireAfterWrite} because its key {@code (tenantId, eventType)}
     * points to <em>whatever is current</em> — which IS mutated by
     * {@link #update(String, List, UUID)}. (D-4.4-5 warroom resolution.)</p>
     *
     * <p><b>Platform-default N× duplication is intentional.</b> If N tenants
     * each look up platform-default policy {@code p}, we cache {@code (A, p)},
     * {@code (B, p)}, {@code (C, p)}, ... separately. N × ~500-byte records is
     * trivial memory at our tenant scale; the complexity of a shared
     * {@code (null, p)} fallback cache lookup is not worth the savings
     * (D-4.4-1 warroom resolution).</p>
     *
     * <p><b>TODO(task 4.b)</b>: first request-path caller migration. No
     * production code path currently invokes {@link #findByTenantAndId} — it
     * exists today to (a) direct offenders of the Family C ArchUnit rule
     * (task 4.2) away from {@link #findByIdForBatch}, and (b) land the
     * on-read tenant-verification + observability contract with testing
     * discipline before the first real caller. When task 4.b migrates a
     * caller, remove this TODO note.</p>
     */
    private final Cache<PolicyKey, EscalationPolicy> policyByTenantAndId = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofMinutes(10))
            .recordStats()
            .build();

    /**
     * Constructor. Binds the three Caffeine caches to Micrometer via
     * {@link CaffeineCacheMetrics#monitor} using fabt-prefixed cache names.
     * The emitted metric family is {@code cache.gets{cache="fabt.escalation.policy.by-id",result="hit|miss"}}
     * and analogous for {@code current-by-tenant} + {@code by-tenant-and-id} —
     * hit rate is a downstream Grafana/PromQL formula, not a directly emitted
     * metric (T-53, Alex Chen review 2026-04-12).
     *
     * <p>The Phase C task 4.4 addition of {@code policyByTenantAndId} (and its
     * {@link DetachedAuditPersister} dependency for cross-tenant-reject audit
     * rows) leaves {@link #findByIdForBatch} untouched — the batch path's
     * unscoped access is reserved for {@code @Scheduled} callers via the
     * {@code EscalationPolicyBatchOnlyArchitectureTest} ArchUnit rule.</p>
     */
    public EscalationPolicyService(EscalationPolicyRepository repository,
                                    DetachedAuditPersister detachedAuditPersister,
                                    MeterRegistry meterRegistry) {
        this.repository = repository;
        this.detachedAuditPersister = detachedAuditPersister;
        this.meterRegistry = meterRegistry;
        CaffeineCacheMetrics.monitor(meterRegistry, policyById, "fabt.escalation.policy.by-id");
        CaffeineCacheMetrics.monitor(meterRegistry, currentPolicyByTenant, "fabt.escalation.policy.current-by-tenant");
        CaffeineCacheMetrics.monitor(meterRegistry, policyByTenantAndId, "fabt.escalation.policy.by-tenant-and-id");
        log.info("EscalationPolicyService caches initialised: policyById (size=500, access=10m), "
                + "currentPolicyByTenant (size=200, write=5m), policyByTenantAndId (size=500, access=10m)");
    }

    /**
     * Resolve a frozen policy by its id — batch-callable, intentionally
     * cross-tenant. Used by the escalation batch job to read the policy
     * that a referral was snapshotted against at creation time.
     *
     * <p>Renamed from {@code findById} per design D7 to make the intent
     * unambiguous: this method is reachable from
     * {@link org.fabt.referral.batch.ReferralEscalationJobConfig} only;
     * non-batch callers must use a tenant-scoped lookup.</p>
     */
    @TenantUnscoped("batch-job policy snapshot resolution for referral escalation — platform-wide by design")
    public Optional<EscalationPolicy> findByIdForBatch(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        EscalationPolicy cached = policyById.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<EscalationPolicy> loaded = repository.findById(id);
        loaded.ifPresent(p -> policyById.put(id, p));
        return loaded;
    }

    /**
     * Tenant-scoped policy lookup by policy id — the request-path counterpart
     * to {@link #findByIdForBatch}. Introduced in Phase C task 4.4 per design-c
     * D-C-2 and spec {@code escalation-policy-service-cache-split}.
     *
     * <p>Both arguments MUST be non-null — a null {@code tenantId} or
     * {@code policyId} from a request path is always a bug (TenantContext
     * unbound, missing route parameter, etc.) and should surface as
     * {@link NullPointerException} rather than a silent empty per
     * {@code feedback_never_skip_silently.md}. The batch-only
     * {@link #findByIdForBatch} tolerates null-id because "missing policy row"
     * is legitimately expected when a snapshotted policy was deleted — that
     * exemption does NOT apply here. (D-4.4-4 warroom resolution.)</p>
     *
     * <p>On cache miss, the method loads via
     * {@link EscalationPolicyRepository#findById} and verifies the returned
     * policy's {@code tenantId} either matches the caller's {@code tenantId}
     * OR is {@code null} (platform-default row, accessible from any tenant
     * by design). A mismatch (cross-tenant reach) returns
     * {@link Optional#empty()} — matching the D3 no-existence-leak posture
     * applied elsewhere in FABT (cross-tenant resource lookups return 404,
     * not 403, so an attacker cannot confirm existence) — AND emits a
     * {@link AuditEventTypes#CROSS_TENANT_POLICY_READ} audit row via
     * {@link DetachedAuditPersister} (REQUIRES_NEW) so the security signal
     * survives attacker-triggered caller rollback, mirroring
     * {@code TenantScopedCacheService.get}'s treatment of
     * {@code CROSS_TENANT_CACHE_READ} (Marcus warroom lens, design-c D-C-9).
     * The {@code fabt.cache.get{cache="escalation.policy.by-tenant-and-id",result="cross_tenant_reject"}}
     * Micrometer counter is also incremented.</p>
     *
     * <p>No {@code @TenantUnscoped} annotation — this method IS tenant-scoped
     * by on-read verification; it is the safe alternative to
     * {@link #findByIdForBatch} for non-batch callers.</p>
     *
     * @throws NullPointerException if either argument is null
     */
    public Optional<EscalationPolicy> findByTenantAndId(UUID tenantId, UUID policyId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(policyId, "policyId");
        PolicyKey key = new PolicyKey(tenantId, policyId);
        EscalationPolicy cached = policyByTenantAndId.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<EscalationPolicy> loaded = repository.findById(policyId);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        EscalationPolicy p = loaded.get();
        if (p.tenantId() == null || p.tenantId().equals(tenantId)) {
            policyByTenantAndId.put(key, p);
            return Optional.of(p);
        }
        // Cross-tenant reach: emit security-evidence audit + reject counter +
        // return empty (no existence leak).
        emitCrossTenantPolicyReject(tenantId, policyId, p.tenantId());
        return Optional.empty();
    }

    /**
     * Resolve the current policy for a tenant + event type, falling back to the
     * platform default if the tenant has no custom policy. Used by
     * {@code ReferralTokenService.create()} to snapshot the policy on each new
     * referral.
     *
     * <p>Returns {@code Optional.empty()} only if there is also no platform
     * default — which would be a deployment misconfiguration since Flyway V40
     * seeds one. Callers should treat empty as a hard error and log it.</p>
     */
    public Optional<EscalationPolicy> getCurrentForTenant(UUID tenantId, String eventType) {
        CurrentKey key = new CurrentKey(tenantId, eventType);
        EscalationPolicy cached = currentPolicyByTenant.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<EscalationPolicy> tenantPolicy = repository.findCurrentByTenantAndEventType(tenantId, eventType);
        if (tenantPolicy.isPresent()) {
            currentPolicyByTenant.put(key, tenantPolicy.get());
            // Also warm the by-id cache.
            policyById.put(tenantPolicy.get().id(), tenantPolicy.get());
            return tenantPolicy;
        }
        // Fall back to platform default. Cache the result under both the
        // tenant-specific key (so we don't re-query for this tenant) AND under
        // the platform-default key (so other tenants without custom policies
        // also benefit).
        Optional<EscalationPolicy> platformDefault = repository.findCurrentPlatformDefault(eventType);
        platformDefault.ifPresent(p -> {
            currentPolicyByTenant.put(key, p);
            currentPolicyByTenant.put(new CurrentKey(null, eventType), p);
            // Batch pre-warm — request paths use findByTenantAndId (task 4.4) which
            // has its own cache; this warm exists only to help findByIdForBatch
            // resolve the same id later in the scheduled job cycle.
            policyById.put(p.id(), p);
        });
        return platformDefault;
    }

    /**
     * Publish a new policy version for the caller's tenant. Validates monotonic
     * thresholds, valid roles, and valid severities BEFORE writing — invalid
     * policies are rejected with {@link IllegalArgumentException} and never
     * enter the append-only table.
     *
     * <p>Design D11 (URL-path-sink class): {@code tenantId} is sourced from
     * {@link TenantContext#getTenantId()} internally. The service SHALL NOT
     * accept {@code tenantId} as a parameter — symmetric with
     * {@code TenantOAuth2ProviderService.create}, {@code ApiKeyService.create},
     * and {@code SubscriptionService.create}.
     *
     * <p>On success, invalidates the {@code currentPolicyByTenant} cache entry
     * for this tenant + event type so the next {@link #getCurrentForTenant}
     * call sees the new version. The {@code policyById} cache is NOT
     * invalidated because each policy id is immutable — old policies are still
     * valid lookups for {@code referral_token} rows that snapshotted them.</p>
     *
     * <p>Platform-default policy rows ({@code tenant_id = NULL}) are seeded by
     * Flyway V40 and are NOT mutable via this API path. If a future change
     * needs to publish new platform-default versions, add a separate
     * {@code updatePlatformDefault(...)} method with a
     * {@code @TenantUnscoped("platform-default policy publication — intentionally cross-tenant")}
     * annotation (Phase 3 ArchUnit Family B rule).</p>
     *
     * @return the newly created policy with assigned id, version, and createdAt
     * @throws IllegalArgumentException if validation fails
     */
    public EscalationPolicy update(String eventType,
                                    List<EscalationPolicy.Threshold> thresholds,
                                    UUID actorUserId) {
        // Validate first — failures throw IllegalArgumentException and don't
        // need TenantContext. Tenant-source happens after validation so the
        // unit test for invalid-policy rejection doesn't need a context wrap.
        validateThresholds(thresholds);
        UUID tenantId = TenantContext.getTenantId();
        EscalationPolicy created = repository.insertNewVersion(tenantId, eventType, thresholds, actorUserId);

        // Invalidate the tenant-specific cache entry; the next read will hit
        // the DB and re-cache.
        currentPolicyByTenant.invalidate(new CurrentKey(tenantId, eventType));
        // Pre-populate by-id cache with the freshly inserted version.
        policyById.put(created.id(), created);

        log.info("Escalation policy updated: tenantId={}, eventType={}, version={}, actor={}",
                tenantId, eventType, created.version(), actorUserId);
        return created;
    }

    /**
     * Validate a threshold list before insertion. Rules:
     * <ul>
     *   <li>Non-empty</li>
     *   <li>Each threshold has a non-null Duration</li>
     *   <li>Strictly monotonically increasing by {@code at} duration
     *       (a threshold cannot fire at the same time or before its predecessor)</li>
     *   <li>Severity is one of INFO, ACTION_REQUIRED, CRITICAL</li>
     *   <li>Each recipient role is one of the valid roles</li>
     *   <li>Recipients list is non-empty (a threshold with no recipients is meaningless)</li>
     * </ul>
     *
     * @throws IllegalArgumentException with a structured error message naming
     *     the offending threshold index where possible
     */
    void validateThresholds(List<EscalationPolicy.Threshold> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException("Policy must contain at least one threshold");
        }
        Duration previous = null;
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < thresholds.size(); i++) {
            EscalationPolicy.Threshold t = thresholds.get(i);
            if (t.id() == null || !THRESHOLD_ID_PATTERN.matcher(t.id()).matches()) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].id '" + t.id() + "' must match " + THRESHOLD_ID_PATTERN.pattern()
                        + " (lowercase letters, digits, underscores; 1-32 chars)");
            }
            if (!seenIds.add(t.id())) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].id '" + t.id() + "' is duplicated within the policy. "
                        + "Each threshold id must be unique because it becomes the notification type suffix.");
            }
            if (t.at() == null) {
                throw new IllegalArgumentException("Threshold[" + i + "].at is null");
            }
            if (t.at().isNegative() || t.at().isZero()) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].at must be positive (got " + t.at() + ")");
            }
            if (previous != null && t.at().compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].at (" + t.at() + ") must be strictly greater than "
                        + "Threshold[" + (i - 1) + "].at (" + previous + ") — thresholds must be "
                        + "monotonically increasing");
            }
            previous = t.at();

            if (!VALID_SEVERITIES.contains(t.severity())) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].severity '" + t.severity() + "' is not a valid severity. "
                        + "Valid: " + VALID_SEVERITIES);
            }
            if (t.recipients() == null || t.recipients().isEmpty()) {
                throw new IllegalArgumentException(
                        "Threshold[" + i + "].recipients is empty — at least one role required");
            }
            for (String role : t.recipients()) {
                if (!VALID_ROLES.contains(role)) {
                    throw new IllegalArgumentException(
                            "Threshold[" + i + "].recipients contains invalid role '" + role + "'. "
                            + "Valid: " + VALID_ROLES);
                }
            }
        }
    }

    /**
     * <b>TEST-ONLY.</b> Drop both internal caches. The production code path
     * never needs this because {@link #update} invalidates the relevant cache
     * entry on every write — manual cache nuking from a controller would be a
     * bug. The method exists so integration tests that publish a tenant
     * policy in one class can leave a clean state for sibling test classes to
     * observe the seeded platform default. Call from {@code @AfterEach}.
     *
     * <p>The {@code _testOnly} suffix is deliberate: it makes any production
     * caller stand out in code review (and would block a copy-paste from a
     * test into a controller without the reviewer noticing).</p>
     */
    public void clearCaches_testOnly() {
        currentPolicyByTenant.invalidateAll();
        policyById.invalidateAll();
        policyByTenantAndId.invalidateAll();
    }

    /**
     * Emit the CROSS_TENANT_POLICY_READ security-evidence audit row + counter.
     * Called from {@link #findByTenantAndId} when the stored policy's tenantId
     * does not match the caller's AND the policy is not a platform-default.
     *
     * <p>Uses {@link DetachedAuditPersister} so the audit row commits under
     * {@code PROPAGATION_REQUIRES_NEW} — survives attacker-triggered caller
     * rollback. Same mechanism as
     * {@code TenantScopedCacheService.get → CROSS_TENANT_CACHE_READ}.</p>
     */
    private void emitCrossTenantPolicyReject(UUID readerTenant, UUID policyId, UUID observedTenant) {
        log.error("CROSS_TENANT_POLICY_READ readerTenant={} policyId={} observedTenant={}",
                readerTenant, policyId, observedTenant);
        if (meterRegistry != null) {
            Counter.builder("fabt.cache.get")
                    .tag("cache", "fabt.escalation.policy.by-tenant-and-id")
                    .tag("tenant", readerTenant.toString())
                    .tag("result", "cross_tenant_reject")
                    .register(meterRegistry)
                    .increment();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyId", policyId.toString());
        details.put("expectedTenant", readerTenant.toString());
        details.put("observedTenant", observedTenant.toString());
        detachedAuditPersister.persistDetached(readerTenant, new AuditEventRecord(
                TenantContext.getUserId(),
                null,
                AuditEventTypes.CROSS_TENANT_POLICY_READ,
                details,
                null));
    }

    /** Cache key for the {@code currentPolicyByTenant} cache. */
    private record CurrentKey(UUID tenantId, String eventType) {}

    /**
     * Cache key for the {@link #policyByTenantAndId} cache. Tenant + policy
     * UUID together identify a specific immutable policy version that the
     * caller's tenant has access to (either as owner or via platform-default
     * fallback).
     */
    private record PolicyKey(UUID tenantId, UUID policyId) {}
}
