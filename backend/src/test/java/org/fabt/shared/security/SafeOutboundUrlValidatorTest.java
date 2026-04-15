package org.fabt.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SafeOutboundUrlValidator} — the SSRF guard
 * introduced in Phase 2.14 of {@code cross-tenant-isolation-audit}.
 *
 * <p>Covers the three-layer validation contract per design D12:</p>
 * <ul>
 *   <li>Layer 1 — scheme/syntax (reject non-http/https, userinfo, malformed)</li>
 *   <li>Layer 2 — DNS + IP category (reject RFC1918, loopback, link-local, ULA, cloud-metadata)</li>
 *   <li>Layer 3 — dial-time re-validation (same checks as layer 2, called before send)</li>
 * </ul>
 */
@DisplayName("SafeOutboundUrlValidator — SSRF guard contract (D12)")
class SafeOutboundUrlValidatorTest {

    private final SafeOutboundUrlValidator validator = new SafeOutboundUrlValidator();

    // -----------------------------------------------------------------
    // Layer 1 — scheme + syntax
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://example.com/",
            "ftp://example.com/",
            "jar:file:///tmp/evil.jar!/",
            "javascript:alert(1)",
    })
    @DisplayName("Non-http/https schemes are rejected")
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> validator.validateAtCreation(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme must be http or https");
    }

    @Test
    @DisplayName("URLs with userinfo (user:password@) are rejected")
    void rejectsUserInfo() {
        assertThatThrownBy(() -> validator.validateAtCreation("http://user:pass@example.com/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    @ParameterizedTest
    @ValueSource(strings = { "not-a-url", "http://", "", "   " })
    @DisplayName("Malformed or empty URLs are rejected")
    void rejectsMalformed(String url) {
        assertThatThrownBy(() -> validator.validateAtCreation(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Non-absolute URLs are rejected")
    void rejectsNonAbsolute() {
        assertThatThrownBy(() -> validator.validateAtCreation("/path/only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not absolute");
    }

    // -----------------------------------------------------------------
    // Layer 2 — DNS + IP category
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            // AWS / Azure / GCP cloud metadata — the SSRF reference-grade exploit
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.169.254/",
            // Loopback — backend self-hijack
            "http://127.0.0.1:9091/actuator/prometheus",
            "http://127.0.0.1/",
            "http://localhost/",
            "http://[::1]/",
            // Link-local — AWS metadata + Azure IMDS
            "http://169.254.1.1/",
            // RFC1918 — internal network ranges
            "http://10.0.0.1/",
            "http://10.255.255.255/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.0.1/",
            "http://192.168.1.1/webhook",
            // Any-local
            "http://0.0.0.0/",
    })
    @DisplayName("Private / loopback / metadata / link-local IPs are rejected")
    void rejectsPrivateAndMetadataIPs(String url) {
        assertThatThrownBy(() -> validator.validateAtCreation(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Outbound URL rejected");
    }

    @Test
    @DisplayName("Unresolvable hostnames are rejected")
    void rejectsUnresolvable() {
        assertThatThrownBy(() ->
                validator.validateAtCreation("http://this-host-absolutely-does-not-exist-fabt-2026.invalid/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-host");
    }

    // -----------------------------------------------------------------
    // Category classifier — exercises blockedCategory() directly
    // -----------------------------------------------------------------

    @Test
    @DisplayName("blockedCategory labels match the RFC classes")
    void blockedCategoryLabels() throws Exception {
        // Loopback
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("127.0.0.1")))
                .isEqualTo("loopback");
        // Link-local (includes cloud metadata 169.254.169.254)
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("169.254.169.254")))
                .isEqualTo("link-local");
        // RFC1918
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("10.0.0.1")))
                .isEqualTo("rfc1918");
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("192.168.1.1")))
                .isEqualTo("rfc1918");
        // Any-local
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("0.0.0.0")))
                .isEqualTo("any-local");
        // IPv6 loopback
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("::1")))
                .isEqualTo("loopback");
        // IPv6 ULA fc00::/7
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("fc00::1")))
                .isEqualTo("ula");
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("fd12:3456:789a:1::1")))
                .isEqualTo("ula");
    }

    @Test
    @DisplayName("Public IPs are permitted (null category)")
    void publicIpsPermitted() throws Exception {
        // 1.1.1.1 — Cloudflare public DNS, stable public IP
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("1.1.1.1")))
                .isNull();
        // 8.8.8.8 — Google public DNS
        assertThat(validator.blockedCategory(java.net.InetAddress.getByName("8.8.8.8")))
                .isNull();
    }

    // -----------------------------------------------------------------
    // Layer 3 — dial-time validation (behavioral; shares layer-2 checks)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("validateForDial blocks private IP with same rules as creation-time")
    void validateForDialBlocksPrivate() {
        assertThatThrownBy(() -> validator.validateForDial("http://127.0.0.1:9091/actuator/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejected at dial time")
                .hasMessageContaining("DNS rebinding");
    }

    @Test
    @DisplayName("validateForDial returns resolved InetAddress for public hosts (when DNS available)")
    void validateForDialReturnsAddressForPublic() {
        // Cloudflare 1.1.1.1 is a literal IP — no DNS needed, stable public.
        assertThatCode(() -> {
            var addr = validator.validateForDial("http://1.1.1.1/");
            assertThat(addr).isNotNull();
            assertThat(addr.getHostAddress()).isEqualTo("1.1.1.1");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Well-formed public URL passes creation-time validation")
    void publicUrlPasses() {
        assertThatCode(() -> validator.validateAtCreation("https://example.com/webhook"))
                .doesNotThrowAnyException();
    }
}
