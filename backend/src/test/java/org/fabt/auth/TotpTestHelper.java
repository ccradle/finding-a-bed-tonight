package org.fabt.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;

/**
 * Test helper for generating valid TOTP codes from known secrets.
 * Used in integration tests to programmatically complete 2FA verification.
 */
public class TotpTestHelper {

    private static final DefaultCodeGenerator codeGenerator =
            new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);

    /**
     * Generate a known test secret (deterministic for testing).
     */
    public static String generateTestSecret() {
        return new DefaultSecretGenerator(32).generate();
    }

    /**
     * Generate a valid TOTP code for the current time step.
     */
    public static String generateCode(String base32Secret) {
        try {
            long timeStep = System.currentTimeMillis() / 1000 / 30;
            return codeGenerator.generate(base32Secret, timeStep);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TOTP code", e);
        }
    }
}
