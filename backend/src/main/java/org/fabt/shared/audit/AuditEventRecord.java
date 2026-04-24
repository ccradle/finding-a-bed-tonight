package org.fabt.shared.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Audit event published via Spring ApplicationEventPublisher.
 * Lives in shared.audit so any module can publish without creating
 * a dependency on the auth module.
 *
 * <p><b>Non-null action contract (Slice G-1 §8.0.16).</b> {@code action}
 * must be non-null. A null action would be hashed by the Phase G-1 chain
 * writer as {@code "action":null} in canonical_json — a forensically
 * meaningless audit row with no event type. Enforced via compact
 * constructor. Casey Drummond warroom concern from G-0 review.</p>
 *
 * <p>Other fields remain nullable:
 * <ul>
 *   <li>{@code actorUserId} — null for system-driven writes (batch jobs,
 *       scheduled tasks)</li>
 *   <li>{@code targetUserId} — null when the audit subject is not a user
 *       (e.g. SHELTER_DEACTIVATED has a shelter id in details)</li>
 *   <li>{@code details} — null when the action type needs no payload
 *       (e.g. TOTP_ENABLED)</li>
 *   <li>{@code ipAddress} — null for non-request-bound contexts</li>
 * </ul>
 */
public record AuditEventRecord(UUID actorUserId, UUID targetUserId, AuditEventType action,
                                Object details, String ipAddress) {

    public AuditEventRecord {
        Objects.requireNonNull(action,
                "AuditEventRecord.action must be non-null (Slice G-1 §8.0.16 contract). "
                + "Every audit row must have a defined event type — null-action rows are "
                + "hashed as \"action\":null by Phase G-1 chain writer, producing "
                + "forensically meaningless evidence. Use AuditEventType.TEST_PROBE for "
                + "tests that need a synthetic action.");
    }
}
