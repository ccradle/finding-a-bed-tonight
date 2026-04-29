package org.fabt.reservation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shared.security.CrossTenantCiphertextException;
import org.fabt.shared.security.EncryptionEnvelope;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.shelter.api.ShelterResponse;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the slice-2C hold attribution + admin hold-duration
 * endpoint (transitional-reentry-support tasks 4.4, 4.5, 4.5a; spec
 * sections 13.5–13.8 and 13.13).
 *
 * <p>Covers four invariants that absence-of-test would let regress:
 * <ol>
 *   <li><b>§13.5 — round-trip persistence.</b> The three PII fields encrypt
 *       on write, store as v1 envelope ciphertext, and decrypt back to the
 *       original plaintext on read.</li>
 *   <li><b>§13.6 — Bean-Validation guard.</b> {@code @Past} rejects future
 *       DOB at the controller layer (400) without ever reaching the service.</li>
 *   <li><b>§13.7 — hold duration applies forward only.</b> A reservation
 *       created BEFORE a duration change keeps its original {@code expires_at};
 *       a reservation created AFTER uses the new duration.</li>
 *   <li><b>§13.8 — role gating.</b> COC_ADMIN can PATCH; OUTREACH_WORKER
 *       and COORDINATOR receive 403.</li>
 *   <li><b>§13.13 — encryption invariants.</b> Stored ciphertext is NOT the
 *       plaintext; cross-tenant decrypt of a tenant A ciphertext under tenant
 *       B context throws {@link CrossTenantCiphertextException} (kid-check
 *       inherits Phase F-6 behavior — see warroom 16.M2).</li>
 * </ol>
 *
 * <p>Each test seeds a fresh tenant and shelter so cross-test bleeding is
 * impossible.
 */
