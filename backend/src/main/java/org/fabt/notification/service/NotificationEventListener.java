package org.fabt.notification.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.domain.User;
import org.fabt.auth.service.UserService;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.service.ShelterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to domain events and creates persistent notifications.
 *
 * Uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)} to ensure the
 * originating transaction (e.g., referral creation) has committed before we write
 * notification rows. This prevents notification writes from causing rollback of
 * the parent transaction.
 *
 * <p>Architecture: lives in the notification module, depends on auth service
 * (not repository) for recipient lookup — consistent with ArchUnit module boundaries.</p>
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationPersistenceService notificationPersistenceService;
    private final UserService userService;
    private final ShelterService shelterService;
    private final ObjectMapper objectMapper;

    public NotificationEventListener(NotificationPersistenceService notificationPersistenceService,
                                     UserService userService,
                                     ShelterService shelterService,
                                     ObjectMapper objectMapper) {
        this.notificationPersistenceService = notificationPersistenceService;
        this.userService = userService;
        this.shelterService = shelterService;
        this.objectMapper = objectMapper;
    }

    /** Serialize a map to JSON safely — no string interpolation, no injection. */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize notification payload: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * DV referral requested → persistent notification to all DV coordinators in tenant.
     * Payload: referralId + shelterId only (zero PII — VAWA/FVPSA).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReferralRequested(DomainEvent event) {
        if (!"dv-referral.requested".equals(event.type())) return;

        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();
        String referralId = (String) payload.get("token_id");
        String shelterId = (String) payload.get("shelter_id");

        // Find all DV coordinators in the tenant
        TenantContext.runWithContext(tenantId, true, () -> {
            List<User> dvCoordinators = userService.findDvCoordinators(tenantId);
            if (dvCoordinators.isEmpty()) {
                log.warn("No DV coordinators found for tenant {} — referral {} will have no notification recipients",
                        tenantId, referralId);
                return;
            }

            String notifPayload = toJson(Map.of("referralId", referralId, "shelterId", shelterId));
            List<UUID> recipientIds = dvCoordinators.stream().map(User::getId).toList();
            notificationPersistenceService.sendToAll(tenantId, recipientIds,
                    "referral.requested", "ACTION_REQUIRED", notifPayload);

            log.info("Referral notification sent to {} DV coordinators for referral {}",
                    recipientIds.size(), referralId);
        });
    }

    /**
     * DV referral responded (accepted/rejected) → persistent notification to the outreach worker.
     * Payload: referralId + status only (zero PII).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReferralResponded(DomainEvent event) {
        if (!"dv-referral.responded".equals(event.type())) return;

        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();
        String referralId = (String) payload.get("token_id");
        String referringUserId = (String) payload.get("referring_user_id");
        String status = (String) payload.get("status");

        if (referringUserId == null) {
            log.warn("dv-referral.responded event missing referring_user_id for referral {}", referralId);
            return;
        }

        String notifPayload = toJson(Map.of("referralId", referralId, "status", status));

        TenantContext.runWithContext(tenantId, false, () -> {
            notificationPersistenceService.send(tenantId, UUID.fromString(referringUserId),
                    "referral.responded", "ACTION_REQUIRED", notifPayload);
        });

        log.info("Referral response notification sent to outreach worker {} for referral {} (status={})",
                referringUserId, referralId, status);
    }

    /**
     * Surge activated → CRITICAL notification to ALL coordinators in the CoC.
     * Not DV-filtered — all coordinators need to know about overflow capacity.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurgeActivated(DomainEvent event) {
        if (!"surge.activated".equals(event.type())) return;

        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();

        TenantContext.runWithContext(tenantId, false, () -> {
            List<User> coordinators = userService.findActiveByRole(tenantId, "COORDINATOR");
            if (coordinators.isEmpty()) return;

            String notifPayload = toJson(Map.of(
                    "surgeEventId", String.valueOf(payload.get("surge_event_id")),
                    "reason", String.valueOf(payload.get("reason"))));
            List<UUID> recipientIds = coordinators.stream().map(User::getId).toList();
            notificationPersistenceService.sendToAll(tenantId, recipientIds,
                    "surge.activated", "CRITICAL", notifPayload);

            log.info("Surge CRITICAL notification sent to {} coordinators", recipientIds.size());
        });
    }

    /**
     * Surge deactivated → INFO notification to all coordinators.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurgeDeactivated(DomainEvent event) {
        if (!"surge.deactivated".equals(event.type())) return;

        UUID tenantId = event.tenantId();

        TenantContext.runWithContext(tenantId, false, () -> {
            List<User> coordinators = userService.findActiveByRole(tenantId, "COORDINATOR");
            if (coordinators.isEmpty()) return;

            List<UUID> recipientIds = coordinators.stream().map(User::getId).toList();
            notificationPersistenceService.sendToAll(tenantId, recipientIds,
                    "surge.deactivated", "INFO", "{}");

            log.info("Surge deactivated INFO notification sent to {} coordinators", recipientIds.size());
        });
    }

    /**
     * Reservation expired → ACTION_REQUIRED notification to the outreach worker who created the hold.
     * Includes shelterName for the i18n message "Your bed hold at {shelter} has expired".
     * shelterName is operational data (not PII) — it's the shelter's public name.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationExpired(DomainEvent event) {
        if (!"reservation.expired".equals(event.type())) return;

        UUID tenantId = event.tenantId();
        Map<String, Object> payload = event.payload();
        String reservationId = (String) payload.get("reservation_id");
        String shelterId = (String) payload.get("shelter_id");
        String userId = (String) payload.get("user_id");

        if (userId == null || shelterId == null) {
            log.warn("reservation.expired event missing user_id or shelter_id for reservation {}", reservationId);
            return;
        }

        TenantContext.runWithContext(tenantId, false, () -> {
            String shelterName = shelterService.findById(UUID.fromString(shelterId))
                    .map(s -> s.getName())
                    .orElse("Unknown shelter");

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("reservationId", reservationId);
            payloadMap.put("shelterId", shelterId);
            payloadMap.put("shelterName", shelterName);
            String notifPayload = toJson(payloadMap);

            notificationPersistenceService.send(tenantId, UUID.fromString(userId),
                    "reservation.expired", "ACTION_REQUIRED", notifPayload);

            log.info("Reservation expiry notification sent to outreach worker {} for reservation {}",
                    userId, reservationId);
        });
    }
}
