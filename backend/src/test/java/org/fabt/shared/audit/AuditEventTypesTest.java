package org.fabt.shared.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pin test for {@link AuditEventTypes} constants. Asserts that every audit
 * action constant exists with a stable, non-empty value. Adding a new constant
 * to {@link AuditEventTypes} should also add an assertion here.
 */
class AuditEventTypesTest {

    @Test
    void bed_holds_reconciled_constant_exists_and_is_stable() {
        assertThat(AuditEventTypes.BED_HOLDS_RECONCILED)
                .as("BED_HOLDS_RECONCILED audit action constant must be defined")
                .isNotNull()
                .isNotBlank()
                .isEqualTo("BED_HOLDS_RECONCILED");
    }
}
