package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ContactInfoController} covering
 * info-email-contact tasks §4.6, §4.7, §4.8.
 *
 * <p>Per {@code feedback_isolated_test_data}: each test uses freshly-uuid'd
 * tenants. Per {@code feedback_no_guessing}: full Spring context boot to
 * verify the SecurityConfig {@code /api/v1/public/**} permitAll matcher and
 * the YAML-declared bucket4j filter both behave correctly.
 *
 * <p>The platform contact email is pinned via
 * {@code @TestPropertySource(fabt.platform.contact-email=...)} so tests
 * have a deterministic value regardless of operator-local env state.
 */
@DisplayName("GET /api/v1/public/contact-info")
@TestPropertySource(properties = {
        "fabt.platform.contact-email=info@findabed.test",
        // Re-enable bucket4j for this class — lite test profile disables it
        // (see src/test/resources/application-lite.yml). The rateLimitOnBurst
        // test requires the YAML-declared rate-limit-public-contact-info filter
        // to actually intercept; AuthRateLimitingTest uses the same override.
        "bucket4j.enabled=true"
})
// Test method ordering: bucket4j buckets are keyed on client IP (always
// 127.0.0.1 from TestRestTemplate). With bucket4j.enabled=true the
// rate-limit-public-contact-info bucket is shared across every test in
// this class — and rateLimitOnBurst intentionally drains the 60-budget.
// Without explicit ordering JUnit Jupiter's default MethodName orderer
// ran rateLimitOnBurst BEFORE unauthed* tests (alphabetical), and those
// later tests got 429 from the still-empty bucket. @Order keeps every
// non-burst test ahead of the drain. The bucket TTL (after-write=1m in
// application.conf) makes this isolation only matter within a single
// minute; CI cold-start typically completes the class in <1 minute.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContactInfoControllerTest extends BaseIntegrationTest {

    private static final String PLATFORM_EMAIL = "info@findabed.test";

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantAId;
    private String tenantASlug;
    private HttpHeaders tenantAHeaders;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantASlug = "contactinfo-a-" + suffix;
        Tenant tenantA = authHelper.setupTestTenant(tenantASlug);
        tenantAId = tenantA.getId();
        User cocAdminA = authHelper.setupUserWithDvAccess(
                "contactinfo-cocadmin-a-" + suffix + "@test.fabt.org",
                "ContactInfo Tenant A Admin",
                new String[]{"COC_ADMIN"});
        tenantAHeaders = authHelper.headersForUser(cocAdminA);
    }

    // ----- Response shape (§4.2) --------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Unauthed — platform-only response, tenant=null")
    void unauthedPlatformOnly() {
        ResponseEntity<Map<String, Object>> response = get(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) response.getBody().get("platform");
        assertThat(platform).containsEntry("email", PLATFORM_EMAIL);
        // Spec §4.2 specifies {"tenant": null} for unauthed callers but the
        // serialized form may either include the explicit null OR omit the key
        // entirely depending on the platform ObjectMapper's null-handling. Both
        // are functionally identical from the frontend's perspective (§5.2's
        // hook treats `tenantEmail || platformEmail || null` — absent and null
        // both fall through). Assert the looser invariant: the response MUST
        // NOT carry a non-null tenant block for unauthed traffic.
        assertThat(response.getBody().get("tenant"))
                .as("Unauthed response MUST NOT expose any tenant block — privacy + auth-state separation")
                .isNull();
    }

    @Test
    @Order(1)
    @DisplayName("Authed with no tenant override — platform + tenant block (email=null)")
    void authedWithNoTenantOverride() {
        clearContactEmailKey(tenantAId);

        ResponseEntity<Map<String, Object>> response = get(tenantAHeaders, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("tenant");
        @SuppressWarnings("unchecked")
        Map<String, Object> tenant = (Map<String, Object>) response.getBody().get("tenant");
        assertThat(tenant)
                .as("Authed callers always receive a non-null tenant block")
                .isNotNull();
        assertThat(tenant).containsEntry("slug", tenantASlug);
        assertThat(tenant.get("email"))
                .as("email=null signals 'inherit platform default'")
                .isNull();
    }

    @Test
    @Order(1)
    @DisplayName("Authed with tenant override — platform + tenant block (email=value)")
    void authedWithTenantOverride() {
        // Pre-clear dv_policy_enabled (TestAuthHelper.setupTestTenant
        // defaults it to true) so the H1-Casey suppression does NOT fire —
        // this test exercises the standard authed-read happy path on a
        // non-DV tenant. The DV-suppression behavior is covered by
        // dvPolicyTenantSuppressesContactEmail.
        clearDvPolicyKey(tenantAId);
        seedContactEmailDirectly(tenantAId, "tenant-a-override@example.com");

        ResponseEntity<Map<String, Object>> response = get(tenantAHeaders, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> tenant = (Map<String, Object>) response.getBody().get("tenant");
        assertThat(tenant).containsEntry("slug", tenantASlug);
        assertThat(tenant).containsEntry("email", "tenant-a-override@example.com");
    }

    // ----- Cache headers split by auth state (§4.3) -------------------------

    @Test
    @Order(1)
    @DisplayName("Unauthed has Cache-Control: public + ETag, no Vary")
    void unauthedCacheHeaders() {
        ResponseEntity<Map<String, Object>> response = get(null, null);

        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl)
                .as("Unauthed response MUST be public-cacheable")
                .contains("public");
        assertThat(cacheControl)
                .as("Unauthed response MUST set max-age=3600 (1 hour)")
                .contains("max-age=3600");
        assertThat(response.getHeaders().getETag())
                .as("ETag MUST be set so 304 conditional GET works")
                .isNotBlank();
        // The Spring CorsFilter contributes its own Vary entries
        // (Origin, Access-Control-Request-*) on every response. Those are
        // necessary for correct CORS-aware caching and unrelated to our
        // auth-state Vary policy. Assert the more precise invariant: the
        // controller MUST NOT add Authorization to the Vary list for unauthed
        // responses — that would unnecessarily fragment shared-cache hits.
        assertThat(response.getHeaders().getVary().stream()
                        .map(String::toLowerCase).toList())
                .as("Unauthed response MUST NOT carry Vary: Authorization "
                        + "(unnecessary fragmentation of shared-cache hits)")
                .doesNotContain("authorization");
    }

    @Test
    @Order(1)
    @DisplayName("Authed has Cache-Control: private + Vary: Authorization + ETag")
    void authedCacheHeaders() {
        ResponseEntity<Map<String, Object>> response = get(tenantAHeaders, null);

        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl)
                .as("Authed response MUST be private (tenant-varying body, never in shared caches)")
                .contains("private");
        assertThat(cacheControl).contains("max-age=3600");
        assertThat(response.getHeaders().getETag()).isNotBlank();
        // Vary list values are case-insensitive per RFC 7231; AssertJ's
        // ListAssert lacks containsIgnoringCase, so normalize both sides.
        assertThat(response.getHeaders().getVary().stream()
                        .map(String::toLowerCase).toList())
                .as("Authed response MUST set Vary: Authorization "
                        + "(belt-and-suspenders for private caches)")
                .contains("authorization");
    }

    @Test
    @Order(1)
    @DisplayName("ETag for unauthed and authed responses differ")
    void etagDiffersByAuthState() {
        clearContactEmailKey(tenantAId);

        String unauthedEtag = get(null, null).getHeaders().getETag();
        String authedEtag = get(tenantAHeaders, null).getHeaders().getETag();

        assertThat(unauthedEtag).isNotBlank();
        assertThat(authedEtag).isNotBlank();
        assertThat(unauthedEtag)
                .as("Unauthed body has no tenant block; authed has tenant.{slug,email} — "
                        + "ETags MUST differ to prevent client cache from re-using one for the other")
                .isNotEqualTo(authedEtag);
    }

    @Test
    @Order(1)
    @DisplayName("304 Not Modified when If-None-Match matches current ETag")
    void conditionalGet304() {
        ResponseEntity<Map<String, Object>> first = get(null, null);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();

        ResponseEntity<Map<String, Object>> second = get(null, etag);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody())
                .as("304 response MUST omit body per RFC 7232")
                .isNull();
        assertThat(second.getHeaders().getETag())
                .as("304 still carries ETag header")
                .isEqualTo(etag);
    }

    // ----- DV-policy suppression (warroom round 1 H1-Casey) -----------------

    @Test
    @Order(1)
    @DisplayName("DV-policy=true tenant — read endpoint suppresses tenant.email even when JSONB has stale value")
    void dvPolicyTenantSuppressesContactEmail() {
        // Setup: seed a stale contact.email AS IF it were written before the
        // DV-policy flag was enabled (the §3 PATCH guard would forbid this
        // sequence today, but the read endpoint must still defend against
        // historical state where the flag was flipped on after the email
        // was already set). setupTestTenant defaults dv_policy_enabled=true,
        // so we don't need to explicitly enable it here.
        seedContactEmailDirectly(tenantAId, "stale-pre-dv@example.com");
        assertThat(readDvPolicyKey(tenantAId)).isEqualTo("true");

        ResponseEntity<Map<String, Object>> response = get(tenantAHeaders, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> tenant = (Map<String, Object>) response.getBody().get("tenant");
        assertThat(tenant).containsEntry("slug", tenantASlug);
        assertThat(tenant.get("email"))
                .as("DV-policy=true MUST suppress tenant.email on read regardless of persisted value — "
                        + "defense-in-depth against historical writes that predate the flag flip")
                .isNull();
        // Confirm the stale value is still in the JSONB — we are NOT mutating
        // state on read, just hiding it from the public surface.
        assertThat(readContactEmailKey(tenantAId))
                .as("Read-side suppression MUST NOT mutate the persisted value")
                .isEqualTo("stale-pre-dv@example.com");
    }

    // ----- Tenant scoping (§4.7) --------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Tenant-scoping: tenant A response carries only tenant A slug + email — no leak of tenant B")
    void tenantScopingNoCrossTenantLeak() {
        // Setup: seed tenant A with one email, tenant B with a different email,
        // then issue authed reads from each — assert each only sees its own.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("contactinfo-b-" + suffix);
        UUID tenantBId = tenantB.getId();
        String tenantBSlug = tenantB.getSlug();
        User cocAdminB = authHelper.setupUserWithDvAccessInTenant(tenantBId,
                "contactinfo-cocadmin-b-" + suffix + "@test.fabt.org",
                "ContactInfo Tenant B Admin",
                new String[]{"COC_ADMIN"});
        HttpHeaders tenantBHeaders = authHelper.headersForUser(cocAdminB);

        // Pre-clear dv_policy_enabled on both (setupTestTenant /
        // setupSecondaryTenant default it to true) so the H1-Casey
        // suppression does NOT mask the test signal — this test verifies
        // tenant-scoping isolation on the read path's standard branch.
        clearDvPolicyKey(tenantAId);
        clearDvPolicyKey(tenantBId);
        seedContactEmailDirectly(tenantAId, "tenant-a-secret@example.com");
        seedContactEmailDirectly(tenantBId, "tenant-b-secret@example.com");

        // Read as tenant A's COC_ADMIN.
        ResponseEntity<Map<String, Object>> responseA = get(tenantAHeaders, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantA = (Map<String, Object>) responseA.getBody().get("tenant");
        assertThat(tenantA).containsEntry("slug", tenantASlug);
        assertThat(tenantA).containsEntry("email", "tenant-a-secret@example.com");
        // No leak of tenant B's slug or email anywhere in the response.
        String bodyAStr = responseA.getBody().toString();
        assertThat(bodyAStr).doesNotContain(tenantBSlug);
        assertThat(bodyAStr).doesNotContain("tenant-b-secret@example.com");

        // Read as tenant B's COC_ADMIN.
        ResponseEntity<Map<String, Object>> responseB = get(tenantBHeaders, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantBOut = (Map<String, Object>) responseB.getBody().get("tenant");
        assertThat(tenantBOut).containsEntry("slug", tenantBSlug);
        assertThat(tenantBOut).containsEntry("email", "tenant-b-secret@example.com");
        String bodyBStr = responseB.getBody().toString();
        assertThat(bodyBStr).doesNotContain(tenantASlug);
        assertThat(bodyBStr).doesNotContain("tenant-a-secret@example.com");
    }

    // ----- Rate limit (§4.8) -------------------------------------------------

    @Test
    @Order(99)
    @DisplayName("Burst 70 requests from one IP — at least 10 are 429")
    void rateLimitOnBurst() {
        // YAML filter rate-limit-public-contact-info budget = 60 req/min/IP.
        // 70 requests in <1s consumes the bucket, ≥10 should 429. The bucket4j
        // filter intercepts before the controller method runs and emits its
        // configured response body + status. We assert the 429 count is at
        // least 10 (allowing for slight variance if any tests above primed the
        // bucket within the same minute window — TestRestTemplate on a random
        // port keeps the bucket key tied to localhost, shared across all tests
        // in this class).
        int total = 70;
        int rateLimitedCount = 0;
        for (int i = 0; i < total; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/public/contact-info",
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rateLimitedCount++;
            }
        }
        // Math: 60-budget bucket, refill 1 token/sec, 70 req in T seconds →
        // expect (10 - T) × 429 in worst case. Local run was 0.694s producing
        // exactly 10 × 429. Asserting ≥10 is the assertion the budget contract
        // implies. If a future CI runner is so slow that the burst stretches
        // beyond ~1s, the proper fix is a tighter test (e.g. 200-burst against
        // a 10-budget pinned via @TestPropertySource) — NOT relaxing the
        // threshold here, which would silently tolerate budget-bug regressions.
        assertThat(rateLimitedCount)
                .as("60 req/min budget; 70-burst MUST produce >=10 rate-limited responses")
                .isGreaterThanOrEqualTo(10);
    }

    // ----- helpers ----------------------------------------------------------

    private ResponseEntity<Map<String, Object>> get(HttpHeaders authHeaders, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (authHeaders != null) {
            headers.addAll(authHeaders);
        }
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return restTemplate.exchange(
                "/api/v1/public/contact-info",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private void clearContactEmailKey(UUID tenantId) {
        // Remove the contact subtree entirely so the read returns null.
        jdbc.update(
                "UPDATE tenant SET config = config - 'contact' WHERE id = ?",
                tenantId);
    }

    private String readContactEmailKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config -> 'contact' ->> 'email' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    private String readDvPolicyKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config ->> 'dv_policy_enabled' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    private void clearDvPolicyKey(UUID tenantId) {
        jdbc.update(
                "UPDATE tenant SET config = config - 'dv_policy_enabled' WHERE id = ?",
                tenantId);
    }

    private void seedContactEmailDirectly(UUID tenantId, String email) {
        // Top-level concat replaces 'contact' wholesale; mirrors the helper
        // used by ContactEmailControllerTest.
        jdbc.update(
                "UPDATE tenant SET config = config || jsonb_build_object('contact', jsonb_build_object('email', ?::text)) WHERE id = ?",
                email, tenantId);
    }

}
