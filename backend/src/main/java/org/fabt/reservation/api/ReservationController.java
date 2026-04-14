package org.fabt.reservation.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.service.ShelterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ShelterService shelterService;

    public ReservationController(ReservationService reservationService, ShelterService shelterService) {
        this.reservationService = reservationService;
        this.shelterService = shelterService;
    }

    @Operation(
            summary = "Create a soft-hold bed reservation",
            description = "Temporarily holds one bed for a specific population type at a shelter. " +
                    "The hold lasts for the tenant's configured duration (default 90 minutes) and " +
                    "auto-expires if not confirmed. Creating a hold increments beds_on_hold in a new " +
                    "availability snapshot, reducing beds_available for other searchers. Returns the " +
                    "reservation with expires_at for countdown display. Returns 409 if no beds are " +
                    "available. Supports optional X-Idempotency-Key header (UUID) for offline queue " +
                    "replay deduplication: if a HELD reservation exists with the same user and key, " +
                    "returns 200 with the existing reservation instead of creating a duplicate (201). " +
                    "Requires OUTREACH_WORKER, COORDINATOR, COC_ADMIN, or PLATFORM_ADMIN role."
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        Reservation reservation = reservationService.createReservation(
                request.shelterId(), request.populationType(), request.notes(), userId, idempotencyKey);
        HttpStatus status = reservation.isIdempotentMatch() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ReservationResponse.from(reservation));
    }

    @Operation(
            summary = "List reservations for the current user",
            description = "Returns reservations for the authenticated user within their tenant. "
                    + "Default behavior (no query params) returns only HELD reservations — preserves "
                    + "the pre-v0.39 contract for existing consumers. "
                    + "Phase 3 of notification-deep-linking adds optional filters for the My Past "
                    + "Holds view: pass {@code status=HELD,CANCELLED,EXPIRED,CONFIRMED,"
                    + "CANCELLED_SHELTER_DEACTIVATED} (comma-separated) to include terminal-state rows, "
                    + "and optionally {@code sinceDays=14} to restrict to the last N days (computed "
                    + "server-side to avoid client-clock skew). "
                    + "Results are sorted by {@code created_at} descending — newest first within each "
                    + "status. Requires any authenticated role with reservation capability."
    )
    @GetMapping
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<ReservationResponse>> listForUser(
            @Parameter(description = "Comma-separated reservation statuses to include. "
                    + "Omit for HELD-only (legacy behavior).")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "Restrict results to reservations created within the last N days. "
                    + "Omit for no date filter.")
            @RequestParam(value = "sinceDays", required = false) Integer sinceDays,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        // Back-compat: no status param → HELD only, and no date filter.
        String[] statuses = status == null || status.isBlank()
                ? new String[] { "HELD" }
                : status.split(",");
        // Trim whitespace + drop empty entries so status="HELD, CANCELLED" works.
        List<String> cleaned = new java.util.ArrayList<>(statuses.length);
        for (String s : statuses) {
            String t = s.trim();
            if (!t.isEmpty()) cleaned.add(t);
        }
        String[] finalStatuses = cleaned.toArray(String[]::new);
        List<Reservation> rows = reservationService.getReservationsForUser(userId, finalStatuses, sinceDays);
        // Batch-load shelter name+phone for the displayed rows so the My Past
        // Holds view (Phase 3) can render meaningful row labels + task 6.4's
        // tel: link. Same pattern as ReferralTokenController's escalated queue
        // enrichment — avoids N+1 lookups. Empty set → empty map, no DB trip.
        Set<UUID> shelterIds = rows.stream().map(Reservation::getShelterId).collect(Collectors.toSet());
        Map<UUID, Shelter> sheltersById = shelterIds.isEmpty()
                ? Map.of()
                : shelterService.findAllById(shelterIds).stream()
                        .collect(Collectors.toMap(Shelter::getId, Function.identity()));
        List<ReservationResponse> reservations = rows.stream()
                .map(r -> {
                    Shelter s = sheltersById.get(r.getShelterId());
                    return ReservationResponse.from(r,
                            s == null ? null : s.getName(),
                            s == null ? null : s.getPhone());
                })
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
