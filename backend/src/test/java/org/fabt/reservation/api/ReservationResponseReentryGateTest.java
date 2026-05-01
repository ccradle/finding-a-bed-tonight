package org.fabt.reservation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.domain.ReservationStatus;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5 §16.B.3 — API serialization gate for {@link ReservationResponse}.
 *
 * <p>Asserts that the three hold-attribution PII fields
 * ({@code heldForClientName}, {@code heldForClientDob}, {@code holdNotes})
 * are populated only when {@link TenantContext#REENTRY_MODE} is bound to
 * {@code true} for the current scope. The gate is the primary control
 * (defense-in-depth) — the §16.C frontend gates are UX polish; if a frontend
 * regression rendered these fields, the API response shape itself must
 * still strip them.
 *
 * <p>Reservation entity is intentionally unmodified by the gate; the gate
 * is purely at serialization time. Tests verify both the gating behavior
 * AND the entity's underlying values stay intact (so the same entity
 * can be reserialized correctly inside a different scope).
 */
@DisplayName("ReservationResponse reentryMode serialization gate (Round 5 §16.B.3)")
class ReservationResponseReentryGateTest {

    private Reservation reservationWithPii() {
        Reservation r = new Reservation();
        r.setId(UUID.randomUUID());
        r.setShelterId(UUID.randomUUID());
        r.setTenantId(UUID.randomUUID());
        r.setUserId(UUID.randomUUID());
        r.setStatus(ReservationStatus.HELD);
        r.setPopulationType("MEN");
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        r.setCreatedAt(Instant.now());
        r.setNotes("public navigator notes — not PII");
        // PII fields per design D4 (V93 _encrypted columns):
        r.setHeldForClientName("Demetrius Synthetic");
        r.setHeldForClientDob(LocalDate.of(1985, 7, 12));
        r.setHoldNotes("Released from supervision Tuesday; needs intake by 18:00.");
        return r;
    }

    // ------------------------------------------------------------------
    // Case 1 — REENTRY_MODE bound true → PII fields populated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REENTRY_MODE=true → all 3 PII fields populated from Reservation entity")
    void reentryModeTrue_populatesAllPiiFields() {
        Reservation r = reservationWithPii();

        ReservationResponse resp = ScopedValue
                .where(TenantContext.REENTRY_MODE, true)
                .call(() -> ReservationResponse.from(r, "Onslow Womens Reentry", "910-555-0100"));

        assertThat(resp.heldForClientName()).isEqualTo("Demetrius Synthetic");
        assertThat(resp.heldForClientDob()).isEqualTo(LocalDate.of(1985, 7, 12));
        assertThat(resp.holdNotes()).contains("supervision Tuesday");
        // Non-PII fields always populated
        assertThat(resp.shelterName()).isEqualTo("Onslow Womens Reentry");
        assertThat(resp.notes()).isEqualTo("public navigator notes — not PII");
    }

    // ------------------------------------------------------------------
    // Case 2 — REENTRY_MODE bound false → PII fields null (defense-in-depth)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REENTRY_MODE=false → all 3 PII fields null even though entity has values")
    void reentryModeFalse_stripsAllPiiFields() {
        Reservation r = reservationWithPii();

        ReservationResponse resp = ScopedValue
                .where(TenantContext.REENTRY_MODE, false)
                .call(() -> ReservationResponse.from(r, "Demo Shelter", "555-0000"));

        assertThat(resp.heldForClientName()).as("PII stripped at serialization").isNull();
        assertThat(resp.heldForClientDob()).as("PII stripped at serialization").isNull();
        assertThat(resp.holdNotes()).as("PII stripped at serialization").isNull();
        // Underlying entity is intact — gate is response-only:
        assertThat(r.getHeldForClientName()).isEqualTo("Demetrius Synthetic");
        assertThat(r.getHeldForClientDob()).isEqualTo(LocalDate.of(1985, 7, 12));
        assertThat(r.getHoldNotes()).contains("supervision Tuesday");
        // Non-PII fields still populated
        assertThat(resp.shelterName()).isEqualTo("Demo Shelter");
        assertThat(resp.notes()).isEqualTo("public navigator notes — not PII");
    }

    // ------------------------------------------------------------------
    // Case 3 — REENTRY_MODE unbound (e.g., batch job, system context) → fields null
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REENTRY_MODE unbound (batch/system context) → PII fields null (safe default)")
    void reentryModeUnbound_stripsAllPiiFields() {
        Reservation r = reservationWithPii();

        // No ScopedValue.where wrapping — REENTRY_MODE is unbound. This
        // mirrors a scheduled job, an unauthenticated request, or any
        // callsite that did not flow through JwtAuthenticationFilter.
        ReservationResponse resp = ReservationResponse.from(r, null, null);

        assertThat(resp.heldForClientName()).as("safe default").isNull();
        assertThat(resp.heldForClientDob()).as("safe default").isNull();
        assertThat(resp.holdNotes()).as("safe default").isNull();
    }

    // ------------------------------------------------------------------
    // Case 4 — single-arg overload also gates (no shelter enrichment path)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("single-arg from(Reservation) overload gates PII identically")
    void singleArgOverload_gatesIdentically() {
        Reservation r = reservationWithPii();

        ReservationResponse gated = ScopedValue
                .where(TenantContext.REENTRY_MODE, false)
                .call(() -> ReservationResponse.from(r));
        assertThat(gated.heldForClientName()).isNull();
        assertThat(gated.heldForClientDob()).isNull();
        assertThat(gated.holdNotes()).isNull();

        ReservationResponse open = ScopedValue
                .where(TenantContext.REENTRY_MODE, true)
                .call(() -> ReservationResponse.from(r));
        assertThat(open.heldForClientName()).isEqualTo("Demetrius Synthetic");
    }

    // ------------------------------------------------------------------
    // Case 5 — gate has no effect when entity already has null PII
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REENTRY_MODE=true with empty entity PII → response also null (no fabrication)")
    void reentryModeTrueWithNullPii_remainsNull() {
        Reservation r = reservationWithPii();
        r.setHeldForClientName(null);
        r.setHeldForClientDob(null);
        r.setHoldNotes(null);

        ReservationResponse resp = ScopedValue
                .where(TenantContext.REENTRY_MODE, true)
                .call(() -> ReservationResponse.from(r));

        assertThat(resp.heldForClientName()).isNull();
        assertThat(resp.heldForClientDob()).isNull();
        assertThat(resp.holdNotes()).isNull();
    }

    // ------------------------------------------------------------------
    // Case 6 — same entity flips between scopes (per-request gate, not entity state)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("same Reservation entity, two scopes → response shape flips per-scope")
    void sameEntityFlipsBetweenScopes() {
        Reservation r = reservationWithPii();

        ReservationResponse hidden = ScopedValue
                .where(TenantContext.REENTRY_MODE, false)
                .call(() -> ReservationResponse.from(r));
        ReservationResponse shown = ScopedValue
                .where(TenantContext.REENTRY_MODE, true)
                .call(() -> ReservationResponse.from(r));

        assertThat(hidden.heldForClientName()).isNull();
        assertThat(shown.heldForClientName()).isEqualTo("Demetrius Synthetic");
        // Same entity ID — it's the *response* that changes per-scope.
        assertThat(hidden.id()).isEqualTo(shown.id());
    }
}
