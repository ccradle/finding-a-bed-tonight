package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.audit.AuditEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for secrets at rest (TOTP shared secrets, webhook
 * callback secrets, and any future functional secrets that must be recoverable).
 *
 * NOT for passwords — passwords use bcrypt (one-way hash, never need recovery).
 * This is for secrets the server must USE later (HMAC keys, TOTP verification).
 *
 * Key management: encryption key sourced from FABT_ENCRYPTION_KEY env var
 * (32 bytes, base64-encoded). The key MUST NOT be stored in the database.
 * Falls back to FABT_TOTP_ENCRYPTION_KEY for backward compatibility.
 */
@Service
public class SecretEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(SecretEncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * Sentinel UUID written to {@link CrossTenantCiphertextException#getActualTenantId()}
     * when the kid is not registered in {@code kid_to_tenant_key}. Per C-A3-1:
     * unknown-kid and wrong-tenant-kid both surface as 403 to clients (no
     * tenant-existence side-channel). The sentinel discriminates the two
     * paths in audit logs without leaking to callers.
     */
    static final java.util.UUID UNKNOWN_KID_SENTINEL_TENANT =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * C-A5-N4 (Marcus warroom): throttle for {@code CIPHERTEXT_V0_DECRYPT} audit
     * events — ≤ once per (tenant, purpose) per 60s window. Post-V74 a spike of
     * v0 fallbacks is either a stuck-row repair case (1 or 2 rows) or an attack
     * (adversary replacing v1 ciphertexts with v0 forgeries). Either way, we
     * want the signal, not a flood.
     */
    private static final Duration V0_DECRYPT_AUDIT_THROTTLE = Duration.ofSeconds(60);

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean configured;
    private final MasterKekProvider masterKekProvider;
    private final KeyDerivationService keyDerivationService;
    private final KidRegistryService kidRegistryService;
    private final TenantDekService tenantDekService;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Last-emit timestamps (epoch ms) keyed by {@code tenantId + ":" + purpose}.
     * Concurrent map because v0-fallback can fire on any request thread. Entries
     * are never evicted (bounded by tenant count × purpose count — tiny at
     * pilot scale; the max-size cap on a real Caffeine cache would be premature
     * optimization here).
     */
    private final Map<String, Long> v0DecryptLastAuditMs = new ConcurrentHashMap<>();

    /**
     * Phase A3 D17: validation + key bytes now owned by {@link MasterKekProvider}.
     * Phase A3 D18+D21: per-tenant typed encrypt/decrypt added; legacy v0 path
     * preserved via {@link CiphertextV0Decoder}. Phase A5 C-A5-N4: v0-fallback
     * observability — every v0 decrypt post-V74 increments a counter + emits a
     * throttled audit event.
     *
     * <p>{@link ObjectProvider} wrappers keep the service usable by unit tests
     * that instantiate without a full Spring context (no MeterRegistry, no
     * event publisher). In a Spring context both beans are injected normally.
     */
    public SecretEncryptionService(
            MasterKekProvider masterKekProvider,
            KeyDerivationService keyDerivationService,
            KidRegistryService kidRegistryService,
            TenantDekService tenantDekService,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        this.masterKekProvider = masterKekProvider;
        this.keyDerivationService = keyDerivationService;
        this.kidRegistryService = kidRegistryService;
        this.tenantDekService = tenantDekService;
        this.secretKey = masterKekProvider.getPlatformKey();
        this.configured = true;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.eventPublisher = eventPublisherProvider.getIfAvailable();
    }

    // ------------------------------------------------------------------
    // Phase A3 typed per-tenant API
    // ------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} for storage under the per-tenant random
     * DEK owned by {@link TenantDekService} for {@code (tenantId, purpose)}.
     * Wraps the result in a v1 {@link EncryptionEnvelope} carrying the kid
     * minted when the DEK was first generated.
     *
     * <p><b>F-6.0 refactor (2026-04-24):</b> was HKDF-derived DEK +
     * {@link KidRegistryService} kid. Now random DEK +
     * {@link TenantDekService} kid, enabling real crypto-shred: deleting
     * the {@code tenant_dek} row destroys the only copy of the DEK, which
     * is what the TDD anchor {@code CryptoShredGapIntegrationTest} tests.
     *
     * <p>Lazy bootstrap: the first encrypt for a {@code (tenant, purpose)}
     * pair creates the {@code tenant_dek} row + generates the random DEK
     * + wraps under the HKDF-derived wrapping key. Subsequent encrypts
     * reuse the same kid + DEK until rotation.
     */
    public String encryptForTenant(java.util.UUID tenantId, KeyPurpose purpose, String plaintext) {
        if (purpose == KeyPurpose.JWT_SIGN) {
            // JWT signing keys have their own lifecycle (kid_to_tenant_key +
            // tenant_key_material, Phase A3). They are deliberately NOT in
            // tenant_dek's purpose CHECK constraint — data encryption and
            // JWT signing are separate concerns with different rotation,
            // audit, and shred semantics. A caller reaching here with
            // JWT_SIGN is a programming bug, not an attack surface.
            throw new IllegalArgumentException(
                    "KeyPurpose.JWT_SIGN is not a data-encryption purpose; "
                    + "JWT signing uses JwtService + kid_to_tenant_key, not "
                    + "SecretEncryptionService + tenant_dek.");
        }
        TenantDekService.ActiveDek active = tenantDekService.getOrCreateActiveDek(tenantId, purpose);
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, active.dek(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptionEnvelope(active.kid(), iv, ciphertextWithTag).encode();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret for tenant " + tenantId, e);
        }
    }

    /**
     * Decrypts a stored ciphertext for the given tenant + purpose. Routes
     * to the v0 legacy path if the bytes don't carry the v1 magic.
     *
     * <p><b>F-6.0 refactor (2026-04-24):</b> resolves kids via
     * {@link TenantDekService} ({@code tenant_dek} table) instead of
     * {@link KidRegistryService} ({@code kid_to_tenant_key} table — now
     * JWT-only). Adds an explicit {@link PurposeMismatchException} for
     * when the caller's {@link KeyPurpose} disagrees with the row's
     * recorded purpose, instead of relying on the GCM auth-tag failure.
     *
     * @throws CrossTenantCiphertextException if the kid resolves to a
     *         different tenant than {@code tenantId}, or if the kid is
     *         unregistered (sentinel {@link #UNKNOWN_KID_SENTINEL_TENANT}
     *         per C-A3-1).
     * @throws PurposeMismatchException if the kid resolves to the caller's
     *         tenant but to a different {@link KeyPurpose}.
     */
    public String decryptForTenant(java.util.UUID tenantId, KeyPurpose purpose, String stored) {
        if (purpose == KeyPurpose.JWT_SIGN) {
            // Symmetric with encryptForTenant's JWT_SIGN guard. Warroom pass-3
            // Marcus blocker: without this guard, a caller passing JWT_SIGN
            // with a v1 envelope whose kid happens to live in kid_to_tenant_key
            // would fall through to decryptV1LegacyHkdf, which calls
            // purpose.deriveKey(keyDerivationService, tenantId) → routes to
            // deriveJwtSigningKey (the HMAC signing key). Feeding an HMAC key
            // into AES-GCM is a cross-primitive key-reuse vector; the GCM tag
            // fails but timing/error channels could signal JWT-kid existence.
            // Reject at the boundary.
            throw new IllegalArgumentException(
                    "KeyPurpose.JWT_SIGN is not a data-encryption purpose; "
                    + "JWT signing uses JwtService + kid_to_tenant_key, not "
                    + "SecretEncryptionService + tenant_dek.");
        }
        byte[] decoded = java.util.Base64.getDecoder().decode(stored);
        if (!EncryptionEnvelope.isV1Envelope(decoded)) {
            // v0 fallback — Phase 0 single-platform-key envelope. Design D42
            // keeps this path alive indefinitely as defense-in-depth. Phase A5
            // C-A5-N4: emit a counter + throttled audit event so any v0
            // decrypt that fires post-V74 is visible (catches both the
            // V74-skipped-row repair case and a hostile v0-forgery downgrade
            // attack).
            recordV0DecryptFallback(tenantId, purpose);
            return CiphertextV0Decoder.decrypt(masterKekProvider.getPlatformKey(), stored);
        }
        EncryptionEnvelope envelope = EncryptionEnvelope.decode(stored);
        TenantDekService.ResolvedDek resolved;
        try {
            resolved = tenantDekService.resolveDek(envelope.kid());
        } catch (java.util.NoSuchElementException notInTenantDek) {
            // Transitional fallback: the kid might belong to a legacy v1
            // envelope produced by V74 (the old HKDF-DEK path), which
            // registered kids in kid_to_tenant_key rather than tenant_dek.
            // V83 re-encrypts these legacy envelopes under fresh tenant_dek
            // DEKs, after which this fallback path becomes unreachable
            // for conforming deployments. Kept as defense-in-depth + smooth
            // deployment during the V82→V83 window where a pod may see a
            // mix of old and new formats. Phase L (task 7.8h ArchUnit
            // removal) retires this path entirely.
            return decryptV1LegacyHkdf(tenantId, purpose, envelope);
        }
        if (!resolved.tenantId().equals(tenantId)) {
            throw new CrossTenantCiphertextException(envelope.kid(), tenantId, resolved.tenantId());
        }
        if (resolved.purpose() != purpose) {
            // F-6.0 explicit purpose-mismatch per warroom pass-2 Alex minor
            // fix. Upstream of the GCM tag check so the type of the failure
            // (programming bug vs forged ciphertext) is captured. The GCM
            // tag would also catch this (different DEK under Option A → wrong
            // tag) — we check explicitly so callers can distinguish.
            throw new PurposeMismatchException(
                    envelope.kid(), tenantId, purpose, resolved.purpose());
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, resolved.dek(), new GCMParameterSpec(GCM_TAG_LENGTH, envelope.iv()));
            byte[] plaintext = cipher.doFinal(envelope.ciphertextWithTag());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt v1 ciphertext for tenant " + tenantId, e);
        }
    }

    /**
     * Transitional legacy decrypt for v1 envelopes whose kid lives in
     * {@code kid_to_tenant_key} rather than {@code tenant_dek} — i.e.,
     * envelopes produced by V74 under the HKDF-DEK scheme before the F-6.0
     * refactor. Falls through to {@link CrossTenantCiphertextException}
     * with the unknown-kid sentinel per C-A3-1 if the kid is unknown to
     * both registries.
     *
     * <p>Post-V83, this path is exercised only for stale/cached envelopes
     * held outside the DB (request-time replay, bug-reported historical
     * values). The counter {@code secret.decrypt.v1.legacy_hkdf} emitted
     * below tracks residual calls; an extended flatline is the signal to
     * retire this method in Phase L.
     */
    private String decryptV1LegacyHkdf(java.util.UUID tenantId, KeyPurpose purpose,
                                        EncryptionEnvelope envelope) {
        KidRegistryService.KidResolution legacy;
        try {
            legacy = kidRegistryService.resolveKid(envelope.kid());
        } catch (java.util.NoSuchElementException unknown) {
            throw new CrossTenantCiphertextException(
                    envelope.kid(), tenantId, UNKNOWN_KID_SENTINEL_TENANT);
        }
        if (!legacy.tenantId().equals(tenantId)) {
            throw new CrossTenantCiphertextException(
                    envelope.kid(), tenantId, legacy.tenantId());
        }
        if (meterRegistry != null) {
            Counter.builder("secret.decrypt.v1.legacy_hkdf")
                    .description("v1 envelopes decrypted via the legacy HKDF-DEK path "
                            + "(kid in kid_to_tenant_key, not tenant_dek). "
                            + "Post-V83 should trend to zero; extended flatline = "
                            + "safe to remove the compat shim in Phase L.")
                    .tag("purpose", purpose.name())
                    .register(meterRegistry)
                    .increment();
        }
        @SuppressWarnings("removal")
        SecretKey dek = purpose.deriveKey(keyDerivationService, tenantId);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, envelope.iv()));
            byte[] plaintext = cipher.doFinal(envelope.ciphertextWithTag());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to decrypt v1 ciphertext (legacy HKDF path) for tenant " + tenantId, e);
        }
    }

    // ------------------------------------------------------------------
     // Legacy v0 API (deprecated)
     // ------------------------------------------------------------------

    /**
     * Encrypt a plaintext secret for database storage.
     * Returns base64-encoded ciphertext (IV + encrypted data + GCM auth tag).
     *
     * @deprecated since v0.42 — use {@link #encryptForTenant} with a
     * {@link KeyPurpose}. This un-typed path writes v0-envelope ciphertexts
     * that are NOT bound to a tenant. Retained only for V74 migration
     * internal use (via {@code db.migration.V74}) and the deprecated
     * test-fixtures path. Scheduled for removal in Phase L under
     * ArchUnit Family F.
     */
    @Deprecated(since = "v0.42", forRemoval = true)
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypt a secret from database storage.
     * Input is base64-encoded (IV + encrypted data + GCM auth tag).
     *
     * @deprecated since v0.42 — use {@link #decryptForTenant}. This path
     * decrypts v0 ciphertext under the platform key with no tenant binding
     * and is retained only for V74 migration use + the deprecated
     * test-fixtures path. Scheduled for removal in Phase L. Application
     * code reaching this method bypasses per-tenant DEK isolation.
     */
    @Deprecated(since = "v0.42", forRemoval = true)
    public String decrypt(String encrypted) {
        requireKey();
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    /**
     * Whether the encryption key is configured. Features requiring encryption
     * should check this and degrade gracefully if false.
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Alias for isConfigured() — backward compatibility with TotpService.
     */
    public boolean isEncryptionConfigured() {
        return configured;
    }

    private void requireKey() {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Encryption key not configured. Set FABT_ENCRYPTION_KEY environment variable. "
                    + "Generate with: openssl rand -base64 32");
        }
    }

    /**
     * C-A5-N4: increments the v0-fallback counter and emits a throttled
     * audit event whenever a legacy v0 ciphertext is decrypted on the
     * runtime path. Post-V74 sweep, any v0 read is either a stuck-row
     * repair case or a downgrade-attack indicator; either way, we want
     * the signal.
     *
     * <p>Throttle is per-(tenant, purpose) at {@link #V0_DECRYPT_AUDIT_THROTTLE}.
     * Counter is always incremented regardless of throttle (so dashboards
     * see the true rate).
     */
    private void recordV0DecryptFallback(java.util.UUID tenantId, KeyPurpose purpose) {
        if (meterRegistry != null) {
            Counter.builder("fabt.security.v0_decrypt_fallback.count")
                    .tag("purpose", purpose.name())
                    .tag("tenant_id", tenantId == null ? "unknown" : tenantId.toString())
                    .register(meterRegistry)
                    .increment();
        }
        if (eventPublisher != null && shouldEmitV0DecryptAudit(tenantId, purpose)) {
            java.util.Map<String, Object> details = new java.util.HashMap<>();
            details.put("tenantId", tenantId == null ? "unknown" : tenantId.toString());
            details.put("purpose", purpose.name());
            details.put("note", "v0 fallback decrypt — expected only for stuck V74 rows or attack indicator");
            eventPublisher.publishEvent(
                    new AuditEventRecord(null, null, "CIPHERTEXT_V0_DECRYPT", details, null));
        }
    }

    /**
     * Returns true if the per-(tenant, purpose) throttle permits an audit
     * emission now. Updates the last-emit timestamp as a side effect.
     */
    private boolean shouldEmitV0DecryptAudit(java.util.UUID tenantId, KeyPurpose purpose) {
        String key = (tenantId == null ? "null" : tenantId.toString()) + ":" + purpose.name();
        long now = System.currentTimeMillis();
        long windowMs = V0_DECRYPT_AUDIT_THROTTLE.toMillis();
        Long last = v0DecryptLastAuditMs.get(key);
        if (last != null && (now - last) < windowMs) {
            return false;
        }
        v0DecryptLastAuditMs.put(key, now);
        return true;
    }
}
