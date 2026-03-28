package org.fabt.auth;

import java.util.Map;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.PasswordService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPasswordResetTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
    }

    @Test
    void admin_can_reset_user_password() {
        User coordinator = authHelper.setupCoordinatorUser();
        HttpHeaders adminHeaders = authHelper.adminHeaders();

        String body = """
                {"newPassword": "TempPassword12!"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/reset-password",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("message")).isEqualTo("Password reset. The user will need to sign in again.");

        // Verify password was actually changed
        User updated = userRepository.findById(coordinator.getId()).orElseThrow();
        assertThat(passwordService.matches("TempPassword12!", updated.getPasswordHash())).isTrue();
        assertThat(updated.getPasswordChangedAt()).isNotNull();

        // Restore original password
        updated.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
        updated.setPasswordChangedAt(null);
        userRepository.save(updated);
    }

    @Test
    void coc_admin_can_reset_user_password() {
        User coordinator = authHelper.setupCoordinatorUser();
        HttpHeaders cocAdminHeaders = authHelper.cocAdminHeaders();

        String body = """
                {"newPassword": "TempPassword12!"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/reset-password",
                HttpMethod.POST, new HttpEntity<>(body, cocAdminHeaders), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Restore original password
        User updated = userRepository.findById(coordinator.getId()).orElseThrow();
        updated.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
        updated.setPasswordChangedAt(null);
        userRepository.save(updated);
    }

    @Test
    void coordinator_cannot_reset_passwords() {
        User admin = authHelper.setupAdminUser();
        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();

        String body = """
                {"newPassword": "TempPassword12!"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + admin.getId() + "/reset-password",
                HttpMethod.POST, new HttpEntity<>(body, coordinatorHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_cannot_reset_password_for_user_in_different_tenant() {
        // Create a second tenant with its own user
        String otherSlug = "other-tenant-" + System.currentTimeMillis();
        Tenant otherTenant = tenantService.create("Other Tenant", otherSlug);

        User otherUser = new User();
        otherUser.setTenantId(otherTenant.getId());
        otherUser.setEmail("user@other.fabt.org");
        otherUser.setDisplayName("Other Tenant User");
        otherUser.setPasswordHash(passwordService.hash("SomePassword12!"));
        otherUser.setRoles(new String[]{"OUTREACH_WORKER"});
        otherUser.setDvAccess(false);
        otherUser.setCreatedAt(java.time.Instant.now());
        otherUser.setUpdatedAt(java.time.Instant.now());
        otherUser = userRepository.save(otherUser);

        HttpHeaders adminHeaders = authHelper.adminHeaders();

        String body = """
                {"newPassword": "TempPassword12!"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + otherUser.getId() + "/reset-password",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders), String.class);

        // Should return 404 (not found in caller's tenant), not 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void admin_reset_rejects_weak_password() {
        User coordinator = authHelper.setupCoordinatorUser();
        HttpHeaders adminHeaders = authHelper.adminHeaders();

        String body = """
                {"newPassword": "short"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/reset-password",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
