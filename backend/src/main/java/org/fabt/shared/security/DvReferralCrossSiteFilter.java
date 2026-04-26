package org.fabt.shared.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects {@code POST /api/v1/dv-referrals} when the {@code Sec-Fetch-Site}
 * request header indicates {@code cross-site} (G-4.5 §6.11).
 *
 * <p>This is an abuse-cost filter, not a security control: the
 * {@code Sec-Fetch-Site} header is set by the browser (Fetch Metadata Request
 * Headers, https://fetch.spec.whatwg.org/#sec-fetch-site-header) and any
 * non-browser caller can simply omit it or forge a different value. The
 * filter raises the bar slightly for casual cross-origin abuse (a malicious
 * page on attacker.example trying to fire DV referral creates against a
 * logged-in tenant user) but does NOT block determined attackers using curl
 * / Postman / a custom client. Marcus Webb's accuracy note: this is "raise
 * abuse cost slightly", not "block abuse" — depth-of-defense alongside the
 * bucket4j per-IP throttle (§6.7) and Prometheus alert on burst rate
 * (§6.8).</p>
 *
 * <p>Header semantics per the spec:</p>
 * <ul>
 *   <li>{@code same-origin} — request from same scheme/host/port. Allow.</li>
 *   <li>{@code same-site} — request from a same-registered-site origin
 *       (different subdomain). Allow.</li>
 *   <li>{@code none} — user-initiated navigation (typed URL, bookmark).
 *       Allow — though our endpoint is POST so this should be rare.</li>
 *   <li>{@code cross-site} — request from an origin that is NOT same-site.
 *       Reject 403.</li>
 *   <li>(absent) — older browsers or non-browser clients. Allow — we cannot
 *       distinguish a missing header from a browser that does not support
 *       the spec. Forging is trivial, so a strict-reject would also
 *       eliminate the dev/test loop without commensurate security gain.</li>
 * </ul>
 *
 * <p>Filter scope is intentionally narrow: only matches POST requests to
 * {@code /api/v1/dv-referrals} (exact path). Other DV referral sub-paths
 * ({@code /mine}, {@code /escalated}, {@code /{id}/claim}, etc.) and other
 * endpoints pass through unaffected. The 403 response carries a JSON body
 * matching the bucket4j-rejection shape so the frontend's existing
 * "service not available" error path renders consistently.</p>
 */
@Component
public class DvReferralCrossSiteFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DvReferralCrossSiteFilter.class);

    /**
     * Both forms accepted because Servlet containers do not normalize
     * trailing slashes on POST. A future curl- or script-driven attacker
     * adding {@code /} would otherwise bypass the filter (Riley warroom H2).
     * Browsers always submit without the trailing slash so the variant
     * never appears in legitimate traffic.
     */
    private static final Set<String> DV_REFERRAL_CREATE_PATHS = Set.of(
            "/api/v1/dv-referrals",
            "/api/v1/dv-referrals/");
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String CROSS_SITE_VALUE = "cross-site";
    private static final String REJECTION_BODY =
            "{\"error\":\"cross_site_blocked\","
                    + "\"message\":\"Cross-site DV referral submissions are not accepted.\","
                    + "\"status\":403}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !DV_REFERRAL_CREATE_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String secFetchSite = request.getHeader(SEC_FETCH_SITE_HEADER);
        if (secFetchSite != null && CROSS_SITE_VALUE.equalsIgnoreCase(secFetchSite.trim())) {
            log.warn("DV referral cross-site rejection: ip={}, origin={}, referer={}",
                    request.getRemoteAddr(),
                    request.getHeader("Origin"),
                    request.getHeader("Referer"));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(REJECTION_BODY);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
