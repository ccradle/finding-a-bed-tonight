package org.fabt.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

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

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<UserResponse>> listUsers() {
        UUID tenantId = TenantContext.getTenantId();
        List<UserResponse> users = userRepository.findByTenantId(tenantId).stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        if (!user.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("User not found: " + id);
        }

        return ResponseEntity.ok(UserResponse.from(user));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<UserResponse> updateUser(@PathVariable UUID id,
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