@DisplayName("Reservation Hold Attribution — slice 2C invariants (§13.5–13.8 + §13.13)")
class HoldAttributionIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationService reservationService;
    @Autowired private SecretEncryptionService encryptionService;

    private UUID tenantId;
    private UUID shelterId;

    @BeforeEach
    void setUp() {
        String slug = "hold-attr-" + UUID.randomUUID().toString().substring(0, 8);
        tenantId = authHelper.setupTestTenant(slug).getId();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();

        shelterId = createTestShelter();
        assignCoordinator();
        submitAvailability(50);
    }

    // ---------------------------------------------------------------------
    // §13.5 — hold attribution: persisted and decrypted on read
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.5 hold attribution round-trips through the row mapper AND through the API response")
    void holdAttribution_roundTripsThroughRepositoryAndApi() {
        String name = "Probe-" + UUID.randomUUID();
        LocalDate dob = LocalDate.of(1985, 6, 15);
        String notes = "Released " + Instant.now() + "; navigator hand-off pending.";

        // Capture the POST response so we also exercise the API DTO contract
        // (slice 2D warroom H1 — `ReservationResponse` was missing these
        // fields; without an assertion at the boundary the contract is
        // untested and the frontend has nothing to bind to).
        Map<String, Object> apiResponse = postHoldRawResponse(formatBody(name, dob.toString(), notes));
        UUID reservationId = UUID.fromString((String) apiResponse.get("id"));

        assertThat(apiResponse.get("heldForClientName"))
                .as("API response must echo the plaintext name back to the caller")
                .isEqualTo(name);
        assertThat(apiResponse.get("heldForClientDob"))
                .as("API response must echo the DOB as ISO-8601 string")
                .isEqualTo(dob.toString());
        assertThat(apiResponse.get("holdNotes")).isEqualTo(notes);

        // Read back via repository in tenant context (the row mapper will decrypt).
        WithTenantContext.doAs(tenantId, () -> {
            var row = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(row.getHeldForClientName())
                    .as("name must round-trip through encrypt → DB → decrypt")
                    .isEqualTo(name);
            assertThat(row.getHeldForClientDob())
                    .as("DOB must round-trip including ISO-8601 parse")
                    .isEqualTo(dob);
            assertThat(row.getHoldNotes()).isEqualTo(notes);
        });
    }

    @Test
    @DisplayName("§13.5 omitted attribution stays null (does not get encrypted-empty-string treatment)")
    void omittedAttribution_isPersistedNull() {
        UUID reservationId = postHoldRaw(
                "{ \"shelterId\": \"" + shelterId + "\", \"populationType\": \"SINGLE_ADULT\", "
                + "\"notes\": \"baseline\" }");

        WithTenantContext.doAs(tenantId, () -> {
            var row = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(row.getHeldForClientName()).isNull();
            assertThat(row.getHeldForClientDob()).isNull();
            assertThat(row.getHoldNotes()).isNull();
        });

        // Negative control: confirm the *_encrypted columns were not written
        // with empty-string ciphertext (which would silently round-trip to "").
        Map<String, Object> raw = jdbcTemplate.queryForMap(
                "SELECT held_for_client_name_encrypted, held_for_client_dob_encrypted, "
                + "hold_notes_encrypted FROM reservation WHERE id = ?", reservationId);
        assertThat(raw.get("held_for_client_name_encrypted")).isNull();
        assertThat(raw.get("held_for_client_dob_encrypted")).isNull();
        assertThat(raw.get("hold_notes_encrypted")).isNull();
    }

    // ---------------------------------------------------------------------
    // §13.6 — future DOB rejected with 400
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.6 future DOB → 400 (Bean Validation @Past)")
    void futureDob_isRejected400() {
        LocalDate future = LocalDate.now().plusDays(1);
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "SINGLE_ADULT",
                    "notes": "future dob test",
                    "heldForClientName": "Probe Person",
                    "heldForClientDob": "%s",
                    "holdNotes": "should never be persisted"
                }
                """.formatted(shelterId, future);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("future DOB must be rejected at the controller layer")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Negative control: nothing was persisted under this user — no row carries
        // "should never be persisted" in the notes column.
        Integer leakRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE shelter_id = ? AND notes = ?",
                Integer.class, shelterId, "future dob test");
        assertThat(leakRows)
                .as("no partial reservation should land when validation rejects")
                .isZero();
    }

    @Test
    @DisplayName("§13.6 DOB before 1900-01-01 rejected with 400 (service-layer floor, verify W1)")
    void dobBefore1900_isRejected400() {
        // The DTO's @Past lets "1850-01-01" through (it IS a past date). The
        // service-layer guard ReservationService.validateHoldClientDob enforces
        // the > 1900-01-01 floor that catches the silently-invalid "1850" typo
        // case Marcus warroom flagged. Spec scenario "heldForClientDob
        // validation rejects implausible dates" includes BOTH future-date AND
        // before-1900-01-01; without this test the service-layer half is
        // unenforced.
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "SINGLE_ADULT",
                    "heldForClientName": "Probe Person",
                    "heldForClientDob": "1850-01-01"
                }
                """.formatted(shelterId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("DOB before 1900-01-01 must be rejected with 400 by the service-layer floor")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Negative control: confirm no row landed.
        Integer leakRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE shelter_id = ? AND held_for_client_name_encrypted IS NOT NULL "
                + "AND notes IS NULL",  // our test body has no top-level notes
                Integer.class, shelterId);
        assertThat(leakRows)
                .as("rejected request must not have written a partial reservation")
                .isZero();
    }

    @Test
    @DisplayName("§13.6 today's DOB also rejected (@Past requires strictly past)")
    void todayDob_isRejected400() {
        // Negative control sibling for the future-date test: today fails the same
        // way as a future date because @Past excludes "now". Catches a regression
        // where someone "fixes" the validator to @PastOrPresent.
        LocalDate today = LocalDate.now();
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "SINGLE_ADULT",
                    "heldForClientName": "Probe Person",
                    "heldForClientDob": "%s"
                }
                """.formatted(shelterId, today);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------------
    // §13.7 — hold-duration change applies forward only
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.7 fresh tenant with no holdDuration config defaults to 90 minutes (verify S2)")
    void freshTenant_defaultsTo90Minutes() {
        // reservation-hold-duration-config "Default is 90 minutes" spec
        // scenario. Direct assertion: this test's @BeforeEach already creates
        // a fresh tenant with empty config; create one hold, assert
        // expires_at - created_at ≈ 90 min ±10s. A regression that bumped
        // DEFAULT_HOLD_DURATION_MINUTES would fail this test even though
        // the in-flight half of §13.7 might still pass.
        UUID hold = postHold("Default Probe", "1990-01-01", "default-90");
        Instant created = createdAtOf(hold);
        Instant expires = expiresAtOf(hold);

        long durationSeconds = (expires.toEpochMilli() - created.toEpochMilli()) / 1000;
        assertThat(durationSeconds)
                .as("default duration must be 90 min (±10s) when tenant.config has no hold_duration_minutes key")
                .isBetween(90L * 60 - 10, 90L * 60 + 10);
    }

    @Test
    @DisplayName("§13.7 in-flight holds retain original expires_at; subsequent holds use new duration")
    void holdDurationChange_appliesForwardOnly() {
        UUID inFlightHold = postHold("Pre-Change Probe", "1990-01-01", "before");
        Instant inFlightExpiresBefore = expiresAtOf(inFlightHold);

        // Bump tenant.config.holdDurationMinutes from default (90) to 180.
        ResponseEntity<Map<String, Object>> patch = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 180}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});
        assertThat(patch.getStatusCode())
                .as("COC_ADMIN must successfully PATCH hold-duration")
                .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);

        // Re-read the in-flight hold — its expires_at must be unchanged.
        Instant inFlightExpiresAfter = expiresAtOf(inFlightHold);
        assertThat(inFlightExpiresAfter)
                .as("in-flight hold's expires_at must not be retroactively rewritten")
                .isEqualTo(inFlightExpiresBefore);

        // Create a NEW hold; its expires_at must reflect the new duration.
        // Need to free a bed first — cancel the in-flight one (one-bed shelter would
        // otherwise 409). In our setUp() we seeded 50 beds so this is fine.
        UUID newHold = postHold("Post-Change Probe", "1990-01-01", "after");
        Instant newExpires = expiresAtOf(newHold);

        // The new hold should be ~180min from createdAt; the old hold ~90min.
        // Delta-based check is robust to clock skew between test JVM + Postgres.
        // Tolerance ±10s (M1 tightened from initial ±2min) — single-JVM test
        // hits localhost Postgres, sub-second skew is realistic. Anything
        // wider than ±10s would mask a real off-by-minute bug.
        Instant newCreatedAt = createdAtOf(newHold);
        Instant inFlightCreatedAt = createdAtOf(inFlightHold);
        long newDurationSeconds = (newExpires.toEpochMilli() - newCreatedAt.toEpochMilli()) / 1000;
        long oldDurationSeconds = (inFlightExpiresBefore.toEpochMilli() - inFlightCreatedAt.toEpochMilli()) / 1000;

        assertThat(newDurationSeconds)
                .as("new hold should use the 180min duration (±10s)")
                .isBetween(180L * 60 - 10, 180L * 60 + 10);
        assertThat(oldDurationSeconds)
                .as("old hold should still reflect the original 90min duration (±10s)")
                .isBetween(90L * 60 - 10, 90L * 60 + 10);
    }

    // ---------------------------------------------------------------------
    // §13.8 — hold-duration endpoint role gating
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.8 OUTREACH_WORKER PATCH /hold-duration → 403")
    void outreachWorker_cannotPatchHoldDuration() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 120}",
                        headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("§13.8 COORDINATOR PATCH /hold-duration → 403")
    void coordinator_cannotPatchHoldDuration() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 120}",
                        headers("application/json", authHelper.coordinatorHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("§13.8 COC_ADMIN PATCH /hold-duration → 2xx + TENANT_CONFIG_UPDATED audit row (positive control)")
    void cocAdmin_canPatchHoldDuration_andEmitsAuditRow() {
        // Capture audit row count BEFORE the PATCH so the assertion is robust
        // to whatever rows may have already accumulated under this tenant.
        int auditRowsBefore = WithTenantContext.readAs(tenantId, () ->
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = 'TENANT_CONFIG_UPDATED'",
                        Integer.class));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 120}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("COC_ADMIN must succeed")
                .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);

        // Slice 2D warroom B1: TENANT_CONFIG_UPDATED audit event MUST be
        // emitted on every successful PATCH. Without this assertion a future
        // refactor that drops the audit emission would silently regress —
        // a COC_ADMIN could change hold duration with zero forensic trail.
        int auditRowsAfter = WithTenantContext.readAs(tenantId, () ->
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = 'TENANT_CONFIG_UPDATED'",
                        Integer.class));
        assertThat(auditRowsAfter - auditRowsBefore)
                .as("PATCH /hold-duration must emit exactly one TENANT_CONFIG_UPDATED audit row")
                .isEqualTo(1);

        // The most recent audit row must include the new value in its details.
        String details = WithTenantContext.readAs(tenantId, () ->
                jdbcTemplate.queryForObject(
                        "SELECT details::text FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "ORDER BY timestamp DESC LIMIT 1",
                        String.class));
        assertThat(details)
                .as("audit details must capture config_key + new_value")
                .contains("hold_duration_minutes")
                .contains("120");
    }

    @Test
    @DisplayName("§13.8 cross-tenant PATCH returns 403; target tenant config unchanged (verify C1)")
    void crossTenantPatch_isRejected_andTargetUnchanged() {
        // Stand up a SECOND tenant and try to PATCH its hold-duration with the
        // first tenant's COC_ADMIN. Per the
        // reservation-hold-duration-config "scoped to caller's tenant"
        // spec scenario, this MUST return 403 and Tenant B's config must
        // be unchanged.
        UUID otherTenantId = authHelper.setupSecondaryTenantWithKeyMaterial(
                "ht-cross-tenant-" + UUID.randomUUID().toString().substring(0, 8)).getId();

        // Snapshot Tenant B's current config (or absence of the key) BEFORE
        // the cross-tenant attempt so we can assert "unchanged" afterwards.
        String configBefore = jdbcTemplate.queryForObject(
                "SELECT config::text FROM tenant WHERE id = ?", String.class, otherTenantId);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + otherTenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 200}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("cross-tenant PATCH must be 403 even though caller has COC_ADMIN role")
                .isEqualTo(HttpStatus.FORBIDDEN);

        String configAfter = jdbcTemplate.queryForObject(
                "SELECT config::text FROM tenant WHERE id = ?", String.class, otherTenantId);
        assertThat(configAfter)
                .as("Tenant B's config must be byte-for-byte unchanged after a rejected cross-tenant PATCH")
                .isEqualTo(configBefore);
    }

    @Test
    @DisplayName("§13.8 holdDurationMinutes=20 (below 30) → 400 (Bean Validation @Min)")
    void belowMinimum_isRejected400() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 20}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("§13.8 holdDurationMinutes=600 (above 480) → 400 (Bean Validation @Max)")
    void aboveMaximum_isRejected400() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 600}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------------
    // §13.9 — Spring Batch purge nulls hold attribution past 24h
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.9 purge nulls hold-attribution ciphertext on EXPIRED hold past expires_at + 24h")
    void purge_nullsCiphertext_onExpiredHoldPast24h() {
        // Create a hold WITH attribution PII and assert ciphertext landed.
        UUID reservationId = postHold("Purge Probe", "1990-01-01", "should-be-purged");
        Map<String, String> beforePurge = pickEncryptedColumns(reservationId);
        assertThat(beforePurge.get("name_enc")).isNotNull();
        assertThat(beforePurge.get("dob_enc")).isNotNull();
        assertThat(beforePurge.get("notes_enc")).isNotNull();

        // Force the row into "resolved + aged 25h" by walking expires_at and
        // status into the past. Both branches of the SQL predicate are
        // satisfied so the test is robust to either path.
        Instant aged = Instant.now().minus(Duration.ofHours(25));
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = ?, status = 'EXPIRED' WHERE id = ?",
                Timestamp.from(aged), reservationId);

        // Run the purge with a 24h cutoff.
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        int purged = WithTenantContext.readAs(tenantId, () ->
                reservationService.purgeExpiredHoldAttribution(cutoff));
        assertThat(purged)
                .as("purge must affect exactly the one aged-out row we seeded")
                .isEqualTo(1);

        // Ciphertext columns are now NULL — the at-rest two-layer posture
        // (D4) is honored.
        Map<String, String> afterPurge = pickEncryptedColumns(reservationId);
        assertThat(afterPurge.get("name_enc")).as("name ciphertext must be nulled").isNull();
        assertThat(afterPurge.get("dob_enc")).as("DOB ciphertext must be nulled").isNull();
        assertThat(afterPurge.get("notes_enc")).as("notes ciphertext must be nulled").isNull();

        // Negative control: other reservation columns must be preserved.
        // A bug that did `UPDATE reservation SET ... WHERE 1=1` (forgot the
        // AND clause) would null these too.
        Map<String, Object> preserved = jdbcTemplate.queryForMap(
                "SELECT id, shelter_id, status, notes FROM reservation WHERE id = ?",
                reservationId);
        assertThat(preserved.get("id")).isNotNull();
        assertThat(preserved.get("shelter_id")).isNotNull();
        assertThat(preserved.get("status")).isEqualTo("EXPIRED");
        assertThat(preserved.get("notes"))
                .as("operator-facing notes must NOT be purged (only the held_for_client_* PII)")
                .isEqualTo("test reservation");
    }

    @Test
    @DisplayName("§13.9 purge does NOT touch a HELD reservation that hasn't aged out (negative control)")
    void purge_skipsActiveRecentHold() {
        UUID reservationId = postHold("Active Probe", "1990-01-01", "should-NOT-be-purged");
        Map<String, String> before = pickEncryptedColumns(reservationId);
        assertThat(before.get("name_enc")).isNotNull();

        // Default expires_at is now+90min; default status is HELD; cutoff is
        // 24h ago. Neither predicate branch matches → purge skips the row.
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        int purged = WithTenantContext.readAs(tenantId, () ->
                reservationService.purgeExpiredHoldAttribution(cutoff));

        // Note: zero rows for THIS active hold; other tests in this run may
        // have left aged rows that get swept too — but our specific row must
        // survive.
        Map<String, String> after = pickEncryptedColumns(reservationId);
        assertThat(after.get("name_enc"))
                .as("active HELD reservation under 24h must NOT have its PII nulled (count=%d)", purged)
                .isNotNull();
    }

    @Test
    @DisplayName("§13.9 purge does NOT touch a CANCELLED reservation younger than 24h (negative control)")
    void purge_skipsRecentlyCancelled() {
        UUID reservationId = postHold("Recently Cancelled", "1990-01-01", "still-fresh");

        // Cancel it but leave created_at recent.
        jdbcTemplate.update(
                "UPDATE reservation SET status = 'CANCELLED' WHERE id = ?",
                reservationId);

        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        WithTenantContext.readAs(tenantId, () ->
                reservationService.purgeExpiredHoldAttribution(cutoff));

        // CANCELLED + created_at within 24h + expires_at hasn't lapsed →
        // both predicate branches false → row NOT purged.
        Map<String, String> after = pickEncryptedColumns(reservationId);
        assertThat(after.get("name_enc"))
                .as("CANCELLED row created < 24h ago must keep its ciphertext")
                .isNotNull();
    }

    // ---------------------------------------------------------------------
    // §13.13 + warroom 16.M2 — encryption invariants
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.13 (a) DB ciphertext is a structurally valid v1 envelope (proves at-rest encryption)")
    void ciphertextIsV1Envelope_provesAtRestEncryption() {
        String name = "AtRestProbe-" + UUID.randomUUID();
        UUID reservationId = postHold(name, "1990-01-01", "atrest");

        String storedCiphertext = jdbcTemplate.queryForObject(
                "SELECT held_for_client_name_encrypted FROM reservation WHERE id = ?",
                String.class, reservationId);

        assertThat(storedCiphertext)
                .as("name_encrypted column must be non-null after a hold-with-attribution insert")
                .isNotNull();
        assertThat(storedCiphertext)
                .as("stored value MUST be ciphertext, not the plaintext probe")
                .isNotEqualTo(name);

        // M2 — verify the FABT v1 envelope structure (matches
        // PerTenantEncryptionIntegrationTest's pattern). Stronger than the
        // earlier "length > plaintext + 20" coarse check: a future change to
        // a shorter envelope or a different envelope format would still pass
        // the length check but fail this magic-bytes check, surfacing the
        // crypto-format regression at the at-rest invariant point.
        byte[] decoded = Base64.getDecoder().decode(storedCiphertext);
        assertThat(EncryptionEnvelope.isV1Envelope(decoded))
                .as("ciphertext must carry the v1 envelope magic + version (FABT/0x01)")
                .isTrue();
        assertThat(decoded.length)
                .as("v1 envelope must include header + ciphertext+tag (>= header length)")
                .isGreaterThanOrEqualTo(EncryptionEnvelope.HEADER_LENGTH);
    }

    @Test
    @DisplayName("§13.13 (b) cross-tenant decryptForTenant on a tenant-A ciphertext throws CrossTenantCiphertextException")
    void crossTenantDecrypt_throwsCrossTenantCiphertextException() {
        // Tenant A: persist a hold and capture the ciphertext.
        String name = "CrossTenantProbe-" + UUID.randomUUID();
        UUID reservationId = postHold(name, "1990-01-01", "cross-tenant");
        String tenantACiphertext = jdbcTemplate.queryForObject(
                "SELECT held_for_client_name_encrypted FROM reservation WHERE id = ?",
                String.class, reservationId);
        assertThat(tenantACiphertext).isNotNull();

        // Tenant B: brand-new tenant with key material bootstrapped (so the kid
        // resolution path doesn't fall through to a "no kid" failure that masks
        // the actual cross-tenant rejection).
        UUID tenantB = authHelper.setupSecondaryTenantWithKeyMaterial(
                "ht-cross-" + UUID.randomUUID().toString().substring(0, 8)).getId();

        // Positive control: tenant A can decrypt its own ciphertext (rules out
        // "decrypt is broken globally for some unrelated reason").
        String roundTripA = encryptionService.decryptForTenant(
                tenantId, KeyPurpose.RESERVATION_PII, tenantACiphertext);
        assertThat(roundTripA)
                .as("tenant A must decrypt its own RESERVATION_PII ciphertext (positive control)")
                .isEqualTo(name);

        // The actual invariant under test: tenant B trying to decrypt tenant A's
        // ciphertext must throw, with the kid-check failure shape from Phase F-6.
        assertThatThrownBy(() -> encryptionService.decryptForTenant(
                        tenantB, KeyPurpose.RESERVATION_PII, tenantACiphertext))
                .as("cross-tenant decrypt must throw CrossTenantCiphertextException, "
                        + "not silently return wrong plaintext")
                .isInstanceOf(CrossTenantCiphertextException.class);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID postHold(String name, String dobIso, String holdNotes) {
        return postHoldRaw(formatBody(name, dobIso, holdNotes));
    }

    private String formatBody(String name, String dobIso, String holdNotes) {
        return """
                {
                    "shelterId": "%s",
                    "populationType": "SINGLE_ADULT",
                    "notes": "test reservation",
                    "heldForClientName": "%s",
                    "heldForClientDob": "%s",
                    "holdNotes": "%s"
                }
                """.formatted(shelterId, name, dobIso,
                holdNotes.replace("\"", "\\\""));
    }

    @SuppressWarnings("unchecked")
    private UUID postHoldRaw(String jsonBody) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(jsonBody, headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postHoldRawResponse(String jsonBody) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(jsonBody, headers("application/json", authHelper.outreachWorkerHeaders())),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, String> pickEncryptedColumns(UUID reservationId) {
        Map<String, Object> raw = jdbcTemplate.queryForMap(
                "SELECT held_for_client_name_encrypted, held_for_client_dob_encrypted, "
                + "hold_notes_encrypted FROM reservation WHERE id = ?", reservationId);
        Map<String, String> out = new HashMap<>();
        out.put("name_enc", (String) raw.get("held_for_client_name_encrypted"));
        out.put("dob_enc", (String) raw.get("held_for_client_dob_encrypted"));
        out.put("notes_enc", (String) raw.get("hold_notes_encrypted"));
        return out;
    }

    private Instant expiresAtOf(UUID reservationId) {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM reservation WHERE id = ?",
                Timestamp.class, reservationId);
        return ts == null ? null : ts.toInstant();
    }

    private Instant createdAtOf(UUID reservationId) {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT created_at FROM reservation WHERE id = ?",
                Timestamp.class, reservationId);
        return ts == null ? null : ts.toInstant();
    }

    private static HttpHeaders headers(String contentType, HttpHeaders auth) {
        HttpHeaders h = new HttpHeaders();
        h.addAll(auth);
        h.set("Content-Type", contentType);
        return h;
    }

    private UUID createTestShelter() {
        String body = """
                {
                    "name": "Hold Attribution Test Shelter %s",
                    "addressStreet": "1 Hold Way",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0410",
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 50}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                ShelterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void assignCoordinator() {
        UUID coordinatorId = authHelper.setupCoordinatorUser().getId();
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>("{\"userId\":\"" + coordinatorId + "\"}",
                        authHelper.cocAdminHeaders()),
                Void.class);
    }

    private void submitAvailability(int bedsTotal) {
        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "bedsTotal": %d,
                    "bedsOccupied": 0,
                    "bedsOnHold": 0,
                    "acceptingNewGuests": true
                }
                """.formatted(bedsTotal);
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
