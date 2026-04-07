package org.fabt.subscription.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.fabt.subscription.domain.Subscription;
import org.fabt.subscription.service.SubscriptionService;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.fabt.subscription.domain.WebhookDeliveryLog;
import org.fabt.subscription.repository.WebhookDeliveryLogRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Webhook Subscriptions", description = "Manage webhook subscriptions for domain event delivery")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final WebhookDeliveryLogRepository deliveryLogRepository;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   WebhookDeliveryLogRepository deliveryLogRepository) {
        this.subscriptionService = subscriptionService;
        this.deliveryLogRepository = deliveryLogRepository;
    }

    @PostMapping
    @Operation(summary = "Create a webhook subscription",
               description = "Register a callback URL to receive domain events of the specified type. "
                       + "The callback secret is used to compute HMAC-SHA256 signatures on delivered payloads.")
    public ResponseEntity<SubscriptionResponse> create(@Valid @RequestBody CreateSubscriptionRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Subscription subscription = subscriptionService.create(
                tenantId,
                request.eventType(),
                request.filter(),
                request.callbackUrl(),
                request.callbackSecret()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription));
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions",
               description = "Returns all webhook subscriptions for the current tenant, ordered by creation date descending.")
    public ResponseEntity<List<SubscriptionResponse>> list() {
        UUID tenantId = TenantContext.getTenantId();
        List<SubscriptionResponse> subscriptions = subscriptionService.findByTenantId(tenantId).stream()
                .map(SubscriptionResponse::from)
                .toList();
        return ResponseEntity.ok(subscriptions);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a webhook subscription",
               description = "Sets the subscription status to CANCELLED. The subscription will no longer receive events.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        subscriptionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Pause or resume a webhook subscription",
               description = "Sets the subscription status to PAUSED or ACTIVE. Only these two values "
                       + "are accepted — other status values are system-managed. Resuming from "
                       + "DEACTIVATED resets the consecutive failure counter.")
    public ResponseEntity<SubscriptionResponse> updateStatus(@PathVariable UUID id,
                                                              @RequestBody java.util.Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Subscription updated = subscriptionService.updateStatus(id, newStatus);
        return ResponseEntity.ok(SubscriptionResponse.from(updated));
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "Get recent delivery log for a subscription",
               description = "Returns the last 20 delivery attempts for the specified subscription, "
                       + "ordered by most recent first. Includes status code, response time, and "
                       + "truncated response body (max 1KB).")
    public ResponseEntity<List<WebhookDeliveryLog>> getDeliveries(@PathVariable UUID id) {
        return ResponseEntity.ok(deliveryLogRepository.findRecentBySubscriptionId(id));
    }
}
