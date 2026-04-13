package org.fabt.referral.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

import org.fabt.auth.service.UserService;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.fabt.shelter.service.ShelterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ShelterService shelterService;
    private final UserService userService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;
    private final MeterRegistry meterRegistry;
    private final ObservabilityMetrics observabilityMetrics;
    private final ApplicationEventPublisher eventPublisher;

    public ReferralTokenController(ReferralTokenService referralTokenService,
                                   ShelterService shelterService,
                                   UserService userService,
                                   CoordinatorAssignmentRepository coordinatorAssignmentRepository,
                                   MeterRegistry meterRegistry,
                                   ObservabilityMetrics observabilityMetrics,
                                   ApplicationEventPublisher eventPublisher) {
        this.referralTokenService = referralTokenService;
        this.shelterService = shelterService;
        this.userService = userService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
        this.meterRegistry = meterRegistry;
        this.observabilityMetrics = observabilityMetrics;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "Get escalated DV referrals queue",
            description = "Returns all pending DV referrals in the tenant, sorted by urgency. " +
                    "Authorized for CoC admins and platform admins. Contains zero PII."
    )
    @GetMapping("/escalated")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<EscalatedReferralDto>> getEscalatedQueue() {
        List<ReferralToken> tokens = referralTokenService.getEscalatedQueue();

        // Sam Okafor Optimization: Batch-fetch shelter and admin display names
        // to avoid N+1. Both lookups return primitives (String/UUID maps), not
        // domain entities, so the referral module never imports auth/User —
        // ArchUnit boundary enforced (Alex Chen).
        Set<UUID> shelterIds = tokens.stream().map(ReferralToken::getShelterId).collect(Collectors.toSet());
        Map<UUID, String> shelterNames = shelterService.findAllById(shelterIds).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));

        Set<UUID> adminIds = tokens.stream()
                .map(ReferralToken::getClaimedByAdminId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> adminNames = userService.findDisplayNamesByIds(adminIds);

        List<EscalatedReferralDto> dtos = tokens.stream()
                .map(t -> toEscalatedDto(t,
                        shelterNames.get(t.getShelterId()),
                        // adminNames is Map.of() (immutable) when no rows are
                        // claimed; immutable maps reject .get(null), so guard.
                        t.getClaimedByAdminId() == null ? null : adminNames.get(t.getClaimedByAdminId())))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Claim an escalated referral",
            description = "Sets a soft-lock claim on a pending referral for the configured " +
                    "claim duration (default 10 minutes). If already claimed by another admin, " +
                    "requires the Override-Claim header (or override=true query param) to steal."
    )
    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<EscalatedReferralDto> claim(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean override,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Override-Claim", required = false)
                    Boolean overrideHeader,
            Authentication authentication) {
        UUID adminId = UUID.fromString(authentication.getName());
        boolean effectiveOverride = override || Boolean.TRUE.equals(overrideHeader);
        try {
            ReferralToken token = referralTokenService.claimToken(id, adminId, effectiveOverride);
            String shelterName = shelterService.findById(token.getShelterId())
                    .map(s -> s.getName()).orElse(null);
            String adminName = userService.findDisplayNamesByIds(java.util.Set.of(adminId))
                    .get(adminId);
            return ResponseEntity.ok(toEscalatedDto(token, shelterName, adminName));
        } catch (ReferralTokenService.ClaimConflictException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @Operation(summary = "Release a referral claim manually")
    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> release(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean override,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Override-Claim", required = false)
                    Boolean overrideHeader,
            Authentication authentication) {
        UUID adminId = UUID.fromString(authentication.getName());
        boolean effectiveOverride = override || Boolean.TRUE.equals(overrideHeader);
        referralTokenService.releaseToken(id, adminId, effectiveOverride);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Reassign an escalated referral",
            description = "Three target types: COORDINATOR_GROUP (re-pages the shelter's "
                    + "coordinators), COC_ADMIN_GROUP (re-pages all CoC admins as CRITICAL), "
                    + "and SPECIFIC_USER (notifies a single named user AND breaks the "
                    + "escalation chain — the system stops auto-escalating because that user "
                    + "took manual ownership). Writes DV_REFERRAL_REASSIGNED audit event."
    )
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> reassign(
            @PathVariable UUID id,
            @Valid @RequestBody ReassignReferralRequest request,
            Authentication authentication) {
        UUID actorUserId = UUID.fromString(authentication.getName());
        referralTokenService.reassignToken(id, actorUserId, request);
        return ResponseEntity.ok().build();
    }

    private EscalatedReferralDto toEscalatedDto(ReferralToken t, String shelterName, String adminName) {
        String resolvedShelterName = shelterName != null ? shelterName : "Unknown Shelter";
        String resolvedAdminName = adminName != null
                ? adminName
                : (t.getClaimedByAdminId() != null ? "Unknown Admin" : null);

        // Coordinator mapping (MVP placeholder - Session 4 will batch-fetch
        // coordinator assignments via shelter module).
        String coordName = "Unassigned";
        UUID coordId = null;

        long remaining = 0;
        if (t.getExpiresAt() != null) {
            remaining = java.time.Duration.between(Instant.now(), t.getExpiresAt()).toMinutes();
        }

        return new EscalatedReferralDto(
                t.getId(), t.getShelterId(), resolvedShelterName,
                t.getPopulationType(), t.getHouseholdSize(), t.getUrgency(),
                t.getCreatedAt(), t.getExpiresAt(), remaining,
                coordId, coordName,
                t.getClaimedByAdminId(), resolvedAdminName, t.getClaimExpiresAt(),
                t.isEscalationChainBroken()
        );
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

        // Snapshot shelter name for the audit trail (Casey Drummond compliance)
        String shelterName = token.getShelterName() != null ? token.getShelterName() : "Unknown Shelter";
        publishAudit(userId, token.getId(), AuditEventTypes.DV_REFERRAL_REQUESTED,
                Map.of("shelter_id", request.shelterId().toString(),
                       "shelter_name", shelterName,
                       "urgency", request.urgency()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReferralTokenResponse.from(token, null, null));
    }

    @Operation(
            summary = "List my DV referral tokens",
            description = "Returns all referral tokens created by the authenticated user, in all " +
                    "statuses (PENDING, ACCEPTED, REJECTED, EXPIRED). For ACCEPTED tokens, the " +
                    "shelter's intake phone number is included for warm handoff. Shelter address " +
                    "is NEVER included. Performs a 'Safety Check' (Marcus Webb / Elena Vasquez) — if the " +
                    "destination shelter is deactivated or no longer a DV shelter, status is returned as SHELTER_CLOSED."
    )
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")

    public ResponseEntity<List<ReferralTokenResponse>> listMine(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        List<ReferralToken> tokens = referralTokenService.getByUserId(userId);

        // Safety Check (Marcus Webb / Elena Vasquez): Batch-fetch shelter data to avoid N+1
        Set<UUID> shelterIds = tokens.stream().map(ReferralToken::getShelterId).collect(Collectors.toSet());
        Map<UUID, Shelter> shelterMap = shelterService.findAllById(shelterIds).stream()
                .collect(Collectors.toMap(Shelter::getId, s -> s));

        List<ReferralTokenResponse> responses = tokens.stream()
                .map(t -> {
                    Shelter shelter = shelterMap.get(t.getShelterId());
                    
                    // Optimization (Marcus Webb): use batch-fetched shelter for phone number to avoid N+1
                    String phone = null;
                    if (shelter != null && "ACCEPTED".equals(t.getStatus())) {
                        phone = shelter.getPhone();
                    }

                    ReferralTokenResponse dto = ReferralTokenResponse.from(t, phone, null);

                    // Safety Check: flag PENDING referrals whose shelter is deactivated
                    // or no longer DV (Marcus Webb / Elena Vasquez). Only PENDING referrals
                    // are overridden — ACCEPTED/REJECTED referrals keep their terminal status
                    // because the worker may have already placed a client there (Keisha
                    // Thompson, war room 2026-04-12: "showing 'Shelter closed' on a
                    // completed referral causes unnecessary panic for the worker who
                    // already connected a client"). The phone IS withheld for non-PENDING
                    // unsafe shelters as a secondary safety measure.
                    if (shelter == null || !shelter.isActive() || !shelter.isDvShelter()) {
                        String reason = shelter == null ? "SHELTER_MISSING"
                                : (!shelter.isActive() ? "SHELTER_CLOSED" : "SHELTER_NOT_DV");

                        observabilityMetrics.dvReferralSafetyCheckCounter(reason).increment();

                        if ("PENDING".equals(t.getStatus())) {
                            // PENDING referrals: override status to SHELTER_CLOSED
                            dto = new ReferralTokenResponse(
                                    dto.id(), dto.shelterId(), dto.shelterName(), dto.householdSize(),
                                    dto.populationType(), dto.urgency(), dto.specialNeeds(),
                                    dto.callbackNumber(), "SHELTER_CLOSED", dto.createdAt(),
                                    dto.respondedAt(), dto.expiresAt(), dto.remainingSeconds(),
                                    dto.rejectionReason(), null);
                        } else {
                            // ACCEPTED/REJECTED: preserve terminal status, but withhold
                            // phone as a secondary safety measure
                            dto = new ReferralTokenResponse(
                                    dto.id(), dto.shelterId(), dto.shelterName(), dto.householdSize(),
                                    dto.populationType(), dto.urgency(), dto.specialNeeds(),
                                    dto.callbackNumber(), dto.status(), dto.createdAt(),
                                    dto.respondedAt(), dto.expiresAt(), dto.remainingSeconds(),
                                    dto.rejectionReason(), null);
                        }
                    }
                    return dto;
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
                .map(t -> ReferralTokenResponse.from(t, null, null))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(
            summary = "Count pending referrals across coordinator's assigned DV shelters",
            description = "Returns total PENDING referral count across all DV shelters assigned to the "
                    + "authenticated coordinator. Used for the dashboard referral banner."
    )
    @GetMapping("/pending/count")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Integer>> countPending(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        List<UUID> assignedShelterIds = coordinatorAssignmentRepository.findShelterIdsByUserId(userId);
        int count = referralTokenService.countPendingByShelterIds(assignedShelterIds);
        return ResponseEntity.ok(Map.of("count", count));
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
        return ResponseEntity.ok(ReferralTokenResponse.from(token, phone, null));
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
        return ResponseEntity.ok(ReferralTokenResponse.from(token, null, null));
    }

    @Operation(
            summary = "Get aggregate DV referral analytics",
            description = "Returns aggregate counts of DV referrals by status (requested, accepted, " +
                    "rejected, expired) and average response time. Backed by Micrometer counters — " +
                    "no PII, no individual referral data. Counters persist in Prometheus when the " +
                    "observability stack is active; otherwise reset on backend restart. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> analytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", getCounterValue("requested"));
        result.put("accepted", getCounterValue("accepted"));
        result.put("rejected", getCounterValue("rejected"));
        result.put("expired", getCounterValue("expired"));

        Timer timer = meterRegistry.find("fabt.dv.referral.response").timer();
        if (timer != null && timer.count() > 0) {
            result.put("averageResponseSeconds", timer.mean(java.util.concurrent.TimeUnit.SECONDS));
            result.put("responseCount", timer.count());
        }

        result.put("note", "Counters reset on backend restart unless observability stack (Prometheus) is active");
        return ResponseEntity.ok(result);
    }

    private double getCounterValue(String status) {
        Counter counter = meterRegistry.find("fabt.dv.referral.total").tag("status", status).counter();
        return counter != null ? counter.count() : 0.0;
    }

    /**
     * Same {@link ApplicationEventPublisher} → {@code AuditEventRecord} pattern as
     * {@link org.fabt.referral.service.ReferralTokenService} and {@link org.fabt.auth.service.UserService}
     * (Casey Drummond chain-of-custody; Alex Chen keeps referral module free of direct {@code AuditService}).
     */
    private void publishAudit(UUID userId, UUID referralId, String action, Map<String, String> details) {
        eventPublisher.publishEvent(new AuditEventRecord(
                userId, referralId, action, details, /* ipAddress */ null));
    }
}
