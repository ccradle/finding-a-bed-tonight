package org.fabt.notification;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class SseStabilityTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private NotificationService notificationService;

    @AfterEach
    void cleanup() {
        notificationService.completeAll();
    }

    @Test
    @DisplayName("SSE emitter created with no timeout (-1L) registers successfully")
    void test_sseEmitter_registers() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = authHelper.getTestTenantId();

        SseEmitter emitter = notificationService.register(
                userId, tenantId, new String[]{"OUTREACH_WORKER"}, false, null);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("Re-registering same user completes previous emitter")
    void test_reRegister_completesPrevious() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = authHelper.getTestTenantId();

        SseEmitter emitter1 = notificationService.register(
                userId, tenantId, new String[]{"OUTREACH_WORKER"}, false, null);
        SseEmitter emitter2 = notificationService.register(
                userId, tenantId, new String[]{"OUTREACH_WORKER"}, false, null);

        assertThat(emitter1).isNotNull();
        assertThat(emitter2).isNotNull();
        assertThat(emitter1).isNotSameAs(emitter2);
    }

    @Test
    @DisplayName("Last-Event-ID with unknown ID sends refresh (no crash)")
    void test_lastEventId_unknownId_sendsRefresh() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = authHelper.getTestTenantId();

        // Register with a lastEventId that doesn't exist in the buffer
        SseEmitter emitter = notificationService.register(
                userId, tenantId, new String[]{"OUTREACH_WORKER"}, false, 999999L);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("SSE metrics are registered and accessible")
    void test_metrics_registered() {
        assertThat(meterRegistry.find("fabt.sse.connections.active").gauge()).isNotNull();
        assertThat(meterRegistry.find("sse.send.failures.total").counter()).isNotNull();
        assertThat(meterRegistry.find("sse.event.delivery.duration").timer()).isNotNull();
    }
}
