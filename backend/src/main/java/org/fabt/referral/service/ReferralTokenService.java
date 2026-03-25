package org.fabt.referral.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.service.TenantService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DV opaque referral token lifecycle.
 * Zero client PII — tokens contain only operational data.
 * Terminal tokens are hard-deleted within 24 hours.
 */
@Service
public class ReferralTokenService {

    private static final Logger log = LoggerFactory.getLogger(ReferralTokenService.class);
    private static final int DEFAULT_EXPIRY_MINUTES = 240;

    private final ReferralTokenRepository repository;
    private final ShelterService shelterService;
    private final TenantService tenantService;
    private final EventBus eventBus;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public ReferralTokenService(ReferralTokenRepository repository,
                                ShelterService shelterService,
                                TenantService tenantService,
                                EventBus eventBus,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.shelterService = shelterService;
        this.tenantService = tenantService;
        this.eventBus = eventBus;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;

        // Gauge for current pending referral count — queried on each Prometheus scrape
        Gauge.builder("fabt.dv.referral.pending", repository, r -> {
            try {
                TenantContext.setDvAccess(true);
                Integer count = r.countAllPending();
                return count != null ? count.doubleValue() : 0.0;
            } finally {
                TenantContext.clear();
            }
        }).description("Current count of pending DV referral tokens")
          .register(meterRegistry);
    }

    /**
     * Create a referral token for a DV shelter. Validates dvShelter=true and no existing PENDING token.
     */
    @Transactional
    public ReferralToken createToken(UUID shelterId, UUID userId, int householdSize,
                                     String populationType, String urgency,
                                     String specialNeeds, String callbackNumber) {
        UUID tenantId = TenantContext.getTenantId();

        // Defense-in-depth: explicitly check dvAccess independent of RLS (D14).
        // Even if the database role bypasses RLS, this check rejects the request.
        if (!TenantContext.getDvAccess()) {
            throw new AccessDeniedException("DV access required for referral operations");
        }

        // Validate shelter exists and is a DV shelter
        Shelter shelter = shelterService.findById(shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));

        if (!shelter.isDvShelter()) {
            throw new IllegalArgumentException("Referral tokens are only for DV shelters");
        }

