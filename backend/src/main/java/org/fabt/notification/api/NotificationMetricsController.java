package org.fabt.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.fabt.observability.ObservabilityMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend-reported metrics for the notification-deep-linking change
 * (Phase 4 task 9a.1). Provides a narrow POST surface the bell-click
 * and useDeepLink host callbacks use to increment the
 * {@code fabt.notification.deeplink.click.count} counter with per-
 * request {@code type} / {@code outcome} tags; the {@code role} tag is
 * derived server-side from the authenticated user so the caller cannot
 * forge it.
 *
 * <p>Why a dedicated endpoint instead of inferring clicks from existing
 * traffic: the {@code outcome} dimension includes {@code 'offline'} —
 * the frontend's only observation, not visible to the backend. And
 * deep-link success/stale is a presentation-layer concern (did the
 * hook reach {@code done} vs {@code stale}), not a domain event. Keeping
 * the metric push explicit avoids conflating it with other traffic.</p>
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "notification-metrics")
public class NotificationMetricsController {

    private final ObservabilityMetrics metrics;

    public NotificationMetricsController(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Operation(
        summary = "Report a notification deep-link click outcome",
        description = "Called by the frontend useDeepLink hook's host effect after a "
                + "deep-link reaches a terminal state. Increments "
                + "fabt.notification.deeplink.click.count tagged by notification type, "
                + "authenticated user role, and outcome (success / stale / offline). "
                + "Fire-and-forget — clients should not block on this response. "
                + "Any authenticated role may call; the role tag is derived server-side "
                + "so the caller cannot forge it."
    )
    @PostMapping("/notification-deeplink-click")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> reportDeepLinkClick(
            @Valid @RequestBody DeepLinkClickReport report,
            Authentication authentication) {
        // Derive role server-side. If the user has multiple roles (rare —
        // most seed users have one), take the first granted authority.
        // Strip the "ROLE_" prefix Spring adds so the tag matches the
        // role names used elsewhere in the app.
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .orElse("unknown");
        metrics.notificationDeepLinkClickCounter(report.type(), role, report.outcome()).increment();
        // Phase 4 task 9a.3 — when the deep-link resolved to 'stale', also
        // increment the stale-specific counter. Two meters is redundant
        // arithmetically (stale rate = stale / total) but keeps the Grafana
        // stale panel query trivial (no ratio, no divisor).
        if ("stale".equals(report.outcome())) {
            metrics.notificationStaleReferralCounter(report.type(), role).increment();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Request body for {@link #reportDeepLinkClick}. {@code outcome} is
     * regex-validated so typos in the client don't pollute Prometheus
     * with unbounded label values — only the three expected values are
     * accepted.
     */
    public record DeepLinkClickReport(
            @NotBlank String type,
            @NotBlank @Pattern(regexp = "success|stale|offline") String outcome
    ) {}
}
