package org.fabt.tenant.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.fabt.shared.config.JsonString;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private static final Map<String, Object> DEFAULT_CONFIG = Map.of(
            "api_key_auth_enabled", true,
            "default_locale", "en"
    );

    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    public TenantService(TenantRepository tenantRepository, ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Legacy tenant-create path. Inserts the {@code tenant} row only; relies on
     * lazy-at-first-login bootstrap in {@code KidRegistryService.findOrCreateActiveKid}
     * to populate {@code tenant_key_material} and register the initial kid, and does
     * NOT seed the {@code tenant_audit_chain_head} row that Phase G's hash-chain
     * writer will need.
     *
     * @deprecated since Phase F slice F-4 (v0.51.0). Prefer
     *     {@code TenantLifecycleService.create(name, slug, actorUserId)} which
     *     performs the full atomic bootstrap in one {@code @Transactional} — eager
     *     key material, audit_chain_head seed, {@code TENANT_CREATED} audit emit.
     *     {@link org.fabt.tenant.api.TenantController#create} delegates to the
     *     lifecycle service when the {@code fabt.tenant.lifecycle.enabled} feature
     *     flag is on and falls back here when it is off. This method stays
     *     available for the legacy path until the flag is removed (planned for
     *     F-6 / v0.51.0 tag).
     */
    @Deprecated(since = "0.51.0")
    @Transactional
    public Tenant create(String name, String slug) {
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalStateException("Tenant with slug '" + slug + "' already exists");
        }

        Tenant tenant = new Tenant();
        // ID left null — database generates via gen_random_uuid()
        // Spring Data JDBC uses isNew() based on null ID → INSERT
        tenant.setName(name);
        tenant.setSlug(slug);
        tenant.setConfig(JsonString.empty());
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());

        return tenantRepository.save(tenant);
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findById(UUID id) {
        return tenantRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug);
    }

    @Transactional(readOnly = true)
    public Iterable<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    @Transactional
    public Tenant update(UUID id, String name) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        tenant.setName(name);
        tenant.setUpdatedAt(Instant.now());

        return tenantRepository.save(tenant);
    }

    /**
     * Partial config update for {@code tenant.config.holdDurationMinutes}
     * (transitional-reentry-support task 4.5, slice 2C). Reads the existing
     * config, sets the one key, writes back — preserves any other keys
     * (e.g. {@code dv_address_visibility}, {@code features.reentryMode},
     * {@code active_counties}) without clobbering them.
     *
     * <p>Range enforced at the {@link org.fabt.shelter.api.HoldDurationRequest}
     * DTO layer (30-480 minutes) per design D5. This method does NOT
     * re-validate — it trusts the caller (controller). Called only from the
     * COC_ADMIN admin endpoint; not exposed elsewhere.
     *
     * @param id tenant id
     * @param holdDurationMinutes new value (caller pre-validated)
     * @return updated Tenant
     */
    @Transactional
    public Tenant setHoldDurationMinutes(UUID id, int holdDurationMinutes) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        try {
            // Read existing config as a mutable map; merge the one key.
            Map<String, Object> config;
            if (tenant.getConfig() != null && tenant.getConfig().value() != null
                && !tenant.getConfig().value().isBlank()) {
                config = new java.util.HashMap<>(
                    objectMapper.readValue(tenant.getConfig().value(),
                        new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } else {
                config = new java.util.HashMap<>();
            }
            config.put("holdDurationMinutes", holdDurationMinutes);

            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to merge holdDurationMinutes into tenant.config", e);
        }
    }

    @Transactional
    public Tenant updateConfig(UUID id, Map<String, Object> config) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        try {
            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid config format", e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getConfig(UUID id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        Map<String, Object> config = new HashMap<>(DEFAULT_CONFIG);

        if (tenant.getConfig() != null && tenant.getConfig().value() != null && !tenant.getConfig().value().isBlank()) {
            try {
                Map<String, Object> stored = objectMapper.readValue(
                        tenant.getConfig().value(),
                        new TypeReference<>() {}
                );
                config.putAll(stored);
            } catch (JacksonException e) {
                throw new IllegalStateException("Corrupt config JSON for tenant: " + id, e);
            }
        }

        return config;
    }
}
