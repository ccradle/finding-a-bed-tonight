package org.fabt.notification.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/admin/escalation-policy/{eventType}}.
 *
 * <p>The eventType comes from the path; the body carries only the new
 * thresholds list. Bean Validation enforces the structural shape; the
 * service-layer {@code validateThresholds(...)} enforces the semantic
 * rules (monotonic, valid roles, unique ids, etc.) per Riley Cho's
 * "validation must live at the service layer" note.</p>
 *
 * <p>{@code @Size(max = 50)} caps the threshold count: a comically large
 * list would OOM the JSONB serializer and cache (Marcus Webb #3, war
 * room round 3). 50 is well above any plausible operational shape — the
 * seeded platform default has 4.</p>
 */
public record UpdateEscalationPolicyRequest(
        @NotNull @Size(min = 1, max = 50) @Valid List<EscalationPolicyThresholdDto> thresholds
) {}
