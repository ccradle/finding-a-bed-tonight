package org.fabt.tenant.domain;

import java.time.Instant;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Table("tenant")
public class Tenant {

    @Id
    private UUID id;
    private String name;
    private String slug;
    private JsonString config;
    private Instant createdAt;
    private Instant updatedAt;
    /**
     * Lifecycle state. Mirrors the {@code tenant_state} enum column declared in V60.
     * Defaults to {@link TenantState#ACTIVE} so existing {@link org.fabt.tenant.service.TenantService}
     * {@code create()} paths (which never touch state) match the DB default.
     * Write-path state mutations flow exclusively through
     * {@code TenantLifecycleService} once Phase F slice F-4 lands.
     */
    private TenantState state = TenantState.ACTIVE;

    /**
     * Set by {@code TenantLifecycleService.offboard} (Phase F slice F-5) with the
     * filesystem path (v0.51.0) or S3 URI (Phase H) where the GDPR Art. 20
     * data-portability export landed. NULL for ACTIVE / SUSPENDED tenants;
     * required non-null before {@code archive()} may transition to ARCHIVED.
     */
    private String offboardExportReceiptUri;

    /**
     * Stamped when {@code archive()} transitions state to ARCHIVED. Starts the
     * 30-day retention window that {@code hardDelete()} (F-6) gates on. NULL for
     * non-ARCHIVED tenants.
     */
    private Instant archivedAt;

    public Tenant() {
    }

    public Tenant(UUID id, String name, String slug, JsonString config, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.config = config;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public JsonString getConfig() {
        return config;
    }

    public void setConfig(JsonString config) {
        this.config = config;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public TenantState getState() {
        return state;
    }

    public void setState(TenantState state) {
        this.state = state;
    }

    public String getOffboardExportReceiptUri() {
        return offboardExportReceiptUri;
    }

    public void setOffboardExportReceiptUri(String offboardExportReceiptUri) {
        this.offboardExportReceiptUri = offboardExportReceiptUri;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    /**
     * Reads {@code tenant.config.dv_policy_enabled} from a tenant config JSONB
     * string. Returns {@code false} on absent key, malformed JSON, non-boolean
     * value, or null/blank input — the conservative read posture mirrors
     * {@code ReservationConfigController.readHoldDurationFromConfig}.
     *
     * <p>The tenant DV-policy flag gates per-shelter {@code dv_shelter=true}
     * writes (see {@code dv-policy-tenant-flag} OpenSpec change). Because this
     * is a security-relevant invariant — a {@code true} reading authorizes a
     * write that would otherwise be rejected — this method is conservative on
     * ambiguity: any read failure resolves to {@code false}, NOT a thrown
     * exception, so a corrupt config row does not accidentally allow DV
     * shelter creation.
     *
     * <p>Static + parameterized rather than instance-state because Tenant is a
     * Spring Data JDBC entity with no Jackson dependency; the ObjectMapper is
     * threaded through from the calling service / controller (which already
     * have it injected).
     */
    public static boolean isDvPolicyEnabled(JsonString config, ObjectMapper objectMapper) {
        if (config == null || config.value() == null || config.value().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(config.value());
            JsonNode val = root.get("dv_policy_enabled");
            return val != null && val.isBoolean() && val.asBoolean();
        } catch (JacksonException e) {
            return false;
        }
    }

    /**
     * Reads {@code tenant.config.contact.email} from a tenant config JSONB
     * string. Returns {@code null} on absent key, malformed JSON, non-textual
     * value, or null/blank input. Mirrors {@link #isDvPolicyEnabled}'s
     * conservative-on-failure posture: an unreadable config row resolves to
     * "no override" rather than throwing, which is the safe default — the
     * frontend then falls back to the platform default.
     *
     * <p>Static + parameterized rather than instance-state for the same
     * reason as {@code isDvPolicyEnabled}: Tenant is a Spring Data JDBC
     * entity with no Jackson dependency; the ObjectMapper is threaded
     * through from the calling controller / service.
     *
     * <p>Extracted from controller-private helpers so
     * {@code ContactEmailController} (audit old_value capture) and
     * {@code ContactInfoController} (read-endpoint response body) share
     * one source of truth — info-email-contact §4 warroom round 1 M2-Sam.
     */
    public static String readContactEmail(JsonString config, ObjectMapper objectMapper) {
        if (config == null || config.value() == null || config.value().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(config.value());
            JsonNode contact = root.get("contact");
            if (contact == null || !contact.isObject()) {
                return null;
            }
            JsonNode email = contact.get("email");
            return (email != null && email.isTextual()) ? email.asText() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
