package org.fabt.security;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import java.time.Instant;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies DV shelter data never leaks to non-DV users under concurrent load.
 *
 * Addresses Marcus Webb AI persona (AppSec) finding 1.2 + REQ-ISO-6:
 * The highest-consequence failure is disclosure of a DV shelter's location
 * or existence to an unauthorized party — including an abuser.
 *
 * RLS enforces DV visibility at the database layer (app.dv_access session var).
 * RlsDataSourceConfig.applyRlsContext() resets this on every connection checkout.
 * This test verifies that reset works under concurrent virtual thread pressure
 * where connections are rapidly recycled between DV-authorized and non-DV users.
 *
 * Riley Cho: "What happens to the person in crisis if this test is missing?"
 * Answer: A DV shelter's existence is disclosed. A survivor could be found.
 */
class DvShelterConcurrentIsolationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantService tenantService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordService passwordService;
    @Autowired private JwtService jwtService;

    private String dvAdminToken;    // dvAccess=true — can see DV shelters
    private String outreachToken;   // dvAccess=false — must NOT see DV shelters
    private String dvShelterName;
    private UUID regularShelterId;
    private UUID dvShelterId;
    private String regularShelterName;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        // Use a UNIQUE tenant per test run to avoid cache interference from other test
        // classes. The bed search service caches availability data by tenant ID. If
        // we share the default "test-tenant", the cache may contain stale data from
        // earlier tests that doesn't include our newly created shelters.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = tenantService.findBySlug("dv-iso-" + suffix)
                .orElseGet(() -> tenantService.create("DV Isolation Tenant", "dv-iso-" + suffix));
        tenantId = tenant.getId();

        // Create users scoped to this unique tenant
        User dvAdmin = createUser(tenantId, "dv-admin-" + suffix + "@test.fabt.org",
                new String[]{"PLATFORM_ADMIN"}, true);
        dvAdminToken = jwtService.generateAccessToken(dvAdmin);

        User outreach = createUser(tenantId, "outreach-" + suffix + "@test.fabt.org",
                new String[]{"OUTREACH_WORKER"}, false);
        outreachToken = jwtService.generateAccessToken(outreach);

        // Create test shelters — need dvAccess=true context for DV shelter INSERT (RLS enforces)
        dvShelterName = "DV-Concurrent-Shelter-" + suffix;
        regularShelterName = "Regular-Concurrent-Shelter-" + suffix;

        TenantContext.runWithContext(tenantId, true, () -> {
            dvShelterId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                    dvShelterId, tenantId, dvShelterName);
            regularShelterId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) VALUES (?, ?, ?, false, NOW(), NOW())",
                    regularShelterId, tenantId, regularShelterName);
        });
    }

    private User createUser(UUID tenantId, String email, String[] roles, boolean dvAccess) {
        return userRepository.findByTenantIdAndEmail(tenantId, email)
                .orElseGet(() -> {
                    User user = new User();
                    user.setTenantId(tenantId);
                    user.setEmail(email);
                    user.setDisplayName("Test User " + email);
                    user.setPasswordHash(passwordService.hash("TestPassword123!"));
                    user.setRoles(roles);
                    user.setDvAccess(dvAccess);
                    user.setCreatedAt(Instant.now());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                });
    }

    @Test
    @DisplayName("Concurrent: DV shelter never appears in non-DV user responses (REQ-ISO-6)")
    void concurrentDvIsolation_dvShelterNeverLeaksToOutreachWorker() throws Exception {
        int requestsPerRole = 50;
        int totalRequests = requestsPerRole * 2;
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<String>> dvAdminResults = new ArrayList<>();
        List<Future<String>> outreachResults = new ArrayList<>();

        // Interleave DV-authorized and non-DV requests — forces connection pool
        // to recycle connections between users with different dvAccess levels
        for (int i = 0; i < requestsPerRole; i++) {
            dvAdminResults.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 5));
                return getShelters(dvAdminToken);
            }));
            outreachResults.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 5));
                return getShelters(outreachToken);
            }));
        }

        assertThat(readyLatch.await(10, TimeUnit.SECONDS))
                .as("All %d threads should be ready", totalRequests).isTrue();
        startLatch.countDown();

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // DV admin MUST see the DV shelter
        for (int i = 0; i < dvAdminResults.size(); i++) {
            String body = dvAdminResults.get(i).get();
            assertThat(body)
                    .as("DV admin response %d must contain DV shelter name", i)
                    .contains(dvShelterName);
            assertThat(body)
                    .as("DV admin response %d must contain regular shelter", i)
                    .contains(regularShelterName);
        }

        // Outreach worker MUST NOT see the DV shelter — not the name, not the ID
        for (int i = 0; i < outreachResults.size(); i++) {
            String body = outreachResults.get(i).get();
            assertThat(body)
                    .as("Outreach response %d must NOT contain DV shelter name", i)
                    .doesNotContain(dvShelterName);
            assertThat(body)
                    .as("Outreach response %d must NOT contain DV shelter ID", i)
                    .doesNotContain(dvShelterId.toString());
            assertThat(body)
                    .as("Outreach response %d must still contain regular shelter", i)
                    .contains(regularShelterName);
        }
    }

    @Test
    @DisplayName("Connection pool: dvAccess=true then dvAccess=false, 100 iterations — DV shelter never leaks (REQ-RLS-POOL-1 through REQ-RLS-POOL-3)")
    void connectionPoolDvAccessReset_100iterations() {
        // Rapidly alternate between DV-authorized and non-DV requests.
        // HikariCP pool size is small (5-10 in test) — connections WILL be reused.
        // RlsDataSourceConfig.applyRlsContext() must correctly reset app.dv_access
        // on every connection checkout, or DV shelter data leaks.
        for (int i = 0; i < 100; i++) {
            // Step 1: DV admin request — sees DV shelter (warms the connection with dvAccess=true)
            String dvBody = getShelters(dvAdminToken);
            assertThat(dvBody)
                    .as("Iteration %d: DV admin must see DV shelter", i)
                    .contains(dvShelterName);

            // Step 2: Outreach request immediately after — must NOT see DV shelter
            // If app.dv_access leaked from the previous connection, this fails
            String outreachBody = getShelters(outreachToken);
            assertThat(outreachBody)
                    .as("Iteration %d: Outreach must NOT see DV shelter name", i)
                    .doesNotContain(dvShelterName);
            assertThat(outreachBody)
                    .as("Iteration %d: Outreach must NOT see DV shelter ID", i)
                    .doesNotContain(dvShelterId.toString());
        }
    }

    @Test
    @DisplayName("Bed search endpoint: DV shelter never appears for non-DV user (REQ-ISO-6)")
    void bedSearchEndpoint_dvShelterNeverLeaksToOutreachWorker() {
        // Create availability snapshots so shelters appear in bed search results.
        // Bed search only returns shelters with snapshots.
        createAvailabilitySnapshot(dvAdminToken, dvShelterId);
        createAvailabilitySnapshot(dvAdminToken, regularShelterId);

        // Verify DV admin CAN see DV shelter in bed search (proves test data is valid)
        String dvBody = searchBeds(dvAdminToken);
        assertThat(dvBody)
                .as("DV admin bed search must contain DV shelter (proves test data valid)")
                .contains(dvShelterName);
        assertThat(dvBody)
                .as("DV admin bed search must contain regular shelter")
                .contains(regularShelterName);

        // Outreach worker must NOT see DV shelter in bed search
        for (int i = 0; i < 20; i++) {
            String body = searchBeds(outreachToken);
            assertThat(body)
                    .as("Bed search response %d must NOT contain DV shelter name", i)
                    .doesNotContain(dvShelterName);
            assertThat(body)
                    .as("Bed search response %d must NOT contain DV shelter ID", i)
                    .doesNotContain(dvShelterId.toString());
            assertThat(body)
                    .as("Bed search response %d must contain regular shelter (proves query works)", i)
                    .contains(regularShelterName);
        }

        // DV admin SHOULD see the DV shelter in bed search
        String dvBody2 = searchBeds(dvAdminToken);
        assertThat(dvBody2)
                .as("DV admin bed search must still contain DV shelter after outreach queries")
                .contains(dvShelterName);
    }

    private void createAvailabilitySnapshot(String token, UUID shelterId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");

        String body = """
                {"populationType": "SINGLE_ADULT", "bedsTotal": 10, "bedsOccupied": 2, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                String.class);
        assertThat(response.getStatusCode())
                .as("Availability snapshot for shelter %s must succeed: %s", shelterId, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private String searchBeds(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String getShelters(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
