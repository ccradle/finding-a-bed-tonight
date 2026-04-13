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
