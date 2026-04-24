package org.fabt.shared.security;

import java.util.UUID;

/**
 * Raised by {@link SecretEncryptionService#decryptForTenant} when the
 * caller's declared {@link KeyPurpose} disagrees with the purpose recorded
 * on the resolved {@code tenant_dek} row.
 *
 * <p>Before F-6.0 Option A, a purpose mismatch surfaced as a generic
 * {@code RuntimeException} from the AES-GCM tag-verification failure
 * (wrong DEK → wrong tag). That path still exists as defense-in-depth,
 * but an explicit check upstream makes the contract testable and gives
 * incident responders a clear signal to distinguish "wrong purpose"
 * (programming bug, benign) from "forged ciphertext" (attack, hostile).
 *
 * <p>Extends {@link SecurityException} so Spring's
 * {@code @ControllerAdvice} treats it as a security boundary event, not
 * a generic 500. The wire-level response is still a generic decrypt
 * failure so clients cannot distinguish "wrong purpose" from other
 * decrypt errors — the discrimination is for logs + audits only.
 */
public class PurposeMismatchException extends SecurityException {

    private final UUID kid;
    private final UUID tenantId;
    private final KeyPurpose expectedPurpose;
    private final KeyPurpose actualPurpose;

    public PurposeMismatchException(UUID kid, UUID tenantId,
                                     KeyPurpose expectedPurpose, KeyPurpose actualPurpose) {
        super("Purpose mismatch for kid " + kid + ": caller expected "
            + expectedPurpose + " but tenant_dek records " + actualPurpose);
        this.kid = kid;
        this.tenantId = tenantId;
        this.expectedPurpose = expectedPurpose;
        this.actualPurpose = actualPurpose;
    }

    public UUID getKid() { return kid; }
    public UUID getTenantId() { return tenantId; }
    public KeyPurpose getExpectedPurpose() { return expectedPurpose; }
    public KeyPurpose getActualPurpose() { return actualPurpose; }
}
