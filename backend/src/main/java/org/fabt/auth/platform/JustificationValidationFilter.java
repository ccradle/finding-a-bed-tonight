package org.fabt.auth.platform;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Phase G-4.3 — Spring servlet filter that validates the
 * {@code X-Platform-Justification} header on requests targeting any
 * controller method annotated {@link PlatformAdminOnly}.
 *
 * <p><b>Filter ordering (warroom M-RV2 fix-up):</b> the original tasks.md
 * §4.4 specified pre-Security ordering ("save auth work for malformed
 * requests"). During implementation we observed that as a
 * {@code @Component}-registered {@code OncePerRequestFilter} with
 * {@code @Order(-100)}, this filter actually runs AFTER Spring Security
 * (whose chain runs at {@code Ordered.HIGHEST_PRECEDENCE + 50 =
 * Integer.MIN_VALUE + 50}, far higher precedence than {@code -100}).
 * This is a DESIGN DRIFT we are deliberately keeping rather than
 * forcing pre-Security via a {@code FilterRegistrationBean}. The
 * post-Security ordering is preferable on security-posture grounds:
 *
 * <ul>
 *   <li><b>Pre-Security</b> (original): an unauthenticated probe to a
 *       {@code @PlatformAdminOnly} endpoint with no justification gets
 *       back 400 — leaks that the endpoint exists + has the
 *       {@code @PlatformAdminOnly} semantic.</li>
 *   <li><b>Post-Security</b> (current): the same probe gets back 401
 *       (Spring Security rejects first) — no info-disclosure. An
 *       authenticated caller with the wrong role gets 403 (Spring
 *       Security URL rule). Only an authenticated caller whose role
 *       passes the URL rule but whose request is missing/short on
 *       justification gets 400 — the design intent.</li>
 * </ul>
 *
 * <p>The performance trade-off is microseconds per rejected request,
 * which platform-admin endpoints don't have at meaningful volume anyway.
 *
 * <p>Triage-pass-2 pinning probe
 * ({@code PlatformAdminAccessAspectTest#unauthenticatedWithoutJustificationRejectedAtFilter}):
 * an anonymous request without the justification header receives 401
 * (Spring Security URL rule rejects), proving the post-Security
 * ordering still holds. If this assertion ever flips to 400, the
 * filter ordering changed and this javadoc + the warroom design
 * decision must be re-evaluated.
 *
 * <p>Practical consequences for tests with legacy {@code PLATFORM_ADMIN}-
 * bearing fixtures: those users pass the {@code /api/v1/tenants/**}
 * URL rule (which admits {@code hasAnyRole("PLATFORM_OPERATOR",
 * "PLATFORM_ADMIN")} during the deprecation window), so the filter is
 * the next gate. Negative-auth assertions for those fixtures MUST
 * include the justification header, or the filter rejects with 400
 * before the method-level {@code @PreAuthorize} can issue the 403 the
 * test wants to observe. Use {@code TestAuthHelper.withJustification(
 * headers, reason)} or {@code platformOperatorHeaders(reason)}.
 *
 * <p>Decision 10 ({@code X-Platform-Justification} is operator-asserted
 * documentation, NOT server-validated authority): the filter only checks
 * that SOMETHING was provided + minimum length. The text is operator-
 * authored and lands verbatim in the {@code platform_admin_access_log}
 * row for compliance review.
 *
 * <p>Resolution path is via Spring's
 * {@link RequestMappingHandlerMapping#getHandler(HttpServletRequest)} —
 * the same machinery the dispatcher uses to route to the controller. The
 * filter inspects the resolved {@link HandlerMethod} for the annotation
 * and skips its logic for any non-{@code @PlatformAdminOnly} path
 * (everything else, including static resources, error pages, the actuator
 * surface, and the platform-auth endpoints themselves).
 *
 * <p>Limit and rationale:
 * <ul>
 *   <li>Header MUST be present.</li>
 *   <li>{@code length(trim(value)) >= 10} — minimum 10 chars after trim.
 *       Anything less (e.g. "ok", "needed") is operationally noise and
 *       provides no compliance value.</li>
 *   <li>Header value is NOT validated for content quality (Marcus's
 *       hard line: server cannot reliably judge whether a justification
 *       is "real"; the value of the field is in producing an artifact
 *       for human review).</li>
 * </ul>
 */
@Component
@Order(-100)
public class JustificationValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JustificationValidationFilter.class);

    private static final String HEADER = "X-Platform-Justification";
    private static final int MIN_LENGTH = 10;
    private static final String JSON_BODY_MISSING =
            "{\"error\":\"missing_justification\",\"message\":\"X-Platform-Justification header is required for platform-admin endpoints.\",\"status\":400}";
    private static final String JSON_BODY_TOO_SHORT =
            "{\"error\":\"justification_too_short\",\"message\":\"X-Platform-Justification must be at least 10 characters.\",\"status\":400}";

    private final RequestMappingHandlerMapping handlerMapping;
    private final MeterRegistry meterRegistry;

    /**
     * Spring Boot registers MULTIPLE {@link RequestMappingHandlerMapping}
     * beans — the standard MVC one (named {@code requestMappingHandlerMapping})
     * and Actuator's controller-endpoint one
     * ({@code controllerEndpointHandlerMapping}). We qualify by the
     * standard MVC bean name; the actuator bean only resolves
     * {@code /actuator/**} paths and would not see {@code @PlatformAdminOnly}
     * controllers anyway, but the autowire resolver fails on ambiguity
     * regardless.
     */
    public JustificationValidationFilter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.handlerMapping = handlerMapping;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (!isPlatformAdminOnlyEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String value = request.getHeader(HEADER);
        if (value == null) {
            reject(response, 400, JSON_BODY_MISSING);
            emitRejectionMetric("missing");
            log.debug("Platform-admin request rejected: missing {}", HEADER);
            return;
        }
        if (value.trim().length() < MIN_LENGTH) {
            reject(response, 400, JSON_BODY_TOO_SHORT);
            emitRejectionMetric("too_short");
            log.debug("Platform-admin request rejected: {} too short ({} chars)",
                    HEADER, value.trim().length());
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the request to its controller {@link HandlerMethod} and
     * checks for the {@link PlatformAdminOnly} annotation. Skips for non-
     * controller paths (static resources, error pages) where the handler
     * resolution returns null or a non-{@code HandlerMethod} chain.
     *
     * <p>{@code RequestMappingHandlerMapping.getHandler} is exception-free
     * for unmatched paths (returns null); we treat any exception as
     * "not-platform-admin" defensively to avoid filter-side failures
     * blocking legitimate traffic.
     */
    private boolean isPlatformAdminOnlyEndpoint(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = handlerMapping.getHandler(request);
            if (chain == null) {
                return false;
            }
            Object handler = chain.getHandler();
            if (!(handler instanceof HandlerMethod hm)) {
                return false;
            }
            return hm.getMethodAnnotation(PlatformAdminOnly.class) != null;
        } catch (Exception e) {
            log.debug("JustificationValidationFilter — handler resolution threw: {}", e.getMessage());
            return false;
        }
    }

    private void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
    }

    private void emitRejectionMetric(String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("fabt.platform.admin.justification.rejected")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
