package org.fabt.shared.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code tenant_dek} table (V82). Creates, wraps, unwraps, and
 * caches per-tenant, per-purpose random DEKs — the shred-surface of the
 * F-6 crypto-shred design.
 *
 * <p><b>Why this service exists.</b> Phase A's
 * {@link KeyDerivationService} derived DEKs deterministically from
 * {@code (master_KEK, tenantId, purpose)} via HKDF-SHA256. The TDD anchor
 * {@code CryptoShredGapIntegrationTest} proved that path has no shred
 * surface: an adversary with {@code master_KEK} + a pre-shred ciphertext
 * recovers plaintext via public primitives in milliseconds. Option A (the
 * warroom-approved fix) replaces the deterministic DEK with a random DEK
 * stored wrapped in {@code tenant_dek}; destroying the row destroys the
 * only copy of the DEK.
 *
 * <h2>Wrapping scheme</h2>
 *
 * <ul>
 *   <li>DEK: 32 random bytes from {@link SecureRandom} (AES-256 key length).</li>
 *   <li>Wrapping key: HKDF-SHA256 of {@code (master_KEK, tenantId, "kek-wrap")}
 *       via {@link KeyDerivationService#deriveKekWrappingKey}. Deterministic,
 *       but safe — see §3 D61 of the design: the wrapping key alone decrypts
 *       nothing; the shred surface is the wrapped_dek row, not the wrapping
 *       key.</li>
 *   <li>Wrap primitive: AES-KWP per RFC 5649 / NIST SP 800-38F §6.3. JDK-native
 *       via {@code Cipher.getInstance("AESWrapPad")}. 8-byte overhead → 40-byte
 *       wrapped output for a 32-byte DEK.</li>
 * </ul>
 *
 * <h2>Caches</h2>
 *
 * <ul>
 *   <li><b>Active-DEK cache</b>: {@code (tenantId, purpose)} → {@link ActiveDek}.
 *       5-min TTL. Keyed on a composite so a single tenant's TOTP DEK never
 *       serves a webhook-secret encrypt request.</li>
 *   <li><b>Kid resolution cache</b>: {@code kid} → {@link ResolvedDek}. 1-hour TTL.
 *       Kid resolution is immutable by design — once a (tenant, purpose,
 *       generation) kid is minted, it never re-points.</li>
 * </ul>
 *
 * <p>Cache invalidation is the key isolation boundary. Per §5 of the design,
 * {@link #invalidateTenantDeks} walks both caches and evicts every entry
 * owned by the given tenant. {@link TenantLifecycleService} calls this on
 * OFFBOARDING/ARCHIVED transitions (post-commit) AND before {@code hardDelete}
 * fires the cascade — the pre-transition invalidation closes the hot-JVM
 * window Alex flagged in pass-1.
 *
 * <h2>RLS</h2>
 *
 * Writes bind {@code app.tenant_id} in-tx so the V82 RESTRICTIVE policies
 * allow the INSERT. Reads (resolveDek) are PERMISSIVE at the table level
 * because the decrypt path runs before tenant context is bound. Delete path
 * is gated by the V82 trigger guard ({@code fabt.shred_in_progress} GUC
 * equality), reachable only from {@link TenantLifecycleService#hardDelete}
 * per ArchUnit rule 7.8j.
 */
@Service
public class TenantDekService {

    private static final Logger log = LoggerFactory.getLogger(TenantDekService.class);

    private static final int DEK_LENGTH_BYTES = 32;  // AES-256
    private static final String AES_KWP_CIPHER = "AESWrapPad";

    private final JdbcTemplate jdbc;
    private final KeyDerivationService keyDerivationService;
    private final SecureRandom secureRandom;

    /**
     * Active-DEK cache. Key is a {@link ActiveDekCacheKey} record; Caffeine
     * supports any hashable key type, and the record's auto-generated
     * {@code hashCode}/{@code equals} keep key identity purpose-scoped.
     * 5-minute TTL bounds staleness if rotation fires out-of-band.
     */
    @TenantScopedByConstruction("key is the composite (tenantId, purpose); cache IS tenant-scoped by construction — no raw tenantId key leaks a different tenant's DEK because lookups require both pieces")
    private final Cache<ActiveDekCacheKey, ActiveDek> activeDekCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * Kid resolution cache. Kid is a globally-unique opaque UUID; resolution
     * (kid → tenant, purpose, generation, DEK) is immutable by design. 1-hour
     * TTL because the mapping never re-points; eviction only on tenant hard-
     * delete or explicit rotation.
     */
    @TenantUnscopedCache("kid is a globally-unique opaque UUID assigned once at tenant_dek insert; resolution (kid → tenant, purpose, generation, DEK) is immutable by design; decrypt path reads this cache BEFORE TenantContext is bound — cache-site tenant-scoping is impossible at this stage")
    private final Cache<UUID, ResolvedDek> resolvedDekCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public TenantDekService(JdbcTemplate jdbc, KeyDerivationService keyDerivationService) {
        this.jdbc = jdbc;
        this.keyDerivationService = keyDerivationService;
        this.secureRandom = new SecureRandom();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns the active DEK for {@code (tenant, purpose)} — unwrapped,
     * ready to use for AES-GCM encrypt. Lazily creates both the
     * {@code tenant_dek} row and a random DEK on first call. Idempotent —
     * concurrent first-encrypts converge on a single row via the V82
     * unique index + {@code ON CONFLICT DO NOTHING}.
     */
    @Transactional
    public ActiveDek getOrCreateActiveDek(UUID tenantId, KeyPurpose purpose) {
        ActiveDekCacheKey key = new ActiveDekCacheKey(tenantId, purpose);
        ActiveDek cached = activeDekCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // Bind app.tenant_id for the V82 RESTRICTIVE INSERT policy. Third
        // arg `true` = is_local → GUC auto-clears at tx commit/rollback,
        // does not leak to subsequent txs on the same pooled connection.
        // Parameterized — never concatenate the tenantId into the SQL.
        jdbc.queryForObject(
            "SELECT set_config('app.tenant_id', ?, true)",
            String.class,
            tenantId.toString());

        // Optimistic read — the common case post-bootstrap. Hits the
        // partial unique index (tenant_id, purpose) WHERE active = TRUE.
        ActiveDek existing = loadActiveDek(tenantId, purpose);
        if (existing != null) {
            activeDekCache.put(key, existing);
            return existing;
        }

        // First encrypt for this (tenant, purpose). Generate a random DEK,
        // wrap it, INSERT with ON CONFLICT DO NOTHING (no target — covers
        // BOTH unique indexes per Sam pass-1: the composite (tenant, purpose,
        // generation) AND the partial (tenant, purpose) WHERE active).
        byte[] rawDek = new byte[DEK_LENGTH_BYTES];
        secureRandom.nextBytes(rawDek);
        byte[] wrappedDek = wrap(tenantId, rawDek);

        UUID newKid = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO tenant_dek (kid, tenant_id, purpose, generation, wrapped_dek, active) "
            + "VALUES (?, ?, ?, 1, ?, TRUE) "
            + "ON CONFLICT DO NOTHING",
            newKid, tenantId, purpose.name(), wrappedDek);

        // Re-SELECT. If our insert won, we get newKid; if a concurrent
        // thread won, we get theirs. Either way, exactly one active row
        // exists for this (tenant, purpose).
        ActiveDek resolved = loadActiveDek(tenantId, purpose);
        if (resolved == null) {
            throw new IllegalStateException(
                "tenant_dek insert + re-select returned no active row for "
                + "(" + tenantId + ", " + purpose + ") — this violates the "
                + "partial unique index invariant and is a bug");
        }
        activeDekCache.put(key, resolved);
        log.info("Created tenant_dek row kid={} for tenant={} purpose={} generation={}",
            resolved.kid(), tenantId, purpose, resolved.generation());
        return resolved;
    }

    /**
     * Resolves a kid back to its unwrapped DEK plus metadata for the
     * decrypt path. Throws {@link NoSuchElementException} on unknown kid;
     * the caller ({@code SecretEncryptionService.decryptForTenant})
     * translates to {@link CrossTenantCiphertextException} with the
     * unknown-kid sentinel per C-A3-1.
     */
    @Transactional(readOnly = true)
    public ResolvedDek resolveDek(UUID kid) {
        ResolvedDek cached = resolvedDekCache.getIfPresent(kid);
        if (cached != null) {
            return cached;
        }

        // PERMISSIVE SELECT on tenant_dek (V82) — no app.tenant_id binding
        // needed here. Safe because kids are opaque UUIDs; enumeration
        // yields no tenant identity.
        ResolvedDek resolved;
        try {
            resolved = jdbc.queryForObject(
                "SELECT kid, tenant_id, purpose, generation, wrapped_dek "
                + "FROM tenant_dek WHERE kid = ?",
                (rs, rowNum) -> {
                    UUID rowKid = (UUID) rs.getObject("kid");
                    UUID tenantId = (UUID) rs.getObject("tenant_id");
                    KeyPurpose purpose = KeyPurpose.valueOf(rs.getString("purpose"));
                    int generation = rs.getInt("generation");
                    byte[] wrappedDek = rs.getBytes("wrapped_dek");
                    SecretKey dek = unwrap(tenantId, wrappedDek);
                    return new ResolvedDek(rowKid, tenantId, purpose, generation, dek);
                },
                kid);
        } catch (EmptyResultDataAccessException notFound) {
            throw new NoSuchElementException("kid not registered in tenant_dek: " + kid);
        }
        resolvedDekCache.put(kid, resolved);
        return resolved;
    }

    /**
     * Evicts every cache entry owned by {@code tenantId} from both caches.
     * Called by {@link TenantLifecycleService} on OFFBOARDING/ARCHIVED
     * transitions (via {@code AFTER_COMMIT} listener) and on hardDelete
     * (pre-CASCADE). Idempotent — safe to call for a tenant with zero
     * cached entries.
     *
     * <p>O(cache-size) via {@code asMap().entrySet()} scan. At pilot scale
     * (cache max 10k + 100k entries) that's ~100µs; no indexing needed.
     */
    public void invalidateTenantDeks(UUID tenantId) {
        activeDekCache.asMap().keySet().removeIf(key -> key.tenantId().equals(tenantId));
        resolvedDekCache.asMap().values().removeIf(resolved -> resolved.tenantId().equals(tenantId));
    }

    // ------------------------------------------------------------------
    // AES-KWP wrap / unwrap
    // ------------------------------------------------------------------

    /** Wraps raw DEK bytes under the per-tenant wrapping key. Output is
     *  RFC 5649 AES-KWP: 40 bytes for a 32-byte DEK (8-byte overhead). */
    private byte[] wrap(UUID tenantId, byte[] rawDek) {
        try {
            SecretKey wrappingKey = keyDerivationService.deriveKekWrappingKey(tenantId);
            Cipher cipher = Cipher.getInstance(AES_KWP_CIPHER);
            cipher.init(Cipher.WRAP_MODE, wrappingKey);
            SecretKey dekAsKey = new SecretKeySpec(rawDek, "AES");
            return cipher.wrap(dekAsKey);
        } catch (Exception e) {
            throw new IllegalStateException(
                "AES-KWP wrap failed for tenant " + tenantId + " — JDK should ship AESWrapPad", e);
        }
    }

    /** Unwraps a wrapped DEK back into a usable {@link SecretKey} for AES-GCM. */
    private SecretKey unwrap(UUID tenantId, byte[] wrappedDek) {
        try {
            SecretKey wrappingKey = keyDerivationService.deriveKekWrappingKey(tenantId);
            Cipher cipher = Cipher.getInstance(AES_KWP_CIPHER);
            cipher.init(Cipher.UNWRAP_MODE, wrappingKey);
            // "AES" algorithm name + Cipher.SECRET_KEY mode → SecretKeySpec
            // wrapping the unwrapped 32 bytes.
            return (SecretKey) cipher.unwrap(wrappedDek, "AES", Cipher.SECRET_KEY);
        } catch (Exception e) {
            throw new IllegalStateException(
                "AES-KWP unwrap failed for tenant " + tenantId
                + " — likely corrupt wrapped_dek or derivation mismatch", e);
        }
    }

    // ------------------------------------------------------------------
    // Private SELECT helper
    // ------------------------------------------------------------------

    private ActiveDek loadActiveDek(UUID tenantId, KeyPurpose purpose) {
        try {
            return jdbc.queryForObject(
                "SELECT kid, generation, wrapped_dek FROM tenant_dek "
                + "WHERE tenant_id = ? AND purpose = ? AND active = TRUE",
                (rs, rowNum) -> {
                    UUID kid = (UUID) rs.getObject("kid");
                    int generation = rs.getInt("generation");
                    byte[] wrappedDek = rs.getBytes("wrapped_dek");
                    SecretKey dek = unwrap(tenantId, wrappedDek);
                    return new ActiveDek(kid, dek, generation);
                },
                tenantId, purpose.name());
        } catch (EmptyResultDataAccessException empty) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------

    /** Composite cache key for the active-DEK cache. */
    record ActiveDekCacheKey(UUID tenantId, KeyPurpose purpose) {}

    /** Result of {@link #getOrCreateActiveDek}: the kid to emit in the
     *  envelope + the unwrapped DEK + the generation. */
    public record ActiveDek(UUID kid, SecretKey dek, int generation) {}

    /** Result of {@link #resolveDek}: full kid-to-(tenant, purpose,
     *  generation, DEK) resolution for the decrypt path. */
    public record ResolvedDek(UUID kid, UUID tenantId, KeyPurpose purpose,
                              int generation, SecretKey dek) {}
}
