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
     * Partial config update for {@code tenant.config.hold_duration_minutes}
     * (transitional-reentry-support task 4.5, slice 2C). Reads the existing
     * config, sets the one key, writes back — preserves any other keys
     * (e.g. {@code dv_address_visibility}, {@code features.reentryMode},
     * {@code active_counties}) without clobbering them.
     *
     * <p><b>JSON key casing:</b> the persisted key is snake_case
     * ({@code hold_duration_minutes}) to match the convention established
     * by the seed migrations (V76, V77) and consumed by
     * {@code ReservationService.getHoldDurationMinutes}. The DTO field
     * ({@link org.fabt.shelter.api.HoldDurationRequest#holdDurationMinutes()})
     * stays camelCase per Java + REST convention; the rename happens only
     * at the JSONB-write boundary. Initial slice-2C draft used camelCase
     * here, which silently no-op'd because the read path looked for the
     * snake_case key — caught by §13.7 integration test 2026-04-29.
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
            config.put("hold_duration_minutes", holdDurationMinutes);

            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to merge hold_duration_minutes into tenant.config", e);
        }
    }

    /**
     * Partial config update for {@code tenant.config.dv_policy_enabled}
     * (dv-policy-tenant-flag OpenSpec change). Reads the existing config,
     * sets the one key, writes back — preserves any other keys
     * ({@code hold_duration_minutes}, {@code dv_address_visibility},
     * {@code features.reentryMode}, {@code active_counties}, etc.) without
     * clobbering them. Mirrors {@link #setHoldDurationMinutes} verbatim.
     *
     * <p><b>JSON key casing:</b> the persisted key is snake_case
     * ({@code dv_policy_enabled}) to match the JSONB convention from V76+;
     * the DTO field stays camelCase per Java + REST convention; the rename
     * happens only at the JSONB-write boundary.
     *
     * <p>This method does NOT enforce the disable-path constraint
     * ("forbidden while DV shelters exist"); that lives at the controller
     * layer ({@code DvPolicyController}) where the structured-error code
     * and audit emission live. This method trusts the caller — by the time
     * a request reaches here, either the disable is allowed or the caller
     * has already short-circuited with a rejection + audit.
     *
     * @param id tenant id
     * @param value new value (caller pre-validated and pre-checked the disable-path constraint)
     * @return updated Tenant
     */
    /**
     * Partial config update for {@code tenant.config.contact.email}
     * (info-email-contact OpenSpec change, task 3.3). Reads the existing
     * config, sets the nested {@code contact.email} key, writes back —
     * preserves any other top-level keys ({@code hold_duration_minutes},
     * {@code dv_policy_enabled}, {@code dv_address_visibility},
     * {@code features.reentryMode}, {@code active_counties}) and any other
     * keys under {@code contact} (in case future contact-method fields are
     * added) without clobbering them.
     *
     * <p><b>Empty / null semantics:</b> a null or blank input clears the
     * key by removing {@code contact.email} from the JSONB. If the
     * {@code contact} object is empty afterwards, the {@code contact} key
     * itself is also removed (cleanest persisted state — readers can rely
     * on "key present implies non-empty value"). A subsequent
     * {@code GET /tenant/config} returns no {@code contact.email} key at
     * all, signaling the operator inherits the platform-default email.
     *
     * <p><b>JSON key casing:</b> {@code contact.email} both written and read
     * in lowercase. The DTO field stays {@code email} (camelCase, single word),
     * matching the JSONB key 1:1 — no rename needed at this boundary.
     *
     * <p><b>Validation:</b> caller (controller) is responsible for format
     * validation (Bean Validation @Email + @Size at the boundary) AND for
     * the DV-policy guard ("non-empty forbidden when dv_policy_enabled=true").
     * This service trusts the caller. The DV-policy guard is NOT enforced
     * here because it requires reading the same row this method is about to
     * write — better to centralize that check at the controller where the
     * structured error code + audit emission already live.
     *
     * @param id tenant id
     * @param email new email value; null or blank clears the key
     * @return updated Tenant
     */
    @Transactional
    public Tenant setContactEmail(UUID id, String email) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        try {
            Map<String, Object> config;
            if (tenant.getConfig() != null && tenant.getConfig().value() != null
                && !tenant.getConfig().value().isBlank()) {
                config = new java.util.HashMap<>(
                    objectMapper.readValue(tenant.getConfig().value(),
                        new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } else {
                config = new java.util.HashMap<>();
            }

            // Read or initialize the contact subtree without clobbering any
            // sibling keys a future feature might add (e.g. contact.phone).
            // Cast is safe because Jackson's TypeReference<Map<String, Object>>
            // produces String keys at deserialization time — non-String JSON
            // keys cannot reach here. Mirrors the cast pattern used in
            // setHoldDurationMinutes / setDvPolicyEnabled above.
            @SuppressWarnings("unchecked")
            Map<String, Object> contact = config.get("contact") instanceof Map<?, ?> existing
                    ? new java.util.HashMap<>((Map<String, Object>) existing)
                    : new java.util.HashMap<>();

            if (email == null || email.isBlank()) {
                contact.remove("email");
            } else {
                contact.put("email", email);
            }

            if (contact.isEmpty()) {
                config.remove("contact");
            } else {
                config.put("contact", contact);
            }

            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to merge contact.email into tenant.config", e);
        }
    }

    @Transactional
    public Tenant setDvPolicyEnabled(UUID id, boolean value) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + id));

        try {
            Map<String, Object> config;
            if (tenant.getConfig() != null && tenant.getConfig().value() != null
                && !tenant.getConfig().value().isBlank()) {
                config = new java.util.HashMap<>(
                    objectMapper.readValue(tenant.getConfig().value(),
                        new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } else {
                config = new java.util.HashMap<>();
            }
            config.put("dv_policy_enabled", value);

            String configJson = objectMapper.writeValueAsString(config);
            tenant.setConfig(JsonString.of(configJson));
            tenant.setUpdatedAt(Instant.now());
            return tenantRepository.save(tenant);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to merge dv_policy_enabled into tenant.config", e);
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
