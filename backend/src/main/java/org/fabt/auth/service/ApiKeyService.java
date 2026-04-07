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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public ApiKeyCreateResult create(UUID tenantId, UUID shelterId, String label) {
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

        // If not found, try old key within grace period
        if (found.isEmpty()) {
            found = apiKeyRepository.findByOldKeyHashAndActiveTrue(keyHash)
                    .filter(apiKey -> apiKey.getOldKeyExpiresAt() != null
                            && apiKey.getOldKeyExpiresAt().isAfter(Instant.now()));
        }

        found.ifPresent(apiKey -> {
            apiKey.setLastUsedAt(Instant.now());
            apiKeyRepository.save(apiKey);
        });
        return found;
    }

    private static final long DEFAULT_GRACE_PERIOD_HOURS = 24;

    @Transactional
    public ApiKeyCreateResult rotate(UUID keyId) {
        ApiKey existing = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new NoSuchElementException("API key not found: " + keyId));

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

    @Transactional
    public void deactivate(UUID keyId) {
        ApiKey existing = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new NoSuchElementException("API key not found: " + keyId));
        existing.setActive(false);
        apiKeyRepository.save(existing);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByTenantId(UUID tenantId) {
        return apiKeyRepository.findByTenantId(tenantId);
    }

    /**
     * Clear expired grace period keys. Runs hourly when scheduling is enabled.
     * Removes old_key_hash and old_key_expires_at after the grace window closes.
     */
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
        byte[] bytes = new byte[16];
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
