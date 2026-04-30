package org.fabt.shared.security;

import java.util.UUID;
import java.util.function.Function;

import javax.crypto.SecretKey;

/**
 * Closed set of canonical purposes for HKDF-derived per-tenant keys.
 * Each enum value carries the {@link KeyDerivationService} accessor it
 * resolves to, so {@link SecretEncryptionService}'s typed encryption
 * methods can dispatch without a {@code switch} statement.
 *
 * <p>Adding a new purpose: add an enum constant + the matching
 * {@code deriveXxxKey} method on {@link KeyDerivationService}. The
 * compiler then forces every callsite to pick from the closed set —
 * the unbounded-{@code String} purpose footgun (warroom Q3) cannot
 * recur.
 */
public enum KeyPurpose {

    JWT_SIGN(KeyDerivationService::deriveJwtSigningKey),
    TOTP(KeyDerivationService::deriveTotpKey),
    WEBHOOK_SECRET(KeyDerivationService::deriveWebhookSecretKey),
    OAUTH2_CLIENT_SECRET(KeyDerivationService::deriveOauth2ClientSecretKey),
    HMIS_API_KEY(KeyDerivationService::deriveHmisApiKey),
    // RESERVATION_PII (transitional-reentry-support task 2.3a, V93):
    // random-DEK only — encrypted via SecretEncryptionService.encryptForTenant
    // which routes through TenantDekService.getOrCreateActiveDek, NOT through
    // the deprecated HKDF-derive path. Resolver throws because deriveKey() on
    // this constant is a "should never reach here" code path; if it fires,
    // a v0.42-deprecated callsite is still using the legacy backward-compat
    // shim with a purpose that was never registered for HKDF.
    RESERVATION_PII((service, tenantId) -> {
        throw new UnsupportedOperationException(
            "RESERVATION_PII uses random-DEK via TenantDekService; HKDF derive path is not supported");
    });

    private final java.util.function.BiFunction<KeyDerivationService, UUID, SecretKey> resolver;

    KeyPurpose(java.util.function.BiFunction<KeyDerivationService, UUID, SecretKey> resolver) {
        this.resolver = resolver;
    }

    /** Derives the per-tenant DEK for this purpose via the supplied service. */
    public SecretKey deriveKey(KeyDerivationService service, UUID tenantId) {
        return resolver.apply(service, tenantId);
    }
}