        // Calculate expiry from tenant config
        int expiryMinutes = getDvReferralExpiryMinutes(tenantId);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(expiryMinutes));

        ReferralToken token = new ReferralToken(
                shelterId, tenantId, userId,
                householdSize, populationType, urgency,
                specialNeeds, callbackNumber, expiresAt);

        ReferralToken saved = repository.insert(token);

        // Publish event (no PII in payload)
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", saved.getId().toString());
        payload.put("shelter_id", shelterId.toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("urgency", urgency);
        eventBus.publish(new DomainEvent("dv-referral.requested", tenantId, payload));

        dvReferralCounter("requested").increment();

        log.info("DV referral token created: tokenId={}, shelterId={}, urgency={}",
                saved.getId(), shelterId, urgency);

        return saved;
    }

    /**
     * Accept a pending referral token. Returns the token with shelter phone for warm handoff.
     */
    @Transactional
    public ReferralToken acceptToken(UUID tokenId, UUID respondedBy) {
        ReferralToken token = repository.findById(tokenId)
                .orElseThrow(() -> new NoSuchElementException("Referral token not found: " + tokenId));

        if (!token.isPending()) {
            throw new IllegalStateException("Token is not pending: " + token.getStatus());
        }
        if (token.isExpired()) {
            throw new IllegalStateException("Token has expired");
        }

        repository.updateStatus(tokenId, "ACCEPTED", respondedBy, null);
        token.setStatus("ACCEPTED");
        token.setRespondedAt(Instant.now());
        token.setRespondedBy(respondedBy);

        // Publish event
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", tokenId.toString());
        payload.put("shelter_id", token.getShelterId().toString());
        payload.put("status", "ACCEPTED");
        eventBus.publish(new DomainEvent("dv-referral.responded", token.getTenantId(), payload));

        dvReferralCounter("accepted").increment();
        recordResponseTime(token);

        log.info("DV referral accepted: tokenId={}, shelterId={}", tokenId, token.getShelterId());

        return token;
    }

    /**
     * Reject a pending referral token with a reason.
     */
    @Transactional
    public ReferralToken rejectToken(UUID tokenId, UUID respondedBy, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }

        ReferralToken token = repository.findById(tokenId)
                .orElseThrow(() -> new NoSuchElementException("Referral token not found: " + tokenId));

        if (!token.isPending()) {
            throw new IllegalStateException("Token is not pending: " + token.getStatus());
        }
        if (token.isExpired()) {
            throw new IllegalStateException("Token has expired");
        }

        repository.updateStatus(tokenId, "REJECTED", respondedBy, rejectionReason);
        token.setStatus("REJECTED");
        token.setRespondedAt(Instant.now());
        token.setRespondedBy(respondedBy);
        token.setRejectionReason(rejectionReason);

        // Publish event
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", tokenId.toString());
        payload.put("shelter_id", token.getShelterId().toString());
        payload.put("status", "REJECTED");
        eventBus.publish(new DomainEvent("dv-referral.responded", token.getTenantId(), payload));

        dvReferralCounter("rejected").increment();
        recordResponseTime(token);

        log.info("DV referral rejected: tokenId={}, shelterId={}, reason={}",
                tokenId, token.getShelterId(), rejectionReason);

        return token;
    }

    /**
     * Expire pending tokens past their expiry time. Called by @Scheduled every 60 seconds.
     * System process — sets TenantContext.dvAccess=true for RLS access to DV-linked tokens (D14).
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireTokens() {
        try {
            TenantContext.setDvAccess(true);
            int expired = repository.expirePendingTokens();
            if (expired > 0) {
                for (int i = 0; i < expired; i++) {
                    dvReferralCounter("expired").increment();
                }
                log.info("Expired {} DV referral tokens", expired);
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(readOnly = true)
    public List<ReferralToken> getPendingByShelterId(UUID shelterId) {
        return repository.findPendingByShelterId(shelterId);
    }

    @Transactional(readOnly = true)
    public List<ReferralToken> getByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public int countPendingByShelterId(UUID shelterId) {
        return repository.countPendingByShelterId(shelterId);
    }

    /**
     * Get the shelter phone number for an accepted token (warm handoff).
     * Returns the phone but NOT the address — FVPSA compliance.
     */
    @Transactional(readOnly = true)
    public String getShelterPhoneForToken(ReferralToken token) {
        if (!"ACCEPTED".equals(token.getStatus())) {
            return null;
        }
        return shelterService.findById(token.getShelterId())
                .map(Shelter::getPhone)
                .orElse(null);
    }

    private Counter dvReferralCounter(String status) {
        return Counter.builder("fabt.dv.referral.total")
                .tag("status", status)
                .register(meterRegistry);
    }

    private void recordResponseTime(ReferralToken token) {
        if (token.getCreatedAt() != null) {
            long responseMs = java.time.Duration.between(token.getCreatedAt(), Instant.now()).toMillis();
            Timer.builder("fabt.dv.referral.response")
                    .description("Time from referral request to shelter response")
                    .register(meterRegistry)
                    .record(java.time.Duration.ofMillis(responseMs));
        }
    }

    private int getDvReferralExpiryMinutes(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode expiry = node.get("dv_referral_expiry_minutes");
                            return expiry != null ? expiry.asInt(DEFAULT_EXPIRY_MINUTES) : DEFAULT_EXPIRY_MINUTES;
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            log.warn("Failed to read DV referral expiry from tenant config, using default: {}", e.getMessage());
                            return DEFAULT_EXPIRY_MINUTES;
                        }
                    })
                    .orElse(DEFAULT_EXPIRY_MINUTES);
        } catch (Exception e) {
            log.warn("Failed to read DV referral expiry from tenant config, using default: {}", e.getMessage());
            return DEFAULT_EXPIRY_MINUTES;
        }
    }
}
