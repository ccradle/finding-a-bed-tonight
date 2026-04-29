package org.fabt.reservation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateReservationRequest(
        @NotNull UUID shelterId,
        @NotBlank String populationType,
        @Size(max = 500) String notes,
        // transitional-reentry-support task 4.4 (slice 2C). Optional
        // third-party navigator hold attribution per design D4. All three
        // fields nullable; ReservationService applies plaintext validation
        // BEFORE encryption (per task 4.4 wording — validation runs on
        // plaintext, not on the ciphertext envelope).
        //
        // heldForClientName: max 100 (matches DB plan; encrypted output is
        //   variable-length base64 v1 envelope, NOT bound by this constraint).
        // heldForClientDob: @Past + ReservationService.validateHoldClientDob
        //   enforces > 1900-01-01 (Bean Validation has no native lower bound;
        //   service-layer guard catches the "1850" case Marcus warroom flagged).
        // holdNotes: max 1000 server-side (UI enforces 500; spec open question
        //   #1 resolution explicitly chose this asymmetric pair).
        @Size(max = 100) String heldForClientName,
        @Past LocalDate heldForClientDob,
        @Size(max = 1000) String holdNotes
) {
}
