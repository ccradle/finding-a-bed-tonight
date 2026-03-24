package org.fabt.shelter.service;

import java.util.UUID;

import org.fabt.shelter.api.ShelterResponse;
import org.fabt.shelter.domain.DvAddressPolicy;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Evaluates DV shelter address redaction based on tenant policy and user context.
 * FVPSA compliance: address shared verbally during warm handoff only.
 */
@Component
public class DvAddressRedactionHelper {

    private final CoordinatorAssignmentRepository assignmentRepository;

    public DvAddressRedactionHelper(CoordinatorAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Determine if address should be redacted for this shelter/user/policy combination.
     * Non-DV shelters are never redacted.
     */
    public boolean shouldRedact(Shelter shelter, Authentication auth, DvAddressPolicy policy) {
        if (!shelter.isDvShelter()) {
            return false;
        }
        return !isAddressVisible(auth, policy, shelter.getId());
    }

    /**
     * Convenience: determine redaction for a shelter response when we don't have the Shelter object.
     */
    public boolean shouldRedact(boolean dvShelter, UUID shelterId, Authentication auth, DvAddressPolicy policy) {
        if (!dvShelter) {
            return false;
        }
        return !isAddressVisible(auth, policy, shelterId);
    }

    private boolean isAddressVisible(Authentication auth, DvAddressPolicy policy, UUID shelterId) {
        if (auth == null) return false;

        return switch (policy) {
            case ADMIN_AND_ASSIGNED -> isAdmin(auth) || isAssignedCoordinator(auth, shelterId);
            case ADMIN_ONLY -> isAdmin(auth);
            case ALL_DV_ACCESS -> true; // RLS already ensures dvAccess=true
            case NONE -> false;
        };
    }

    private boolean isAdmin(Authentication auth) {
        return hasRole(auth, "ROLE_PLATFORM_ADMIN") || hasRole(auth, "ROLE_COC_ADMIN");
    }

    private boolean isAssignedCoordinator(Authentication auth, UUID shelterId) {
        if (!hasRole(auth, "ROLE_COORDINATOR")) return false;
        UUID userId = UUID.fromString(auth.getName());
        return assignmentRepository.isAssigned(userId, shelterId);
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(role));
    }

    /**
     * Redact address fields from a ShelterResponse. Returns a new record with nulled location fields.
     */
    public static ShelterResponse redactAddress(ShelterResponse response) {
        return new ShelterResponse(
                response.id(),
                response.name(),
                null, // addressStreet
                null, // addressCity
                null, // addressState
                null, // addressZip
                response.phone(), // keep phone for warm handoff
                null, // latitude
                null, // longitude
                response.dvShelter(),
                response.createdAt(),
                response.updatedAt()
        );
    }
}
