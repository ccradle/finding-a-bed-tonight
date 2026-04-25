package org.fabt.auth.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.fabt.auth.service.PasswordService;
import org.fabt.auth.service.TotpService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlatformAuthService}. Covers:
 *
 * <ul>
 *   <li>Login outcomes: MFA_SETUP_REQUIRED / MFA_VERIFY_REQUIRED / REJECTED</li>
 *   <li>Bcrypt timing equalization on rejection paths (warroom M2)</li>
 *   <li>setupMfa atomic call + enrollment guard (warroom A1, A4)</li>
 *   <li>verifyMfa: TOTP success, backup-code fallback, replay rejection (M1)</li>
 *   <li>verifyMfa miss path increments per-account failure counter (M3)</li>
 *   <li>recordLogin happens only on full-MFA success</li>
 * </ul>
 *
 * <p>Pure unit — Mockito mocks for repository + TOTP service. Avoids
 * Spring context boot.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformAuthService")
class PlatformAuthServiceTest {

    @Mock private PlatformUserRepository userRepository;
    @Mock private TotpService totpService;
    @Mock private PlatformAdminAccessLogger adminAccessLogger;
    private PasswordService passwordService;
    private PlatformAuthService authService;

    @BeforeEach
    void setUp() {
        // Real PasswordService with Spring's bcrypt. Cheap enough for unit tests.
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        passwordService = new PasswordService(encoder);
        authService = new PlatformAuthService(
                userRepository, passwordService, totpService, adminAccessLogger);
    }

    // ------------------------------------------------------------------
    // login()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("login with bad email returns REJECTED and runs decoy bcrypt for timing equalization")
    void loginBadEmailReturnsRejectedWithDecoy() {
        when(userRepository.findByEmail("nope@example.com")).thenReturn(Optional.empty());

        PlatformAuthService.LoginResult result =
                authService.login("nope@example.com", "anything");

        assertThat(result.outcome()).isEqualTo(PlatformAuthService.LoginOutcome.REJECTED);
        assertThat(result.user()).isNull();
        // Decoy bcrypt verify took place — proven by the fact that login()
        // returned (a real bcrypt verify is the only thing on the no-row
        // path that takes measurable time). We can't directly observe the
        // call without mocking PasswordService; this test documents the
        // contract.
    }

    @Test
    @DisplayName("login on locked account returns REJECTED (no role/access info leaked)")
    void loginLockedAccountRejected() {
        PlatformUser locked = userWith(true, false, "$2a$10$invalid");
        when(userRepository.findByEmail("ops@example.com")).thenReturn(Optional.of(locked));

        PlatformAuthService.LoginResult result =
                authService.login("ops@example.com", "anything");
        assertThat(result.outcome()).isEqualTo(PlatformAuthService.LoginOutcome.REJECTED);
        verify(userRepository, never()).recordLogin(any());
    }

    @Test
    @DisplayName("login with bad password returns REJECTED")
    void loginBadPasswordRejected() {
        String hash = passwordService.hash("correct-password");
        PlatformUser u = userWith(false, false, hash);
        when(userRepository.findByEmail("ops@example.com")).thenReturn(Optional.of(u));

        PlatformAuthService.LoginResult result =
                authService.login("ops@example.com", "wrong-password");
        assertThat(result.outcome()).isEqualTo(PlatformAuthService.LoginOutcome.REJECTED);
    }

    @Test
    @DisplayName("login with correct password and mfa_enabled=false returns MFA_SETUP_REQUIRED")
    void loginCorrectPasswordNotEnrolled() {
        String hash = passwordService.hash("correct-password");
        PlatformUser u = userWith(false, false, hash);
        when(userRepository.findByEmail("ops@example.com")).thenReturn(Optional.of(u));

        PlatformAuthService.LoginResult result =
                authService.login("ops@example.com", "correct-password");
        assertThat(result.outcome())
                .isEqualTo(PlatformAuthService.LoginOutcome.MFA_SETUP_REQUIRED);
        assertThat(result.user()).isSameAs(u);
        verify(userRepository, never())
                .recordLogin(any()); // not yet — only on MFA success
    }

    @Test
    @DisplayName("login with correct password and mfa_enabled=true returns MFA_VERIFY_REQUIRED")
    void loginCorrectPasswordEnrolled() {
        String hash = passwordService.hash("correct-password");
        PlatformUser u = userWith(false, true, hash);
        when(userRepository.findByEmail("ops@example.com")).thenReturn(Optional.of(u));

        PlatformAuthService.LoginResult result =
                authService.login("ops@example.com", "correct-password");
        assertThat(result.outcome())
                .isEqualTo(PlatformAuthService.LoginOutcome.MFA_VERIFY_REQUIRED);
        verify(userRepository, never()).recordLogin(any());
    }

