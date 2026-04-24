package org.fabt.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.fabt.BaseIntegrationTest;
import org.fabt.tenant.service.TenantLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Companion to {@link TenantStatePersistenceIntegrationTest}: asserts that flipping
 * the {@code fabt.tenant.lifecycle.enabled} flag to {@code true} wires up the
 * {@link TenantLifecycleService} bean. The flag-off case is covered in the persistence
 * test (cheap — no extra context). This test is in its own class because
 * {@code @TestPropertySource} produces a separate Spring application context, and we
 * want the cost of that second context to be visible and isolated rather than
 * blended into an unrelated test class.
 */
@TestPropertySource(properties = "fabt.tenant.lifecycle.enabled=true")
class TenantLifecycleFeatureFlagEnabledIntegrationTest extends BaseIntegrationTest {

    @Autowired(required = false)
    private TenantLifecycleService tenantLifecycleService;

    @Test
    void tenantLifecycleServiceBeanIsWiredWhenFeatureFlagIsTrue() {
        assertThat(tenantLifecycleService)
            .as("fabt.tenant.lifecycle.enabled=true must cause Spring to instantiate "
                + "TenantLifecycleService via @ConditionalOnProperty")
            .isNotNull();
    }
}
