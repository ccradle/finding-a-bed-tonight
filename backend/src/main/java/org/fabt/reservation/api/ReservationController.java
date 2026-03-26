package org.fabt.reservation.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Operation(
            summary = "Create a soft-hold bed reservation",
            description = "Temporarily holds one bed for a specific population type at a shelter. " +
                    "The hold lasts for the tenant's configured duration (default 90 minutes) and " +
                    "auto-expires if not confirmed. Creating a hold increments beds_on_hold in a new " +
                    "availability snapshot, reducing beds_available for other searchers. Returns the " +
                    "reservation with expires_at for countdown display. Returns 409 if no beds are " +
                    "available. Requires OUTREACH_WORKER, COORDINATOR, COC_ADMIN, or PLATFORM_ADMIN role."
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        Reservation reservation = reservationService.createReservation(
                request.shelterId(), request.populationType(), request.notes(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @Operation(
            summary = "List active reservations for the current user",
            description = "Returns all HELD reservations for the authenticated user within their tenant. " +
                    "Each reservation includes remaining seconds until expiry for countdown display. " +
                    "Requires any authenticated role with reservation capability."
    )
    @GetMapping
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<ReservationResponse>> listActive(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        List<ReservationResponse> reservations = reservationService.getActiveReservations(userId).stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(reservations);
    }

    @Operation(
            summary = "Confirm a reservation — client has arrived",
            description = "Transitions a HELD reservation to CONFIRMED. Decrements beds_on_hold and " +
                    "increments beds_occupied in a new availability snapshot. Only the reservation " +
                    "creator can confirm (COC_ADMIN/PLATFORM_ADMIN can confirm any). Returns 409 if " +
                    "the reservation has already expired, been cancelled, or been confirmed."
    )
    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReservationResponse> confirm(
            @Parameter(description = "UUID of the reservation to confirm") @PathVariable UUID id,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        Reservation reservation = reservationService.confirmReservation(id, userId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    @Operation(
            summary = "Cancel a reservation — release the held bed",
            description = "Transitions a HELD reservation to CANCELLED. Decrements beds_on_hold in a " +
                    "new availability snapshot, making the bed available again. Only the reservation " +
                    "creator can cancel (COC_ADMIN/PLATFORM_ADMIN can cancel any). Returns 409 if " +
                    "the reservation has already expired, been cancelled, or been confirmed."
    )
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReservationResponse> cancel(
            @Parameter(description = "UUID of the reservation to cancel") @PathVariable UUID id,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        Reservation reservation = reservationService.cancelReservation(id, userId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }
}
