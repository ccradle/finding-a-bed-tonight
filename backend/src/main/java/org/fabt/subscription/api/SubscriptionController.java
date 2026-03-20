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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
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
}
