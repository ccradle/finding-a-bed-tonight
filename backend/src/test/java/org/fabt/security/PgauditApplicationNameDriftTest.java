package org.fabt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Drift-safety guard for the co-located {@code application_name} +
 * {@code app.tenant_id} session variables set by
 * {@link org.fabt.shared.security.RlsDataSourceConfig#applyRlsContext}
 * (task 3.11 follow-up, v0.45.0).
 *
 * <p><b>Why this test is load-bearing.</b> pgaudit tags every logged
 * statement with {@code application_name}; RLS enforces {@code app.tenant_id}.
 * If those two values ever disagree, the audit log credibly names tenant-A
 * while the actual DML ran as tenant-B — a compliance-grade log that lies
 * about tenant identity, worse than no tag at all. Marcus's v0.45.0
 * warroom acceptance criterion: without this test the feature ships
 * un-vouched-for.
 *
 * <p>The existing {@link TenantIdPoolBleedTest} covers {@code app.tenant_id}
 * in isolation; this class specifically targets the invariant that the
 * two GUCs are set in the same statement block and cannot diverge.
 */
@DisplayName("pgaudit application_name + app.tenant_id drift-safety (task 3.11)")
class PgauditApplicationNameDriftTest extends BaseIntegrationTest {

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
        Tenant tenantB = authHelper.setupSecondaryTenant("pgaudit-drift-" + suffix);
        tenantBId = tenantB.getId();
    }

    @Test
    @DisplayName("application_name always matches app.tenant_id on every borrow")
    void sequentialAlternatingTenants_applicationNameMatchesTenantId() {
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            UUID expectedTenantId = (i % 2 == 0) ? tenantAId : tenantBId;

            TenantContext.runWithContext(expectedTenantId, false, () -> {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT current_setting('app.tenant_id', true) AS tenant_id, "
                                + "current_setting('application_name', true) AS app_name");
                String tenantId = (String) row.get("tenant_id");
                String appName = (String) row.get("app_name");

                assertThat(tenantId)
                        .as("Iteration %d: app.tenant_id bound to expected tenant", iteration)
                        .isEqualTo(expectedTenantId.toString());
                assertThat(appName)
                        .as("Iteration %d: application_name carries same tenant UUID as app.tenant_id", iteration)
                        .isEqualTo("fabt:tenant:" + expectedTenantId);
            });
        }
    }

    @Test
    @DisplayName("Null tenant context: application_name = 'fabt:tenant:none'")
    void nullTenantContext_applicationNameIsNone() {
        TenantContext.runWithContext(tenantAId, false, () -> {
            String warmup = jdbcTemplate.queryForObject(
                    "SELECT current_setting('application_name', true)", String.class);
            assertThat(warmup).isEqualTo("fabt:tenant:" + tenantAId);
        });

        String afterNull = jdbcTemplate.queryForObject(
                "SELECT current_setting('application_name', true)", String.class);
        assertThat(afterNull)
                .as("After TenantContext scope exits, application_name must reset to "
                        + "'fabt:tenant:none' — never carry the previous tenant's UUID")
                .isEqualTo("fabt:tenant:none");
    }

    @Test
    @DisplayName("Transaction-scoped app.tenant_id override: application_name keeps session tenant (known skew)")
    void transactionScopedOverride_applicationNameKeepsSessionTenant() {
        // Seven service-layer call sites (AccessCodeService, AuditEventPersister,
        // HmisPushService, KidRegistryService, PasswordResetTokenPersister,
        // TenantKeyRotationService, V74__reencrypt) override app.tenant_id
        // transaction-scoped with is_local=true to do system-scoped work under
        // a specific tenant's RLS. application_name is session-scoped, so it
        // keeps the borrow-time value. Pgaudit logs the session tenant; RLS
        // enforces the transaction tenant. For current callers all run from
        // SYSTEM_TENANT_ID context where session tenant is 'none', so the
        // divergence is documented-and-safe rather than a correctness bug.
        // This test pins that contract — if a caller starts overriding
        // app.tenant_id under a real TenantContext, this test still passes
        // (correctly documenting the skew) and the caller's own tests should
        // catch any resulting audit-log lie.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runWithContext(tenantAId, false, () ->
                tx.executeWithoutResult(status -> {
                    // Inside a real transaction so is_local=true persists to later
                    // queries on this same connection. Mirrors the @Transactional
                    // boundary that AccessCodeService et al. operate under.
                    Map<String, Object> before = jdbcTemplate.queryForMap(
                            "SELECT current_setting('app.tenant_id', true) AS tid, "
                                    + "current_setting('application_name', true) AS app");
                    assertThat(before.get("tid")).isEqualTo(tenantAId.toString());
                    assertThat(before.get("app")).isEqualTo("fabt:tenant:" + tenantAId);

                    jdbcTemplate.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)",
                            String.class, tenantBId.toString());

                    Map<String, Object> after = jdbcTemplate.queryForMap(
                            "SELECT current_setting('app.tenant_id', true) AS tid, "
                                    + "current_setting('application_name', true) AS app");

                    assertThat(after.get("tid"))
                            .as("transaction-scoped override: app.tenant_id now reads the is_local=true value")
                            .isEqualTo(tenantBId.toString());
                    assertThat(after.get("app"))
                            .as("application_name is session-scoped — still tagged with the borrow-time (A) tenant. "
                                    + "This is the documented is_local=true drift; see RlsDataSourceConfig javadoc.")
                            .isEqualTo("fabt:tenant:" + tenantAId);
                }));
    }

    @Test
    @DisplayName("Concurrent virtual threads: per-checkout pair stays consistent")
    void concurrentVirtualThreads_noDriftBetweenTenantIdAndApplicationName() throws Exception {
        int threadsPerTenant = 50;
        int iterationsPerThread = 20;

        List<UUID> tenants = List.of(tenantAId, tenantBId);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tenants.size() * threadsPerTenant);
        AtomicInteger driftCount = new AtomicInteger();
        AtomicInteger totalChecks = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID tenantId : tenants) {
                for (int t = 0; t < threadsPerTenant; t++) {
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < iterationsPerThread; i++) {
                                TenantContext.runWithContext(tenantId, false, () -> {
                                    Map<String, Object> row = jdbcTemplate.queryForMap(
                                            "SELECT current_setting('app.tenant_id', true) AS tid, "
                                                    + "current_setting('application_name', true) AS app");
                                    String boundTid = (String) row.get("tid");
                                    String boundApp = (String) row.get("app");
                                    totalChecks.incrementAndGet();
                                    String expectedApp = "fabt:tenant:" + tenantId;
                                    if (!tenantId.toString().equals(boundTid)
                                            || !expectedApp.equals(boundApp)) {
                                        driftCount.incrementAndGet();
                                    }
                                });
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            startGate.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("Concurrent virtual-thread barrier completed within 60s")
                    .isTrue();
        }

        assertThat(totalChecks.get())
                .isEqualTo(tenants.size() * threadsPerTenant * iterationsPerThread);
        assertThat(driftCount.get())
                .as("Under concurrent load, application_name and app.tenant_id must stay "
                        + "co-located — any drift means pgaudit would log the wrong tenant "
                        + "for that query. Drift count MUST be zero.")
                .isZero();
    }
}
