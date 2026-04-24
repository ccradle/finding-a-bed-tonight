package org.fabt.tenant.service;

import java.time.Duration;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.fabt.shared.security.TenantScopedByConstruction;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.domain.TenantStateGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-path gate for the F-3 tenant-state enforcement. Call
 * {@link #requireActive(UUID)} at service-layer entry points whose repositories don't
 * use the {@code findByIdAndActiveTenantId} SQL-JOIN pattern — typically paths where
 * the tenant id comes from JWT/TenantContext rather than a URL variable, and whose
 * lookups go via FK-chain or self-path queries that wouldn't naturally JOIN to
 * {@code tenant.state}.
 *
 * <p><b>Caching.</b> Caffeine with 10-second TTL + 1000-entry cap (W-TinyLFU eviction). At 10 seconds,
 * a SUSPENDED tenant's reads can linger up to that window post-quarantine — acceptable
 * because suspend ALSO bumps the JWT key generation (all live tokens invalidated) and
 * deactivates API keys. The tenant-state check here is defense-in-depth; the JWT/APK
 * revocations are the first line. Cache is invalidated by
 * {@link TenantLifecycleService#suspend}/{@code unsuspend}/etc. via
 * {@link #invalidate(UUID)} called after-commit so a committed transition is visible
 * to subsequent requests within ~ms, not 10s.</p>
 *
 * <p><b>Why not always JOIN in SQL?</b> The JOIN-to-{@code tenant.state} pattern (used
 * by F-3 Tier 1 repos) is the stronger enforcement because it's atomic with the read.
 * But it requires the caller to pass tenantId to the query. Paths that don't have a
 * tenantId parameter in their repository method (self-path-from-JWT notifications,
 * FK-chain webhook logs) can't use that pattern without an invasive refactor. Those
 * paths go through {@code requireActive} at the service entry instead — tight loop
 * of "ensure tenant is ACTIVE" exactly once per request.</p>
 */
@Service
public class TenantStateGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantStateGuard.class);

    private final JdbcTemplate jdbc;

    /**
     * Short-TTL cache. 10s is the max staleness window after an operator-triggered
     * suspend before this guard starts rejecting reads (even without the explicit
     * {@link #invalidate} call, which operates at-ms latency). Smaller means more
     * DB load; larger means longer post-suspend-read window. 10s matches the
     * operator's expected "suspend takes effect now" human-scale latency.
     */
    @TenantScopedByConstruction("cache key IS tenantId — entry is the tenant's own state, no cross-tenant confusion possible; explicit invalidate(tenantId) from TenantLifecycleService.suspend/unsuspend/etc. after-commit hook bounds post-transition staleness to ms instead of the 10s TTL")
    private final Cache<UUID, TenantState> stateCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(10))
        .maximumSize(1000)
        .build();

    public TenantStateGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Verifies that {@code tenantId} resolves to a tenant in {@link TenantState#ACTIVE}.
     * Throws {@link TenantStateGuardException} otherwise. Callers typically place this
     * at the start of a service method — before repository reads — so a SUSPENDED /
     * OFFBOARDING / ARCHIVED / DELETED tenant's request short-circuits with 404 or 503
     * (per {@code TenantLifecycleExceptionAdvice}'s HTTP-method branching) instead of
     * returning data.
     *
     * @throws TenantStateGuardException.Kind#NOT_FOUND    tenant id does not exist
     * @throws TenantStateGuardException.Kind#NON_ACTIVE   tenant exists but state != ACTIVE
     */
    public void requireActive(UUID tenantId) {
        if (tenantId == null) {
            // Defensive — a null tenantId is itself a 404; same "no existence leak"
            // posture applies. Callers should have resolved tenantId before this, so
            // reaching here is a caller bug worth surfacing — log with stack so the
            // operator can find the missing TenantContext binding.
            log.warn("TenantStateGuard.requireActive called with null tenantId",
                new IllegalArgumentException("null tenantId at requireActive"));
            throw TenantStateGuardException.notFound(null);
        }
        TenantState state = stateCache.get(tenantId, this::loadState);
        if (state == null) {
            throw TenantStateGuardException.notFound(tenantId);
        }
        if (state != TenantState.ACTIVE) {
            throw TenantStateGuardException.nonActive(tenantId, state);
        }
    }

    /**
     * Invalidates the cached state entry for a tenant. Call after any committed
     * lifecycle transition so the next request sees the fresh state without the 10s
     * TTL wait. {@code TenantLifecycleService} calls this via an after-commit hook.
     */
    public void invalidate(UUID tenantId) {
        if (tenantId != null) {
            stateCache.invalidate(tenantId);
        }
    }

    /**
     * Loader — pulled into its own method so the caller's {@link Cache#get} call can
     * pass it as a reference. Returns {@code null} on tenant-not-found so Caffeine
     * doesn't cache a sentinel; {@code requireActive} re-inspects and throws.
     *
     * <p>Reads {@code tenant.state} (VARCHAR since V79) and maps via
     * {@link TenantState#valueOf}. If the DB holds a value outside the enum (which
     * the CHECK constraint in V79 forbids, but defensive), the valueOf will throw
     * {@link IllegalArgumentException} — propagates as 500, not 404, so it's clearly
     * a bug signal not a graceful miss.</p>
     *
     * <p><b>RLS fragility note:</b> the bare {@code SELECT state FROM tenant WHERE id = ?}
     * assumes the {@code tenant} table has NO Row-Level Security enabled (verified
     * in V68/V69 — tenant itself is not RLS-protected, only tenant-owned entity
     * tables are). If a future migration ever enables RLS on {@code tenant} without
     * also adding a permissive policy for the {@code fabt_app} role, this method
     * silently returns null for every tenant → every request 404s → platform-wide
     * outage. Any PR that touches RLS on the {@code tenant} table MUST update this
     * loader (e.g. wrap in a {@code TenantContext.runUnscoped} block, or use
     * {@code fabt} owner credentials for this one query) AND add a unit test for
     * the new path.</p>
     */
    private TenantState loadState(UUID tenantId) {
        try {
            String raw = jdbc.queryForObject(
                "SELECT state FROM tenant WHERE id = ?", String.class, tenantId);
            return raw == null ? null : TenantState.valueOf(raw);
        } catch (EmptyResultDataAccessException notFound) {
            return null;
        }
    }
}
