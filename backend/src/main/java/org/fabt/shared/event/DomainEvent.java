package org.fabt.shared.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Self-describing domain event (REQ-MCP-5). Events carry enough context that
 * consumers (including future MCP agents) do not need a follow-up API call
 * to understand what happened.
 *
 * @param id            Unique event ID
 * @param type          Event type (e.g., "availability.updated", "surge.activated")
 * @param schemaVersion Semantic version of the event schema (e.g., "1.0.0")
 * @param tenantId      Tenant that owns this event
 * @param payload       Structured event data with entity names, previous values, etc.
 * @param timestamp     When the event occurred
 */
public record DomainEvent(
        UUID id,
        String type,
        String schemaVersion,
        UUID tenantId,
        Map<String, Object> payload,
        Instant timestamp
) {
    public DomainEvent(String type, UUID tenantId, Map<String, Object> payload) {
        this(UUID.randomUUID(), type, "1.0.0", tenantId, payload, Instant.now());
    }
}
