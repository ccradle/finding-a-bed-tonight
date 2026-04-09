package org.fabt.shelter.api;

import java.util.UUID;

/**
 * Lightweight shelter projection: id + name only.
 * Used by GET /api/v1/users/{id}/shelters and the coordinator assignment UI.
 */
public record ShelterSummary(UUID id, String name) {}
