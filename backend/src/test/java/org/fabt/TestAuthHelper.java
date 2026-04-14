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

    // ---------------------------------------------------------------------------
    // Cross-tenant helpers (Riley + Alex, notification-deep-linking Section 16
    // refactor + task 8.5 unblock). The default {@link #setupTestTenant} family
    // mutates {@link #testTenant}; these tenant-explicit helpers do NOT touch
    // that field so a test can hold references to multiple tenants at once.
    //
    // Follows the pattern established in {@code CrossTenantIsolationTest} —
    // tenants go through {@code TenantService}, users through
    // {@code UserRepository.save} with explicit tenantId, everything else
    // through the API with JWT-scoped auth. No raw SQL, no RLS workarounds —
    // {@code tenant}, {@code app_user}, and {@code coordinator_assignment}
    // have no RLS policies (tenant isolation is application-layer), and
    // {@code shelter} / {@code referral_token} RLS is naturally satisfied
    // when the caller's JWT carries the right tenantId + dvAccess claims.
    // ---------------------------------------------------------------------------

    /**
     * Create-or-find a secondary tenant identified by the supplied slug.
     * Unlike {@link #setupTestTenant(String)}, this does NOT mutate
     * {@link #testTenant}, so callers can hold a reference to the returned
     * Tenant independently of the default test-tenant state. Intended for
     * cross-tenant isolation tests.
     *
     * <p><b>Slug-collision note (Riley Cho, Section 16 warroom):</b> callers
     * SHOULD include a random suffix in the slug (e.g.
     * {@code UUID.randomUUID().toString().substring(0, 8)}) so repeated
     * test runs do not collide on the unique {@code tenant.slug} constraint.
     * This is safe today because the Testcontainers DB is single-container
     * and tests run sequentially. If JUnit Jupiter parallelism is ever
     * enabled ({@code junit.jupiter.execution.parallel.enabled=true}),
     * revisit — two threads calling this simultaneously with the same
     * slug would race into the {@code create} branch and one would 409.</p>
     */
    public Tenant setupSecondaryTenant(String slug) {
        return tenantService.findBySlug(slug)
                .orElseGet(() -> tenantService.create("Secondary Tenant " + slug, slug));
    }

    /**
     * Create-or-find a user inside an arbitrary tenant. Accepts the tenantId
     * explicitly so it can be used for users in secondary tenants during
     * cross-tenant isolation tests. Does NOT use any shared helper state.
     */
    public User createUserInTenant(UUID tenantId, String email, String displayName,
                                   String[] roles, boolean dvAccess) {
        return userRepository.findByTenantIdAndEmail(tenantId, email)
                .orElseGet(() -> {
                    User user = new User();
                    // ID left null — database generates via gen_random_uuid()
                    user.setTenantId(tenantId);
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

    /**
     * Convenience overload matching the shape of
     * {@link #setupUserWithDvAccess(String, String, String[])} but tenant-
     * explicit. Unblocks task 8.5 (cross-tenant 404 test for
     * {@code GET /api/v1/dv-referrals/{id}}) and Section 16 cross-tenant
     * routing-hint isolation test.
     */
    public User setupUserWithDvAccessInTenant(UUID tenantId, String email,
                                              String displayName, String[] roles) {
        return createUserInTenant(tenantId, email, displayName, roles, true);
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
