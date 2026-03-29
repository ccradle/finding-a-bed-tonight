package org.fabt.auth.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
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
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User saved = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    @Operation(
            summary = "List all users in the authenticated tenant",
            description = "Returns all users belonging to the caller's tenant. Each user record " +
                    "includes id, email, displayName, roles, dvAccess flag, status, and timestamps. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<List<UserResponse>> listUsers() {
        List<UserResponse> users = userService.listUsers().stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @Operation(
            summary = "Get a single user by ID within the authenticated tenant",
            description = "Returns the user with the specified UUID, provided the user belongs to " +
                    "the caller's tenant. Returns 404 if the user does not exist or belongs to a " +
                    "different tenant. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "UUID of the user to retrieve") @PathVariable UUID id) {
        return ResponseEntity.ok(UserResponse.from(userService.getUser(id)));
    }

    @Operation(
            summary = "Update a user's profile, roles, or DV access",
            description = "Partially updates a user within the caller's tenant. Only non-null fields " +
                    "in the request body are applied. Updatable fields: displayName, email, roles " +
                    "(replaces entire list), dvAccess. Role or dvAccess changes increment " +
                    "tokenVersion, invalidating the user's existing JWTs. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(description = "UUID of the user to update") @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID actorUserId = (UUID) authentication.getPrincipal();
        String ipAddress = httpRequest.getRemoteAddr();
        User saved = userService.updateUser(id, request, actorUserId, ipAddress);
        return ResponseEntity.ok(UserResponse.from(saved));
    }

    @Operation(
            summary = "Deactivate or reactivate a user account",
            description = "Sets the user's status to DEACTIVATED or ACTIVE. Deactivated users " +
                    "cannot log in and their existing JWTs are immediately invalidated via " +
                    "tokenVersion increment. Deactivation also disconnects any active SSE " +
                    "notification stream. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PatchMapping("/{id}/status")
    public ResponseEntity<UserResponse> changeStatus(
            @Parameter(description = "UUID of the user") @PathVariable UUID id,
            @RequestBody StatusChangeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID actorUserId = (UUID) authentication.getPrincipal();
        String ipAddress = httpRequest.getRemoteAddr();

        User saved = switch (request.status()) {
            case "DEACTIVATED" -> userService.deactivateUser(id, actorUserId, ipAddress);
            case "ACTIVE" -> userService.reactivateUser(id, actorUserId, ipAddress);
            default -> throw new IllegalArgumentException("Invalid status: " + request.status()
                    + ". Valid values: ACTIVE, DEACTIVATED");
        };

        return ResponseEntity.ok(UserResponse.from(saved));
    }

    public record StatusChangeRequest(String status) {}
}
