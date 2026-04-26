package org.fabt.referral;

import java.time.Instant;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.web.TenantContext;
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
 * Verifies the cutoff semantics of
 * {@link ReferralTokenService#deleteStalePendingForTenant} (G-4.5 §6.10).
 * The full batch wiring lives in {@code DvReferralDemoCleanupJobConfig} and
 * is gated by {@code @Profile("demo")}; this test exercises the underlying
 * service + repository layer that the tasklet calls per-tenant.
 */
class DvReferralDemoCleanupTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferralTokenService referralTokenService;

    private UUID dvShelterId;
    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "cleanup-dvadmin@test.fabt.org", "Cleanup DV Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "cleanup-dvoutreach@test.fabt.org", "Cleanup DV Outreach",
                new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        var dvCoord = authHelper.setupUserWithDvAccess(
                "cleanup-dvcoord@test.fabt.org", "Cleanup DV Coord", new String[]{"COORDINATOR"});

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter();
            patchAvailability(dvShelterId);
            restTemplate.exchange(
                    "/api/v1/shelters/" + dvShelterId + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\": \"" + dvCoord.getId() + "\"}", adminHeaders),
                    String.class);
        });
    }

    @Test
    @DisplayName("Stale PENDING referral (created 49h ago) is deleted; fresh PENDING is preserved")
    void cutoff_keeps_fresh_deletes_stale() {
        UUID tenantId = authHelper.getTestTenantId();

        // Create two PENDING referrals via API (they share the same outreach
        // worker but target the same shelter — the per-account-per-shelter
        // dedupe rejects the second with 409, so we use two shelters).
        UUID secondShelterId = TenantContext.callWithContext(tenantId, true, () -> {
            UUID sid = createSecondDvShelter();
            patchAvailability(sid);
            return sid;
        });

        String staleTokenId = createReferralForShelter(dvShelterId);
        String freshTokenId = createReferralForShelter(secondShelterId);

        // Backdate one to 49h ago (cutoff is 48h in the batch job).
        TenantContext.runWithContext(tenantId, true, () -> {
            int updated = jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '49 hours' "
                            + "WHERE id = ?::uuid",
                    staleTokenId);
            assertThat(updated).isEqualTo(1);
        });

        // Cutoff at 48h ago.
        Instant cutoff = Instant.now().minusSeconds(48L * 3600);

        int deleted = TenantContext.<Integer, RuntimeException>callWithContext(
                tenantId, true,
                () -> referralTokenService.deleteStalePendingForTenant(tenantId, cutoff));

        assertThat(deleted)
                .describedAs("Only the 49h-old referral should be deleted; the fresh one survives.")
                .isEqualTo(1);

        TenantContext.runWithContext(tenantId, true, () -> {
            Integer staleCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid",
                    Integer.class, staleTokenId);
            Integer freshCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid",
                    Integer.class, freshTokenId);
            assertThat(staleCount).isEqualTo(0);
            assertThat(freshCount).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Non-PENDING referrals are preserved even when older than cutoff")
    void cutoff_skips_non_pending_status() {
        UUID tenantId = authHelper.getTestTenantId();

        String tokenId = createReferralForShelter(dvShelterId);

        // Backdate AND flip to ACCEPTED — the cleanup must only touch PENDING.
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token "
                            + "SET created_at = NOW() - INTERVAL '49 hours', status = 'ACCEPTED' "
                            + "WHERE id = ?::uuid",
                    tokenId);
        });

        Instant cutoff = Instant.now().minusSeconds(48L * 3600);
        int deleted = TenantContext.<Integer, RuntimeException>callWithContext(
                tenantId, true,
                () -> referralTokenService.deleteStalePendingForTenant(tenantId, cutoff));

        assertThat(deleted)
                .describedAs("ACCEPTED referrals are out of scope; only PENDING qualifies.")
                .isEqualTo(0);

        TenantContext.runWithContext(tenantId, true, () -> {
            Integer surviving = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid",
                    Integer.class, tokenId);
            assertThat(surviving).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Cleanup is tenant-scoped: rows belonging to other tenants are not touched")
    void tenant_scope_isolated() {
        // The repository method is tenant-scoped via the explicit WHERE
        // tenant_id = ?. This test verifies that scoping by deleting from
        // tenant A and confirming tenant B's PENDING rows survive — even
        // though the bare RLS only filters by dv_access.
        UUID tenantA = authHelper.getTestTenantId();
        UUID tenantB = UUID.randomUUID(); // synthetic id never owns a row

        String tokenId = createReferralForShelter(dvShelterId);

        TenantContext.runWithContext(tenantA, true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '49 hours' "
                            + "WHERE id = ?::uuid",
                    tokenId);
        });

        // Call with tenantB — should match zero rows in tenantA's data.
        Instant cutoff = Instant.now().minusSeconds(48L * 3600);
        int deletedFromB = TenantContext.<Integer, RuntimeException>callWithContext(
                tenantB, true,
                () -> referralTokenService.deleteStalePendingForTenant(tenantB, cutoff));

        assertThat(deletedFromB)
                .describedAs("Calling with tenantB must not touch tenantA's row.")
                .isEqualTo(0);

        // Now call correctly with tenantA — the row should be deleted.
        int deletedFromA = TenantContext.<Integer, RuntimeException>callWithContext(
                tenantA, true,
                () -> referralTokenService.deleteStalePendingForTenant(tenantA, cutoff));

        assertThat(deletedFromA).isEqualTo(1);
    }

    // =========================================================================
    // Helpers (mirrors DvReferralExpiryRlsTest pattern)
    // =========================================================================

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
        return createDvShelter("Cleanup DV Shelter");
    }

    private UUID createSecondDvShelter() {
        return createDvShelter("Cleanup DV Shelter Two");
    }

    private UUID createDvShelter(String name) {
        String body = String.format("""
                {
                  "name": "%s %s",
                  "addressStreet": "100 Shelter Way",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-0001",
                  "dvShelter": true,
                  "constraints": { "populationTypesServed": ["DV_SURVIVOR"] },
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """, name, UUID.randomUUID().toString().substring(0, 8));
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
}
