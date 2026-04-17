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
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        this.masterKekProvider = masterKekProvider;
        this.keyDerivationService = keyDerivationService;
        this.kidRegistryService = kidRegistryService;
        this.secretKey = masterKekProvider.getPlatformKey();
        this.configured = true;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.eventPublisher = eventPublisherProvider.getIfAvailable();
    }

    // ------------------------------------------------------------------
    // Phase A3 typed per-tenant API
    // ------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} for storage under the per-tenant DEK
     * derived for {@code (tenantId, purpose)}. Wraps the result in a v1
     * {@link EncryptionEnvelope} carrying the kid registered for the
     * tenant's active generation.
     *
     * <p>Lazy bootstrap: the first encrypt for a tenant creates its
     * {@code tenant_key_material} active generation + kid via
     * {@link KidRegistryService#findOrCreateActiveKid(java.util.UUID)}.
     * Subsequent encrypts reuse the same kid until rotation.
     */
    public String encryptForTenant(java.util.UUID tenantId, KeyPurpose purpose, String plaintext) {
        java.util.UUID kid = kidRegistryService.findOrCreateActiveKid(tenantId);
        SecretKey dek = purpose.deriveKey(keyDerivationService, tenantId);
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptionEnvelope(kid, iv, ciphertextWithTag).encode();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret for tenant " + tenantId, e);
        }
    }

    /**
     * Decrypts a stored ciphertext for the given tenant + purpose. Routes
     * to the v0 legacy path if the bytes don't carry the v1 magic.
     *
     * @throws CrossTenantCiphertextException if the kid resolves to a
     *         different tenant than {@code tenantId}
     */
    public String decryptForTenant(java.util.UUID tenantId, KeyPurpose purpose, String stored) {
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
        KidRegistryService.KidResolution resolved;
        try {
            resolved = kidRegistryService.resolveKid(envelope.kid());
        } catch (java.util.NoSuchElementException unknownKid) {
            // C-A3-1: an unregistered kid is presented as cross-tenant rejection
            // (same 403 response, same audit action) so an attacker cannot
            // distinguish "kid doesn't exist anywhere" (would be 404) from
            // "kid exists for a different tenant" (403). The sentinel
            // actualTenantId in the audit JSONB discriminates the two cases
            // for incident responders without leaking to the client.
            throw new CrossTenantCiphertextException(
                    envelope.kid(), tenantId, UNKNOWN_KID_SENTINEL_TENANT);
        }
        if (!resolved.tenantId().equals(tenantId)) {
            throw new CrossTenantCiphertextException(envelope.kid(), tenantId, resolved.tenantId());
        }
        SecretKey dek = purpose.deriveKey(keyDerivationService, tenantId);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, envelope.iv()));
            byte[] plaintext = cipher.doFinal(envelope.ciphertextWithTag());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt v1 ciphertext for tenant " + tenantId, e);
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
