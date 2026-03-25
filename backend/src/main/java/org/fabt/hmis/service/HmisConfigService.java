package org.fabt.hmis.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.fabt.hmis.domain.HmisVendorConfig;
import org.fabt.hmis.domain.HmisVendorType;
import org.fabt.tenant.service.TenantService;
import org.springframework.stereotype.Service;

/**
 * Reads HMIS vendor configuration from tenant config JSONB.
 * Cached via the tenant service's existing cache refresh pattern.
 */
@Service
public class HmisConfigService {

    private static final Logger log = LoggerFactory.getLogger(HmisConfigService.class);

    private final TenantService tenantService;
    private final ObjectMapper objectMapper;

    public HmisConfigService(TenantService tenantService, ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
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
                                        v.has("api_key_encrypted") ? v.get("api_key_encrypted").asText() : null,
                                        !v.has("enabled") || v.get("enabled").asBoolean(true),
                                        v.has("push_interval_hours") ? v.get("push_interval_hours").asInt(6) : 6
                                ));
                            }
                            return result;
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
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
}
