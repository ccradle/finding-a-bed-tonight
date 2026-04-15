package org.fabt.observability;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityMetrics {

    private final MeterRegistry registry;

    private final AtomicInteger surgeActive = new AtomicInteger(0);
    private final AtomicInteger staleShelterCount = new AtomicInteger(0);
    private final AtomicInteger dvCanaryPass = new AtomicInteger(1);
    private final AtomicInteger temperatureSurgeGap = new AtomicInteger(0);

    public ObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;

        registry.gauge("fabt.surge.active", surgeActive);
        registry.gauge("fabt.shelter.stale.count", staleShelterCount);
        registry.gauge("fabt.dv.canary.pass", dvCanaryPass);
        registry.gauge("fabt.temperature.surge.gap", temperatureSurgeGap);
    }

    public Counter bedSearchCounter(String populationType) {
        return Counter.builder("fabt.bed.search.count")
                .tag("populationType", populationType != null ? populationType : "all")
                .register(registry);
    }

    public Timer bedSearchTimer(String populationType) {
        return Timer.builder("fabt.bed.search.duration")
                .tag("populationType", populationType != null ? populationType : "all")
                .publishPercentileHistogram()
                .register(registry);
    }

    public Timer availabilityUpdateTimer() {
        return Timer.builder("fabt.availability.update.duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public Counter availabilityUpdateCounter(String shelterId, String actor) {
        return Counter.builder("fabt.availability.update.count")
                .tag("shelterId", shelterId)
                .tag("actor", actor)
                .register(registry);
    }

    public Counter reservationCounter(String status) {
        return Counter.builder("fabt.reservation.count")
                .tag("status", status)
                .register(registry);
    }

    // --- Bed-holds reconciliation metrics (Issue #102 RCA, bed-hold-integrity) ---

    public Counter bedHoldReconciliationRunsCounter() {
        return Counter.builder("fabt.bed.hold.reconciliation.batch.runs.total")
                .register(registry);
    }

    public Timer bedHoldReconciliationDurationTimer() {
        return Timer.builder("fabt.bed.hold.reconciliation.batch.duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public Counter bedHoldReconciliationCorrectionsCounter() {
        return Counter.builder("fabt.bed.hold.reconciliation.corrections.total")
                .register(registry);
    }

    public Counter webhookDeliveryCounter(String eventType, String status) {
        return Counter.builder("fabt.webhook.delivery.count")
                .tag("event_type", eventType)
                .tag("status", status)
                .register(registry);
    }

    public Timer webhookDeliveryTimer(String eventType) {
        return Timer.builder("fabt.webhook.delivery.duration")
                .tag("event_type", eventType)
                .publishPercentileHistogram()
                .register(registry);
    }

    // --- DV referral escalation metrics (coc-admin-escalation, T-52/T-53) ---

    /**
     * Wall-clock duration of the referral escalation batch tasklet.
     *
     * <p>T-52 reframed (Alex Chen review 2026-04-12): no timer pre-existed this
     * change, so pre-refactor p95 is unrecoverable. This timer is the SLO
     * baseline going forward. Spring Batch's auto-emitted
     * {@code spring.batch.step{name=checkEscalationThresholds}} is retained as
     * the secondary signal.</p>
     *
     * <p>Single timer (no tags) because the job runs system-wide once per
     * 5-minute interval — a single p95 is the meaningful Grafana number.</p>
     */
    public Timer escalationBatchDurationTimer() {
        return Timer.builder("fabt.escalation.batch.duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * HTTP claim endpoint duration, tagged by {@code outcome}.
     *
     * <p>Outcome values (low, bounded cardinality per Alex Chen):</p>
     * <ul>
     *   <li>{@code success} — {@code tryClaim} returned a row (first-claim
     *       or override steal)</li>
     *   <li>{@code conflict} — {@code ClaimConflictException} thrown; another
     *       admin holds the claim and no override was supplied</li>
     *   <li>{@code error} — any other throwable (DB down, RLS misconfig,
     *       IllegalStateException for non-pending)</li>
     * </ul>
     *
     * <p><b>DO NOT</b> add tenantId, shelterId, userId, or referralId tags —
     * unbounded cardinality would kill Prometheus.</p>
     */
    public Timer dvReferralClaimTimer(String outcome) {
        return Timer.builder("fabt.dv-referral.claim.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Counter for claims auto-released by the minute-interval sweep after
     * their claim-expires-at elapsed without a manual release.
     *
     * <p>Incremented by {@code released.size()} inside
     * {@code ReferralTokenService.autoReleaseClaims} after
     * {@code repository.clearExpiredClaims()} returns. Grafana panel should
     * show {@code rate(...[5m])} for auto-release frequency — a rising rate
     * indicates admins are claiming but not releasing, which is a
     * workflow/training signal (Keisha Thompson + Devon Kessler lens).</p>
     *
     * <p>No tags — a single scalar is sufficient, and any dimensional split
     * (by tenant, by outcome) would pollute Prometheus with unbounded labels
     * given the system-wide sweep.</p>
     */
    public Counter dvReferralClaimAutoReleaseCounter() {
        return Counter.builder("fabt.dv-referral.claim.auto-release.count")
                .register(registry);
    }

    /**
     * Counter for how many referrals were flagged as SHELTER_CLOSED or
     * SHELTER_NOT_DV during the listMine safety check.
     *
     * <p>Incremented in {@code ReferralTokenController.listMine} when a
     * deactivated or non-DV shelter is detected for an existing referral.
     * High rate indicates operational instability or DV status revocation
     * while referrals were in-flight (Marcus Webb / Sam Okafor lens).</p>
     */
    public Counter dvReferralSafetyCheckCounter(String reason) {
        return Counter.builder("fabt.dv.referral.safety.check.count")
                .tag("reason", reason)
                .register(registry);
    }

    // --- notification-deep-linking metrics (Priya differentiator, Phase 4 9a.x) ---

    /**
     * Counter for notification deep-link clicks, tagged by the notification
     * {@code type} (e.g., referral.requested, escalation.1h,
     * SHELTER_DEACTIVATED), the clicking user's {@code role}, and the
     * {@code outcome} of the click (success / stale / offline).
     *
     * <p>Incremented on every deep-link resolution the frontend reports via
     * POST /api/v1/metrics/notification-deeplink-click. The resolution
     * outcome maps 1:1 to useDeepLink's terminal dlState.kind:
     * {@code 'done'} → success, {@code 'stale'} (404/403/race/timeout) →
     * stale. The offline tag is emitted only by the frontend when
     * navigator.onLine was false at the failure point — backend can't
     * observe offline state directly.</p>
     *
     * <p>Clickthrough analysis uses this counter divided by the count of
     * delivered notifications of the same type to produce the
     * "deep-link click-through rate > 70%" success metric from the
     * OpenSpec design doc.</p>
     */
    public Counter notificationDeepLinkClickCounter(String type, String role, String outcome) {
        return Counter.builder("fabt.notification.deeplink.click.count")
                .tag("type", type != null ? type : "unknown")
                .tag("role", role != null ? role : "unknown")
                .tag("outcome", outcome != null ? outcome : "unknown")
                .register(registry);
    }

    /**
     * Histogram of time-from-notification-to-successful-action, tagged by
     * notification {@code type}. Measured server-side in the PATCH /acted
     * handler as {@code now - notification.createdAt} the moment the
     * markActed state transition fires.
     *
     * <p>This is the <b>primary success metric</b> for the
     * notification-deep-linking change (Priya's lens in the OpenSpec):
     * median time-from-notification-to-accept &lt; 30 seconds, baseline
     * per coordinator self-report was 2-5 minutes. {@code publishPercentileHistogram}
     * emits p50 / p95 / p99 buckets so the Grafana panel (task 9a.4) can
     * render the full latency distribution.</p>
     *
     * <p>If the median reading exceeds 60 seconds after 2 weeks of pilot
     * data, investigate UX — the deep-link may not be hitting the right
     * target (OpenSpec design doc Success Targets).</p>
     */
    public Timer notificationTimeToActionTimer(String type) {
        return Timer.builder("fabt.notification.time_to_action.seconds")
                .tag("type", type != null ? type : "unknown")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Counter for stale deep-link resolutions — the notification referenced
     * a target that no longer exists or the user lacks access to (404/403
     * from the resolve fetch) OR the target didn't materialize in time
     * (awaiting-target deadline). Tagged by notification {@code type} and
     * user {@code role} so operations can see whether stale-rate correlates
     * with a specific role (e.g., admins racing coordinators) or a specific
     * escalation tier.
     *
     * <p>High stale rate indicates the notification delivery or routing is
     * out of sync with the domain state — actionable signal for backend
     * escalation-timing tuning.</p>
     */
    public Counter notificationStaleReferralCounter(String type, String role) {
        return Counter.builder("fabt.notification.stale_referral.count")
                .tag("type", type != null ? type : "unknown")
                .tag("role", role != null ? role : "unknown")
                .register(registry);
    }

    public void setSurgeActive(boolean active) {
        surgeActive.set(active ? 1 : 0);
    }

    public void setStaleShelterCount(int count) {
        staleShelterCount.set(count);
    }

    public void setDvCanaryPass(boolean pass) {
        dvCanaryPass.set(pass ? 1 : 0);
    }

    public double getStaleShelterCountValue() {
        return staleShelterCount.get();
    }

    public double getDvCanaryPassValue() {
        return dvCanaryPass.get();
    }

    public void setTemperatureSurgeGap(boolean gapDetected) {
        temperatureSurgeGap.set(gapDetected ? 1 : 0);
    }

    public double getTemperatureSurgeGapValue() {
        return temperatureSurgeGap.get();
    }
}
