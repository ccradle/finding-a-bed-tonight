package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link TenantService#setDvPolicyEnabled} (dv-policy-tenant-flag
 * OpenSpec change task §2.4). Verifies the JSONB-merge round-trip preserves
 * existing config keys and writes the new flag correctly on both populated
 * and empty initial states. Repository is mocked; the controller-level
 * integration test (§4.8) covers the full HTTP → DB path.
 */
class TenantServiceDvPolicyTest {

    private TenantRepository repository;
    private ObjectMapper objectMapper;
    private TenantService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TenantRepository.class);
        objectMapper = new ObjectMapper();
        service = new TenantService(repository, objectMapper);
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("sets dv_policy_enabled=true on tenant with empty config — initial JSONB write")
    void setsTrueOnEmptyConfig() throws Exception {
        Tenant tenant = newTenantWithConfig(JsonString.empty());
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.setDvPolicyEnabled(tenantId, true);

        Map<String, Object> persisted = parse(result.getConfig());
        assertThat(persisted).containsEntry("dv_policy_enabled", true);
    }

    @Test
    @DisplayName("sets dv_policy_enabled=false on tenant with empty config")
    void setsFalseOnEmptyConfig() throws Exception {
        Tenant tenant = newTenantWithConfig(JsonString.empty());
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.setDvPolicyEnabled(tenantId, false);

        Map<String, Object> persisted = parse(result.getConfig());
        assertThat(persisted).containsEntry("dv_policy_enabled", false);
    }

    @Test
    @DisplayName("preserves other keys when adding dv_policy_enabled to existing config")
    void preservesOtherKeysOnExistingConfig() throws Exception {
        JsonString existing = JsonString.of(
                "{\"hold_duration_minutes\":120,\"default_locale\":\"en\",\"active_counties\":[\"Buncombe\"]}");
        Tenant tenant = newTenantWithConfig(existing);
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.setDvPolicyEnabled(tenantId, true);

        Map<String, Object> persisted = parse(result.getConfig());
        assertThat(persisted).containsEntry("dv_policy_enabled", true);
        assertThat(persisted).containsEntry("hold_duration_minutes", 120);
        assertThat(persisted).containsEntry("default_locale", "en");
        assertThat(persisted.get("active_counties")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> counties = (java.util.List<String>) persisted.get("active_counties");
        assertThat(counties).containsExactly("Buncombe");
    }

    @Test
    @DisplayName("overwrites existing dv_policy_enabled value on flip")
    void overwritesExistingValue() throws Exception {
        JsonString existing = JsonString.of(
                "{\"dv_policy_enabled\":false,\"hold_duration_minutes\":120}");
        Tenant tenant = newTenantWithConfig(existing);
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.setDvPolicyEnabled(tenantId, true);

        Map<String, Object> persisted = parse(result.getConfig());
        assertThat(persisted).containsEntry("dv_policy_enabled", true);
        assertThat(persisted).containsEntry("hold_duration_minutes", 120);
    }

    @Test
    @DisplayName("idempotent — repeated set to same value persists same JSON")
    void idempotentRepeatedSet() throws Exception {
        Tenant tenant = newTenantWithConfig(JsonString.of("{\"dv_policy_enabled\":true}"));
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.setDvPolicyEnabled(tenantId, true);

        Map<String, Object> persisted = parse(captor.getValue().getConfig());
        assertThat(persisted).containsEntry("dv_policy_enabled", true);
    }

    @Test
    @DisplayName("updatedAt timestamp is bumped")
    void bumpsUpdatedAt() {
        Tenant tenant = newTenantWithConfig(JsonString.empty());
        java.time.Instant before = tenant.getUpdatedAt();
        when(repository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(repository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = service.setDvPolicyEnabled(tenantId, true);

        assertThat(result.getUpdatedAt()).isAfter(before);
    }

    private Tenant newTenantWithConfig(JsonString config) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Test CoC");
        tenant.setSlug("test-coc");
        tenant.setConfig(config);
        tenant.setCreatedAt(java.time.Instant.now().minusSeconds(60));
        tenant.setUpdatedAt(java.time.Instant.now().minusSeconds(60));
        return tenant;
    }

    private Map<String, Object> parse(JsonString config) throws Exception {
        return objectMapper.readValue(config.value(), new TypeReference<Map<String, Object>>() {});
    }
}
