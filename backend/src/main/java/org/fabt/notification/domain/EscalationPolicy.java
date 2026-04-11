package org.fabt.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-tenant escalation policy with append-only versioning.
 *
 * <p>Stored in the {@code escalation_policy} table (Flyway V40). Each PATCH from
 * the admin inserts a new row with version+1; existing rows are NEVER updated
 * or deleted. Each {@code referral_token} stores its {@code escalation_policy_id}
 * (FK to the policy version active at creation time) — frozen-at-creation
 * semantics, the audit-trail load-bearing piece (Casey Drummond's lens).</p>
 *
 * <p>The {@code thresholds} field is parsed from JSONB into a list of
 * {@link Threshold} records. Each threshold carries an ISO-8601 duration
 * (e.g. {@code PT1H}, {@code PT2H}, {@code PT3H30M}, {@code PT4H}) measured
 * from referral creation, a severity, and the list of recipient roles to
 * notify when that threshold is crossed.</p>
 *
 * <p><b>Why a record (not a Spring Data JDBC entity):</b> the JSONB column does
 * not map cleanly to Spring Data JDBC's auto-mapping for nested types. We
 * deserialize the JSONB column manually in the repository's {@code @Query}
 * methods using Jackson and construct the record explicitly. This is the same
 * pattern used by {@code Notification.payload} elsewhere in this module.</p>
 */
public record EscalationPolicy(
        UUID id,
        UUID tenantId,           // null = platform default
        String eventType,
        int version,
        List<Threshold> thresholds,
        Instant createdAt,
        UUID createdBy           // null on platform-default seed
) {

    /**
     * One escalation threshold within a policy. Each crossed threshold during
     * the batch job's check fires a notification of the given severity to the
     * given recipient roles.
     *
     * @param id         short label that becomes the notification type suffix
     *                   (e.g. {@code "1h"} → notification type {@code escalation.1h}).
     *                   Must be unique within a policy. The frontend
     *                   {@code NotificationBell} switches on the full type
     *                   string, so the seed values must remain stable across
     *                   versions: {@code 1h, 2h, 3_5h, 4h}. Custom policies
     *                   may add new ids; existing ids must not be renamed
     *                   without a frontend update.
     * @param at         duration since referral creation (ISO-8601, e.g. {@code PT2H})
     * @param severity   one of {@code INFO}, {@code ACTION_REQUIRED}, {@code CRITICAL}
     * @param recipients list of role enum values (e.g. {@code COORDINATOR}, {@code COC_ADMIN})
     */
    public record Threshold(String id, Duration at, String severity, List<String> recipients) {

        /** Notification type column value, e.g. {@code escalation.1h}. */
        public String notificationType() {
            return "escalation." + id;
        }
    }
}
