package org.fabt.testsupport;

import java.util.UUID;
import java.util.function.Supplier;

import org.fabt.shared.web.TenantContext;

/**
 * Test-side helper for querying RLS'd tables under an explicit tenant binding.
 *
 * <p><b>Why this exists.</b> Phase B (V67-V69) enables FORCE RLS on 7 regulated
 * tables with policies that filter by {@code fabt_current_tenant_id()}. If a
 * test calls {@code jdbcTemplate.queryForList(...)} on one of those tables
 * without first binding {@link TenantContext}, the pool-borrow callback in
 * {@code RlsDataSourceConfig} sets {@code app.tenant_id = ''} → the function
 * returns {@code NULL} → the policy evaluates to UNKNOWN → zero rows returned.
 * The test fails with "expected 1 but was 0" even though the row was written
 * correctly by production code.
 *
 * <p><b>The pattern.</b> Any test-side SELECT against a Phase-B-RLS'd table
 * (audit_events, hmis_audit_log, password_reset_token, one_time_access_code,
 * hmis_outbox, tenant_key_material, kid_to_tenant_key) must run inside a
 * {@link TenantContext#runWithContext} block bound to the tenant whose rows
 * it expects to see.
 *
 * <p>This helper reduces the wrap-then-query boilerplate to one call:
 *
 * <pre>{@code
 *   int count = WithTenantContext.readAs(tenantA, () ->
 *       jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events ...",
 *                                   Integer.class, tenantA));
 * }</pre>
 *
 * <p><b>Anti-pattern warning.</b> Do NOT "just wrap in the tenant that happens
 * to work" — that masks cross-tenant leak bugs. Pair every positive assertion
 * with a cross-tenant negative:
 *
 * <pre>{@code
 *   // Positive — expect tenant A to see the row
 *   int visibleToA = WithTenantContext.readAs(tenantA, () ->
 *       jdbcTemplate.queryForObject("SELECT COUNT(*) ...", Integer.class));
 *   assertEquals(1, visibleToA);
 *
 *   // Negative — expect tenant B to NOT see tenant A's row
 *   int visibleToB = WithTenantContext.readAs(tenantB, () ->
 *       jdbcTemplate.queryForObject("SELECT COUNT(*) ...", Integer.class));
 *   assertEquals(0, visibleToB);
 * }</pre>
 *
 * <p>The negative test is what catches "row was written under wrong tenant"
 * bugs — which a test that always wraps in the "expected" tenant would miss.
 *
 * <p>See {@code docs/testing/post-phase-b-rls-testing.md} for the full pattern.
 */
public final class WithTenantContext {

    private WithTenantContext() {}

    /** Runs {@code action} with {@link TenantContext} bound to {@code tenantId}. */
    public static void doAs(UUID tenantId, Runnable action) {
        TenantContext.runWithContext(tenantId, false, action);
    }

    /** Returns {@code supplier.get()} evaluated inside a {@link TenantContext} bound to {@code tenantId}. */
    public static <T> T readAs(UUID tenantId, Supplier<T> supplier) {
        return TenantContext.callWithContext(tenantId, false, () -> supplier.get());
    }

    /** Convenience for system-context operations (SYSTEM_TENANT_ID sentinel). */
    public static void doAsSystem(Runnable action) {
        doAs(TenantContext.SYSTEM_TENANT_ID, action);
    }

    /** Convenience read under the SYSTEM_TENANT_ID sentinel. */
    public static <T> T readAsSystem(Supplier<T> supplier) {
        return readAs(TenantContext.SYSTEM_TENANT_ID, supplier);
    }
}
