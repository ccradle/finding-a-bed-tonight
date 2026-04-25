package org.fabt.auth.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.fabt.auth.service.PasswordService;
import org.fabt.auth.service.TotpService;

/**
 * Orchestrates platform_user authentication: password verify, forced
 * MFA-on-first-login, TOTP/backup-code MFA verify on subsequent logins,
 * and per-account MFA failure tracking with auto-lockout at 5 fails / 15 min
 * (warroom-hardened).
 *
 * <p>Per design Decision 4 (forced MFA-on-first-login):
 * <ol>
 *   <li>{@link #login(String, String)} — password check; returns one of three
 *       outcomes: {@code MFA_SETUP_REQUIRED} (operator never enrolled),
 *       {@code MFA_VERIFY_REQUIRED} (operator has TOTP, must verify), or
 *       {@code REJECTED} (bad creds, locked, anonymized). Rejected paths
 *       run a decoy bcrypt verify so the timing of "bad email" /
 *       "account locked" matches "bad password" — closes the enumeration
 *       oracle (warroom M2).</li>
 *   <li>{@link #setupMfa(UUID)} — issues TOTP secret + 10 backup codes via
 *       a single SECURITY DEFINER call ({@code platform_user_setup_mfa})
 *       so a partial failure can never leave a secret without codes
 *       (warroom A1). Refuses if the user is already enrolled
 *       (warroom A4).</li>
 *   <li>{@link #confirmMfaSetup(UUID, String)} — verifies first TOTP code,
 *       flips {@code mfa_enabled = true}, records the TOTP code as used
 *       to seed replay-protection.</li>
 *   <li>{@link #verifyMfa(UUID, String)} — verifies TOTP-or-backup-code on
 *       subsequent logins. <b>Replay-protected</b> (warroom M1) — a TOTP
 *       code accepted within the last 90 seconds is rejected on second
 *       presentation. On miss, increments the per-account failure counter
 *       via {@code platform_user_record_failure}; transition-to-locked is
 *       WARN-logged (warroom J3).</li>
 * </ol>
 *
 * <p>Per design Decision 12, backup codes are stored as SHA-256(salt || code)
 * — NOT bcrypt. Backup codes are random short strings, used at most once;
 * bcrypt's slow-compare adds latency during recovery without defending
 * against any attack the entropy doesn't already cover.
 *
 * <p>Per spec line 99-104, per-account lockout fires only on
 * {@code /login/mfa-verify} failures (the MFA challenge phase). Per-account
 * password-attempt protection is delegated to the per-IP rate limit on
 * {@code /login} (G-4.2 task 3.8).
 */
