package org.fabt.auth.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/oauth2")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class OAuth2TestConnectionController {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TestConnectionController.class);

    private final RestClient restClient;

    public OAuth2TestConnectionController(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Operation(
            summary = "Test OIDC discovery endpoint reachability",
            description = "Fetches the .well-known/openid-configuration from the given issuer URI " +
                    "to validate that the identity provider is reachable and properly configured."
    )
    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestParam String issuerUri) {
        String wellKnown = issuerUri.replaceAll("/$", "") + "/.well-known/openid-configuration";
        try {
            String response = restClient.get()
                    .uri(wellKnown)
                    .retrieve()
                    .body(String.class);

            if (response != null && response.contains("issuer")) {
                return ResponseEntity.ok(Map.of("success", true, "message", "OIDC discovery endpoint is reachable"));
            } else {
                return ResponseEntity.ok(Map.of("success", false, "message", "Response does not contain OIDC configuration"));
            }
        } catch (Exception e) {
            log.warn("OIDC discovery test failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", "Could not reach OIDC discovery endpoint: " + e.getMessage()));
        }
    }
}
