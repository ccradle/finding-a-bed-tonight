package org.fabt.notification.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.fabt.shared.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE connections and dispatches domain events to connected clients.
 * Each authenticated user gets one SseEmitter keyed by userId.
 *
 * Spring #33421: onCompletion/onTimeout/onError callbacks registered to prevent deadlock.
 * No server-side timeout (-1L) — dead connections detected by 20-second heartbeat failures.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long EMITTER_TIMEOUT_MS = -1L; // No timeout — dead connections detected by heartbeat failure

    private static final int MAX_BUFFER_SIZE = 100;
    private static final long MAX_BUFFER_AGE_MS = 5 * 60 * 1000L; // 5 minutes

    private final ConcurrentHashMap<UUID, EmitterEntry> emitters = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<BufferedEvent> eventBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong eventIdCounter = new AtomicLong(0);
    private final MeterRegistry meterRegistry;
    private final Counter sendFailuresCounter;
    private final Timer eventDeliveryTimer;

    /**
     * Buffered event for Last-Event-ID replay. Includes tenant/DV metadata for per-user filtering.
     */
    public record BufferedEvent(long id, String eventType, Object data, UUID tenantId, boolean requiresDvAccess, Instant timestamp) {}

    public NotificationService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("fabt.sse.connections.active", activeConnections);
        this.sendFailuresCounter = Counter.builder("sse.send.failures.total")
                .description("SSE send failures (dead connection detection)")
                .register(meterRegistry);
        this.eventDeliveryTimer = Timer.builder("sse.event.delivery.duration")
                .description("Time to deliver an event to all connected clients")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
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
     * @param lastEventId if present, replay missed events from buffer after this ID
     */
    public SseEmitter register(UUID userId, UUID tenantId, String[] roles, boolean dvAccess, Long lastEventId) {
        // Close any existing emitter for this user (reconnection case)
        EmitterEntry existing = emitters.remove(userId);
        if (existing != null) {
            existing.emitter().complete();
            activeConnections.decrementAndGet();
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        // Idempotent cleanup — Spring may fire onCompletion after onTimeout/onError,
        // so cleanup can be called multiple times per emitter. Only decrement the gauge once.
        Runnable cleanup = () -> {
            if (emitters.remove(userId) != null) {
                activeConnections.decrementAndGet();
                log.debug("SSE emitter removed for user {}", userId);
            }
        };

        // Spring #33421/#33340: register all three callbacks to prevent deadlock and leaks.
        // Callbacks must be idempotent — heartbeat/sendEvent may have already removed the
        // emitter from the map before these callbacks fire asynchronously (Design D5).
        emitter.onCompletion(() -> {
            if (emitters.containsKey(userId)) {
                cleanup.run();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for user {}", userId);
            if (emitters.containsKey(userId)) {
                cleanup.run();
            }
        });
        emitter.onError(e -> {
            log.warn("SSE emitter error for user {}: {}", userId, e.getClass().getSimpleName());
            if (emitters.containsKey(userId)) {
                cleanup.run();
            }
        });

        emitters.put(userId, new EmitterEntry(emitter, userId, tenantId, roles, dvAccess));
        activeConnections.incrementAndGet();

        // Send initial connection event with retry field and monotonic id
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(eventIdCounter.incrementAndGet()))
                    .name("connected")
                    .data("{\"heartbeatInterval\":20000}")
                    .reconnectTime(5000));
        } catch (IOException e) {
            log.debug("Failed to send initial connection event to user {}", userId);
        }

        // Replay missed events if client reconnects with Last-Event-ID
        if (lastEventId != null) {
            replayFromBuffer(emitter, lastEventId, tenantId, roles, dvAccess);
        }

        log.debug("SSE emitter registered for user {} (tenant {})", userId, tenantId);
        return emitter;
    }

    /**
     * Complete a specific user's emitter. Used when a user is deactivated
     * to immediately disconnect their SSE notification stream.
     */
    public void completeEmitter(UUID userId) {
        EmitterEntry entry = emitters.remove(userId);
        if (entry != null) {
            try {
                entry.emitter().complete();
                activeConnections.decrementAndGet();
                log.debug("SSE emitter completed for deactivated user {}", userId);
            } catch (Exception e) {
                log.debug("Error completing emitter for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * Complete all active emitters. Called on graceful shutdown to trigger immediate
     * client reconnection to a healthy node, and during test cleanup.
     */
    @PreDestroy
    public void completeAll() {
        // Remove from map FIRST, then complete — prevents heartbeat scheduler
        // from seeing completed emitters during the cleanup window
        var snapshot = new java.util.ArrayList<>(emitters.values());
        int count = snapshot.size();
        emitters.clear();
        activeConnections.set(0);
        if (count > 0) {
            log.info("Closing {} SSE connections for graceful shutdown", count);
        }
        for (var entry : snapshot) {
            try {
                entry.emitter().complete();
            } catch (Exception e) {
                log.debug("Error completing emitter for user {}: {}", entry.userId(), e.getMessage());
            }
        }
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
            case "dv-referral.expired" -> notifyReferralExpired(event);
            case "availability.updated" -> notifyAvailabilityUpdate(event);
            default -> { /* Other events not pushed via SSE */ }
        }
    }

    /**
     * Send heartbeat as a named event (not a comment) every 20 seconds.
     * Named events advance Last-Event-ID for accurate reconnect replay.
     * Also detects dead connections — IOException triggers immediate cleanup.
     * Per-emitter try-catch: one stuck emitter cannot block heartbeats to others.
     */
    @Scheduled(fixedRate = 20_000)
    public void sendHeartbeat() {
        emitters.forEach((userId, entry) -> {
            try {
                entry.emitter().send(SseEmitter.event()
                        .id(String.valueOf(eventIdCounter.incrementAndGet()))
                        .name("heartbeat")
                        .data("{}"));
            } catch (IOException e) {
                sendFailuresCounter.increment();
                log.warn("Heartbeat failed for user {}: {} — removing emitter", userId, e.getClass().getSimpleName());
                // Remove from map FIRST to prevent onError callback race (Design D1)
                if (emitters.remove(userId) != null) {
                    activeConnections.decrementAndGet();
                }
                try { entry.emitter().completeWithError(e); } catch (Exception ignored) { /* already completed */ }
            } catch (IllegalStateException e) {
                sendFailuresCounter.increment();
                log.warn("Heartbeat skipped for user {} (emitter already completed): {}", userId, e.getMessage());
                if (emitters.remove(userId) != null) {
                    activeConnections.decrementAndGet();
                }
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

            sendAndBufferEvent(entry, "dv-referral.responded", ssePayload, tenantId, false);
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

            sendAndBufferEvent(entry, "dv-referral.requested", ssePayload, tenantId, true);
        });
    }

    /**
     * dv-referral.expired → coordinators in same tenant.
     * Payload: list of expired token IDs (no client PII).
     */
    private void notifyReferralExpired(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();

        Map<String, Object> ssePayload = new java.util.LinkedHashMap<>();
        ssePayload.put("tokenIds", payload.get("token_ids"));

        emitters.forEach((userId, entry) -> {
            if (!entry.tenantId().equals(tenantId)) return;
            if (!hasRole(entry.roles(), "COORDINATOR")) return;

            sendAndBufferEvent(entry, "dv-referral.expired", ssePayload, tenantId, false);
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

            sendAndBufferEvent(entry, "availability.updated", ssePayload, tenantId, false);
        });
    }

    /**
     * Replay events from the buffer that the client missed (after lastEventId),
     * filtered by tenant and role. If lastEventId is not found in buffer, send a
     * 'refresh' event type so the client does a single bulk refetch.
     */
    private void replayFromBuffer(SseEmitter emitter, long lastEventId, UUID tenantId, String[] roles, boolean dvAccess) {
        // Find events after lastEventId
        List<BufferedEvent> missed = new ArrayList<>();
        boolean found = false;
        for (BufferedEvent ev : eventBuffer) {
            if (ev.id() == lastEventId) {
                found = true;
                continue;
            }
            if (found) {
                // Filter by tenant and DV access (same logic as onDomainEvent)
                if (!ev.tenantId().equals(tenantId)) continue;
                if (ev.requiresDvAccess() && !dvAccess) continue;
                // Skip heartbeats — client doesn't need them replayed
                if ("heartbeat".equals(ev.eventType())) continue;
                missed.add(ev);
            }
        }

        if (!found) {
            // lastEventId is too stale — send refresh event
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(eventIdCounter.incrementAndGet()))
                        .name("refresh")
                        .data("{\"reason\":\"event_buffer_expired\"}"));
                meterRegistry.counter("sse.reconnections.total", "type", "stale").increment();
            } catch (IOException e) {
                log.debug("Failed to send refresh event on reconnect");
            }
            return;
        }

        // Replay missed events
        for (BufferedEvent ev : missed) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(ev.id()))
                        .name(ev.eventType())
                        .data(ev.data()));
            } catch (IOException e) {
                log.debug("Failed to replay event {} on reconnect", ev.id());
                break;
            }
        }
        if (!missed.isEmpty()) {
            meterRegistry.counter("sse.reconnections.total", "type", "replayed").increment();
            log.debug("Replayed {} events after lastEventId {}", missed.size(), lastEventId);
        } else {
            meterRegistry.counter("sse.reconnections.total", "type", "no_gap").increment();
        }
    }

    /**
     * Add an event to the replay buffer. Evicts old entries by size and age.
     */
    private void bufferEvent(long id, String eventType, Object data, UUID tenantId, boolean requiresDvAccess) {
        eventBuffer.addLast(new BufferedEvent(id, eventType, data, tenantId, requiresDvAccess, Instant.now()));
        // Evict by size
        while (eventBuffer.size() > MAX_BUFFER_SIZE) {
            eventBuffer.pollFirst();
        }
        // Evict by age
        Instant cutoff = Instant.now().minusMillis(MAX_BUFFER_AGE_MS);
        while (!eventBuffer.isEmpty() && eventBuffer.peekFirst().timestamp().isBefore(cutoff)) {
            eventBuffer.pollFirst();
        }
    }

    /**
     * Push a persistent notification to a specific user's SSE emitter (Design D5 write-through).
     * Called by NotificationPersistenceService after DB write. Non-fatal if user is not connected.
     */
    public void pushNotification(UUID recipientId, org.fabt.notification.domain.Notification notification) {
        EmitterEntry entry = emitters.get(recipientId);
        if (entry == null) {
            return; // User not connected — notification will be delivered via catch-up on next login
        }
        long eventId = eventIdCounter.incrementAndGet();
        Map<String, Object> data = Map.of(
                "notificationId", notification.getId().toString(),
                "type", notification.getType(),
                "severity", notification.getSeverity(),
                "payload", notification.getPayloadValue(),
                "createdAt", notification.getCreatedAt().toString()
        );
        sendEvent(entry, "notification", data, eventId);
    }

    /**
     * Send an event to a specific emitter and buffer it for replay.
     * The eventId is pre-assigned so it's consistent across the buffer and the stream.
     */
    private void sendEvent(EmitterEntry entry, String eventType, Map<String, Object> data, long eventId) {
        eventDeliveryTimer.record(() -> {
            try {
                entry.emitter().send(
                        SseEmitter.event()
                                .id(String.valueOf(eventId))
                                .name(eventType)
                                .data(data)
                );
                eventsSentCounter(eventType).increment();
            } catch (IOException e) {
                sendFailuresCounter.increment();
                log.warn("SSE event send failed for user {}: {} — removing emitter", entry.userId(), e.getClass().getSimpleName());
                // Remove from map FIRST to prevent onError callback race (Design D1)
                if (emitters.remove(entry.userId()) != null) {
                    activeConnections.decrementAndGet();
                }
                try { entry.emitter().completeWithError(e); } catch (Exception ignored) { /* already completed */ }
            } catch (IllegalStateException e) {
                sendFailuresCounter.increment();
                log.warn("SSE event skipped for user {} (emitter already completed): {}", entry.userId(), e.getMessage());
                if (emitters.remove(entry.userId()) != null) {
                    activeConnections.decrementAndGet();
                }
            }
        });
    }

    /**
     * Allocate an event ID, buffer the event, then send to the target emitter.
     */
    private void sendAndBufferEvent(EmitterEntry entry, String eventType, Map<String, Object> data,
                                     UUID tenantId, boolean requiresDvAccess) {
        long eventId = eventIdCounter.incrementAndGet();
        bufferEvent(eventId, eventType, data, tenantId, requiresDvAccess);
        sendEvent(entry, eventType, data, eventId);
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
