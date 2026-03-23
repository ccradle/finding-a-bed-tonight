package org.fabt.referral.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.service.ReferralTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DV opaque referral API. All endpoints enforce zero-PII design.
 * Accepted referrals return shelter phone (warm handoff) but NEVER shelter address.
 */
@RestController
@RequestMapping("/api/v1/dv-referrals")
public class ReferralTokenController {

    private final ReferralTokenService referralTokenService;

    public ReferralTokenController(ReferralTokenService referralTokenService) {
        this.referralTokenService = referralTokenService;
    }

    @Operation(
            summary = "Request a DV shelter referral",
            description = "Creates a privacy-preserving referral token for a DV shelter. The token " +
                    "contains zero client PII — only household size, population type, urgency, special " +
                    "needs, and the referring worker's callback number. The DV shelter must exist and " +
                    "be marked as dvShelter=true. Only one PENDING token per worker per shelter is " +
                    "allowed. Returns 409 if a duplicate exists. Requires dvAccess=true."
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReferralTokenResponse> create(
            @Valid @RequestBody CreateReferralRequest request,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        ReferralToken token = referralTokenService.createToken(
                request.shelterId(), userId, request.householdSize(),
                request.populationType(), request.urgency(),
                request.specialNeeds(), request.callbackNumber());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReferralTokenResponse.from(token, null));
    }

    @Operation(
            summary = "List my DV referral tokens",
            description = "Returns all referral tokens created by the authenticated user, in all " +
                    "statuses (PENDING, ACCEPTED, REJECTED, EXPIRED). For ACCEPTED tokens, the " +
                    "shelter's intake phone number is included for warm handoff. Shelter address " +
                    "is NEVER included."
    )
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<ReferralTokenResponse>> listMine(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        List<ReferralToken> tokens = referralTokenService.getByUserId(userId);
        List<ReferralTokenResponse> responses = tokens.stream()
                .map(t -> {
                    String phone = referralTokenService.getShelterPhoneForToken(t);
                    return ReferralTokenResponse.from(t, phone);
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(
            summary = "List pending referrals for a DV shelter",
            description = "Returns all PENDING referral tokens for a specific DV shelter. Used by " +
                    "DV shelter coordinators for safety screening. Each token shows household size, " +
                    "population type, urgency, special needs, and the referring worker's callback " +
                    "number. Contains zero client PII."
    )
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<ReferralTokenResponse>> listPending(
            @Parameter(description = "UUID of the DV shelter") @RequestParam UUID shelterId) {
        List<ReferralToken> tokens = referralTokenService.getPendingByShelterId(shelterId);
        List<ReferralTokenResponse> responses = tokens.stream()
                .map(t -> ReferralTokenResponse.from(t, null))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(
            summary = "Accept a DV referral — begin warm handoff",
            description = "Accepts a pending referral token. The referring outreach worker will be " +
                    "notified and receive the shelter's intake phone number for the warm handoff " +
                    "call. The shelter's physical address is shared verbally during the call, " +
                    "NEVER through this system. Returns 409 if token is expired or not pending."
    )
    @PatchMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReferralTokenResponse> accept(
            @Parameter(description = "UUID of the referral token") @PathVariable UUID id,
            Authentication authentication) {
        UUID respondedBy = UUID.fromString(authentication.getName());
        ReferralToken token = referralTokenService.acceptToken(id, respondedBy);
        String phone = referralTokenService.getShelterPhoneForToken(token);
        return ResponseEntity.ok(ReferralTokenResponse.from(token, phone));
    }

    @Operation(
            summary = "Reject a DV referral with reason",
            description = "Rejects a pending referral token. A reason is required (e.g., 'no capacity " +
                    "for pets', 'safety concern'). The reason must NOT contain client PII — an advisory " +
                    "label is shown to shelter staff. Returns 409 if token is expired or not pending."
    )
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<ReferralTokenResponse> reject(
            @Parameter(description = "UUID of the referral token") @PathVariable UUID id,
            @Valid @RequestBody RejectReferralRequest request,
            Authentication authentication) {
        UUID respondedBy = UUID.fromString(authentication.getName());
        ReferralToken token = referralTokenService.rejectToken(id, respondedBy, request.reason());
        return ResponseEntity.ok(ReferralTokenResponse.from(token, null));
    }
}
