package org.fabt.tenant.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Transactional
    public Tenant updateConfig(UUID id, Map<String, Object> config) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        try {
            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JsonProcessingException e) {
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
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupt config JSON for tenant: " + id, e);
            }
        }

        return config;
    }
}
