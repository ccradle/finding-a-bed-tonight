package org.fabt.auth.api;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.service.DynamicClientRegistrationSource;
import org.fabt.auth.service.OAuth2AccountLinkService;
import org.fabt.auth.service.OAuth2AccountLinkService.LinkResult;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpSession;

/**
 * Handles the OAuth2 authorization code flow with PKCE.
 *
 * Flow:
 * 1. GET /oauth2/authorization/{registrationId} → redirect to IdP
 * 2. IdP authenticates user, redirects to callback
 * 3. GET /oauth2/callback/{registrationId}?code=...&state=... → exchange code for tokens
 * 4. Link or reject user, redirect to frontend with FABT JWT
 */
@RestController
public class OAuth2RedirectController {

    private static final Logger log = LoggerFactory.getLogger(OAuth2RedirectController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DynamicClientRegistrationSource clientRegistrationRepo;
    private final OAuth2AccountLinkService linkService;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;

    public OAuth2RedirectController(DynamicClientRegistrationSource clientRegistrationRepo,
                                     OAuth2AccountLinkService linkService,
                                     TenantService tenantService,
                                     ObjectMapper objectMapper) {
        this.clientRegistrationRepo = clientRegistrationRepo;
        this.linkService = linkService;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Initiate OAuth2 login redirect",
            description = "Redirects the browser to the IdP's authorization endpoint with PKCE challenge.")
    @GetMapping("/oauth2/authorization/{registrationId}")
    public ResponseEntity<Void> authorize(@PathVariable String registrationId, HttpSession session) {
        ClientRegistration registration = clientRegistrationRepo.findByRegistrationId(registrationId);
        if (registration == null) {
            log.warn("OAuth2 provider not found or disabled: {}", registrationId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/login?error=provider_not_found"))
                    .build();
        }

        // Generate PKCE code verifier and challenge
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateState();

        // Store PKCE verifier and registration in session for callback
        session.setAttribute("oauth2_code_verifier", codeVerifier);
        session.setAttribute("oauth2_state", state);
        session.setAttribute("oauth2_registration_id", registrationId);

        // Build authorization URL
        String authUrl = registration.getProviderDetails().getAuthorizationUri()
                + "?response_type=code"
                + "&client_id=" + registration.getClientId()
                + "&redirect_uri=" + registration.getRedirectUri()
                + "&scope=" + String.join("+", registration.getScopes())
                + "&state=" + state
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authUrl))
                .build();
    }

    @Operation(summary = "OAuth2 callback handler",
            description = "Receives the authorization code from the IdP, exchanges it for tokens, " +
                    "links or rejects the user, and redirects to the frontend with a FABT JWT.")
    @GetMapping("/oauth2/callback/{registrationId}")
    public ResponseEntity<Void> callback(@PathVariable String registrationId,
                                          @RequestParam String code,
                                          @RequestParam String state,
                                          HttpSession session) {
        // Verify state
        String expectedState = (String) session.getAttribute("oauth2_state");
        if (expectedState == null || !expectedState.equals(state)) {
            log.warn("OAuth2 state mismatch for registration {}", registrationId);
            return redirectToLogin("invalid_state");
        }

        String codeVerifier = (String) session.getAttribute("oauth2_code_verifier");
        session.removeAttribute("oauth2_code_verifier");
        session.removeAttribute("oauth2_state");
        session.removeAttribute("oauth2_registration_id");

        ClientRegistration registration = clientRegistrationRepo.findByRegistrationId(registrationId);
        if (registration == null) {
            return redirectToLogin("provider_not_found");
        }

        // Exchange code for tokens
        try {
            JsonNode tokenResponse = exchangeCodeForTokens(registration, code, codeVerifier);
            String idToken = tokenResponse.has("id_token") ? tokenResponse.get("id_token").asText() : null;
            String accessToken = tokenResponse.has("access_token") ? tokenResponse.get("access_token").asText() : null;

            if (idToken == null && accessToken == null) {
                log.error("OAuth2 token exchange returned no tokens for {}", registrationId);
                return redirectToLogin("token_exchange_failed");
            }

            // Decode ID token to get user info (sub, email)
            String sub = null;
            String email = null;

            if (idToken != null) {
                JsonNode claims = decodeJwtPayload(idToken);
                sub = claims.has("sub") ? claims.get("sub").asText() : null;
                email = claims.has("email") ? claims.get("email").asText() : null;
            }

            if (sub == null || email == null) {
                // Try userinfo endpoint
                if (accessToken != null && registration.getProviderDetails().getUserInfoEndpoint() != null) {
                    JsonNode userInfo = fetchUserInfo(registration, accessToken);
                    if (sub == null && userInfo.has("sub")) sub = userInfo.get("sub").asText();
                    if (email == null && userInfo.has("email")) email = userInfo.get("email").asText();
                }
            }

            if (email == null) {
                log.warn("OAuth2 login for {} — no email returned by IdP", registrationId);
                return redirectToLogin("email_required");
            }

            // Resolve tenant from registration ID
            int lastDash = registrationId.lastIndexOf('-');
            String slug = registrationId.substring(0, lastDash);
            String providerName = registrationId.substring(lastDash + 1);

            Optional<Tenant> tenant = tenantService.findBySlug(slug);
            if (tenant.isEmpty()) {
                return redirectToLogin("tenant_not_found");
            }

            // Link or reject
            LinkResult result = linkService.linkOrReject(providerName, sub, email, tenant.get().getId());

            if (result.success()) {
                // Redirect to frontend with tokens
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/?accessToken=" + result.accessToken()
                                + "&refreshToken=" + result.refreshToken()))
                        .build();
            } else {
                // Rejected — no pre-provisioned account
                return redirectToLogin("no_account&message=" + encodeURIComponent(result.error()));
            }

        } catch (Exception e) {
            log.error("OAuth2 callback failed for {}: {}", registrationId, e.getMessage());
            return redirectToLogin("callback_error");
        }
    }

    private JsonNode exchangeCodeForTokens(ClientRegistration registration, String code, String codeVerifier) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(registration.getClientId(), registration.getClientSecret());

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", registration.getRedirectUri());
        params.add("code_verifier", codeVerifier);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        String response = restTemplate.postForObject(
                registration.getProviderDetails().getTokenUri(), request, String.class);

        try {
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token response", e);
        }
    }

    private JsonNode fetchUserInfo(ClientRegistration registration, String accessToken) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            String response = restTemplate.exchange(
                    registration.getProviderDetails().getUserInfoEndpoint().getUri(),
                    org.springframework.http.HttpMethod.GET, request, String.class).getBody();
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.warn("Failed to fetch userinfo: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return objectMapper.createObjectNode();
            String payload = parts[1];
            // Add padding
            while (payload.length() % 4 != 0) payload += "=";
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return objectMapper.readTree(decoded);
        } catch (Exception e) {
            log.warn("Failed to decode JWT payload: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private ResponseEntity<Void> redirectToLogin(String error) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/login?error=" + error))
                .build();
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PKCE challenge", e);
        }
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encodeURIComponent(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
