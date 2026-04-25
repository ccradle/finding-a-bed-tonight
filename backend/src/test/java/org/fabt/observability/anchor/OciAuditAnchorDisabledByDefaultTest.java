package org.fabt.observability.anchor;

import com.oracle.bmc.objectstorage.ObjectStorage;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the OCI audit-anchor integration is OFF by default.
 *
 * <p>The test profile does not set {@code fabt.oci.audit-anchor.enabled},
 * so {@link OciAuditAnchorConfig} (and the {@code AuditChainAnchorJobConfig}
 * + {@code AuditChainAnchorService} that depend on it) should not load.
 * No {@link ObjectStorage} bean should exist; no {@code auditChainAnchor}
 * batch job should be registered.
 *
 * <p>This is the load-bearing safety property: developers, CI runners, and
 * local dev environments never need OCI credentials, never make OCI network
 * calls, and never accidentally write to a real bucket.
 */
@DisplayName("OCI audit-anchor — disabled by default in dev/CI/test profiles")
class OciAuditAnchorDisabledByDefaultTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ObjectStorage bean does not exist when fabt.oci.audit-anchor.enabled is unset")
    void noObjectStorageBeanWhenDisabled() {
        // The condition is: bean exists ONLY if fabt.oci.audit-anchor.enabled=true.
        // Test profile leaves it unset (defaulting to false), so the bean
        // must not be present.
        String[] beanNames = context.getBeanNamesForType(ObjectStorage.class);
        assertThat(beanNames)
                .as("ObjectStorage bean must not be loaded in dev/CI/test "
                    + "— fabt.oci.audit-anchor.enabled is not set in this profile")
                .isEmpty();
    }

    @Test
    @DisplayName("AuditChainAnchorService does not exist when disabled")
    void noAnchorServiceWhenDisabled() {
        String[] beanNames = context.getBeanNamesForType(AuditChainAnchorService.class);
        assertThat(beanNames)
                .as("AuditChainAnchorService must not be loaded when OCI integration is off")
                .isEmpty();
    }
}
