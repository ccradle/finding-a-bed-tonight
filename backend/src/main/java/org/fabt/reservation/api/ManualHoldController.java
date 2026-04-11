package org.fabt.reservation.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for the offline-hold endpoint introduced by the bed-hold-integrity
 * change (Issue #102 RCA). The endpoint creates a real reservation row through
 * the standard reservation lifecycle so the new single-write-path discipline
 * for {@code beds_on_hold} applies automatically.
 *
 * <p>The URL is shelter-scoped ({@code /api/v1/shelters/{id}/manual-hold}), so
 * this controller is class-mapped to {@code /api/v1/shelters} rather than
 * {@code /api/v1/reservations}. Single-purpose by design.</p>
 */
@RestController
@RequestMapping("/api/v1/shelters")
public class ManualHoldController {

    private final ReservationService reservationService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    public ManualHoldController(ReservationService reservationService,
                                CoordinatorAssignmentRepository coordinatorAssignmentRepository) {
        this.reservationService = reservationService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
    }

    /**
     * Two-layer authorization contract:
     *
     * <ol>
     *   <li><b>Filter chain (coarse pass)</b> -- {@code SecurityConfig} rule
     *       for {@code POST /api/v1/shelters/{id}/manual-hold} admits roles
     *       {@code COORDINATOR}, {@code COC_ADMIN}, {@code PLATFORM_ADMIN}.
     *       Anonymous and other roles are rejected with 401/403 before this
     *       method runs.
     *   <li><b>Controller body (fine pass)</b> -- admins ({@code COC_ADMIN},
     *       {@code PLATFORM_ADMIN}) bypass the assignment check. Coordinators
     *       must be assigned to the target shelter via
     *       {@code coordinator_assignment};
     *       {@code CoordinatorAssignmentRepository.isAssigned} returning
     *       {@code false} throws {@link AccessDeniedException} which
     *       {@code GlobalExceptionHandler} converts to a 403.
     * </ol>
     *
     * <p>The two layers must agree on the role list. If a future refactor
     * narrows the SecurityConfig rule (e.g., removes COORDINATOR) the
     * controller will silently become unreachable for that role -- exactly
     * the regression that Issue #102's manual-test smoke caught and that
     * {@code OfflineHoldEndpointTest.coordinator_creates_offline_hold_succeeds_when_assigned}
     * is now gating against.
     */
    @Operation(
            summary = "Create an offline hold (manual coordinator override)",
            description = "Creates a real HELD reservation for a bed at this shelter and " +
                    "population type, with the requesting coordinator as the creator. Used for " +
                    "off-system holds such as phone reservations or expected guests. The " +
                    "reservation participates in the standard lifecycle (auto-expiry, recompute, " +
                    "audit) so the bed becomes available again automatically when the hold " +
                    "expires. Coordinators must be assigned to the shelter; COC_ADMIN and " +
                    "PLATFORM_ADMIN can create offline holds at any shelter in the tenant. The " +
                    "endpoint is server-managed via the reservation table — there is no manual " +
                    "PATCH path that bypasses the recompute discipline."
    )
    @PostMapping("/{shelterId}/manual-hold")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReservationResponse> create(
            @Parameter(description = "UUID of the shelter to hold a bed at") @PathVariable UUID shelterId,
            @Valid @RequestBody ManualHoldRequest request,
            Authentication authentication) {

        UUID userId = UUID.fromString(authentication.getName());

        // Coordinators must be assigned to this shelter; admins bypass.
        if (hasRole(authentication, "ROLE_COORDINATOR")
                && !hasRole(authentication, "ROLE_COC_ADMIN")
                && !hasRole(authentication, "ROLE_PLATFORM_ADMIN")) {
            if (!coordinatorAssignmentRepository.isAssigned(userId, shelterId)) {
                throw new AccessDeniedException("Coordinator is not assigned to this shelter");
            }
        }

        Reservation reservation = reservationService.createManualHold(
                shelterId, request.populationType(), userId, request.reason());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReservationResponse.from(reservation));
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }
}
