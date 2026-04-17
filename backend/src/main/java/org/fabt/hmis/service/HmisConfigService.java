package org.fabt.hmis.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.fabt.hmis.domain.HmisVendorConfig;
import org.fabt.hmis.domain.HmisVendorType;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.tenant.service.TenantService;
import org.springframework.stereotype.Service;

/**
 * Reads HMIS vendor configuration from tenant config JSONB.
 * Cached via the tenant service's existing cache refresh pattern.
 *
 * <p>API keys are decrypted at read time so adapters receive plaintext. Writes
 * flow through {@link #encryptApiKey(String)} at the typed vendor-CRUD boundary
 * (stubbed today; implemented by platform-hardening). Existing plaintext values
 * are tolerated — {@link #decryptApiKey(String)} falls back to passthrough when
 * the stored value is not valid ciphertext, so a tenant with legacy plaintext
 * config continues to function until V74 re-encrypts on upgrade.
 */
@Service
public class HmisConfigService {

    private static final Logger log = LoggerFactory.getLogger(HmisConfigService.class);

    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final SecretEncryptionService encryptionService;

    public HmisConfigService(TenantService tenantService, ObjectMapper objectMapper,
                              SecretEncryptionService encryptionService) {
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
    }

    /**
     * Get all configured HMIS vendors for a tenant.
     */
    public List<HmisVendorConfig> getVendors(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode vendors = node.get("hmis_vendors");
                            if (vendors == null || !vendors.isArray() || vendors.isEmpty()) {
                                return List.<HmisVendorConfig>of();
                            }
                            List<HmisVendorConfig> result = new ArrayList<>();
                            for (JsonNode v : vendors) {
                                result.add(new HmisVendorConfig(
                                        v.has("id") ? v.get("id").asText() : UUID.randomUUID().toString(),
                                        HmisVendorType.valueOf(v.get("type").asText()),
                                        v.has("base_url") ? v.get("base_url").asText() : null,
                                        v.has("api_key_encrypted") ? decryptApiKey(v.get("api_key_encrypted").asText()) : null,
                                        !v.has("enabled") || v.get("enabled").asBoolean(true),
                                        v.has("push_interval_hours") ? v.get("push_interval_hours").asInt(6) : 6
                                ));
                            }
                            return result;
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to parse HMIS vendor config: {}", e.getMessage());
                            return List.<HmisVendorConfig>of();
                        } catch (IllegalArgumentException e) {
                            log.warn("Failed to parse HMIS vendor config: {}", e.getMessage());
                            return List.<HmisVendorConfig>of();
                        }
                    })
                    .orElse(List.of());
        } catch (java.util.NoSuchElementException e) {
            log.warn("Failed to parse HMIS vendor config: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get only enabled vendors.
     */
    public List<HmisVendorConfig> getEnabledVendors(UUID tenantId) {
        return getVendors(tenantId).stream()
                .filter(HmisVendorConfig::enabled)
                .toList();
    }

    /**
     * Encrypts a plaintext API key for JSONB storage. Typed vendor-CRUD
     * endpoints (platform-hardening) call this before serializing the vendor
     * config into the tenant config Map. Safe to call with null (returns null).
     */
    public String encryptApiKey(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        return encryptionService.encrypt(plaintext);
    }

    /**
     * Decrypts a stored API key. Falls back to passthrough on decrypt failure
     * so legacy plaintext values remain usable until the V74 re-encryption
     * migration runs on upgrade. Safe to call with null (returns null).
     */
    private String decryptApiKey(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            return encryptionService.decrypt(stored);
        } catch (RuntimeException e) {
            log.debug("api_key_encrypted is not valid ciphertext; returning plaintext fallback");
            return stored;
        }
    }
}
