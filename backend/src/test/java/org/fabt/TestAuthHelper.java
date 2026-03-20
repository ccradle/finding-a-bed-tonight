package org.fabt;

import java.time.Instant;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class TestAuthHelper {

    public static final String TEST_PASSWORD = "TestPassword123!";
    public static final String ADMIN_EMAIL = "admin@test.fabt.org";
    public static final String COC_ADMIN_EMAIL = "cocadmin@test.fabt.org";
    public static final String COORDINATOR_EMAIL = "coordinator@test.fabt.org";
    public static final String OUTREACH_EMAIL = "outreach@test.fabt.org";

    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    private Tenant testTenant;
    private User adminUser;
    private User cocAdminUser;
    private User coordinatorUser;
    private User outreachWorkerUser;

    public TestAuthHelper(TenantService tenantService,
                          UserRepository userRepository,
                          PasswordService passwordService,
                          JwtService jwtService) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    /**
     * Creates a test tenant with the given slug, or returns the existing one.
     * Must be called before creating users.
     */
    public Tenant setupTestTenant(String slug) {
        testTenant = tenantService.findBySlug(slug).orElse(null);
        if (testTenant == null) {
            testTenant = tenantService.create("Test Tenant " + slug, slug);
        }
        return testTenant;
    }

    /**
     * Ensures the default "test-tenant" exists and sets it as the active test tenant.
     */
    public Tenant setupTestTenant() {
        return setupTestTenant("test-tenant");
    }

    public User setupAdminUser() {
        ensureTenant();
        adminUser = findOrCreateUser(ADMIN_EMAIL, "Platform Admin",
                new String[]{"PLATFORM_ADMIN"}, false);
        return adminUser;
    }

    public User setupCocAdminUser() {
        ensureTenant();
        cocAdminUser = findOrCreateUser(COC_ADMIN_EMAIL, "CoC Admin",
                new String[]{"COC_ADMIN"}, false);
        return cocAdminUser;
    }

    public User setupCoordinatorUser() {
        ensureTenant();
        coordinatorUser = findOrCreateUser(COORDINATOR_EMAIL, "Coordinator",
                new String[]{"COORDINATOR"}, false);
        return coordinatorUser;
    }

    public User setupOutreachWorkerUser() {
        ensureTenant();
        outreachWorkerUser = findOrCreateUser(OUTREACH_EMAIL, "Outreach Worker",
                new String[]{"OUTREACH_WORKER"}, false);
        return outreachWorkerUser;
    }

    public User setupUserWithDvAccess(String email, String displayName, String[] roles) {
        ensureTenant();
        return findOrCreateUser(email, displayName, roles, true);
    }

    public HttpHeaders adminHeaders() {
        if (adminUser == null) {
            setupAdminUser();
        }
        return headersForUser(adminUser);
    }

    public HttpHeaders cocAdminHeaders() {
        if (cocAdminUser == null) {
            setupCocAdminUser();
        }
        return headersForUser(cocAdminUser);
    }

    public HttpHeaders coordinatorHeaders() {
        if (coordinatorUser == null) {
            setupCoordinatorUser();
        }
        return headersForUser(coordinatorUser);
    }

    public HttpHeaders outreachWorkerHeaders() {
        if (outreachWorkerUser == null) {
            setupOutreachWorkerUser();
        }
        return headersForUser(outreachWorkerUser);
    }

    public HttpHeaders headersForUser(User user) {
        String token = jwtService.generateAccessToken(user);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    public UUID getTestTenantId() {
        ensureTenant();
        return testTenant.getId();
    }

    public Tenant getTestTenant() {
        ensureTenant();
        return testTenant;
    }

    public String getTestTenantSlug() {
        ensureTenant();
        return testTenant.getSlug();
    }

    public JwtService getJwtService() {
        return jwtService;
    }

    public PasswordService getPasswordService() {
        return passwordService;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    private void ensureTenant() {
        if (testTenant == null) {
            setupTestTenant();
        }
    }

    private User findOrCreateUser(String email, String displayName, String[] roles, boolean dvAccess) {
        return userRepository.findByTenantIdAndEmail(testTenant.getId(), email)
                .orElseGet(() -> {
                    User user = new User();
                    // ID left null — database generates via gen_random_uuid()
                    user.setTenantId(testTenant.getId());
                    user.setEmail(email);
                    user.setDisplayName(displayName);
                    user.setPasswordHash(passwordService.hash(TEST_PASSWORD));
                    user.setRoles(roles);
                    user.setDvAccess(dvAccess);
                    user.setCreatedAt(Instant.now());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                });
    }
}
