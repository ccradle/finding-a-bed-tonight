package org.fabt.tenant.domain;

import java.time.Instant;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

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
}
