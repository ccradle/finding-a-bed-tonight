package org.fabt.auth.platform.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-self metadata returned by {@code GET /api/v1/auth/platform/me}.
 *
 * <p>Populated from the V90 {@code platform_user_get_me} SECURITY DEFINER
 * function in a single round-trip. Drives the F11 platform-operator
 * dashboard header (operator email, last login, MFA enrollment date,
 * backup-codes-remaining urgency badge).
 *
 * <p>Deliberately omitted: {@code passwordHash}, {@code mfaSecret}, any
 * backup-code material, last-login IP. The dashboard renders only what
 * the operator needs to see; secrets and IP addresses are out of scope
 * for v0.54 (last-login IP is F37, deferred to v0.55).
 */
public record PlatformOperatorMeDto(
        UUID id,
        String email,
        boolean mfaEnabled,
        Instant lastLoginAt,
        Instant mfaEnrolledAt,
        int backupCodesRemaining) {
}
