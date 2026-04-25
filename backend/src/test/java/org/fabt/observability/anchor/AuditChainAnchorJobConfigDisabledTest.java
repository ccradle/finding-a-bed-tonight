package org.fabt.observability.anchor;

import org.fabt.BaseIntegrationTest;
import org.fabt.observability.batch.AuditChainAnchorJobConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link AuditChainAnchorJobConfig} is NOT loaded when the
 * anchor integration is disabled (the default in dev/CI/test profiles).
 *
 * <p>If this test ever fails, the anchor batch job is loading without the
 * required OCI configuration and would crash at startup in any environment
 * lacking real OCI credentials. Load-bearing safety property.
 */
@DisplayName("AuditChainAnchorJobConfig — not loaded when OCI integration disabled")
class AuditChainAnchorJobConfigDisabledTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("AuditChainAnchorJobConfig bean does not exist when fabt.oci.audit-anchor.enabled is unset")
    void jobConfigNotLoadedWhenDisabled() {
        String[] beanNames = context.getBeanNamesForType(AuditChainAnchorJobConfig.class);
        assertThat(beanNames)
                .as("AuditChainAnchorJobConfig must not load in dev/CI/test profiles "
                    + "(fabt.oci.audit-anchor.enabled is unset). If this fails, the OCI "
                    + "batch job is being instantiated without credentials and will crash "
                    + "at startup in any environment without a real OCI bucket.")
                .isEmpty();
    }

    @Test
    @DisplayName("auditChainAnchor job is not registered with the batch scheduler")
    void jobNotRegisteredWithScheduler() {
        // BatchJobScheduler exposes registered job names via getCurrentCrons().
        // The 'auditChainAnchor' name should be ABSENT when the OCI config is
        // disabled — registerWithScheduler is gated on the same conditional
        // as the @Configuration class itself.
        var scheduler = context.getBean(org.fabt.analytics.config.BatchJobScheduler.class);
        assertThat(scheduler.getCurrentCrons())
                .as("auditChainAnchor must not be registered with BatchJobScheduler when OCI is disabled")
                .doesNotContainKey(AuditChainAnchorJobConfig.JOB_NAME);
    }
}