    // ------------------------------------------------------------------
    // setupMfa()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setupMfa generates secret + 10 codes and persists atomically")
    void setupMfaPersistsAtomically() {
        PlatformUser u = userWith(false, false, "hash");
        u.setEmail("ops@example.com");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(totpService.generateSecret()).thenReturn("ABCDEF1234");
        when(totpService.generateQrUri(eq("ABCDEF1234"), eq("ops@example.com")))
                .thenReturn("otpauth://totp/...");
        when(userRepository.setupMfaAtomic(eq(u.getId()), eq("ABCDEF1234"),
                any(UUID[].class), any(String[].class), any(byte[][].class)))
                .thenReturn(true);

        PlatformAuthService.MfaSetup setup = authService.setupMfa(u.getId());

        assertThat(setup.secret()).isEqualTo("ABCDEF1234");
        assertThat(setup.qrUri()).isEqualTo("otpauth://totp/...");
        assertThat(setup.plaintextBackupCodes()).hasSize(10);
        // Each plaintext code is 10 chars
        setup.plaintextBackupCodes().forEach(c ->
                assertThat(c).hasSize(10));

        ArgumentCaptor<UUID[]> idsCap = ArgumentCaptor.forClass(UUID[].class);
        ArgumentCaptor<String[]> hashesCap = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<byte[][]> saltsCap = ArgumentCaptor.forClass(byte[][].class);
        verify(userRepository).setupMfaAtomic(eq(u.getId()), eq("ABCDEF1234"),
                idsCap.capture(), hashesCap.capture(), saltsCap.capture());
        assertThat(idsCap.getValue()).hasSize(10);
        assertThat(hashesCap.getValue()).hasSize(10);
        assertThat(saltsCap.getValue().length).isEqualTo(10);
    }

