package org.fabt.auth.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;

import org.fabt.shared.security.KeyPurpose;
import org.springframework.stereotype.Service;

/**
 * TOTP two-factor authentication service.
 *
 * Handles secret generation, QR code URI generation, code verification
 * (with ±1 time step for clock drift per RFC 6238), and backup code
 * management. TOTP secrets are encrypted by org.fabt.shared.security.SecretEncryptionService
 * before storage — this service works with plaintext base32 secrets
 * only in memory, never persisted unencrypted.
 */
@Service
public class TotpService {

    private static final String ISSUER = "Finding A Bed Tonight";
    private static final int SECRET_LENGTH = 32; // 32 base32 chars = 160 bits
    private static final int RECOVERY_CODE_COUNT = 8;
    private static final int RECOVERY_CODE_LENGTH = 8;
    private static final String RECOVERY_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I confusion

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(SECRET_LENGTH);
    private final CodeVerifier codeVerifier;
    private final PasswordService passwordService;
    private final org.fabt.shared.security.SecretEncryptionService encryptionService;

    public TotpService(PasswordService passwordService, org.fabt.shared.security.SecretEncryptionService encryptionService) {
        this.passwordService = passwordService;
        this.encryptionService = encryptionService;

        // ±1 time step (discrepancy = 1) for clock drift tolerance (~89 seconds total window)
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.codeVerifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
        ((DefaultCodeVerifier) this.codeVerifier).setAllowedTimePeriodDiscrepancy(1);
    }

    /**
     * Generate a new TOTP secret (base32-encoded, plaintext).
     * The caller must encrypt before storing.
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Generate a QR code URI for authenticator app scanning.
     */
    public String generateQrUri(String secret, String userEmail) {
        QrData qrData = new QrData.Builder()
                .label(userEmail)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return qrData.getUri();
    }

    /**
     * Verify a TOTP code against a plaintext secret.
     * Accepts ±1 time step for clock drift.
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null || code.length() != 6) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code);
    }

    /**
     * Generate backup codes (plaintext). Returns the plaintext codes
     * for display to the user, plus bcrypt-hashed versions for storage.
     */
    public BackupCodes generateBackupCodes() {
        SecureRandom random = new SecureRandom();
        List<String> plaintext = new ArrayList<>();
        List<String> hashed = new ArrayList<>();

        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH);
            for (int j = 0; j < RECOVERY_CODE_LENGTH; j++) {
                code.append(RECOVERY_CODE_CHARS.charAt(random.nextInt(RECOVERY_CODE_CHARS.length())));
            }
            String plain = code.toString();
            plaintext.add(plain);
            hashed.add(passwordService.hash(plain));
        }

        return new BackupCodes(plaintext, hashed);
    }

    /**
     * Verify a backup code against the list of hashed codes.
     * Returns the index of the matching code (for marking as consumed), or -1 if no match.
     */
    public int verifyBackupCode(String code, List<String> hashedCodes) {
        if (code == null || hashedCodes == null) return -1;
        String normalized = code.toUpperCase().trim();
        for (int i = 0; i < hashedCodes.size(); i++) {
            if (hashedCodes.get(i) != null && passwordService.matches(normalized, hashedCodes.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Decrypt a stored TOTP secret for verification, scoped to the owning
     * tenant's DEK. Phase A5 D38: the controller must pass {@code tenantId}
     * explicitly because the MFA-verify flow runs before a SecurityContext
     * is bound — there is no {@code TenantContext} to implicit-scope against.
     * The controller has {@code user.getTenantId()} in hand; passing it here
     * is the right plumbing.
     *
     * <p>v0 ciphertexts (pre-V74) continue to decrypt via the defense-in-depth
     * v0 fallback path in {@code SecretEncryptionService.decryptForTenant} —
     * no caller-side fallback logic needed here.
     */
    public String decryptSecret(UUID tenantId, String encrypted) {
        return encryptionService.decryptForTenant(tenantId, KeyPurpose.TOTP, encrypted);
    }

    /**
     * Encrypt a TOTP secret for storage under the owning tenant's DEK.
     * Phase A5 D38: {@code tenantId} explicit for the same reason as
     * {@link #decryptSecret}.
     */
    public String encryptSecret(UUID tenantId, String plaintext) {
        return encryptionService.encryptForTenant(tenantId, KeyPurpose.TOTP, plaintext);
    }

    public boolean isEncryptionConfigured() {
        return encryptionService.isConfigured();
    }

    public record BackupCodes(List<String> plaintext, List<String> hashed) {}
}
