package org.fabt.auth.platform;

import java.time.Duration;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.fabt.shared.security.KeyDerivationService;

/**
 * Owns the {@code platform_key_material} table for the iss=fabt-platform
 * JWT signing key (Phase G-4 / issue #141).
 *
 * <h2>First-boot bootstrap</h2>
 *
 * <p>On {@link ApplicationReadyEvent}, ensures exactly one active row exists
 * in {@code platform_key_material}. If none does, derives a 32-byte key via
 * {@link KeyDerivationService#derivePlatformJwtKeyBytes(UUID)} (HKDF-SHA256
 * over the master KEK, salted by the new row id) and inserts it through the
 * {@code platform_key_material_create_first_active} SECURITY DEFINER function
 * — {@code fabt_app} cannot {@code INSERT} directly per the V87 REVOKE.
 *
 * <p>The race window between two app instances starting concurrently is
 * handled by the partial unique index {@code platform_key_material_one_active}
 * (V87:119): the second insert returns {@code false} and the second instance
 * proceeds with whatever the first wrote.
 *
 * <h2>JWT-time read path</h2>
 *
 * <p>{@link #findActiveKey()} returns the active row for use by the platform
 * JWT signer / verifier. Reads go through plain {@code SELECT} — V87 grants
 * {@code SELECT} on the table to {@code fabt_app} (write privileges are
 * revoked, so no SECURITY DEFINER wrap is needed for reads).
 *
 * <h2>Rotation</h2>
 *
 * <p>v0.53 ships single-generation. Future rotation tooling will mint a new
 * row with {@code generation=N+1, active=true} and flip the previous row to
 * {@code active=false} inside one transaction; the unique index prevents
 * two-active states. Manual break-glass procedure is documented in the
 * runbook.
 */
@Service
public class PlatformKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(PlatformKeyRotationService.class);

    private static final String CACHE_KEY = "active";

    private final JdbcTemplate jdbc;
    private final KeyDerivationService keyDerivationService;

    /**
     * Caches the single active row so platform JWT sign + verify don't
     * round-trip the DB on every call (warroom A2). 5-minute TTL is short
     * enough that a manual rotation (operator UPDATEs the table) is picked
     * up within ~5 min without an explicit invalidation API; the bootstrap
     * path also evicts on insert. Bounded at 1 entry — this is a single-row
     * cache, not a multi-key map.
     */
    @org.fabt.shared.security.TenantUnscopedCache("Platform JWT signing key — exactly one active row, no tenant scope")
    private final Cache<String, ActiveKey> activeKeyCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public PlatformKeyRotationService(JdbcTemplate jdbc, KeyDerivationService keyDerivationService) {
        this.jdbc = jdbc;
        this.keyDerivationService = keyDerivationService;
    }

    /**
     * First-boot bootstrap. Idempotent — a no-op when an active row already
     * exists. Called once per app start via {@link ApplicationReadyEvent}.
     *
     * <p>Deliberately INFO-level on success so ops can grep deploy logs for
     * the canonical "PLATFORM_KEY_BOOTSTRAPPED" string when verifying a
     * fresh-cluster deploy.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureActiveKey() {
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_key_material WHERE active = true",
                Integer.class);
        if (activeCount != null && activeCount > 0) {
            log.debug("platform_key_material: active row present, skipping bootstrap");
            return;
        }

        UUID keyId = UUID.randomUUID();
        String kid = UUID.randomUUID().toString();
        byte[] keyBytes = keyDerivationService.derivePlatformJwtKeyBytes(keyId);

        Boolean inserted = jdbc.queryForObject(
                "SELECT platform_key_material_create_first_active(?, ?, ?)",
                Boolean.class, keyId, kid, keyBytes);

        if (Boolean.TRUE.equals(inserted)) {
            log.info("PLATFORM_KEY_BOOTSTRAPPED keyId={} kid={} generation=1 — "
                    + "subsequent iss=fabt-platform JWTs will sign with this key",
                    keyId, kid);
        } else {
            log.info("platform_key_material: another instance won the bootstrap race; "
                    + "active row already in place");
        }
        // Either path may have changed the active row from this JVM's
        // viewpoint — evict so the next read hits the new state.
        activeKeyCache.invalidateAll();
    }

    /**
     * Reads the single active row from {@code platform_key_material}.
     *
     * @throws IllegalStateException if no active row exists. The bootstrap
     *     event listener runs at startup; an empty result here means either
     *     (a) startup never completed or (b) the row was manually deleted —
     *     both are operational incidents, not normal-flow conditions.
     */
    public ActiveKey findActiveKey() {
        ActiveKey cached = activeKeyCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        ActiveKey loaded = loadActiveKey();
        activeKeyCache.put(CACHE_KEY, loaded);
        return loaded;
    }

    private ActiveKey loadActiveKey() {
        try {
            return jdbc.queryForObject(
                    "SELECT id, generation, kid, key_bytes "
                            + "FROM platform_key_material WHERE active = true",
                    (rs, rowNum) -> new ActiveKey(
                            (UUID) rs.getObject("id"),
                            rs.getInt("generation"),
                            rs.getString("kid"),
                            new SecretKeySpec(rs.getBytes("key_bytes"), "HmacSHA256")));
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "No active row in platform_key_material — bootstrap should have run "
                            + "at startup. Investigate app startup logs.", e);
        }
    }

    /**
     * Test-visible cache reset hook. Production code does not call this —
     * the bootstrap path evicts on insert and the 5-min TTL handles
     * operator-initiated rotation within an acceptable window.
     */
    void invalidateCacheForTest() {
        activeKeyCache.invalidateAll();
    }

    /**
     * Active platform JWT signing key as returned to JWT signer / verifier.
     * The {@code key} is bound to {@code HmacSHA256} (the platform JWT alg).
     */
    public record ActiveKey(UUID id, int generation, String kid, SecretKey key) {
    }
}
