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
