package org.fabt.shelter.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.fabt.shelter.service.ShelterHsdsMapper;
import org.fabt.shelter.service.ShelterService;
import org.fabt.shelter.service.ShelterService.ShelterDetail;
import org.fabt.shelter.service.ShelterService.ShelterFilterCriteria;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shelters")
public class ShelterController {

    private final ShelterService shelterService;
    private final ShelterHsdsMapper hsdsMapper;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    public ShelterController(ShelterService shelterService,
                             ShelterHsdsMapper hsdsMapper,
                             CoordinatorAssignmentRepository coordinatorAssignmentRepository) {
        this.shelterService = shelterService;
        this.hsdsMapper = hsdsMapper;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
    }

    @Operation(
            summary = "Register a new shelter within the authenticated tenant",
            description = "Creates a shelter record scoped to the caller's tenant. Required fields " +
                    "include name and address. Optional fields include phone, latitude/longitude " +
                    "coordinates, and the dvShelter flag. Shelters marked as dvShelter=true are " +
                    "domestic violence shelters — their records are hidden from users who do not " +
                    "have dvAccess=true on their user profile. Returns 201 with the created shelter " +
                    "including its generated UUID. Returns 400 if validation fails. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ShelterResponse> create(@Valid @RequestBody CreateShelterRequest request) {
        Shelter shelter = shelterService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ShelterResponse.from(shelter));
    }

    @Operation(
            summary = "List shelters with optional constraint filters",
            description = "Returns shelters within the authenticated tenant. All filter parameters " +
                    "are optional — omit all filters to retrieve every shelter in the tenant. When " +
                    "one or more filters are provided, only shelters whose constraints match ALL " +
                    "specified filters are returned (AND logic). DV shelters are automatically " +
                    "excluded from results for users without dvAccess. Each result includes shelter " +
                    "id, name, full address, phone, coordinates, dvShelter flag, and timestamps. " +
                    "The updatedAt timestamp indicates when the shelter profile was last modified — " +
                    "treat shelters not updated within the last hour as potentially stale. " +
                    "Requires any authenticated role."
    )
    @GetMapping
    public ResponseEntity<?> list(
            @Parameter(description = "Filter to shelters that allow pets. Omit to not filter on this constraint.")
            @RequestParam(required = false) Boolean petsAllowed,
            @Parameter(description = "Filter to wheelchair-accessible shelters. Omit to not filter on this constraint.")
            @RequestParam(required = false) Boolean wheelchairAccessible,
            @Parameter(description = "Filter to shelters that require sobriety. Omit to not filter on this constraint.")
            @RequestParam(required = false) Boolean sobrietyRequired,
            @Parameter(description = "Filter by population type served (e.g., 'FAMILIES', 'SINGLE_MEN', 'SINGLE_WOMEN', 'YOUTH'). Omit to not filter on this constraint.")
            @RequestParam(required = false) String populationType) {
        boolean hasFilters = petsAllowed != null || wheelchairAccessible != null
                || sobrietyRequired != null || populationType != null;

        if (hasFilters) {
            ShelterFilterCriteria criteria = new ShelterFilterCriteria(
                    petsAllowed, wheelchairAccessible, sobrietyRequired, populationType);
            List<ShelterResponse> shelters = shelterService.findFiltered(criteria).stream()
                    .map(ShelterResponse::from)
                    .toList();
            return ResponseEntity.ok(shelters);
        }

        List<ShelterResponse> shelters = shelterService.findByTenantId().stream()
                .map(ShelterResponse::from)
                .toList();
        return ResponseEntity.ok(shelters);
    }

    @Operation(
            summary = "Get full shelter details including constraints and capacity",
            description = "Returns comprehensive details for a single shelter: basic info (name, " +
                    "address, phone, coordinates), constraint profile (sobriety required, pets " +
                    "allowed, wheelchair accessible, ID required, referral required, curfew time, " +
                    "max stay days, population types served), and bed capacity breakdown by " +
                    "population type. Pass format=hsds to receive the response in HSDS 3.0 " +
                    "(Human Services Data Specification) JSON format instead of the native format — " +
                    "useful for interoperability with other HMIS systems. Returns 404 if the shelter " +
                    "does not exist within the caller's tenant. Requires any authenticated role."
    )
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(
            @Parameter(description = "UUID of the shelter to retrieve") @PathVariable UUID id,
            @Parameter(description = "Response format. Omit for native format. Set to 'hsds' for HSDS 3.0 JSON output.")
            @RequestParam(required = false) String format) {
        ShelterDetail detail = shelterService.getDetail(id);

        if ("hsds".equalsIgnoreCase(format)) {
            Map<String, Object> hsds = hsdsMapper.toHsds(detail);
            return ResponseEntity.ok(hsds);
        }

        return ResponseEntity.ok(ShelterDetailResponse.from(detail));
    }

    @Operation(
            summary = "Update a shelter's profile information",
            description = "Updates the specified shelter's mutable fields (name, address, phone, " +
                    "coordinates, dvShelter flag, constraints, capacity). Coordinators can only " +
                    "update shelters they are explicitly assigned to — attempting to update an " +
                    "unassigned shelter returns 403 AccessDeniedException. COC_ADMIN and " +
                    "PLATFORM_ADMIN can update any shelter in the tenant. Returns the updated " +
                    "shelter record. Returns 404 if the shelter does not exist. " +
                    "Requires COORDINATOR (assigned only), COC_ADMIN, or PLATFORM_ADMIN role."
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ShelterResponse> update(
            @Parameter(description = "UUID of the shelter to update") @PathVariable UUID id,
            @Valid @RequestBody UpdateShelterRequest request,
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

        Shelter shelter = shelterService.update(id, request);
        return ResponseEntity.ok(ShelterResponse.from(shelter));
    }

    @Operation(
            summary = "Assign a coordinator user to a shelter",
            description = "Creates an assignment linking a user (who should have the COORDINATOR role) " +
                    "to the specified shelter. Once assigned, the coordinator can update that shelter's " +
                    "profile via PUT /api/v1/shelters/{id}. The shelter must exist within the caller's " +
                    "tenant — returns 404 if not found. The request body must include the userId of the " +
                    "user to assign. Assigning an already-assigned coordinator is idempotent. " +
                    "Returns 200 on success. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/{id}/coordinators")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> assignCoordinator(
            @Parameter(description = "UUID of the shelter to assign the coordinator to") @PathVariable UUID id,
            @Valid @RequestBody AssignCoordinatorRequest request) {
        // Verify shelter exists and belongs to tenant
        shelterService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + id));

        coordinatorAssignmentRepository.assign(request.userId(), id);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Remove a coordinator assignment from a shelter",
            description = "Removes the assignment linking the specified user to the specified shelter. " +
                    "After removal, the user can no longer update this shelter (unless they also hold " +
                    "COC_ADMIN or PLATFORM_ADMIN role). The shelter must exist within the caller's " +
                    "tenant — returns 404 if not found. Removing a non-existent assignment is " +
                    "idempotent and returns 204. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @DeleteMapping("/{id}/coordinators/{userId}")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> unassignCoordinator(
            @Parameter(description = "UUID of the shelter") @PathVariable UUID id,
            @Parameter(description = "UUID of the coordinator user to unassign") @PathVariable UUID userId) {
        // Verify shelter exists and belongs to tenant
        shelterService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + id));

        coordinatorAssignmentRepository.unassign(userId, id);
        return ResponseEntity.noContent().build();
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }
}
