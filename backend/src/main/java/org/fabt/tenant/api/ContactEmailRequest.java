package org.fabt.tenant.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * PATCH body for {@code /api/v1/admin/tenants/{tenantId}/contact-email}
 * (info-email-contact OpenSpec change, task 3.2).
 *
 * <p>Validation posture mirrors {@code HoldDurationRequest} in style but
 * with permissive null/empty handling: {@code @Email} considers null and
 * empty string valid (Hibernate-Validator default), and {@code @Size(max=254)}
 * permits null. This is intentional — operators MUST be able to clear the
 * per-tenant override (revert to platform inheritance) by PATCH'ing an
 * empty string. The controller treats null and empty string identically as
 * "clear the override."
 *
 * <p><b>Why 254?</b> RFC 5321 4.5.3.1.3 caps the path of an SMTP forward path
 * (the {@code <local@domain>} part) at 254 octets. {@code @Email} validates
 * format (presence of {@code @}, well-formed local + domain parts); {@code @Size}
 * caps length. Both fire at the controller boundary as 400 Bad Request before
 * the request reaches the service layer.
 *
 * <p><b>DV-policy guard:</b> validation here does NOT enforce the
 * "non-empty forbidden when {@code tenant.config.dv_policy_enabled = true}"
 * rule — that lives in {@code ContactEmailController} where the tenant
 * lookup + structured-error emission live (info-email-contact task 3.3,
 * Q4=B operator decision 2026-05-01). Bean validation runs first; the
 * DV-policy guard runs after the value is already format-valid.
 */
public record ContactEmailRequest(
        @Email(message = "email must be a well-formed RFC 5322 address")
        @Size(max = 254, message = "email must be <= 254 characters")
        String email
) {
}
