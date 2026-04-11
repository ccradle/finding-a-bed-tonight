package org.fabt.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-side DTO for {@code GET /api/v1/admin/escalation-policy/{eventType}}
 * and the response of the corresponding PATCH.
 *
 * <p>Mirrors {@link org.fabt.notification.domain.EscalationPolicy} with one
 * deliberate difference: the {@code thresholds} field is the
 * {@link EscalationPolicyThresholdDto} list, not the domain record. This
 * keeps the wire contract stable independently of any future domain-record
 * field reordering.</p>
 */
public record EscalationPolicyDto(
        UUID id,
        UUID tenantId,           // null = caller's tenant has no custom policy → platform default
        String eventType,
        int version,
        List<EscalationPolicyThresholdDto> thresholds,
        Instant createdAt,
        UUID createdBy           // null on platform-default seed
) {}
