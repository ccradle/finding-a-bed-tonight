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

class DvReferralCrossSiteFilterTest {

    private DvReferralCrossSiteFilter filter;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new DvReferralCrossSiteFilter();
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setRemoteAddr("203.0.113.42");
    }

    // --- Filter scope: only POST /api/v1/dv-referrals exact path ---

    @Test
    void get_dv_referrals_passes_regardless_of_header() throws ServletException, IOException {
        request.setMethod("GET");
        request.setRequestURI("/api/v1/dv-referrals/mine");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void post_dv_referral_subpath_not_filtered() throws ServletException, IOException {
        // Filter is narrow: only matches exact /api/v1/dv-referrals path.
        // Sub-paths like /{id}/claim are out of scope.
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/00000000-0000-0000-0000-000000000001/claim");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void post_unrelated_endpoint_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // --- Sec-Fetch-Site header semantics on POST /api/v1/dv-referrals ---

    @Test
    void cross_site_post_rejected_403() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("cross_site_blocked");
    }

    @Test
    void same_origin_post_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "same-origin");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void same_site_post_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "same-site");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void none_post_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "none");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void absent_header_post_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        // no Sec-Fetch-Site header — simulates older browser or non-browser client

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void cross_site_with_padding_still_rejected() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "  cross-site  ");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void cross_site_case_insensitive_rejected() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals");
        request.addHeader("Sec-Fetch-Site", "CROSS-SITE");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    // --- Trailing slash variant (Riley warroom H2) ---

    @Test
    void cross_site_trailing_slash_also_rejected() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void same_origin_trailing_slash_passes() throws ServletException, IOException {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/dv-referrals/");
        request.addHeader("Sec-Fetch-Site", "same-origin");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
