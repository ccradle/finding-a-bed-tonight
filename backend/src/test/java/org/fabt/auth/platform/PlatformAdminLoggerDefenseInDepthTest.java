package org.fabt.auth.platform;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.fabt.shared.audit.AuditEventType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the §6.17 defense-in-depth check at
 * {@link PlatformAdminLogger#aroundPlatformAdminOnly} (warroom H1 fix).
 *
 * <p>The {@code JustificationValidationFilter} normally rejects requests
 * missing {@code X-Platform-Justification} with 400 BEFORE the @Around
 * aspect runs. The aspect's defense-in-depth check is a regression
 * sentinel: if the filter is ever disabled, mis-ordered, or bypassed by
 * a new code path, the aspect MUST still reject + emit the
 * {@code fabt.platform.action.without_justification} counter that drives
 * the {@code FabtPlatformActionWithoutJustification} CRITICAL alert.</p>
 *
 * <p>These tests bypass the full HTTP filter chain by binding a
 * {@link MockHttpServletRequest} directly to {@link RequestContextHolder}
 * and invoking the aspect's @Around method as plain Java. That simulates
 * the "filter chain broken" scenario the alert is built to catch.</p>
 */
class PlatformAdminLoggerDefenseInDepthTest {

    private SimpleMeterRegistry meterRegistry;
    private PlatformAdminLogger aspect;
    private ProceedingJoinPoint pjp;
    private PlatformAdminOnly annotation;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aspect = new PlatformAdminLogger(
                mock(PlatformAdminAccessLogger.class),
                mock(PlatformActionStateCapture.class),
                new tools.jackson.databind.ObjectMapper(),
                providerOf(meterRegistry));
        pjp = mock(ProceedingJoinPoint.class);
        annotation = mock(PlatformAdminOnly.class);
        // Pick any audit event type for the metric tag — TEST_PROBE is
        // the canonical synthetic action per AuditEventRecord javadoc.
        org.mockito.Mockito.when(annotation.emits())
                .thenReturn(AuditEventType.TEST_PROBE);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("Missing X-Platform-Justification header → AccessDeniedException + counter increment")
    void rejectsAndIncrementsCounter_whenJustificationMissing() throws Throwable {
        bindRequestWithoutJustification();

        assertThatThrownBy(() -> aspect.aroundPlatformAdminOnly(pjp, annotation))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("X-Platform-Justification");

        // Counter increment is the sole programmatic signal that drives
        // the FabtPlatformActionWithoutJustification CRITICAL alert.
        double count = meterRegistry
                .find("fabt.platform.action.without_justification")
                .tag("action", AuditEventType.TEST_PROBE.name())
                .counter().count();
        assertThat(count)
                .as("Defense-in-depth counter must increment on every "
                        + "missing-justification rejection so the alert can fire")
                .isEqualTo(1.0);

        // The wrapped business method MUST NOT run when the gate rejects.
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("Blank X-Platform-Justification header → AccessDeniedException + counter increment")
    void rejectsAndIncrementsCounter_whenJustificationBlank() throws Throwable {
        bindRequestWithJustification("   ");

        assertThatThrownBy(() -> aspect.aroundPlatformAdminOnly(pjp, annotation))
                .isInstanceOf(AccessDeniedException.class);

        double count = meterRegistry
                .find("fabt.platform.action.without_justification")
                .tag("action", AuditEventType.TEST_PROBE.name())
                .counter().count();
        assertThat(count)
                .as("Whitespace-only header is treated as missing — "
                        + "the filter normalizes the same way")
                .isEqualTo(1.0);

        verify(pjp, never()).proceed();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void bindRequestWithoutJustification() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/v1/test/probe");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private void bindRequestWithJustification(String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/v1/test/probe");
        req.addHeader("X-Platform-Justification", value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /**
     * Tiny ObjectProvider stub that returns the provided MeterRegistry —
     * matches the production constructor signature without dragging
     * Spring's full ApplicationContext into the test.
     */
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> providerOf(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject() {
                return registry;
            }
            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject(Object... args) {
                return registry;
            }
            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
                return registry;
            }
            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfUnique() {
                return registry;
            }
        };
    }
}
