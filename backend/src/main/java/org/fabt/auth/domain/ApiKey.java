package org.fabt.auth.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("api_key")
public class ApiKey {

    @Id
    private UUID id;
    private UUID tenantId;
    private UUID shelterId;
    private String keyHash;
    private String keySuffix;
    private String label;
    private String role;
    private boolean active;
    private Instant createdAt;
    private Instant lastUsedAt;
    private String oldKeyHash;
    private Instant oldKeyExpiresAt;

    public ApiKey() {
    }

    public ApiKey(UUID id, UUID tenantId, UUID shelterId, String keyHash, String keySuffix,
                  String label, String role, boolean active, Instant createdAt, Instant lastUsedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.shelterId = shelterId;
        this.keyHash = keyHash;
        this.keySuffix = keySuffix;
        this.label = label;
        this.role = role;
        this.active = active;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getShelterId() {
        return shelterId;
    }

    public void setShelterId(UUID shelterId) {
        this.shelterId = shelterId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeySuffix() {
        return keySuffix;
    }

    public void setKeySuffix(String keySuffix) {
        this.keySuffix = keySuffix;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public String getOldKeyHash() {
        return oldKeyHash;
    }

    public void setOldKeyHash(String oldKeyHash) {
        this.oldKeyHash = oldKeyHash;
    }

    public Instant getOldKeyExpiresAt() {
        return oldKeyExpiresAt;
    }

    public void setOldKeyExpiresAt(Instant oldKeyExpiresAt) {
        this.oldKeyExpiresAt = oldKeyExpiresAt;
    }
}
