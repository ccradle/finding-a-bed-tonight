package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Phase A3 D17: validation + key bytes now owned by {@link MasterKekProvider}.
     * This constructor reads the platform key from the provider; the dev-key
     * fallback / prod-fail-fast / wrong-length / dev-key-in-prod-rejection
     * logic that lived here previously is now in one place. {@code configured}
     * is always true post-A3 because the provider throws on any invalid input
     * before this constructor runs.
     */
    public SecretEncryptionService(MasterKekProvider masterKekProvider) {
        this.secretKey = masterKekProvider.getPlatformKey();
        this.configured = true;
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
