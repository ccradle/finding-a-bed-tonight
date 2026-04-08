package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean configured;

    // The dev-start.sh key — committed to the public repo, MUST NOT be used in production
    private static final String DEV_KEY = "s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA=";

    public SecretEncryptionService(
            @Value("${fabt.encryption-key:${fabt.totp.encryption-key:}}") String base64Key,
            org.springframework.core.env.Environment environment) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("FABT_ENCRYPTION_KEY not configured — features requiring encryption (TOTP, webhook HMAC) will be unavailable");
            this.secretKey = null;
            this.configured = false;
        } else {
            // Reject the dev key in production — it's committed to the public repo
            java.util.Set<String> profiles = java.util.Set.of(environment.getActiveProfiles());
            if (DEV_KEY.equals(base64Key) && profiles.contains("prod")) {
                throw new IllegalStateException(
                        "FABT_ENCRYPTION_KEY must not use the dev-start.sh key in production. "
                        + "Generate a unique key with: openssl rand -base64 32");
            }
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "FABT_ENCRYPTION_KEY must be 32 bytes (256 bits). Got: " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.configured = true;
        }
    }

    /**
     * Encrypt a plaintext secret for database storage.
     * Returns base64-encoded ciphertext (IV + encrypted data + GCM auth tag).
     */
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
     */
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
}
