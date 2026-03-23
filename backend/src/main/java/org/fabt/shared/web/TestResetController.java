package org.fabt.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test data cleanup endpoint — ONLY available in dev/test profiles.
 *
 * This controller is annotated with @Profile("dev | test") so the bean is
 * never created in production. It does not exist in the application context
 * when running with production profiles.
 *
 * Additional safeguards:
 * - Requires PLATFORM_ADMIN role
 * - Requires X-Confirm-Reset: DESTROY header (prevents accidental calls)
 *
 * WARNING: This endpoint deletes transient data (referral tokens, reservations).
 * It preserves seed data (shelters, users, tenants, availability snapshots).
 */
@RestController
@RequestMapping("/api/v1/test")
@Profile("dev | test")
public class TestResetController {

    private static final Logger log = LoggerFactory.getLogger(TestResetController.class);

    private final JdbcTemplate jdbcTemplate;

    public TestResetController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Operation(
            summary = "Reset transient test data (dev/test only)",
            description = "Deletes transient data created by E2E tests: referral tokens, held/expired " +
                    "reservations, and test-created shelters (name starting with 'E2E Test' or 'DV Shelter' " +
                    "or 'Invariant Test'). Preserves seed shelters, users, tenants, and availability " +
                    "snapshots. THIS ENDPOINT DOES NOT EXIST IN PRODUCTION — it is profile-gated " +
                    "with @Profile(\"dev | test\"). Requires PLATFORM_ADMIN role and X-Confirm-Reset: " +
                    "DESTROY header."
    )
    @DeleteMapping("/reset")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<?> resetTestData(
            @RequestHeader(value = "X-Confirm-Reset", required = false) String confirmHeader) {

        if (!"DESTROY".equals(confirmHeader)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing or invalid X-Confirm-Reset header. Send 'DESTROY' to confirm."));
        }

        // Set dvAccess so RLS allows cleanup of DV-related data
        TenantContext.setDvAccess(true);

        try {
            Map<String, Integer> deleted = new LinkedHashMap<>();

            // Referral tokens — all transient, safe to delete
            int tokens = jdbcTemplate.update("DELETE FROM referral_token");
            deleted.put("referral_tokens", tokens);

            // Reservations in terminal or held state
            int reservations = jdbcTemplate.update(
                    "UPDATE reservation SET status = 'CANCELLED' WHERE status = 'HELD'");
            deleted.put("held_reservations_cancelled", reservations);

            // Test-created shelters (cascade deletes their availability, constraints, assignments)
            int testShelters = jdbcTemplate.update(
                    "DELETE FROM shelter WHERE name LIKE 'E2E Test%' OR name LIKE 'Invariant Test%' " +
                    "OR name LIKE 'DV %' AND name LIKE '%Test%' OR name LIKE 'D10 SSoT%' " +
                    "OR name LIKE 'Regular Shelter%' OR name LIKE 'Updated E2E%'");
            deleted.put("test_shelters", testShelters);

            log.warn("TEST RESET executed: {}", deleted);

            return ResponseEntity.ok(Map.of("reset", deleted, "warning", "This endpoint only exists in dev/test profiles"));
        } finally {
            TenantContext.clear();
        }
    }
}
