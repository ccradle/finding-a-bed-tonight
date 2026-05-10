package org.fabt.notification;

import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
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

    /**
     * Regression guard for the SseCatchupDeliversUnreadNotifications flake.
     *
     * <p>Pre-fix shape of the bug: each {@code register()} captured a {@code Runnable
     * cleanup} closure that called {@code emitters.remove(userId)} (by KEY only). When
     * a user reconnected, the previous emitter's {@code complete()} scheduled its
     * onCompletion callback to fire async via Spring's DeferredResult lifecycle. If
     * the new {@code register()} put a new entry under the same key BEFORE that stale
     * callback fired, the callback would still {@code remove(userId)} — evicting the
     * newer entry. The next {@code pushNotification(userId, ...)} then silently
     * no-op'd (entry == null path), the catch-up never delivered, and the latch in
     * {@code SseNotificationIntegrationTest.sseCatchupDeliversUnreadNotifications}
     * timed out at exactly 5.020s.
     *
     * <p>The fix routes all three lifecycle callbacks through
     * {@link NotificationService#removeEmitterIfMatches(UUID, NotificationService.EmitterEntry)},
     * which uses {@code ConcurrentHashMap.remove(key, value)}. This test invokes the
     * stale-callback path directly — no async timing required.
     */
    @Test
    @DisplayName("Stale lifecycle callback for retired emitter does not evict the current entry (GH SSE flake fix)")
    void test_staleCleanupCallback_doesNotEvictNewerEmitter() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = authHelper.getTestTenantId();
        String[] roles = new String[]{"COORDINATOR"};

        // First register — emitter1 + entry1 land in the map
        notificationService.register(userId, tenantId, roles, false, null);
        @SuppressWarnings("unchecked")
        Map<UUID, NotificationService.EmitterEntry> emitters =
                (Map<UUID, NotificationService.EmitterEntry>)
                        ReflectionTestUtils.getField(notificationService, "emitters");
        assertThat(emitters).isNotNull();
        NotificationService.EmitterEntry entry1 = emitters.get(userId);
        assertThat(entry1).as("entry1 must be in the map after first register").isNotNull();

        // Second register — emitter2 + entry2 replace entry1
        notificationService.register(userId, tenantId, roles, false, null);
        NotificationService.EmitterEntry entry2 = emitters.get(userId);
        assertThat(entry2).as("entry2 must be in the map after second register").isNotNull();
        assertThat(entry2)
                .as("second register must produce a distinct EmitterEntry — equals() compares all record components incl. SseEmitter identity")
                .isNotEqualTo(entry1);

        // Simulate emitter1's stale lifecycle callback firing AFTER emitter2 is registered.
        // This is the bug shape: pre-fix, this would call emitters.remove(userId) by KEY
        // and evict entry2. Post-fix, it's a no-op because entry1 != current value.
        boolean removed = notificationService.removeEmitterIfMatches(userId, entry1);
        assertThat(removed)
                .as("stale callback for retired entry1 must NOT remove anything — it is value-conditional now")
                .isFalse();

        // The newer entry MUST still be the current one. Pre-fix this assertion would
        // fail with entry2 evicted from the map.
        NotificationService.EmitterEntry afterStale = emitters.get(userId);
        assertThat(afterStale)
                .as("entry2 must survive a stale callback fired by emitter1's lifecycle (was the GH SSE flake root cause)")
                .isEqualTo(entry2);

        // The legitimate cleanup (callback fired by emitter2's own lifecycle) is still wired.
        boolean removedLive = notificationService.removeEmitterIfMatches(userId, entry2);
        assertThat(removedLive)
                .as("legitimate callback for live entry2 MUST remove it")
                .isTrue();
        assertThat(emitters.get(userId))
                .as("after live callback fires, the entry is gone")
                .isNull();
    }
}
