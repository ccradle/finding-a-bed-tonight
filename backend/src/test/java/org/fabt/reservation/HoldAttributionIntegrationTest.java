package org.fabt.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.shared.security.CrossTenantCiphertextException;
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
    @DisplayName("§13.5 hold attribution round-trips through the row mapper")
    void holdAttribution_roundTripsThroughRepository() {
        String name = "Probe-" + UUID.randomUUID();
        LocalDate dob = LocalDate.of(1985, 6, 15);
        String notes = "Released " + Instant.now() + "; navigator hand-off pending.";

        UUID reservationId = postHold(name, dob.toString(), notes);
        assertThat(reservationId).isNotNull();

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
        Instant newCreatedAt = createdAtOf(newHold);
        Instant inFlightCreatedAt = createdAtOf(inFlightHold);
        long newDurationMinutes = (newExpires.toEpochMilli() - newCreatedAt.toEpochMilli()) / 60_000;
        long oldDurationMinutes = (inFlightExpiresBefore.toEpochMilli() - inFlightCreatedAt.toEpochMilli()) / 60_000;

        assertThat(newDurationMinutes)
                .as("new hold should use the 180min duration")
                .isBetween(178L, 182L);   // ±2min slack for any second-rounding
        assertThat(oldDurationMinutes)
                .as("old hold should still reflect the original 90min duration")
                .isBetween(88L, 92L);
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
    @DisplayName("§13.8 COC_ADMIN PATCH /hold-duration → 2xx (positive control)")
    void cocAdmin_canPatchHoldDuration() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/hold-duration",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"holdDurationMinutes\": 120}",
                        headers("application/json", authHelper.cocAdminHeaders())),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("COC_ADMIN must succeed")
                .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
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
    // §13.13 + warroom 16.M2 — encryption invariants
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.13 (a) DB ciphertext column does NOT equal plaintext (proves at-rest encryption)")
    void ciphertextDiffersFromPlaintext_provesAtRestEncryption() {
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
        // Sanity: a v1 envelope is base64 of >>30 bytes, so much longer than the
        // plaintext probe. This is a coarse fingerprint, not a hash check.
        assertThat(storedCiphertext.length())
                .as("v1 envelope ciphertext is much longer than the plaintext")
                .isGreaterThan(name.length() + 20);
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
        String body = """
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
        return postHoldRaw(body);
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

    private Instant expiresAtOf(UUID reservationId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM reservation WHERE id = ?",
                java.sql.Timestamp.class, reservationId);
        return ts == null ? null : ts.toInstant();
    }

    private Instant createdAtOf(UUID reservationId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT created_at FROM reservation WHERE id = ?",
                java.sql.Timestamp.class, reservationId);
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
