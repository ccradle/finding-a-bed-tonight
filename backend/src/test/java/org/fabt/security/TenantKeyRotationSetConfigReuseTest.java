package org.fabt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warroom W-B-FIXA-1 guard (v0.45 task #166).
 *
 * <p>{@link org.fabt.shared.security.TenantKeyRotationService#bumpJwtKeyGeneration}
 * runs under {@code @Transactional} and sets {@code app.tenant_id} via
 * {@code set_config(..., is_local=true)} so Phase B FORCE RLS on
 * {@code audit_events} accepts the audit INSERT done later in the same
 * transaction. The warroom flagged a theoretical leak: if
 * {@code is_local=false} were used instead, the session-scoped GUC
 * would survive the connection's return to the Hikari pool and poison
 * the next borrow.
 *
 * <p>The actual code correctly uses {@code is_local=true}, but the
 * defense-in-depth question is: even in the {@code is_local=true} case,
 * does a subsequent pool borrow under a DIFFERENT tenant see the right
 * tenant binding (not the rotation target)? {@code RlsDataSourceConfig}
 * re-applies {@code applyRlsContext} on every borrow, so the answer
 * should be yes — this test pins that.
 */
@DisplayName("W-B-FIXA-1 — TenantKeyRotationService is_local=true doesn't leak via pool reuse (task #166)")
class TenantKeyRotationSetConfigReuseTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID tenantAId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantAId = authHelper.getTestTenantId();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("wb-fixa-1-" + suffix);
        tenantBId = tenantB.getId();
    }

    @Test
    @DisplayName("set_config with is_local=true inside @Transactional does not leak to next pool borrow")
    void transactionScopedSetConfig_doesNotLeakToNextBorrow() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Mimic TenantKeyRotationService.bumpJwtKeyGeneration — under
        // TenantContext(tenantA), inside @Transactional, set_config
        // app.tenant_id = tenantB with is_local=true (this is what the
        // real service does when a platform admin rotates tenant B's key).
        TenantContext.runWithContext(tenantAId, false, () ->
                tx.executeWithoutResult(status -> {
                    String bound = jdbcTemplate.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)",
                            String.class, tenantBId.toString());
                    assertThat(bound)
                            .as("Inside the transaction, is_local=true binds tenantB as the effective tenant")
                            .isEqualTo(tenantBId.toString());
                }));

        // Borrow a fresh connection under tenantA. applyRlsContext
        // re-fires, which must set app.tenant_id to tenantA — NOT
        // leaving tenantB's rotation-scoped value behind.
        String afterUnderA = TenantContext.callWithContext(tenantAId, null, false, () ->
                jdbcTemplate.queryForObject(
                        "SELECT current_setting('app.tenant_id', true)", String.class));
        assertThat(afterUnderA)
                .as("Next borrow under tenantA must see tenantA — the rotation's is_local=true override must "
                        + "not survive transaction commit (proven by Hikari returning the connection + "
                        + "applyRlsContext re-applying the session-scoped tenantA binding on the new borrow)")
                .isEqualTo(tenantAId.toString());

        // And borrow under null context — must reset to empty, not carry
        // any stale value.
        String afterUnderNull = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.tenant_id', true)", String.class);
        assertThat(afterUnderNull)
                .as("Next borrow under null context must see empty — no bleed from the rotation transaction")
                .isEqualTo("");
    }

    @Test
    @DisplayName("100 alternating rotation-pattern iterations show zero leak")
    void alternatingRotationPattern_noLeakOver100Iterations() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        for (int i = 0; i < 100; i++) {
            UUID sessionTenant = (i % 2 == 0) ? tenantAId : tenantBId;
            UUID rotationTarget = (i % 2 == 0) ? tenantBId : tenantAId;
            final int iteration = i;

            // 1. Inside @Transactional, is_local=true override.
            TenantContext.runWithContext(sessionTenant, false, () ->
                    tx.executeWithoutResult(status ->
                            jdbcTemplate.queryForObject(
                                    "SELECT set_config('app.tenant_id', ?, true)",
                                    String.class, rotationTarget.toString())));

            // 2. Fresh borrow under session tenant — must NOT see rotation target.
            String actual = TenantContext.callWithContext(sessionTenant, null, false, () ->
                    jdbcTemplate.queryForObject(
                            "SELECT current_setting('app.tenant_id', true)", String.class));
            assertThat(actual)
                    .as("Iteration %d: borrow after rotation must see session tenant %s, not rotation target %s",
                            iteration, sessionTenant, rotationTarget)
                    .isEqualTo(sessionTenant.toString());
        }
    }
}
