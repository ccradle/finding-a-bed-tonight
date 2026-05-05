package org.fabt.tenant.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.fabt.shared.platform.PlatformContactProperties;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Public read-only endpoint surfacing the resolved contact email for the
 * site footer + auth-page placeholders (info-email-contact OpenSpec change,
 * tasks §4.1-§4.5).
 *
 * <p><b>Path + auth:</b> {@code GET /api/v1/public/contact-info}, permitAll
 * via SecurityConfig's {@code /api/v1/public/**} matcher (the matcher was
 * added with this slice — info-email-contact §4 task tasks.md notes the
 * spec correction). Anonymous and authenticated traffic both reach the
 * method body; auth state is read from {@link TenantContext#getTenantId()}.
 *
 * <p><b>Response shape (§4.2):</b>
 * <ul>
 *   <li>Unauthed: {@code { "platform": { "email": "<value>" }, "tenant": null }}.
 *       The {@code platform.email} field is always present; an empty string
 *       signals "platform contact not configured" — the frontend (§6 / §7)
 *       falls back to a GH-issues link.</li>
 *   <li>Authed: {@code { "platform": { "email": ... }, "tenant": { "slug": "<caller-slug>", "email": "<value-or-null>" } }}.
 *       The {@code tenant} block is ALWAYS returned for authed callers, even
 *       when {@code email} is null — null signals "inherit platform default".</li>
 * </ul>
 *
 * <p><b>Cache headers split by auth state (§4.3 / B1):</b>
 * <ul>
 *   <li>Unauthed 200: {@code Cache-Control: public, max-age=3600} + ETag.
 *       NO {@code Vary: Authorization} — the response does not vary by
 *       Authorization for anonymous callers, and adding Vary would
 *       fragment shared-cache hits unnecessarily.</li>
 *   <li>Authed 200: {@code Cache-Control: private, max-age=3600} +
 *       {@code Vary: Authorization} + ETag. {@code private} keeps tenant-
 *       varying responses out of shared caches; {@code Vary: Authorization}
 *       is belt-and-suspenders for any private-cache implementation that
 *       does not already isolate by Authorization header.</li>
 * </ul>
 *
 * <p><b>ETag:</b> derived from a SHA-256 of the canonical JSON body,
 * truncated to 16 hex chars. The ETag is computed AFTER the body is built,
 * so it reflects the actual served bytes — no extra DB read per request.
 * Honors {@code If-None-Match} with 304 Not Modified (no body, ETag header
 * still set per RFC 7232).
 *
 * <p><b>Rate limit (§4.4 / H2):</b> Per-IP via the bucket4j-spring-boot-starter
 * {@code rate-limit-public-contact-info} filter declared in application.yml
 * (60 req/min default, X-Real-IP-aware cache key). On exhaustion the filter
 * intercepts the request before this method runs and returns
 * {@code 429 + X-Rate-Limit-Retry-After-Seconds}. <b>Spec correction:</b>
 * tasks.md §4.4 requested literal {@code Retry-After} header naming, but
 * the platform-wide bucket4j filter convention is
 * {@code X-Rate-Limit-Retry-After-Seconds} — the contact-info filter
 * matches the platform convention rather than diverging.
 *
 * <p><b>Counter (§4.5):</b> emits
 * {@code fabt.contact_info.requests_total{auth_state, outcome}} on every
 * served request. {@code auth_state} ∈ {@code anonymous|authenticated};
 * {@code outcome} ∈ {@code 200|304}. 429 is owned by the bucket4j filter
 * and is not counted here (filter-served responses never hit this method).
 */
@RestController
@RequestMapping("/api/v1/public")
public class ContactInfoController {

    private final PlatformContactProperties platformContact;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ContactInfoController(PlatformContactProperties platformContact,
                                 TenantService tenantService,
                                 ObjectMapper objectMapper,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.platformContact = platformContact;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Operation(
            summary = "Public contact-info read",
            description = "Returns the resolved contact email surface for the "
                    + "footer + auth-page placeholders. Unauthenticated callers "
                    + "see only the platform-level email; authenticated callers "
                    + "additionally see their own tenant's per-tenant override "
                    + "(or null = inherit platform default). Cached via ETag + "
                    + "Cache-Control; cache directives split by auth state."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact info; ETag + Cache-Control set per auth state"),
            @ApiResponse(responseCode = "304", description = "If-None-Match matched the current ETag — body omitted"),
            @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded (60 req/min default; bucket4j filter)")
    })
    @GetMapping("/contact-info")
    public ResponseEntity<Map<String, Object>> getContactInfo(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        UUID callerTenantId = TenantContext.getTenantId();
        boolean authenticated = callerTenantId != null;

        Map<String, Object> body = buildResponseBody(callerTenantId);
        String etag = computeEtag(body);

        // RFC 7232 weak-equality is acceptable for the static body shape
        // here; a literal-string compare against the quoted form covers it.
        // The header may carry the value with surrounding quotes per RFC, so
        // accept both forms.
        boolean ifNoneMatchHits = ifNoneMatch != null
                && (ifNoneMatch.equals(etag) || ifNoneMatch.equals(quote(etag)));

        HttpHeaders headers = new HttpHeaders();
        headers.setETag(quote(etag));
        if (authenticated) {
            // private + Vary: keeps tenant-varying body out of shared caches.
            headers.setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate());
            headers.setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));
        } else {
            // public + no Vary: anonymous response is cacheable across users.
            headers.setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
        }

        if (ifNoneMatchHits) {
            recordRequest(authenticated, "304");
            return ResponseEntity.status(304).headers(headers).build();
        }
        recordRequest(authenticated, "200");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private Map<String, Object> buildResponseBody(UUID callerTenantId) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("email", platformContact.contactEmail());
        root.put("platform", platform);

        if (callerTenantId == null) {
            root.put("tenant", null);
            return root;
        }

        Optional<Tenant> tenantOpt = tenantService.findById(callerTenantId);
        Map<String, Object> tenantBlock = new LinkedHashMap<>();
        // Slug is always the caller's tenant slug (the JWT-bound tenant);
        // there is no path-tenantId, so cross-tenant enumeration is
        // structurally impossible at this surface.
        tenantBlock.put("slug", tenantOpt.map(Tenant::getSlug).orElse(null));

        // DV-policy suppression on read (info-email-contact §4 warroom round 1
        // H1-Casey): the §3 PATCH endpoint forbids non-empty contact emails on
        // DV-flagged tenants, but does NOT sanitize values written BEFORE the
        // flag was enabled. A tenant that set contact.email while DV-policy
        // was off, then enabled DV-policy, would still surface the stale
        // email through this endpoint without a read-side guard. Belt-and-
        // suspenders: when dv_policy_enabled=true, ALWAYS return null for
        // tenant.email regardless of the persisted value. The frontend then
        // falls back to the platform-default inbox — which is the correct
        // behavior for a DV-flagged tenant.
        String tenantEmail = tenantOpt
                .filter(t -> !Tenant.isDvPolicyEnabled(t.getConfig(), objectMapper))
                .map(t -> Tenant.readContactEmail(t.getConfig(), objectMapper))
                .orElse(null);
        tenantBlock.put("email", tenantEmail);
        root.put("tenant", tenantBlock);
        return root;
    }

    /**
     * SHA-256 of the canonical JSON serialization of the body, truncated
     * to 16 hex chars (64 bits). 64 bits of entropy is more than sufficient
     * for cache validation — the threat model is accidental collision
     * between distinct response bodies, and 2^64 distinct bodies would have
     * to be served before a birthday-paradox collision became likely.
     */
    private String computeEtag(Map<String, Object> body) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical);
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM since 1.4; this is impossible.
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (JacksonException e) {
            // Body is built from String + Map<String, Object> only — Jackson
            // cannot fail to serialize. Defensive fallback uses the response's
            // hash code so the surface still emits a (weaker) ETag rather than
            // throwing 500.
            return Integer.toHexString(body.hashCode());
        }
    }

    private static String quote(String etag) {
        return "\"" + etag + "\"";
    }

    private void recordRequest(boolean authenticated, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("fabt.contact_info.requests_total")
                .tag("auth_state", authenticated ? "authenticated" : "anonymous")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
