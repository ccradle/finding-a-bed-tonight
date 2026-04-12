package org.fabt.notification.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Wire shape for one escalation threshold inside an
 * {@link EscalationPolicyDto} or {@link UpdateEscalationPolicyRequest}.
 *
 * <p>{@code at} is an ISO-8601 duration string (e.g. {@code "PT1H"},
 * {@code "PT2H"}, {@code "PT3H30M"}, {@code "PT4H"}). The service layer
 * parses it via {@code Duration.parse} and rejects malformed values with
 * a 400.</p>
 *
 * <p>Validation rules (severity whitelist, role whitelist, monotonic
 * ordering, unique id, non-empty recipients) are enforced in
 * {@code EscalationPolicyService.validateThresholds(...)} so direct
 * service callers cannot bypass them — see Riley Cho's persona note in
 * Session 2 T-9.</p>
 */
public record EscalationPolicyThresholdDto(
        @NotBlank @Size(max = 32) String id,
        @NotBlank String at,
        @NotBlank String severity,
        @NotNull @Size(min = 1) List<@NotBlank String> recipients
) {}