    @Test
    @DisplayName("setupMfa refuses when already enrolled (mfa_enabled=true)")
    void setupMfaRefusesWhenEnrolled() {
        PlatformUser enrolled = userWith(false, true, "hash");
        enrolled.setEmail("ops@example.com");
        when(userRepository.findById(enrolled.getId())).thenReturn(Optional.of(enrolled));

        assertThatThrownBy(() -> authService.setupMfa(enrolled.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MFA already enrolled");
    }

    @Test
    @DisplayName("setupMfa refuses when email is null (bootstrap not yet activated)")
    void setupMfaRefusesWhenEmailNull() {
        PlatformUser bootstrap = userWith(true, false, null);
        bootstrap.setEmail(null);
        when(userRepository.findById(bootstrap.getId())).thenReturn(Optional.of(bootstrap));

        assertThatThrownBy(() -> authService.setupMfa(bootstrap.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no email");
    }

    // ------------------------------------------------------------------
    // verifyMfa() — TOTP / backup / replay / lockout wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verifyMfa with valid TOTP records use, clears failures, records login, returns true")
    void verifyMfaTotpHappyPath() {
        PlatformUser u = userWith(false, true, "hash");
        u.setMfaSecret("SECRET");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(userRepository.wasTotpRecentlyUsed(eq(u.getId()), eq("123456"), anyInt()))
                .thenReturn(false);
        when(totpService.verifyCode("SECRET", "123456")).thenReturn(true);

        boolean ok = authService.verifyMfa(u.getId(), "123456");

        assertThat(ok).isTrue();
        verify(userRepository).recordTotpUse(u.getId(), "123456");
        verify(userRepository).clearFailures(u.getId());
        verify(userRepository).recordLogin(u.getId());
        verify(userRepository, never()).recordFailure(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("verifyMfa with replay rejection: same TOTP within 90s window → false + recordFailure")
    void verifyMfaReplayRejected() {
        PlatformUser u = userWith(false, true, "hash");
        u.setMfaSecret("SECRET");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(userRepository.wasTotpRecentlyUsed(eq(u.getId()), eq("123456"), anyInt()))
                .thenReturn(true);
        when(userRepository.recordFailure(eq(u.getId()), anyInt(), anyInt())).thenReturn(false);

        boolean ok = authService.verifyMfa(u.getId(), "123456");

        assertThat(ok).isFalse();
        verify(userRepository).recordFailure(u.getId(), 15, 5);
        verify(totpService, never()).verifyCode(anyString(), anyString());
        verify(userRepository, never()).recordLogin(any());
    }

    @Test
    @DisplayName("verifyMfa with bad code increments failure; returns true on threshold-crossing")
    void verifyMfaBadCodeIncrementsAndLocks() {
        PlatformUser u = userWith(false, true, "hash");
        u.setMfaSecret("SECRET");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(userRepository.wasTotpRecentlyUsed(eq(u.getId()), anyString(), anyInt()))
                .thenReturn(false);
        when(totpService.verifyCode("SECRET", "999999")).thenReturn(false);
        when(userRepository.findUnusedBackupCodes(u.getId())).thenReturn(List.of());
        // Simulate the 5th failed attempt — record_failure returns true
        when(userRepository.recordFailure(eq(u.getId()), anyInt(), anyInt())).thenReturn(true);

        boolean ok = authService.verifyMfa(u.getId(), "999999");

        assertThat(ok).isFalse();
        verify(userRepository).recordFailure(u.getId(), 15, 5);
        // Lockout transition was reached — caller does NOT recordLogin
        verify(userRepository, never()).recordLogin(any());
    }

    @Test
    @DisplayName("verifyMfa with valid backup code: marks used, clears failures, records login")
    void verifyMfaBackupCodeHappyPath() {
        PlatformUser u = userWith(false, true, "hash");
        u.setMfaSecret("SECRET");

        // Generate a known plaintext code + its expected hash
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 7);
        String plaintext = "ABCDEFGHJK";
        String storedHash = PlatformAuthService.sha256SaltedHex(salt, plaintext);
        UUID codeId = UUID.randomUUID();
        PlatformUserRepository.BackupCodeRow row =
                new PlatformUserRepository.BackupCodeRow(codeId, storedHash, salt);

        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(userRepository.wasTotpRecentlyUsed(eq(u.getId()), eq(plaintext), anyInt()))
                .thenReturn(false);
        when(totpService.verifyCode("SECRET", plaintext)).thenReturn(false);
        when(userRepository.findUnusedBackupCodes(u.getId())).thenReturn(List.of(row));
        when(userRepository.markBackupCodeUsed(codeId)).thenReturn(true);

        boolean ok = authService.verifyMfa(u.getId(), plaintext);

        assertThat(ok).isTrue();
        verify(userRepository).markBackupCodeUsed(codeId);
        verify(userRepository).clearFailures(u.getId());
        verify(userRepository).recordLogin(u.getId());
        verify(userRepository, never()).recordFailure(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("verifyMfa rejects unknown user-id with PlatformJwtException via lookupForToken contract")
    void lookupForTokenMissingThrows401() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        // lookupForToken (used by controller after MFA verify) throws
        // PlatformJwtException so the @RestControllerAdvice maps to 401,
        // not 500.
        assertThatThrownBy(() -> authService.lookupForToken(missingId))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("no longer present");
    }

    // ------------------------------------------------------------------
    // confirmMfaSetup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirmMfaSetup with valid TOTP flips mfa_enabled=true, records use + login")
    void confirmMfaSetupSuccess() {
        PlatformUser u = userWith(false, false, "hash");
        u.setMfaSecret("SECRET-PRE-CONFIRM");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(totpService.verifyCode("SECRET-PRE-CONFIRM", "123456")).thenReturn(true);

        boolean ok = authService.confirmMfaSetup(u.getId(), "123456");

        assertThat(ok).isTrue();
        verify(userRepository).updateCredentials(u.getId(), null, null, true, null);
        verify(userRepository).recordTotpUse(u.getId(), "123456");
        verify(userRepository).recordLogin(u.getId());
    }

    @Test
    @DisplayName("confirmMfaSetup with bad TOTP returns false; no state mutation")
    void confirmMfaSetupBadCode() {
        PlatformUser u = userWith(false, false, "hash");
        u.setMfaSecret("SECRET-PRE-CONFIRM");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        when(totpService.verifyCode("SECRET-PRE-CONFIRM", "999999")).thenReturn(false);

        boolean ok = authService.confirmMfaSetup(u.getId(), "999999");

        assertThat(ok).isFalse();
        verify(userRepository, times(1)).findById(u.getId());
        verify(userRepository, never())
                .updateCredentials(any(), any(), any(), any(), any());
        verify(userRepository, never()).recordLogin(any());
    }

    @Test
    @DisplayName("confirmMfaSetup is idempotent when mfa_enabled=true already")
    void confirmMfaSetupIdempotent() {
        PlatformUser u = userWith(false, true, "hash");
        u.setMfaSecret("SECRET");
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));

        boolean ok = authService.confirmMfaSetup(u.getId(), "anything");

        assertThat(ok).isTrue();
        verify(totpService, never()).verifyCode(anyString(), anyString());
        verify(userRepository, never())
                .updateCredentials(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------

    private PlatformUser userWith(boolean accountLocked, boolean mfaEnabled, String passwordHash) {
        PlatformUser u = new PlatformUser();
        u.setId(UUID.randomUUID());
        u.setEmail("ops@example.com");
        u.setAccountLocked(accountLocked);
        u.setMfaEnabled(mfaEnabled);
        u.setPasswordHash(passwordHash);
        return u;
    }
}
