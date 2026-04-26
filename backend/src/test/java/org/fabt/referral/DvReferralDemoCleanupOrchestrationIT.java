package org.fabt.referral;

import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orchestration IT for {@code DvReferralDemoCleanupJobConfig} (G-4.5
 * §6.10) — covers the bits {@link DvReferralDemoCleanupTest} can NOT
 * exercise because the {@code @Profile("demo")} bean does not load in
 * the default profile:
 *
 * <ul>
 *   <li>Tasklet's slug-prefix filter (only {@code dev-*} tenants).</li>
 *   <li>Tasklet's per-tenant audit publish via
 *       {@code ApplicationEventPublisher.publishEvent} — verifies the
 *       sync {@code @EventListener} path actually inserts an
 *       {@code audit_events} row (Riley/Marcus warroom H5).</li>
 *   <li>End-to-end Job execution via Spring Batch's {@code JobLauncher}
 *       (rather than calling the service method directly).</li>
 * </ul>
 *
 * <p>The {@code demo} profile also activates {@code DemoGuardFilter},
 * which blocks most mutations from public traffic — but TestRestTemplate
 * connects from localhost so the filter's localhost-bypass exempts the
 * test setup mutations.</p>
 */
@ActiveProfiles({"lite", "test", "demo"})
class DvReferralDemoCleanupOrchestrationIT extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private ApplicationContext context;

    private UUID dvShelterId;
    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;

    // No @AfterEach cleanup of audit_events: V70 REVOKES DELETE on the
    // table for fabt_app (audit chain integrity). Each test instead uses a
    // unique tenant slug (shortUuid()) so its DV_REFERRAL_DEMO_CLEANUP rows
    // are isolated by tenant_id from any prior test method's rows.

    @Test
    @DisplayName("Tasklet runs against demo (dev-*) tenant: deletes stale, emits audit row")
    void demo_slug_triggers_cleanup_and_audit() throws Exception {
        UUID tenantId = setUpDemoTenant("dev-cleanup-it-" + shortUuid());
        String tokenId = createReferralForShelter(dvShelterId);

        // Backdate the referral past the 48h cutoff hardcoded in the tasklet.
        TenantContext.runWithContext(tenantId, true, () -> {
            int updated = jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '49 hours' "
                            + "WHERE id = ?::uuid",
                    tokenId);
            assertThat(updated).isEqualTo(1);
        });

        runDemoCleanupJob();

        // Stale row gone.
        TenantContext.runWithContext(tenantId, true, () -> {
            Integer surviving = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid",
                    Integer.class, tokenId);
            assertThat(surviving)
                    .as("Stale PENDING row in dev-* tenant must be deleted by the tasklet.")
                    .isEqualTo(0);
        });

        // Audit row landed under the affected tenant's chain — proves the
        // synchronous @EventListener path actually wrote to audit_events.
        TenantContext.runWithContext(tenantId, true, () -> {
            List<Integer> deletedCounts = jdbcTemplate.queryForList(
                    "SELECT (details->>'deleted_count')::int FROM audit_events "
                            + "WHERE action = 'DV_REFERRAL_DEMO_CLEANUP' AND tenant_id = ?",
                    Integer.class, tenantId);
            assertThat(deletedCounts)
                    .as("Exactly one DV_REFERRAL_DEMO_CLEANUP row should land under the demo tenant.")
                    .hasSize(1)
                    .first()
                    .isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Tasklet skips non-demo (prod-*) tenants: row preserved, no audit emitted")
    void non_demo_slug_skipped_by_filter() throws Exception {
        UUID tenantId = setUpDemoTenant("prod-cleanup-it-" + shortUuid());
        String tokenId = createReferralForShelter(dvShelterId);

        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '49 hours' "
                            + "WHERE id = ?::uuid",
                    tokenId);
        });

        runDemoCleanupJob();

        // Row survives.
        TenantContext.runWithContext(tenantId, true, () -> {
            Integer surviving = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid",
                    Integer.class, tokenId);
            assertThat(surviving)
                    .as("PENDING row in prod-* tenant must NOT be touched (slug filter rejects).")
                    .isEqualTo(1);
        });

        // No audit row for this tenant.
        TenantContext.runWithContext(tenantId, true, () -> {
            Integer auditCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_events "
                            + "WHERE action = 'DV_REFERRAL_DEMO_CLEANUP' AND tenant_id = ?",
                    Integer.class, tenantId);
            assertThat(auditCount)
                    .as("Non-demo tenant must produce zero DV_REFERRAL_DEMO_CLEANUP audit rows.")
                    .isEqualTo(0);
        });
    }

    // =========================================================================
    // Setup + helpers
    // =========================================================================

    private UUID setUpDemoTenant(String slug) {
        authHelper.setupTestTenant(slug);
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "orch-dvadmin@test.fabt.org", "Orch DV Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "orch-dvoutreach@test.fabt.org", "Orch DV Outreach",
                new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        var dvCoord = authHelper.setupUserWithDvAccess(
                "orch-dvcoord@test.fabt.org", "Orch DV Coord", new String[]{"COORDINATOR"});

        UUID tenantId = authHelper.getTestTenantId();
        TenantContext.runWithContext(tenantId, true, () -> {
            dvShelterId = createDvShelter();
            patchAvailability(dvShelterId);
            restTemplate.exchange(
                    "/api/v1/shelters/" + dvShelterId + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\": \"" + dvCoord.getId() + "\"}", adminHeaders),
                    String.class);
        });
        return tenantId;
    }

    private void runDemoCleanupJob() throws Exception {
        var job = (org.springframework.batch.core.job.Job)
                context.getBean("dvReferralDemoCleanupJob");
        JobParameters params = new JobParametersBuilder()
                .addLong("invocationAt", System.nanoTime())
                .toJobParameters();
        // BatchJobScheduler wraps scheduled invocations in
        // TenantContext.runWithContext(null, dvAccess=true) before launching
        // the job — that's why the production tasklet's dvAccess guard
        // succeeds. JobLauncher.run() called directly does NOT do this
        // wrapping, so the test must provide the same context bind.
        var execution = TenantContext.<org.springframework.batch.core.job.JobExecution, Exception>callWithContext(
                null, true,
                () -> jobLauncher.run(job, params));
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    }

    private String createReferralForShelter(UUID shelterId) {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 1,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "STANDARD",
                  "specialNeeds": "",
                  "callbackNumber": "919-555-0100"
                }
                """, shelterId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return extractField(resp.getBody(), "id");
    }

    private UUID createDvShelter() {
        String body = String.format("""
                {
                  "name": "Orchestration DV Shelter %s",
                  "addressStreet": "100 Shelter Way",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-0001",
                  "dvShelter": true,
                  "constraints": { "populationTypesServed": ["DV_SURVIVOR"] },
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """, shortUuid());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private void patchAvailability(UUID shelterId) {
        String body = """
                {"populationType": "DV_SURVIVOR", "bedsTotal": 10, "bedsOccupied": 3, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, adminHeaders), String.class);
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) {
            throw new AssertionError("Field '" + field + "' not found in response: " + json);
        }
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
