package org.fabt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.fabt.auth.platform.PlatformJwtService;
import org.fabt.auth.platform.PlatformUser;
import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.auth.service.TotpService;
import org.fabt.shared.security.KidRegistryService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final KidRegistryService kidRegistryService;

    private Tenant testTenant;
    private User adminUser;
    private User cocAdminUser;
    private User coordinatorUser;
    private User outreachWorkerUser;

    public TestAuthHelper(TenantService tenantService,
                          UserRepository userRepository,
                          PasswordService passwordService,
                          JwtService jwtService,
                          KidRegistryService kidRegistryService) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.kidRegistryService = kidRegistryService;
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
        // Post-V87 (Phase G-4 / issue #141 / platform-admin-split-and-access-log
        // change): the V87 backfill grants COC_ADMIN to every PLATFORM_ADMIN-bearing
        // app_user row at migration time. Test fixtures created at runtime
        // (post-migration) must mirror that reality so the V87 backfill
        // assertions remain truthful AND so test users can hit endpoints
        // gated on COC_ADMIN once G-4.4 migrates them. PLATFORM_ADMIN is kept
        // for backward-compat with any test still asserting on it; both
        // coexist in the roles array. The cleanup release will drop
        // PLATFORM_ADMIN entirely from this fixture.
        adminUser = findOrCreateUser(ADMIN_EMAIL, "Platform Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"}, false);
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
     * Variant of {@link #setupSecondaryTenant(String)} that ALSO bootstraps
     * the JWT key material (gen-1 row in {@code tenant_key_material} +
     * fresh kid in {@code kid_to_tenant_key}) via
     * {@link KidRegistryService#findOrCreateActiveKid(UUID)}.
     *
     * <p>Required by tests that exercise the {@code TenantLifecycleService}
     * write paths (suspend / unsuspend / offboard / hardDelete) — the suspend
     * flow calls {@code TenantKeyRotationService.bumpJwtKeyGeneration} which
     * fails with {@code "no active key generation; cannot rotate"} if the
     * tenant has no kid bootstrapped yet. The lazy-bootstrap path that
     * {@code TenantService.create} relies on (first-login warm-up) does
     * NOT fire under these tests because they bypass login entirely.
     *
     * <p>Encapsulates the bootstrap so each lifecycle IT does not have to
     * inject {@link KidRegistryService} and remember the call. G-4.6 warroom
     * MEDIUM (Sam): the previous pattern of inlining the call in each IT
     * was a copy-paste smell — the helper makes the contract explicit and
     * reduces the chance the next IT author forgets and gets a confusing
     * "no active key generation" failure.
     */
    public Tenant setupSecondaryTenantWithKeyMaterial(String slug) {
        Tenant tenant = setupSecondaryTenant(slug);
        kidRegistryService.findOrCreateActiveKid(tenant.getId());
        return tenant;
    }

    /**
     * Round 5 §16.B test-support: enable {@code features.reentryMode} on the
     * given tenant. Tests that assert the API surfaces hold-attribution PII
     * fields ({@code heldForClientName}, {@code heldForClientDob},
     * {@code holdNotes}) MUST call this before minting JWTs — the v0.55
     * serialization gate strips those fields when the JWT claim is false,
     * which is the safe default for tenants without an explicit opt-in.
     *
     * <p>Call order matters: the flag is read at JWT-issuance time, so this
     * must run before {@code cocAdminAuthHeaders()} / similar token-mint
     * helpers. The 60s {@code JwtService.reentryModeCache} also keys on
     * tenantId, so each {@code setupTestTenant} (random slug per test) is
     * isolated from cross-test stale-cache pollution.
     */
    @SuppressWarnings("unchecked")
    public Tenant enableReentryMode(UUID tenantId) {
        Map<String, Object> config = new java.util.HashMap<>(tenantService.getConfig(tenantId));
        Map<String, Object> features = config.get("features") instanceof Map<?, ?> existing
                ? new java.util.HashMap<>((Map<String, Object>) existing)
                : new java.util.HashMap<>();
        features.put("reentryMode", true);
        config.put("features", features);
        return tenantService.updateConfig(tenantId, config);
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

    // -----------------------------------------------------------------
    // Platform-operator helpers (G-4.3 task 4.7a / R3)
    // -----------------------------------------------------------------
    //
    // Used by every IT in G-4.3 / G-4.4 / G-4.5 that needs to issue a
    // post-MFA platform JWT for an operator without going through the
    // full forced-MFA-on-first-login flow each time. Delegates schema
    // mutation to the V88 SECURITY DEFINER functions (fabt_app cannot
    // direct-UPDATE platform_user); JWT minting goes through the real
    // PlatformJwtService so the resulting token works against the
    // production iss-routed JwtDecoder dispatch.

    private static final UUID PLATFORM_BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    public static final String PLATFORM_TEST_PASSWORD = "PlatformTestPwd!@#$1234";

    @Autowired(required = false) private JdbcTemplate platformJdbc;
    @Autowired(required = false) private PlatformUserRepository platformUserRepository;
    @Autowired(required = false) private PlatformJwtService platformJwtService;
    @Autowired(required = false) private TotpService totpService;

    /**
     * Activates the V87 bootstrap platform_user (id = {@code 0fab}) to a
     * fully-enrolled state and returns a fresh access token. Convenience
     * for IT scenarios that need a working platform JWT to hit
     * {@code @PlatformAdminOnly} endpoints.
     *
     * <p>Idempotent — first resets to bootstrap state via the V88 reset
     * function, then activates with a known email + bcrypt password +
     * generated TOTP secret, then issues a real access token via
     * {@link PlatformJwtService#generateAccessToken}. Caller can call
     * {@link #resetPlatformBootstrap()} in {@code @AfterEach} to scrub
     * shared-context pollution.
     *
     * @return a fresh platform access token with {@code mfaVerified=true},
     *         {@code roles=[PLATFORM_OPERATOR]}, 15-min TTL
     */
    public PlatformOperatorFixture setupPlatformOperator(String email) {
        if (platformJdbc == null || platformUserRepository == null
                || platformJwtService == null || totpService == null) {
            throw new IllegalStateException(
                    "Platform-operator dependencies not autowired — "
                            + "test class must extend BaseIntegrationTest with the full Spring context");
        }
        // Reset to bootstrap, then activate.
        platformJdbc.queryForObject(
                "SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, PLATFORM_BOOTSTRAP_ID);
        platformJdbc.queryForObject(
                "SELECT platform_user_set_email(?::uuid, ?)",
                Object.class, PLATFORM_BOOTSTRAP_ID, email);
        String secret = totpService.generateSecret();
        platformJdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, ?, ?, ?, ?)",
                Object.class, PLATFORM_BOOTSTRAP_ID,
                passwordService.hash(PLATFORM_TEST_PASSWORD),
                secret,
                true,    // mfa_enabled = true (skip enrollment)
                false);  // account_locked = false (allow login)

        PlatformUser user = platformUserRepository.findById(PLATFORM_BOOTSTRAP_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_user lookup failed after activation"));
        String accessToken = platformJwtService.generateAccessToken(user);
        return new PlatformOperatorFixture(PLATFORM_BOOTSTRAP_ID, email,
                PLATFORM_TEST_PASSWORD, secret, accessToken);
    }

    /**
     * Default-email convenience.
     */
    public PlatformOperatorFixture setupPlatformOperator() {
        return setupPlatformOperator("ops-it@test.fabt.org");
    }

    /**
     * Convenience: activate the bootstrap platform_user and return a fully
     * formed {@link HttpHeaders} with the platform JWT bearer + the
     * {@code X-Platform-Justification} header populated. Designed for the
     * G-4.4 IT triage pass where every {@code @PlatformAdminOnly} endpoint
     * call needs both pieces.
     *
     * <p>The justification header MUST be ASCII (JDK 17+ http client rejects
     * non-ASCII) and at least 10 characters after trim.
     */
    public HttpHeaders platformOperatorHeaders(String justification) {
        PlatformOperatorFixture fixture = setupPlatformOperator();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", fixture.bearer());
        headers.set("X-Platform-Justification", justification);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    /**
     * Convenience: build a {@code X-Platform-Justification}-only header set
     * for tests that want to exercise the security/aspect rejection path
     * for non-platform-operator roles. Without the justification header the
     * JustificationValidationFilter rejects with 400 before security can
     * reach the role check; this helper builds the bypass for non-platform
     * JWTs so the security/aspect rejection (401/403) is what the test
     * actually observes.
     */
    public HttpHeaders withJustification(HttpHeaders headers, String justification) {
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(headers);
        copy.set("X-Platform-Justification", justification);
        return copy;
    }

    /**
     * Resets the bootstrap platform_user to the V87 INSERTed state.
     * IT classes call this in {@code @AfterEach} to keep the shared
     * Spring context clean for sibling test classes.
     */
    public void resetPlatformBootstrap() {
        if (platformJdbc != null) {
            platformJdbc.queryForObject(
                    "SELECT platform_user_reset_to_bootstrap(?::uuid)",
                    Boolean.class, PLATFORM_BOOTSTRAP_ID);
        }
    }

    /**
     * Activated platform_user fixture returned by
     * {@link #setupPlatformOperator(String)}. Carries the access token
     * directly so tests can build {@code Authorization: Bearer ...}
     * headers without re-querying.
     */
    public record PlatformOperatorFixture(UUID userId, String email,
                                           String plaintextPassword,
                                           String totpSecret,
                                           String accessToken) {
        /** Bearer header value ready for {@code HttpHeaders.set("Authorization", ...)}. */
        public String bearer() {
            return "Bearer " + accessToken;
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
