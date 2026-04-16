package org.fabt.security;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4.8 (D13) — verifies the {@code app.tenant_id} session variable
 * installed by {@code RlsDataSourceConfig.applyRlsContext} does not bleed
 * across connection-pool checkouts when tenants alternate rapidly.
 *
 * <p>Mirrors {@code CrossTenantIsolationTest.connectionPoolReuse_alternatingTenants_noLeakage}
 * but targets the tenant_id session variable specifically (that test targets
 * dvAccess via shelter-list visibility). 100 sequential iterations swapping
 * tenant_id between requests on the same JdbcTemplate, asserting no bleed.
 *
 * <p>The session variable is installed as infrastructure for the companion
 * change {@code multi-tenant-production-readiness} (D14 — tenant-RLS on
 * regulated tables). No current RLS policy reads it; this test ensures the
 * per-borrow set_config is working correctly BEFORE D14 adds the policies.
 */
@DisplayName("app.tenant_id connection pool bleed test (D13)")
class TenantIdPoolBleedTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantAId = authHelper.getTestTenantId();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("pool-bleed-" + suffix);
        tenantBId = tenantB.getId();
    }

    @Test
    @DisplayName("100 alternating-tenant iterations: app.tenant_id never bleeds")
    void alternatingTenants_tenantIdSessionVarNeverBleeds() {
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            UUID expectedTenantId = (i % 2 == 0) ? tenantAId : tenantBId;

            TenantContext.runWithContext(expectedTenantId, false, () -> {
                String actual = jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.tenant_id', true)",
                        String.class);
                assertThat(actual)
                        .as("Iteration %d: app.tenant_id must match the "
                                + "TenantContext tenant — connection pool must "
                                + "not carry stale tenant_id from prior checkout", iteration)
                        .isEqualTo(expectedTenantId.toString());
            });
        }
    }

    @Test
    @DisplayName("Null tenant context sets empty app.tenant_id (scheduled-task case)")
    void nullTenantContext_setsEmptySessionVar() {
        // Scheduled tasks run without TenantContext. The session variable
        // should be empty string (not the previous tenant's ID).
        TenantContext.runWithContext(tenantAId, false, () -> {
            String warmup = jdbcTemplate.queryForObject(
                    "SELECT current_setting('app.tenant_id', true)",
                    String.class);
            assertThat(warmup).isEqualTo(tenantAId.toString());
        });

        // Now run without TenantContext (simulates scheduled job)
        String afterNull = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.tenant_id', true)",
                String.class);
        assertThat(afterNull)
                .as("After TenantContext scope exits, next connection borrow "
                        + "must reset app.tenant_id to empty (not carry the "
                        + "previous tenant)")
                .isEqualTo("");
    }
}
