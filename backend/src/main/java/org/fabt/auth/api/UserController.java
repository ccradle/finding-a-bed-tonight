package org.fabt.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.PasswordService;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;

    public UserController(UserRepository userRepository, PasswordService passwordService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
    }

    @Operation(
            summary = "Create a new user within the authenticated tenant",
            description = "Creates a user scoped to the caller's tenant (resolved from the JWT). " +
                    "Required fields: email (must be unique within the tenant) and displayName. " +
                    "Optional fields: password (if null, user can only authenticate via OAuth2), " +
                    "roles (string array — valid values include COORDINATOR, COC_ADMIN; defaults " +
                    "to empty), and dvAccess (boolean — grants access to domestic violence shelter " +
                    "records; defaults to false). The dvAccess flag is a sensitive permission: only " +
                    "set it for users who have completed DV confidentiality training. Returns 201 " +
                    "with the created user. Returns 400 if email or displayName is blank. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping
    @Transactional
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        User user = new User();
        // ID left null — database generates via gen_random_uuid()
        user.setTenantId(tenantId);
        user.setEmail(request.email());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(request.password() != null ? passwordService.hash(request.password()) : null);
        user.setRoles(request.roles() != null ? request.roles() : new String[0]);
        user.setDvAccess(request.dvAccess() != null && request.dvAccess());
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    @Operation(
            summary = "List all users in the authenticated tenant",
            description = "Returns all users belonging to the caller's tenant. The list is unfiltered " +
                    "and unpaginated. Each user record includes id, email, displayName, roles, " +
                    "dvAccess flag, and timestamps. Users from other tenants are never returned — " +
                    "tenant isolation is enforced server-side via the JWT's tenant claim. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<UserResponse>> listUsers() {
        UUID tenantId = TenantContext.getTenantId();
        List<UserResponse> users = userRepository.findByTenantId(tenantId).stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @Operation(
            summary = "Get a single user by ID within the authenticated tenant",
            description = "Returns the user with the specified UUID, provided the user belongs to " +
                    "the caller's tenant. Returns 404 (via NoSuchElementException) if the user does " +
                    "not exist or belongs to a different tenant — the error is intentionally " +
                    "indistinguishable to prevent cross-tenant enumeration. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "UUID of the user to retrieve") @PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        if (!user.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("User not found: " + id);
        }

        return ResponseEntity.ok(UserResponse.from(user));
    }

    @Operation(
            summary = "Update a user's profile, roles, or DV access",
            description = "Partially updates a user within the caller's tenant. Only non-null fields " +
                    "in the request body are applied — omit a field to leave it unchanged. Updatable " +
                    "fields: displayName (string), roles (string array — replaces the entire role " +
                    "list, not a merge), and dvAccess (boolean). Email and password cannot be changed " +
                    "through this endpoint. Returns the full updated user object. Returns 404 if the " +
                    "user does not exist or belongs to a different tenant. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(description = "UUID of the user to update") @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        if (!user.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("User not found: " + id);
        }

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.roles() != null) {
            user.setRoles(request.roles());
        }
        if (request.dvAccess() != null) {
            user.setDvAccess(request.dvAccess());
        }
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);
        return ResponseEntity.ok(UserResponse.from(saved));
    }
}
