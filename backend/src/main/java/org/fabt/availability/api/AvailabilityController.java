package org.fabt.availability.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.availability.service.AvailabilityService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.shelter.domain.PopulationType;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
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

    private final AvailabilityService availabilityService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    public AvailabilityController(AvailabilityService availabilityService,
                                   CoordinatorAssignmentRepository coordinatorAssignmentRepository) {
        this.availabilityService = availabilityService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
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

        String updatedBy = authentication.getName();

        AvailabilitySnapshot snapshot = availabilityService.createSnapshot(
                id, request.populationType(),
                request.bedsTotal(), request.bedsOccupied(), request.bedsOnHoldOrDefault(),
                request.acceptingNewGuests(), request.notes(), updatedBy
        );

        return ResponseEntity.ok(snapshot);
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }
}
