package org.fabt.shared.cache;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped wrapper around {@link CacheService} — the single source of
 * truth for tenant-safe cache access in FABT.
 *
 * <p>Published as a <b>distinct Spring bean</b> named {@code tenantScopedCacheService},
 * NOT as {@code @Primary} over the underlying {@link CacheService}. Callers that
 * want tenant scoping inject this class explicitly; callers that carry a
 * {@code @TenantUnscopedCache} justification keep using the raw {@link CacheService}.
 * This separation is intentional — {@code @Primary} would silently double-prefix
 * any legacy call site that already manually embeds {@code tenantId} in the key.
 *
 * <h2>Four load-bearing contracts</h2>
 *
 * <ol>
 *   <li><b>Key prefix.</b> Every {@code get}/{@code put}/{@code evict} prepends
 *       {@code TenantContext.getTenantId() + "|"} to the caller's logical key.
 *       Separator is pipe (not colon) so existing composite call-site keys like
 *       {@code AnalyticsService}'s {@code tenantId + ":" + from + ":" + to}
 *       remain visually unambiguous during migration (design-c D-C-10).</li>
 *   <li><b>Value stamp + verify.</b> On write, values are wrapped in a
 *       {@link TenantScopedValue} envelope stamped with the writer's tenant.
 *       On read, the envelope's stamp is verified against the reader's
 *       {@code TenantContext}; mismatch throws {@code CROSS_TENANT_CACHE_READ}.
 *       This is a <em>second</em> isolation control — prefix defends the read
 *       side, stamp-and-verify defends the write side (design-c D-C-13).</li>
 *   <li><b>{@code invalidateTenant(UUID)}.</b> Iterates the eager-seeded
 *       {@link #registeredCacheNames} registry (populated at {@code @PostConstruct}
 *       from {@link CacheNames} reflection — NOT lazily on first put per
 *       design-c D-C-8) and evicts every entry matching
 *       {@code <tenantId>|*} via {@link CacheService#evictAllByPrefix}.
 *       Idempotent across retries; emits an audit row with per-cache eviction
 *       counts. Called from the tenant-lifecycle FSM (Phase F F4) and the
 *       platform-admin API.</li>
 *   <li><b>Observability.</b> Every {@code get}/{@code put} emits Micrometer
 *       counters {@code fabt.cache.get{cache,tenant,result}} +
 *       {@code fabt.cache.put{cache,tenant}}. Result ∈
 *       {{@code hit, miss, cross_tenant_reject, malformed_entry}}. Tag
 *       {@code tenant} (not {@code tenant_id}) matches the G4 OTel baggage key.</li>
 * </ol>
 *
 * <h2>Exception posture</h2>
 *
 * <p>All {@code IllegalStateException} / {@code IllegalArgumentException}
 * messages produced by this class carry ONLY short action tags
 * ({@code TENANT_CONTEXT_UNBOUND}, {@code CROSS_TENANT_CACHE_READ},
 * {@code MALFORMED_CACHE_ENTRY}). UUIDs, keys, and payload fragments go to
 * audit rows + structured logs — never to exception messages (prevents
 * information disclosure through {@code GlobalExceptionHandler}; design-c
 * D-C-11 + OWASP ASVS 5.0 §7.4.1).
 *
 * <h2>Audit routing</h2>
 *
 * <ul>
 *   <li>{@code TENANT_CACHE_INVALIDATED} — normal event-bus path
 *       ({@link ApplicationEventPublisher} →
 *       {@code AuditEventService.onAuditEvent} → {@code AuditEventPersister}
 *       REQUIRED). Operator-initiated; rollback-coupling is correct.</li>
 *   <li>{@code CROSS_TENANT_CACHE_READ} — detached REQUIRES_NEW path via
 *       {@link DetachedAuditPersister}. Security evidence must survive
 *       attacker-triggered caller rollback.</li>
 *   <li>{@code MALFORMED_CACHE_ENTRY} — event-bus path; not attacker-triggered
 *       (indicates a wrapper-bypass bug on the write side, task 4.b migration
 *       not yet complete).</li>
 * </ul>
 *
 * @see TenantScopedValue
 * @see DetachedAuditPersister
 * @see CacheService#evictAllByPrefix
 */
@Service
public class TenantScopedCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedCacheService.class);

    /** Separator between tenant prefix and caller's logical key. See design-c D-C-10. */
    static final String PREFIX_SEPARATOR = "|";

    // Cardinality budget (observability):
    //   N_tenants × N_caches × N_results × N_ops
    //   = 100 × 11 × 4 × 2
    //   = ~8800 time-series maximum.
    // Acceptable within Prometheus's practical per-metric-family ceiling.
    // Review if pooled tenant count grows past 500.

    private final CacheService delegate;
    private final ApplicationEventPublisher eventPublisher;
    private final DetachedAuditPersister detachedAuditPersister;
    private final MeterRegistry meterRegistry;

    /**
     * Authoritative registry of cache names the wrapper will iterate during
     * {@code invalidateTenant}. Seeded eagerly at {@code @PostConstruct} from
     * {@link CacheNames} reflection (design-c D-C-8) — a lazy-on-first-put
     * registry would silently no-op after JVM restart for tenants that haven't
     * yet been written to, turning the Phase F tenant-suspension page at 3am
     * into a false "succeeded" signal.
     */
    private final Set<String> registeredCacheNames = ConcurrentHashMap.newKeySet();

    public TenantScopedCacheService(CacheService delegate,
                                     ApplicationEventPublisher eventPublisher,
                                     DetachedAuditPersister detachedAuditPersister,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.delegate = delegate;
        this.eventPublisher = eventPublisher;
        this.detachedAuditPersister = detachedAuditPersister;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @PostConstruct
    void seedRegisteredCacheNames() {
        for (Field f : CacheNames.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    Object v = f.get(null);
                    if (v instanceof String s && !s.isBlank()) {
                        registeredCacheNames.add(s);
                    }
                } catch (IllegalAccessException e) {
                    // CacheNames is a public interface with public static final String
                    // fields; this cannot happen in practice.
                    throw new IllegalStateException(
                            "TenantScopedCacheService failed to reflect CacheNames.", e);
                }
            }
        }
        if (registeredCacheNames.isEmpty()) {
            throw new IllegalStateException(
                    "TenantScopedCacheService found no cache names in CacheNames reflection. "
                    + "Refusing to start — invalidateTenant would silently no-op.");
        }
        log.info("TenantScopedCacheService eager-seeded with {} cache names: {}",
                registeredCacheNames.size(), registeredCacheNames);
        if (meterRegistry != null) {
            Gauge.builder("fabt.cache.registered_cache_names",
                            registeredCacheNames, Set::size)
                    .description("Number of cache names seeded into TenantScopedCacheService at startup")
                    .register(meterRegistry);
        }
    }

    /**
     * Tenant-scoped get with on-read envelope verification.
     *
     * @throws IllegalStateException tagged {@code TENANT_CONTEXT_UNBOUND} if no
     *         TenantContext is bound; tagged {@code CROSS_TENANT_CACHE_READ} if
     *         the stored envelope's stamp does not match the reader's tenant;
     *         tagged {@code MALFORMED_CACHE_ENTRY} if the stored value is not a
     *         {@link TenantScopedValue} or its inner value's type does not match
     *         the caller's requested type.
     */
    public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
        UUID tenantId = requireTenantContext();
        String scopedKey = tenantId + PREFIX_SEPARATOR + key;

        // Fetch as Object.class (not TenantScopedValue.class) so a raw
        // non-envelope payload written by a wrapper-bypass caller surfaces as
        // MALFORMED_CACHE_ENTRY — NOT a bare ClassCastException from the
        // delegate's generic unchecked cast at the return-type use site.
        Optional<Object> raw = delegate.get(cacheName, scopedKey, Object.class);
        if (raw.isEmpty()) {
            recordGet(cacheName, tenantId, "miss");
            return Optional.empty();
        }

        if (!(raw.get() instanceof TenantScopedValue<?> envelope)) {
            handleMalformedEntry(cacheName, tenantId, raw.get().getClass().getName());
            recordGet(cacheName, tenantId, "malformed_entry");
            throw new IllegalStateException("MALFORMED_CACHE_ENTRY");
        }

        if (!tenantId.equals(envelope.tenantId())) {
            handleCrossTenantRead(cacheName, tenantId, envelope.tenantId());
            recordGet(cacheName, tenantId, "cross_tenant_reject");
            throw new IllegalStateException("CROSS_TENANT_CACHE_READ");
        }

        Object v = envelope.value();
        if (v != null && !type.isInstance(v)) {
            handleMalformedEntry(cacheName, tenantId, v.getClass().getName());
            recordGet(cacheName, tenantId, "malformed_entry");
            throw new IllegalStateException("MALFORMED_CACHE_ENTRY");
        }

        recordGet(cacheName, tenantId, "hit");
        return Optional.ofNullable(type.cast(v));
    }

    /**
     * Tenant-scoped put with value stamping.
     *
     * @throws IllegalStateException tagged {@code TENANT_CONTEXT_UNBOUND} if no
     *         TenantContext is bound.
     * @throws IllegalArgumentException if {@code value} is null (belt-and-
     *         suspenders with the Family C ArchUnit rule rejecting
     *         {@code put(…, null, …)} call sites).
     */
    public <T> void put(String cacheName, String key, T value, Duration ttl) {
        if (value == null) {
            throw new IllegalArgumentException("TenantScopedCacheService.put value must not be null");
        }
        UUID tenantId = requireTenantContext();
        String scopedKey = tenantId + PREFIX_SEPARATOR + key;
        delegate.put(cacheName, scopedKey, new TenantScopedValue<>(tenantId, value), ttl);
        recordPut(cacheName, tenantId);
    }

    /** Tenant-scoped evict. */
    public void evict(String cacheName, String key) {
        UUID tenantId = requireTenantContext();
        String scopedKey = tenantId + PREFIX_SEPARATOR + key;
        delegate.evict(cacheName, scopedKey);
    }

    /**
     * Evicts every cache entry whose key begins with the given tenant's prefix
     * across every registered cache name. Idempotent across retries.
     *
     * <p>Emits an {@code audit_events} row with action
     * {@link AuditEventTypes#TENANT_CACHE_INVALIDATED} and per-cache eviction
     * counts in the details column. Uses the normal event-bus audit path
     * (operator-initiated; rollback-coupling is correct here).
     *
     * <p>Does NOT require a bound {@code TenantContext} — this method is called
     * from the tenant-lifecycle FSM and platform-admin API, neither of which
     * necessarily carries the target tenant's context.
     */
    public void invalidateTenant(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        String prefix = tenantId + PREFIX_SEPARATOR;
        Map<String, Long> perCacheEvictions = new LinkedHashMap<>();
        long total = 0L;
        for (String cacheName : registeredCacheNames) {
            long evicted = delegate.evictAllByPrefix(cacheName, prefix);
            perCacheEvictions.put(cacheName, evicted);
            total += evicted;
        }
        log.info("TENANT_CACHE_INVALIDATED tenant={} totalEvicted={} perCache={}",
                tenantId, total, perCacheEvictions);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", tenantId.toString());
        details.put("totalEvicted", total);
        details.put("perCacheEvictionCounts", perCacheEvictions);
        eventPublisher.publishEvent(new AuditEventRecord(
                TenantContext.getUserId(),
                null,
                AuditEventTypes.TENANT_CACHE_INVALIDATED,
                details,
                null));
    }

    // --- private helpers ---

    private UUID requireTenantContext() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) {
            throw new IllegalStateException("TENANT_CONTEXT_UNBOUND");
        }
        return tid;
    }

    private void recordGet(String cacheName, UUID tenantId, String result) {
        if (meterRegistry == null) return;
        Counter.builder("fabt.cache.get")
                .tag("cache", cacheName)
                .tag("tenant", tenantId.toString())
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    private void recordPut(String cacheName, UUID tenantId) {
        if (meterRegistry == null) return;
        Counter.builder("fabt.cache.put")
                .tag("cache", cacheName)
                .tag("tenant", tenantId.toString())
                .register(meterRegistry)
                .increment();
    }

    private void handleCrossTenantRead(String cacheName, UUID readerTenant, UUID stampedTenant) {
        log.error("CROSS_TENANT_CACHE_READ cache={} readerTenant={} stampedTenant={}",
                cacheName, readerTenant, stampedTenant);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cacheName", cacheName);
        details.put("expectedTenant", readerTenant.toString());
        details.put("observedTenant", stampedTenant.toString());
        detachedAuditPersister.persistDetached(readerTenant, new AuditEventRecord(
                TenantContext.getUserId(),
                null,
                AuditEventTypes.CROSS_TENANT_CACHE_READ,
                details,
                null));
    }

    private void handleMalformedEntry(String cacheName, UUID readerTenant, String observedType) {
        log.error("MALFORMED_CACHE_ENTRY cache={} readerTenant={} observedType={}",
                cacheName, readerTenant, observedType);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cacheName", cacheName);
        details.put("observedType", observedType);
        eventPublisher.publishEvent(new AuditEventRecord(
                TenantContext.getUserId(),
                null,
                AuditEventTypes.MALFORMED_CACHE_ENTRY,
                details,
                null));
    }

    // --- test accessors (package-private) ---

    /** Package-private for tests: the eager-seeded registry snapshot. */
    Set<String> registeredCacheNamesSnapshot() {
        return Set.copyOf(registeredCacheNames);
    }
}
