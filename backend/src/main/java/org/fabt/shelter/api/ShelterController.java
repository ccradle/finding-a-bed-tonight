package org.fabt.shelter.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @PostMapping
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ShelterResponse> create(@Valid @RequestBody CreateShelterRequest request) {
        Shelter shelter = shelterService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ShelterResponse.from(shelter));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Boolean petsAllowed,
                                  @RequestParam(required = false) Boolean wheelchairAccessible,
                                  @RequestParam(required = false) Boolean sobrietyRequired,
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

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id,
                                     @RequestParam(required = false) String format) {
        ShelterDetail detail = shelterService.getDetail(id);

        if ("hsds".equalsIgnoreCase(format)) {
            Map<String, Object> hsds = hsdsMapper.toHsds(detail);
            return ResponseEntity.ok(hsds);
        }

        return ResponseEntity.ok(ShelterDetailResponse.from(detail));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ShelterResponse> update(@PathVariable UUID id,
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

    @PostMapping("/{id}/coordinators")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> assignCoordinator(@PathVariable UUID id,
                                                   @Valid @RequestBody AssignCoordinatorRequest request) {
        // Verify shelter exists and belongs to tenant
        shelterService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + id));

        coordinatorAssignmentRepository.assign(request.userId(), id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/coordinators/{userId}")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> unassignCoordinator(@PathVariable UUID id,
                                                     @PathVariable UUID userId) {
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
