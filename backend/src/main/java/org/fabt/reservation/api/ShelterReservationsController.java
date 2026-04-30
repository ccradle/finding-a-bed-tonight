package org.fabt.reservation.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shelter-scoped reservation read endpoint
 * (transitional-reentry-support slice 4 §11.5).
 *
 * <p>Coordinators need to see the list of HELD reservations for their
 * own shelter so the §11 attribution data ({@code heldForClientName} et
 * al.) renders alongside the hold on the coordinator dashboard. The
 * existing {@code GET /api/v1/reservations} endpoint is user-scoped
 * (returns reservations created BY the calling user — outreach worker
 * own-holds view); shelter-scoped reads are a different surface.
 *
 * <p>Two-layer authorization mirrors {@link ManualHoldController}:
 * <ol>
 *   <li>Method-level {@code @PreAuthorize} coarse pass admits
 *       {@code COORDINATOR} and {@code COC_ADMIN}. Per the post-G-4.4
 *       role taxonomy in {@link org.fabt.auth.domain.Role}, the
 *       deprecated {@code PLATFORM_ADMIN} role is intentionally excluded
 *       — the {@code NoPlatformAdminPreauthorizeTest} ArchUnit guard
 *       prohibits new {@code @PreAuthorize} references to it. Genuine
 *       platform-spanning operations live on platform endpoints under
 *       {@code @PlatformAdminOnly}, not here.</li>
 *   <li>Controller fine pass: {@code COC_ADMIN} bypasses the assignment
 *       check; {@code COORDINATOR}-only callers must be assigned to the
 *       target shelter via {@code coordinator_assignment}. Mismatched
 *       coordinators get {@link AccessDeniedException} → 403 via
 *       {@code GlobalExceptionHandler}.</li>
 * </ol>
 *
 * <p>PII surfacing: the response carries {@code heldForClientName /
 * heldForClientDob / holdNotes} per slice 2D warroom H1 (those are
 * already on {@link ReservationResponse}). Decryption happens in the
 * row mapper; the response contains plaintext values for the duration
 * of the request. Tenant scoping is enforced by RLS on the reservation
 * table joined through {@code shelter} — a coordinator from tenant A
 * cannot see tenant B's reservations even if they spoofed a tenant-B
 * shelterId, because the JOIN to {@code shelter} returns nothing for
 * the wrong tenant context.
 */
@RestController
@RequestMapping("/api/v1/shelters")
public class ShelterReservationsController {

    private final ReservationService reservationService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    public ShelterReservationsController(ReservationService reservationService,
                                          CoordinatorAssignmentRepository coordinatorAssignmentRepository) {
        this.reservationService = reservationService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
    }

    @Operation(
            summary = "List active HELD reservations for a shelter (coordinator/admin view)",
            description = "Returns reservations currently in HELD status for the given shelter. "
                    + "Used by the coordinator dashboard to display per-hold details including "
                    + "the optional client attribution PII (heldForClientName, heldForClientDob, "
                    + "holdNotes) entered by the outreach worker who placed the hold. "
                    + "Coordinators must be assigned to the target shelter; COC_ADMIN and "
                    + "PLATFORM_ADMIN see any shelter in their tenant. Tenant scoping is "
                    + "enforced by RLS through the shelter join — cross-tenant access is "
                    + "structurally impossible. Returns an empty list when the shelter has "
                    + "no active holds."
    )
    @GetMapping("/{shelterId}/reservations")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN')")
    public ResponseEntity<List<ReservationResponse>> listHeldForShelter(
            @Parameter(description = "UUID of the shelter to read holds for")
            @PathVariable UUID shelterId,
            Authentication authentication) {
        // Fine-pass: COORDINATOR-only callers must be assigned to the
        // shelter; COC_ADMIN bypasses the assignment check. PLATFORM_ADMIN
        // is deliberately not handled here — see class-level Javadoc.
        boolean isCoordinatorOnly = hasRole(authentication, "ROLE_COORDINATOR")
                && !hasRole(authentication, "ROLE_COC_ADMIN");
        if (isCoordinatorOnly) {
            UUID userId = UUID.fromString(authentication.getName());
            if (!coordinatorAssignmentRepository.isAssigned(userId, shelterId)) {
                throw new AccessDeniedException(
                        "Coordinator is not assigned to this shelter");
            }
        }

        List<Reservation> rows = reservationService.findHeldByShelterId(shelterId);
        List<ReservationResponse> body = rows.stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }
}