@Service
public class PlatformAuthService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthService.class);

    private static final int BACKUP_CODE_COUNT = 10;
    private static final int BACKUP_CODE_LENGTH = 10;
    private static final String BACKUP_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int BACKUP_CODE_SALT_BYTES = 16;

    /** 5 fails within 15 minutes locks the account (Decision 5). */
    static final int LOCKOUT_WINDOW_MIN = 15;
    static final int LOCKOUT_THRESHOLD = 5;

    /**
     * TOTP replay-protection window. RFC 6238 30-sec step + ±1 step drift =
     * 89-second acceptance window; 90s rejection window covers the entire
     * acceptance surface for the most-recently-used code.
     */
    static final int TOTP_REPLAY_WINDOW_SECONDS = 90;

    private final PlatformUserRepository userRepository;
    private final PasswordService passwordService;
    private final TotpService totpService;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Decoy bcrypt hash used to equalize timing on the "bad email" /
     * "account locked" rejection paths (warroom M2). The decoy is generated
     * at construction time over a random secret no caller will ever know;
     * a {@code passwordService.matches(presentedPassword, decoyHash)} call
     * costs the same as a real verify, so the wall-clock timing of all
     * REJECTED outcomes is indistinguishable.
     */
    private final String decoyPasswordHash;

    public PlatformAuthService(PlatformUserRepository userRepository,
                               PasswordService passwordService,
                               TotpService totpService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.totpService = totpService;
        byte[] decoySeed = new byte[32];
        new SecureRandom().nextBytes(decoySeed);
        this.decoyPasswordHash = passwordService.hash(
                HexFormat.of().formatHex(decoySeed));
    }

    /**
     * Step 1 of every platform login. Looks up the user by email, password-
     * verifies, and decides whether to issue an MFA-setup or MFA-verify
     * scoped token (caller composes the actual JWT via
     * {@link PlatformJwtService}).
     *
     * <p>The {@link LoginOutcome#REJECTED} path is deliberately
     * indistinguishable across all failure modes — bad email, bad password,
     * locked account, anonymized row — to deny enumeration attacks. Timing
     * is equalized via a decoy bcrypt verify on the no-real-hash paths.
     *
     * <p>Note: this method does NOT increment a per-account failure
     * counter. Per-account lockout is MFA-specific (Decision 5); password
     * brute-force is throttled by the per-IP rate limit on the {@code /login}
     * endpoint (G-4.2 task 3.8).
     */
    public LoginResult login(String email, String password) {
        Optional<PlatformUser> opt = userRepository.findByEmail(email);
        if (opt.isEmpty()) {
            passwordService.matches(password, decoyPasswordHash);
            return LoginResult.rejected();
        }
        PlatformUser user = opt.get();
        if (!user.isLoginAllowed()) {
            passwordService.matches(password, decoyPasswordHash);
            return LoginResult.rejected();
        }
        if (!passwordService.matches(password, user.getPasswordHash())) {
            return LoginResult.rejected();
        }
        if (user.isMfaEnabled()) {
            return LoginResult.mfaVerifyRequired(user);
        }
        return LoginResult.mfaSetupRequired(user);
    }

    /**
     * Generates a fresh TOTP secret + 10 backup codes for an operator who
     * has presented a valid {@code scope=mfa-setup} token. Persists the
     * plaintext TOTP secret + the 10 SHA-256(salt || code) hashes in a
     * single SECURITY DEFINER call.
     *
     * @throws IllegalStateException if the operator is already enrolled
     *     (defense against stale-token replay) or has no email.
     */
    public MfaSetup setupMfa(UUID userId) {
        PlatformUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_user not found: " + userId));
        if (user.getEmail() == null) {
            throw new IllegalStateException(
                    "platform_user has no email — bootstrap activation incomplete");
        }
        if (user.isMfaEnabled()) {
            throw new IllegalStateException(
                    "MFA already enrolled for this user — refusing replay of mfa-setup token");
        }

        String secret = totpService.generateSecret();
        String qrUri = totpService.generateQrUri(secret, user.getEmail());

        List<String> plaintextCodes = new ArrayList<>(BACKUP_CODE_COUNT);
        UUID[] ids = new UUID[BACKUP_CODE_COUNT];
        String[] hashes = new String[BACKUP_CODE_COUNT];
        byte[][] salts = new byte[BACKUP_CODE_COUNT][];
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateBackupCode();
            byte[] salt = new byte[BACKUP_CODE_SALT_BYTES];
            secureRandom.nextBytes(salt);
            plaintextCodes.add(code);
            ids[i] = UUID.randomUUID();
            hashes[i] = sha256SaltedHex(salt, code);
            salts[i] = salt;
        }

        boolean inserted = userRepository.setupMfaAtomic(userId, secret, ids, hashes, salts);
        if (!inserted) {
            // Race: another concurrent setup just enrolled, OR the row was
            // anonymized. Refuse without disclosing which.
            throw new IllegalStateException(
                    "MFA setup refused — user already enrolled or row missing");
        }

        log.info("Platform operator MFA setup initiated userId={} — TOTP enrollment pending confirm",
                userId);
        return new MfaSetup(secret, qrUri, plaintextCodes);
    }

    /**
     * Confirms the first TOTP code after MFA setup. On success:
     * {@code mfa_enabled = true}; the next login goes through the
     * {@link #verifyMfa(UUID, String)} path. Records the TOTP code as
     * used for replay-protection (so a leaked first-confirm code can't be
     * presented again at /mfa-verify within 90s). Returns true on success,
     * false on an invalid TOTP code.
     */
    public boolean confirmMfaSetup(UUID userId, String totpCode) {
        PlatformUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_user not found: " + userId));
        if (user.isMfaEnabled()) {
            return true;
        }
        if (user.getMfaSecret() == null) {
            throw new IllegalStateException(
                    "Cannot confirm MFA setup before /mfa-setup has been called");
        }
        if (!totpService.verifyCode(user.getMfaSecret(), totpCode)) {
            return false;
        }
        userRepository.updateCredentials(userId, null, null, true, null);
        userRepository.recordTotpUse(userId, totpCode);
        userRepository.recordLogin(userId);
        log.info("Platform operator MFA setup confirmed userId={} — mfa_enabled=true", userId);
        return true;
    }

    /**
     * Verifies an MFA challenge on subsequent logins. Order:
     * <ol>
     *   <li><b>Replay check</b> — the most-recently-used TOTP within the
     *       last 90 seconds is rejected on re-presentation (warroom M1).
     *       This counts as a failure.</li>
     *   <li>TOTP — if the presented code matches the secret, success.</li>
     *   <li>Backup code — if any unused code matches, success +
     *       mark used.</li>
     *   <li>None matched — record failure; if 5/15-min threshold met,
     *       account is auto-locked + WARN-logged.</li>
     * </ol>
     *
     * @return true iff TOTP-or-backup-code verified
     */
    public boolean verifyMfa(UUID userId, String code) {
        PlatformUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_user not found: " + userId));
        if (!user.isMfaEnabled() || user.getMfaSecret() == null) {
            recordFailureAndMaybeLock(userId);
            return false;
        }
        if (userRepository.wasTotpRecentlyUsed(userId, code, TOTP_REPLAY_WINDOW_SECONDS)) {
            log.warn("Platform operator TOTP replay rejected userId={}", userId);
            recordFailureAndMaybeLock(userId);
            return false;
        }
        if (totpService.verifyCode(user.getMfaSecret(), code)) {
            userRepository.recordTotpUse(userId, code);
            userRepository.clearFailures(userId);
            userRepository.recordLogin(userId);
            return true;
        }
        if (verifyBackupCode(userId, code)) {
            userRepository.clearFailures(userId);
            userRepository.recordLogin(userId);
            return true;
        }
        recordFailureAndMaybeLock(userId);
        return false;
    }

    private void recordFailureAndMaybeLock(UUID userId) {
        boolean nowLocked = userRepository.recordFailure(
                userId, LOCKOUT_WINDOW_MIN, LOCKOUT_THRESHOLD);
        if (nowLocked) {
            log.warn("PLATFORM_USER_LOCKED_OUT userId={} threshold={} windowMin={}",
                    userId, LOCKOUT_THRESHOLD, LOCKOUT_WINDOW_MIN);
        }
    }

    private boolean verifyBackupCode(UUID userId, String presentedCode) {
        if (presentedCode == null || presentedCode.isBlank()) {
            return false;
        }
        String normalized = presentedCode.toUpperCase().trim();
        List<PlatformUserRepository.BackupCodeRow> rows =
                userRepository.findUnusedBackupCodes(userId);
        for (PlatformUserRepository.BackupCodeRow row : rows) {
            String candidateHash = sha256SaltedHex(row.codeSalt(), normalized);
            if (constantTimeEquals(candidateHash, row.codeHash())) {
                userRepository.markBackupCodeUsed(row.id());
                log.info("Platform operator MFA backup code consumed userId={} codeId={}",
                        userId, row.id());
                return true;
            }
        }
        return false;
    }

    private String generateBackupCode() {
        StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            sb.append(BACKUP_CODE_CHARS.charAt(secureRandom.nextInt(BACKUP_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    static String sha256SaltedHex(byte[] salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] digest = md.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Re-reads a {@link PlatformUser} for token issuance after an MFA flow
     * has succeeded. Throws {@link PlatformJwtException} (mapped to 401) if
     * the row vanished between the JWT issue and the verify step (the row
     * would have to be anonymized concurrently — an operational incident,
     * not an internal-error case so it must NOT 500).
     */
    public PlatformUser lookupForToken(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new PlatformJwtException(
                        "platform_user no longer present"));
    }

    public enum LoginOutcome {
        MFA_SETUP_REQUIRED,
        MFA_VERIFY_REQUIRED,
        REJECTED
    }

    public record LoginResult(LoginOutcome outcome, PlatformUser user) {
        public static LoginResult mfaSetupRequired(PlatformUser user) {
            return new LoginResult(LoginOutcome.MFA_SETUP_REQUIRED, user);
        }

        public static LoginResult mfaVerifyRequired(PlatformUser user) {
            return new LoginResult(LoginOutcome.MFA_VERIFY_REQUIRED, user);
        }

        public static LoginResult rejected() {
            return new LoginResult(LoginOutcome.REJECTED, null);
        }
    }

    public record MfaSetup(String secret, String qrUri, List<String> plaintextBackupCodes) {
    }
}
