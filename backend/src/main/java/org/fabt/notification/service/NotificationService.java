package org.fabt.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE connections and dispatches domain events to connected clients.
 * Each authenticated user gets one SseEmitter keyed by userId.
 *
 * Spring #33421: onCompletion/onTimeout/onError callbacks registered to prevent deadlock.
 * Spring #33340: 5-minute timeout + cleanup callbacks prevent emitter accumulation.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes

    private final ConcurrentHashMap<UUID, EmitterEntry> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final MeterRegistry meterRegistry;

    public NotificationService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Register gauge for active SSE connections
        meterRegistry.gauge("fabt.sse.connections.active", activeConnections);
    }

    /**
     * Metadata stored alongside each emitter for event filtering.
     */
    public record EmitterEntry(
            SseEmitter emitter,
            UUID userId,
            UUID tenantId,
            String[] roles,
            boolean dvAccess
    ) {}

    /**
     * Register a new SSE connection for an authenticated user.
     */
    public SseEmitter register(UUID userId, UUID tenantId, String[] roles, boolean dvAccess) {
        // Close any existing emitter for this user (reconnection case)
        EmitterEntry existing = emitters.remove(userId);
        if (existing != null) {
            existing.emitter().complete();
            activeConnections.decrementAndGet();
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        Runnable cleanup = () -> {
            emitters.remove(userId);
            activeConnections.decrementAndGet();
            log.debug("SSE emitter removed for user {}", userId);
        };

        // Spring #33421/#33340: register all three callbacks to prevent deadlock and leaks
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE emitter error for user {}: {}", userId, e.getMessage());
            cleanup.run();
        });

        emitters.put(userId, new EmitterEntry(emitter, userId, tenantId, roles, dvAccess));
        activeConnections.incrementAndGet();

        // Send initial retry directive
        try {
            emitter.send(SseEmitter.event().reconnectTime(5000));
        } catch (IOException e) {
            log.debug("Failed to send initial retry directive to user {}", userId);
        }

        log.debug("SSE emitter registered for user {} (tenant {})", userId, tenantId);
        return emitter;
    }

    /**
     * Complete all active emitters. Used during test cleanup and graceful shutdown
     * to ensure Tomcat doesn't block waiting for SSE requests to finish.
     */
    public void completeAll() {
        emitters.forEach((userId, entry) -> {
            try {
                entry.emitter().complete();
            } catch (Exception e) {
                log.debug("Error completing emitter for user {}: {}", userId, e.getMessage());
            }
        });
    }

    /**
     * Listens to all DomainEvents published via SpringEventBus and dispatches
     * to relevant connected SSE clients based on event type, tenant, and role.
     */
    @EventListener
    public void onDomainEvent(DomainEvent event) {
        switch (event.type()) {
            case "dv-referral.responded" -> notifyReferralResponse(event);
            case "dv-referral.requested" -> notifyReferralRequest(event);
            case "availability.updated" -> notifyAvailabilityUpdate(event);
            default -> { /* Other events not pushed via SSE */ }
        }
    }

    /**
     * Send SSE comment as keepalive to prevent proxy/LB idle timeout.
     * SSE comments are ignored by EventSource — they don't trigger event handlers.
     */
    @Scheduled(fixedRate = 30_000)
    public void sendKeepalive() {
        emitters.forEach((userId, entry) -> {
            try {
                entry.emitter().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException e) {
                log.debug("Keepalive failed for user {}, removing emitter", userId);
                entry.emitter().completeWithError(e);
            }
        });
    }

    /**
     * dv-referral.responded → only the outreach worker who created the referral.
     * Payload: referralId, status, shelterPhone (if accepted), rejectionReason (if rejected).
     * DV safety: NEVER include shelter name or address.
     */
    private void notifyReferralResponse(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();
        String tokenId = (String) payload.get("token_id");
        String status = (String) payload.get("status");

        // Build SSE payload — DV safety: only status + phone (if accepted)
        Map<String, Object> ssePayload = new java.util.LinkedHashMap<>();
        ssePayload.put("referralId", tokenId);
        ssePayload.put("status", status);

        // Note: shelter phone is not in the domain event payload.
        // The client will fetch updated referral list via REST for full details.

        emitters.forEach((userId, entry) -> {
            if (!entry.tenantId().equals(tenantId)) return;
            // Only send to the outreach worker who created the referral.
            // The domain event doesn't carry the referring user ID, so we send to all
            // outreach workers in the tenant. The client filters by referral ownership.
            // This is safe because the payload contains no sensitive data (no shelter info).
            if (!hasRole(entry.roles(), "OUTREACH_WORKER")) return;

            sendEvent(entry, "dv-referral.responded", ssePayload);
        });
    }

    /**
     * dv-referral.requested → DV-authorized coordinators in same tenant.
     * Payload: referralId, urgency, populationType (no client PII).
     */
    private void notifyReferralRequest(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();

        Map<String, Object> ssePayload = new java.util.LinkedHashMap<>();
        ssePayload.put("referralId", payload.get("token_id"));
        ssePayload.put("urgency", payload.get("urgency"));

        emitters.forEach((userId, entry) -> {
            if (!entry.tenantId().equals(tenantId)) return;
            if (!entry.dvAccess()) return;
            if (!hasRole(entry.roles(), "COORDINATOR")) return;

            sendEvent(entry, "dv-referral.requested", ssePayload);
        });
    }

    /**
     * availability.updated → all authenticated users in same tenant.
     */
    private void notifyAvailabilityUpdate(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();

        Map<String, Object> ssePayload = new java.util.LinkedHashMap<>();
        ssePayload.put("shelterId", payload.get("shelter_id"));
        ssePayload.put("shelterName", payload.get("shelter_name"));
        ssePayload.put("populationType", payload.get("population_type"));
        ssePayload.put("bedsAvailable", payload.get("beds_available"));
        ssePayload.put("bedsAvailablePrevious", payload.get("beds_available_previous"));

        emitters.forEach((userId, entry) -> {
            if (!entry.tenantId().equals(tenantId)) return;

            sendEvent(entry, "availability.updated", ssePayload);
        });
    }

    private void sendEvent(EmitterEntry entry, String eventType, Map<String, Object> data) {
        try {
            entry.emitter().send(
                    SseEmitter.event()
                            .id(String.valueOf(eventIdCounter.incrementAndGet()))
                            .name(eventType)
                            .data(data)
            );
            eventsSentCounter(eventType).increment();
        } catch (IOException e) {
            log.debug("Failed to send SSE event to user {}, removing emitter", entry.userId());
            entry.emitter().completeWithError(e);
        }
    }

    private Counter eventsSentCounter(String eventType) {
        return Counter.builder("fabt.sse.events.sent.count")
                .tag("eventType", eventType)
                .register(meterRegistry);
    }

    private boolean hasRole(String[] roles, String role) {
        if (roles == null) return false;
        for (String r : roles) {
            if (role.equals(r)) return true;
        }
        return false;
    }
}
