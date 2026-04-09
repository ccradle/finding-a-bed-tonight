package org.fabt.shelter.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shelter-module controller serving user-scoped shelter data.
 * Mapped to /api/v1/users — shares URL prefix with UserController (auth module).
 * UserController handles CRUD; this controller handles shelter-side read views.
 *
 * @see org.fabt.auth.api.UserController
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class UserShelterController {

    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    public UserShelterController(CoordinatorAssignmentRepository coordinatorAssignmentRepository) {
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
    }

    @Operation(
            summary = "List shelters assigned to a user",
            description = "Returns the shelters assigned to the specified user via coordinator_assignment, " +
                    "scoped to the caller's tenant. Each entry contains the shelter's UUID and name. " +
                    "Returns an empty array if the user has no assignments or belongs to a different tenant. " +
                    "DV shelters are only visible if the caller has dvAccess=true (RLS enforced). " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/{id}/shelters")
    public ResponseEntity<List<ShelterSummary>> getUserShelters(
            @Parameter(description = "UUID of the user") @PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(coordinatorAssignmentRepository.findShelterSummariesByUserId(id, tenantId));
    }
}
