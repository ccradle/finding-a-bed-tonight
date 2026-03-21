package org.fabt.surge.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.surge.domain.SurgeEvent;
import org.fabt.surge.service.SurgeEventService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/surge-events")
public class SurgeEventController {

    private final SurgeEventService surgeEventService;

    public SurgeEventController(SurgeEventService surgeEventService) {
        this.surgeEventService = surgeEventService;
    }

    @Operation(
            summary = "Activate a surge / White Flag event",
            description = "Creates a new surge event for the tenant, signaling that emergency " +
                    "shelter capacity is being opened. Only one surge can be active per tenant. " +
                    "The response includes affected_shelter_count and estimated_overflow_beds. " +
                    "A surge.activated event is published to the EventBus for webhook delivery. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<SurgeEventResponse> activate(
            @Valid @RequestBody ActivateSurgeRequest request,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        SurgeEvent event = surgeEventService.activate(
                request.reason(), request.boundingBox(), request.scheduledEnd(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(SurgeEventResponse.from(event));
    }

    @Operation(
            summary = "List surge events (active and historical)",
            description = "Returns all surge events for the tenant, ordered by activation time " +
                    "descending. Includes active, deactivated, and expired events."
    )
    @GetMapping
    public ResponseEntity<List<SurgeEventResponse>> list() {
        List<SurgeEventResponse> events = surgeEventService.list().stream()
                .map(SurgeEventResponse::from)
                .toList();
        return ResponseEntity.ok(events);
    }

    @Operation(
            summary = "Get surge event details",
            description = "Returns a single surge event by ID."
    )
    @GetMapping("/{id}")
    public ResponseEntity<SurgeEventResponse> getById(
            @Parameter(description = "UUID of the surge event") @PathVariable UUID id) {
        return surgeEventService.findById(id)
                .map(e -> ResponseEntity.ok(SurgeEventResponse.from(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Deactivate a surge event",
            description = "Ends an active surge event. Transitions status to DEACTIVATED and " +
                    "publishes a surge.deactivated event to the EventBus. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<SurgeEventResponse> deactivate(
            @Parameter(description = "UUID of the surge event to deactivate") @PathVariable UUID id,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        SurgeEvent event = surgeEventService.deactivate(id, userId);
        return ResponseEntity.ok(SurgeEventResponse.from(event));
    }
}
