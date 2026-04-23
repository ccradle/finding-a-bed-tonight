package org.fabt.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.ApiKey;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscoped;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private final ApiKeyRepository apiKeyRepository;
    private final JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, JdbcTemplate jdbc) {
        this.apiKeyRepository = apiKeyRepository;
        this.jdbc = jdbc;
    }

    /**
     * Creates a new API key for the caller's tenant.
     *
     * <p>Design D11 (URL-path-sink class): {@code tenantId} is sourced from
     * {@link TenantContext#getTenantId()} internally. The service SHALL NOT
     * accept {@code tenantId} as a parameter — doing so would invite a
     * future caller to pass an attacker-influenced value (e.g. URL path
     * variable, request body field) to a write operation. Symmetric with
     * {@code TenantOAuth2ProviderService.create}, {@code SubscriptionService.create},
     * and {@code ShelterService.create}.
     */
    @Transactional
    public ApiKeyCreateResult create(UUID shelterId, String label) {
        UUID tenantId = TenantContext.getTenantId();

        String plaintextKey = generateRandomKey();
        String keyHash = sha256Hex(plaintextKey);
        String keySuffix = plaintextKey.substring(plaintextKey.length() - 4);
        String role = shelterId != null ? "COORDINATOR" : "COC_ADMIN";

        ApiKey apiKey = new ApiKey();
        // ID left null — database generates via gen_random_uuid()
        apiKey.setTenantId(tenantId);
        apiKey.setShelterId(shelterId);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeySuffix(keySuffix);
        apiKey.setLabel(label);
        apiKey.setRole(role);
        apiKey.setActive(true);
        apiKey.setCreatedAt(Instant.now());

        apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResult(apiKey.getId(), plaintextKey, keySuffix);
    }

    @Transactional
    public Optional<ApiKey> validate(String rawKey) {
        String keyHash = sha256Hex(rawKey);

        // Try current key first
        Optional<ApiKey> found = apiKeyRepository.findByKeyHashAndActiveTrue(keyHash);

        // If not found, try old key within grace period (expiry checked in SQL)
        if (found.isEmpty()) {
            found = apiKeyRepository.findByOldKeyHashWithinGracePeriod(keyHash);
        }

        found.ifPresent(apiKey -> {
            apiKey.setLastUsedAt(Instant.now());
            apiKeyRepository.save(apiKey);
        });
        return found;
    }

    private static final long DEFAULT_GRACE_PERIOD_HOURS = 24;

    /**
     * Rotates an API key — issues a new plaintext key, preserves the old
     * hash for the grace period, returns the new plaintext to the caller.
     *
     * <p>Tenant-scoped: the key MUST belong to the caller's tenant
     * (resolved from {@link TenantContext}). A cross-tenant id returns 404
     * via {@link NoSuchElementException} — not 403 — to avoid existence
     * disclosure (design D3). See {@link #findByIdOrThrow(UUID)} and the
     * {@code cross-tenant-isolation-audit} change.
     */
    @Transactional
    public ApiKeyCreateResult rotate(UUID keyId) {
        ApiKey existing = findByIdOrThrow(keyId);

        if (!existing.isActive()) {
            throw new IllegalStateException("Cannot rotate a deactivated API key: " + keyId);
        }

        // Preserve old key hash for grace period — both keys authenticate during overlap
        existing.setOldKeyHash(existing.getKeyHash());
        existing.setOldKeyExpiresAt(Instant.now().plusSeconds(DEFAULT_GRACE_PERIOD_HOURS * 3600));

        String plaintextKey = generateRandomKey();
        String keyHash = sha256Hex(plaintextKey);
        String keySuffix = plaintextKey.substring(plaintextKey.length() - 4);

        existing.setKeyHash(keyHash);
        existing.setKeySuffix(keySuffix);
        apiKeyRepository.save(existing);

        return new ApiKeyCreateResult(existing.getId(), plaintextKey, keySuffix);
    }

    /**
     * Bulk-deactivates every API key belonging to {@code tenantId} as part of
     * a platform-admin-triggered tenant quarantine (Phase F suspend, §D11
     * action 2 of 5). Clears {@code active} and both grace-period columns so
     * neither the current key nor a recently-rotated key in its grace window
     * can still authenticate.
     *
     * <p><b>Explicit tenantId parameter (NOT from TenantContext) is intentional.</b>
     * This is a system-initiated bulk operation invoked from
     * {@code TenantLifecycleService.suspend} — there is no request-bound
     * TenantContext at the call site (the platform admin's tenant != the
     * target tenant being suspended). Mirrors {@link org.fabt.shared.security.TenantKeyRotationService#bumpJwtKeyGeneration}'s
     * signature convention. The F-3 ArchUnit Family D rule will apply
     * {@code @TenantInternal} to this method to restrict callers; for F-2 the
     * Javadoc is the enforcement contract.</p>
     *
     * @param tenantId the tenant whose keys to deactivate (must not be null)
     * @return the number of rows updated (zero if the tenant has no keys)
     */
    @Transactional
    public int deactivateAllForTenant(UUID tenantId) {
        // api_key is not RLS-protected (verified: no policy on this table in
        // V8, V68, V69) — direct UPDATE WHERE tenant_id=? is the idiomatic
        // bulk path. No set_config needed.
        //
        // Deliberately NOT gated on `active = TRUE`. Matches the per-key
        // deactivate() behavior which always NULLs both grace-period columns
        // regardless of prior active state. Defense-in-depth: if
        // findByOldKeyHashWithinGracePeriod ever loses its `active = true`
        // clause, a deactivated key with a lingering old_key_hash + unexpired
        // old_key_expires_at could re-authenticate. Warroom (Sam) F-2
        // pre-commit 2026-04-23.
        int affected = jdbc.update(
            "UPDATE api_key SET active = FALSE, "
            + "old_key_hash = NULL, old_key_expires_at = NULL "
            + "WHERE tenant_id = ?",
            tenantId);
        if (affected > 0) {
            log.info("Deactivated {} API keys for tenant {} (tenant quarantine)", affected, tenantId);
        }
        return affected;
    }

    /**
     * Deactivates an API key and clears any active grace-period hash so the
     * revoked key cannot authenticate via the {@code old_key_hash} path.
     *
     * <p>Tenant-scoped: the key MUST belong to the caller's tenant
     * (resolved from {@link TenantContext}). A cross-tenant id returns 404.
     */
    @Transactional
    public void deactivate(UUID keyId) {
        ApiKey existing = findByIdOrThrow(keyId);
        existing.setActive(false);
        // Clear any active grace period — revoked key should not authenticate via old hash
        existing.setOldKeyHash(null);
        existing.setOldKeyExpiresAt(null);
        apiKeyRepository.save(existing);
    }

    /**
     * Tenant-scoped single-key lookup used by every state-mutating path in
     * this service ({@link #rotate} and {@link #deactivate}). Pulls the
     * caller's {@code tenantId} from {@link TenantContext} and delegates to
     * {@link ApiKeyRepository#findByIdAndTenantId(UUID, UUID)}. Throws
     * {@link NoSuchElementException} on empty — mapped to 404 by
     * {@code GlobalExceptionHandler}. All state-mutating call sites go
     * through this helper so the tenant-scoping invariant cannot be
     * forgotten at one site while the others are hardened.
     */
    private ApiKey findByIdOrThrow(UUID keyId) {
        return apiKeyRepository.findByIdAndTenantId(keyId, TenantContext.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("API key not found: " + keyId));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByTenantId(UUID tenantId) {
        return apiKeyRepository.findByTenantId(tenantId);
    }

    /**
     * Clear expired grace period keys. Runs hourly when scheduling is enabled.
     * Removes old_key_hash and old_key_expires_at after the grace window closes.
     * Idempotent — safe if multiple runs overlap.
     * NOTE: For multi-instance deployments, add ShedLock to prevent duplicate execution.
     */
    @TenantUnscoped("hourly scheduled cleanup — runs across all tenants")
    @Scheduled(fixedRate = 3600_000) // every hour
    @Transactional
    public void cleanupExpiredGracePeriodKeys() {
        List<ApiKey> expired = apiKeyRepository.findExpiredGracePeriodKeys();
        for (ApiKey key : expired) {
            key.setOldKeyHash(null);
            key.setOldKeyExpiresAt(null);
            apiKeyRepository.save(key);
            log.info("Cleared expired grace period for API key {} (suffix: {})", key.getId(), key.getKeySuffix());
        }
        if (!expired.isEmpty()) {
            log.info("Cleaned up {} expired API key grace periods", expired.size());
        }
    }

    private String generateRandomKey() {
        byte[] bytes = new byte[32]; // 256 bits — industry standard for API keys
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ApiKeyCreateResult(UUID id, String plaintextKey, String suffix) {
    }
}
