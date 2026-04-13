package org.fabt.availability.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.availability.service.AvailabilityRetryService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shelter.domain.PopulationType;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.fabt.shelter.service.ShelterService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shelters")
public class AvailabilityController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AvailabilityController.class);

    private final AvailabilityRetryService availabilityRetryService;
    private final ReservationService reservationService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;
    private final ShelterService shelterService;

    public AvailabilityController(AvailabilityRetryService availabilityRetryService,
                                   ReservationService reservationService,
                                   CoordinatorAssignmentRepository coordinatorAssignmentRepository,
                                   ShelterService shelterService) {
        this.availabilityRetryService = availabilityRetryService;
        this.reservationService = reservationService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
        this.shelterService = shelterService;
    }

    @Operation(
            summary = "Submit a real-time bed availability snapshot for a shelter",
            description = "Creates an append-only availability snapshot for the specified shelter and " +
                    "population type. The snapshot records beds_total, beds_occupied, beds_on_hold, " +
                    "and accepting_new_guests at the current moment. The derived value beds_available " +
                    "(beds_total - beds_occupied - beds_on_hold) is computed and returned but never " +
                    "stored. Each call creates a new snapshot row — previous snapshots are preserved " +
                    "for audit history. On success, L1/L2 caches are invalidated synchronously and " +
                    "an availability.updated event is published to the EventBus for webhook delivery. " +
                    "Coordinators can only update shelters they are assigned to. COC_ADMIN and " +
                    "PLATFORM_ADMIN can update any shelter in the tenant. Concurrent submissions " +
                    "for the same shelter/population/timestamp are handled via ON CONFLICT DO NOTHING. " +
                    "Requires COORDINATOR (assigned only), COC_ADMIN, or PLATFORM_ADMIN role."
    )
    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<AvailabilitySnapshot> updateAvailability(
            @Parameter(description = "UUID of the shelter to update availability for") @PathVariable UUID id,
            @Valid @RequestBody AvailabilityUpdateRequest request,
            Authentication authentication) {

        // Coordinators must be assigned to this shelter
        if (hasRole(authentication, "ROLE_COORDINATOR")
                && !hasRole(authentication, "ROLE_COC_ADMIN")
                && !hasRole(authentication, "ROLE_PLATFORM_ADMIN")) {
            UUID userId = UUID.fromString(authentication.getName());
            if (!coordinatorAssignmentRepository.isAssigned(userId, id)) {
                throw new AccessDeniedException("Coordinator is not assigned to this shelter");
            }
        }

        // Validate population type
        PopulationType.valueOf(request.populationType());

        // Check if shelter is active
        Shelter shelter = shelterService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + id));
        if (!shelter.isActive()) {
            throw new IllegalStateException(
                    "Cannot update availability for an inactive shelter — contact your CoC admin to reactivate.");
        }

        String updatedBy = authentication.getName();

        // beds_on_hold is server-managed (Issue #102 RCA, deprecated v0.34.0).
        // Any non-null, non-zero value sent by a coordinator is ignored — the snapshot
        // is written with the actual count of HELD reservations from the source of truth.
        // A WARN is logged so legacy clients can be identified and migrated. A null or
        // zero value is silently accepted (common in legacy clients and the no-op case).
        // Hard rejection is planned for v0.35.0.
        Integer requestedHold = request.bedsOnHold();
        int activeHeldCount = reservationService.countActiveHolds(id, request.populationType());
        if (requestedHold != null && requestedHold != 0) {
            log.warn("Ignored coordinator-supplied beds_on_hold={} for shelter {} / {} — "
                    + "server-managed via reservation table (deprecated v0.34.0, will be "
                    + "hard-rejected v0.35.0)",
                    requestedHold, id, request.populationType());
        }

        AvailabilitySnapshot snapshot = availabilityRetryService.createSnapshotWithRetry(
                id, request.populationType(),
                request.bedsTotal(), request.bedsOccupied(), activeHeldCount,
                request.acceptingNewGuests(), request.notes(), updatedBy,
                request.overflowBedsOrDefault()
        );

        return ResponseEntity.ok(snapshot);
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }
}
