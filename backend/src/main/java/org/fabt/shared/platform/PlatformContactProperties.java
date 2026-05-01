package org.fabt.shared.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the platform-level public contact email surfaced via
 * {@code GET /api/v1/public/contact-info} and the static-site footer
 * placeholder hydration in {@code /contact.js}. Bound from
 * {@code fabt.platform.*} properties (sourced from
 * {@code FABT_PLATFORM_CONTACT_EMAIL} env var on prod via
 * {@code ~/fabt-secrets/.env.prod}).
 *
 * <p>Empty {@link #contactEmail()} signals "not configured" — the contact-info
 * endpoint returns the empty value and the static-site fallback (D6 in
 * design.md) replaces the placeholder with a GH-issues link. Same anti-leak
 * posture as {@code feedback_no_ip_in_repo}: the literal address never
 * enters git.
 */
@ConfigurationProperties(prefix = "fabt.platform")
public record PlatformContactProperties(String contactEmail) {

    public PlatformContactProperties {
        if (contactEmail == null) {
            contactEmail = "";
        }
    }
}
