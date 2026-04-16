package org.fabt.shared.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DemoGuardFilterTest {

    private DemoGuardFilter filter;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new DemoGuardFilter();
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        // Default: public traffic (has X-Forwarded-For, Docker bridge IP)
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Forwarded-For", "104.21.88.145");
    }

    // --- GET requests always pass through ---

    @Test
    void get_users_allowed() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void get_shelters_allowed() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/v1/shelters");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Destructive operations blocked ---

    @Test
    void post_users_blocked() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("demo_restricted");
        assertThat(response.getContentAsString()).contains("User management is disabled");
    }

    @Test
    void put_shelter_blocked() throws ServletException, IOException {
        request.setMethod("PUT");
        request.setRequestURI("/api/v1/shelters/123e4567-e89b-12d3-a456-426614174000");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Shelter modification is disabled");
    }

    @Test
    void password_change_blocked() throws ServletException, IOException {
        request.setMethod("PUT");
        request.setRequestURI("/api/v1/auth/password");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Password changes are disabled");
    }

    @Test
    void surge_activation_blocked() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/surge-events");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Surge management is disabled");
    }

    @Test
    void import_blocked() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/import/211");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Data import is disabled");
    }

    // --- v0.40 demoguard threat-model audit (regression guards) ---

    @Test
    void totp_enroll_blocked() throws ServletException, IOException {
        // Account-hijack vector: confirm-totp-enrollment sets totp_enabled=true +
        // increments token_version on the shared seed account, locking out every
        // subsequent demo visitor. Removed from allowlist 2026-04-16.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/enroll-totp");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Two-factor enrollment is disabled");
    }

    @Test
    void totp_confirm_enrollment_blocked() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/confirm-totp-enrollment");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Two-factor enrollment is disabled");
    }

    @Test
    void subscription_create_blocked() throws ServletException, IOException {
        // Abuse vector: outbound-dial amplification, exfiltration via attacker-
        // chosen public webhook URL, persistent state pollution (365-day expiry).
        // Removed from allowlist 2026-04-16.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/subscriptions");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Webhook subscription management is disabled");
    }

    @Test
    void subscription_delete_blocked() throws ServletException, IOException {
        request.setMethod("DELETE");
        request.setRequestURI("/api/v1/subscriptions/00000000-0000-0000-0000-000000000001");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Webhook subscription management is disabled");
    }

    // --- Allowlisted safe mutations pass through ---

    @Test
    void login_allowed() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void bed_search_allowed() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/queries/beds");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void reservation_create_allowed() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/reservations");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void reservation_confirm_allowed() throws ServletException, IOException {
        request.setMethod("PATCH");
        request.setRequestURI("/api/v1/reservations/123e4567-e89b-12d3-a456-426614174000/confirm");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void reservation_cancel_allowed() throws ServletException, IOException {
        request.setMethod("PATCH");
        request.setRequestURI("/api/v1/reservations/123e4567-e89b-12d3-a456-426614174000/cancel");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void dv_referral_request_allowed() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void availability_update_allowed() throws ServletException, IOException {
        request.setMethod("PATCH");
        request.setRequestURI("/api/v1/shelters/123e4567-e89b-12d3-a456-426614174000/availability");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Localhost bypass (SSH tunnel to :8080) ---

    @Test
    void localhost_ipv4_bypasses_guard() throws ServletException, IOException {
        request.setRemoteAddr("127.0.0.1");
        request.removeHeader("X-Forwarded-For");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void localhost_ipv6_bypasses_guard() throws ServletException, IOException {
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        request.removeHeader("X-Forwarded-For");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Docker bridge tunnel bypass (SSH tunnel to :8081, no X-Forwarded-For) ---

    @Test
    void docker_bridge_without_forwarded_for_bypasses_guard() throws ServletException, IOException {
        request.setRemoteAddr("172.18.0.3");
        request.removeHeader("X-Forwarded-For");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Docker bridge tunnel WITH private-only X-Forwarded-For (SSH tunnel to :8081 via container nginx) ---

    @Test
    void docker_bridge_with_private_forwarded_for_bypasses_guard() throws ServletException, IOException {
        request.setRemoteAddr("172.18.0.3");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void docker_bridge_with_docker_forwarded_for_bypasses_guard() throws ServletException, IOException {
        request.setRemoteAddr("172.18.0.3");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "172.17.0.1");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Public traffic with X-Forwarded-For containing public IP is NOT exempt ---

    @Test
    void public_traffic_with_forwarded_for_not_exempt() throws ServletException, IOException {
        request.setRemoteAddr("172.18.0.3");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void mixed_forwarded_for_with_public_ip_not_exempt() throws ServletException, IOException {
        request.setRemoteAddr("172.18.0.3");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 172.18.0.1");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    // --- Fail-secure: unknown endpoint blocked ---

    @Test
    void unknown_post_endpoint_blocked() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/some-new-feature");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("demo_restricted");
    }

    // --- Response format ---

    @Test
    void blocked_response_is_json_with_correct_fields() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/users");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("\"error\":\"demo_restricted\"");
        assertThat(response.getContentAsString()).contains("\"status\":403");
        assertThat(response.getContentAsString()).contains("\"timestamp\":");
    }

    // ---------------------------------------------------------------
    // coc-admin-escalation (v0.33.0) — claim/release allowed,
    // reassign/policy-update blocked with friendly messages.
    // ---------------------------------------------------------------

    @Test
    void post_dv_referral_claim_allowed() throws ServletException, IOException {
        // Demo visitors can claim a row to experience the CoC admin
        // workflow. The 10-min soft-lock auto-expires so state noise
        // is self-healing for the next visitor.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/123e4567-e89b-12d3-a456-426614174000/claim");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void post_dv_referral_release_allowed() throws ServletException, IOException {
        // Symmetric to claim. Releasing returns the referral to the
        // queue for the next visitor.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/123e4567-e89b-12d3-a456-426614174000/release");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void post_dv_referral_reassign_blocked_with_friendly_message() throws ServletException, IOException {
        // Reassign to SPECIFIC_USER would break the escalation chain
        // for the next visitor — deliberately blocked. The friendly
        // message tells the visitor that claim and release are still
        // available so they understand why this specific button is
        // disabled rather than seeing a generic error.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/123e4567-e89b-12d3-a456-426614174000/reassign");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("demo_restricted");
        assertThat(response.getContentAsString()).contains("Reassigning escalated referrals is disabled");
        assertThat(response.getContentAsString()).contains("claim and release referrals to experience the queue workflow");
    }

    @Test
    void patch_escalation_policy_blocked_with_friendly_message() throws ServletException, IOException {
        // Policy updates affect ALL tenants on the demo site, so a
        // demo visitor changing thresholds would silently disrupt
        // every other visitor's experience. Hard block with a clear
        // message naming the cross-tenant impact.
        request.setMethod("PATCH");
        request.setRequestURI("/api/v1/admin/escalation-policy/dv-referral");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("demo_restricted");
        assertThat(response.getContentAsString()).contains("Escalation policy changes are disabled");
        assertThat(response.getContentAsString()).contains("affect all tenants");
    }

    @Test
    void get_escalation_policy_allowed_read_only() throws ServletException, IOException {
        // Reading the policy is fine — every GET passes the
        // READ_METHODS guard at the top of the filter.
        request.setMethod("GET");
        request.setRequestURI("/api/v1/admin/escalation-policy/dv-referral");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void get_escalated_queue_allowed_read_only() throws ServletException, IOException {
        // CoC admin escalation queue endpoint — read-only, always
        // allowed. Demo visitors can browse the queue.
        request.setMethod("GET");
        request.setRequestURI("/api/v1/dv-referrals/escalated");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
