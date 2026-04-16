package org.fabt.shared.security;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates user-supplied outbound URLs against SSRF attack classes.
 *
 * <p>Design D12 from {@code cross-tenant-isolation-audit}: this validator
 * is the system-of-record for any URL that the platform dials on behalf
 * of a tenant. Applied to:
 * <ul>
 *   <li>Webhook subscription callback URLs ({@code SubscriptionService.validateCallbackUrl})</li>
 *   <li>OAuth2 provider test-connection / issuer URI ({@code TenantOAuth2ProviderService.create})</li>
 *   <li>HMIS vendor endpoint URLs ({@code HmisPushService})</li>
 * </ul>
 *
 * <h2>Three-layer validation</h2>
 *
 * <ol>
 *   <li><b>Static scheme + syntax check</b> — reject non-http/https, reject
 *       userinfo, reject non-ASCII hostnames post-IDNA. Cheap, catches
 *       malformed or obviously-malicious URLs.</li>
 *   <li><b>DNS resolution + IP category check at creation time</b>
 *       ({@link #validateAtCreation}) — reject RFC1918 (10/8, 172.16/12,
 *       192.168/16), loopback (127/8, ::1), link-local (169.254/16 —
 *       includes the 169.254.169.254 cloud-metadata IP, fe80::/10), ULA
 *       (fc00::/7), 0.0.0.0/8. Caller registers the URL; we resolve +
 *       block obvious private/metadata targets.</li>
 *   <li><b>Dial-time IP re-validation</b> ({@link #validateForDial}) — a
 *       custom pre-flight check that re-resolves the hostname immediately
 *       before the HTTP send and re-checks the IP. This is designed to
 *       mitigate the DNS rebinding class of bypass (TTL=0 records that
 *       resolve public at registration time and private at dial time —
 *       the pattern publicly documented in CVE-2026-27127, which
 *       URL-parse-only and creation-time-only validation miss).</li>
 * </ol>
 *
 * <p>The remaining TOCTOU window between our final validation call and the
 * HTTP client's own DNS resolution is microseconds (DNS cache hit typical)
 * and is accepted as a known limitation. A fully hermetic fix would
 * require injecting a custom {@code Socket} factory or {@code ProxySelector}
 * — out of scope for this change.</p>
 *
 * <p><b>Exception contract:</b> both {@code validateAtCreation} and
 * {@code validateForDial} throw {@link IllegalArgumentException} on a
 * blocked URL, carrying a {@code reason} string naming the blocking
 * category (e.g., {@code loopback}, {@code link-local}, {@code rfc1918},
 * {@code ula}, {@code unknown-host}, {@code bad-scheme}). Callers are
 * expected to map to HTTP 400 with an error code like
 * {@code webhook_url_blocked}.</p>
 */
@Component
public class SafeOutboundUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SafeOutboundUrlValidator.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Validate a URL at creation / registration time.
     *
     * @throws IllegalArgumentException if the URL is syntactically invalid,
     *         uses a disallowed scheme, resolves to a blocked IP category,
     *         or fails DNS resolution.
     */
    public void validateAtCreation(String url) {
        URI parsed = parseOrThrow(url);
        InetAddress resolved = resolveOrThrow(parsed);
        String blockReason = blockedCategory(resolved);
        if (blockReason != null) {
            log.warn("SSRF guard: blocked outbound URL at creation — {} resolves to {} ({})",
                    url, resolved.getHostAddress(), blockReason);
            throw new IllegalArgumentException(
                    "Outbound URL rejected at creation: " + parsed.getHost()
                            + " (" + blockReason + ")");
        }
    }

    /**
     * Validate a URL at dial time. Re-resolves DNS + re-checks IP.
     * Callers should invoke this immediately before each outbound HTTP send
     * to defeat DNS rebinding.
     *
     * @return the freshly-resolved {@link InetAddress} for the hostname
     * @throws IllegalArgumentException if the re-resolved IP falls into a
     *         blocked category (DNS rebinding detected) or the hostname no
     *         longer resolves
     */
    public InetAddress validateForDial(String url) {
        URI parsed = parseOrThrow(url);
        InetAddress resolved = resolveOrThrow(parsed);
        String blockReason = blockedCategory(resolved);
        if (blockReason != null) {
            log.warn("SSRF guard: blocked outbound URL at dial time — {} resolves to {} ({})",
                    url, resolved.getHostAddress(), blockReason);
            throw new IllegalArgumentException(
                    "Outbound URL rejected at dial time: " + parsed.getHost()
                            + " (" + blockReason + " — possible DNS rebinding)");
        }
        return resolved;
    }

    private URI parseOrThrow(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Outbound URL rejected: null or blank");
        }

        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Outbound URL rejected: invalid syntax (" + e.getMessage() + ")");
        }

        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("Outbound URL rejected: not absolute — " + url);
        }

        String scheme = parsed.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Outbound URL rejected: scheme must be http or https (got: " + scheme + ") — " + url);
        }

        if (parsed.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Outbound URL rejected: userinfo (user:password@) not allowed — " + url);
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Outbound URL rejected: missing host — " + url);
        }

        // Normalize via IDN to catch non-ASCII lookalike homograph attacks.
        try {
            IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Outbound URL rejected: host fails IDN normalization — " + url);
        }

        return parsed;
    }

    private InetAddress resolveOrThrow(URI parsed) {
        String host = parsed.getHost();
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "Outbound URL rejected: hostname does not resolve — " + host + " (unknown-host)");
        }
    }

    /**
     * Return a human-readable category name if the address is blocked, else null.
     * Visible for testing.
     */
    String blockedCategory(InetAddress addr) {
        if (addr == null) return "null-address";
        if (addr.isLoopbackAddress()) return "loopback";     // 127/8, ::1
        if (addr.isAnyLocalAddress()) return "any-local";     // 0.0.0.0, ::
        if (addr.isLinkLocalAddress()) return "link-local";   // 169.254/16 (includes 169.254.169.254 cloud metadata), fe80::/10
        if (addr.isSiteLocalAddress()) return "rfc1918";      // 10/8, 172.16/12, 192.168/16
        if (isUniqueLocalIPv6(addr)) return "ula";            // fc00::/7 (Java has no built-in)
        if (isMulticast(addr)) return "multicast";            // 224/4, ff00::/8
        return null;
    }

    private static boolean isUniqueLocalIPv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length != 16) return false;
        // fc00::/7 — first byte 1111 110x — check top 7 bits == 0xFC (binary 1111_1100)
        return (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isMulticast(InetAddress addr) {
        return addr.isMulticastAddress();
    }
}
