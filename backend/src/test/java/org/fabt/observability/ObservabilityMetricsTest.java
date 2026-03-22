package org.fabt.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityMetricsTest {

    private SimpleMeterRegistry registry;
    private ObservabilityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(registry);
    }

    @Test
    void gauges_registeredOnConstruction() {
        assertNotNull(registry.find("fabt.surge.active").gauge());
        assertNotNull(registry.find("fabt.shelter.stale.count").gauge());
        assertNotNull(registry.find("fabt.dv.canary.pass").gauge());
    }

    @Test
    void surgeActive_togglesCorrectly() {
        Gauge gauge = registry.find("fabt.surge.active").gauge();
        assertNotNull(gauge);

        assertEquals(0.0, gauge.value());
        metrics.setSurgeActive(true);
        assertEquals(1.0, gauge.value());
        metrics.setSurgeActive(false);
        assertEquals(0.0, gauge.value());
    }

    @Test
    void staleShelterCount_updatesCorrectly() {
        Gauge gauge = registry.find("fabt.shelter.stale.count").gauge();
        assertNotNull(gauge);

        assertEquals(0.0, gauge.value());
        metrics.setStaleShelterCount(3);
        assertEquals(3.0, gauge.value());
        metrics.setStaleShelterCount(0);
        assertEquals(0.0, gauge.value());
    }

    @Test
    void dvCanaryPass_updatesCorrectly() {
        Gauge gauge = registry.find("fabt.dv.canary.pass").gauge();
        assertNotNull(gauge);

        assertEquals(1.0, gauge.value()); // default is pass
        metrics.setDvCanaryPass(false);
        assertEquals(0.0, gauge.value());
        metrics.setDvCanaryPass(true);
        assertEquals(1.0, gauge.value());
    }

    @Test
    void bedSearchCounter_incrementsWithTags() {
        Counter counter = metrics.bedSearchCounter("families");
        assertNotNull(counter);
        counter.increment();
        counter.increment();

        Counter found = registry.find("fabt.bed.search.count")
                .tag("populationType", "families")
                .counter();
        assertNotNull(found);
        assertEquals(2.0, found.count());
    }

    @Test
    void bedSearchTimer_recordsWithTags() {
        Timer timer = metrics.bedSearchTimer("individuals");
        assertNotNull(timer);

        timer.record(() -> {
            // simulate work
        });

        Timer found = registry.find("fabt.bed.search.duration")
                .tag("populationType", "individuals")
                .timer();
        assertNotNull(found);
        assertEquals(1, found.count());
    }

    @Test
    void availabilityUpdateCounter_incrementsWithTags() {
        Counter counter = metrics.availabilityUpdateCounter("shelter-123", "coordinator");
        counter.increment();

        Counter found = registry.find("fabt.availability.update.count")
                .tag("shelterId", "shelter-123")
                .tag("actor", "coordinator")
                .counter();
        assertNotNull(found);
        assertEquals(1.0, found.count());
    }

    @Test
    void reservationCounter_incrementsWithStatus() {
        metrics.reservationCounter("CREATED").increment();
        metrics.reservationCounter("CONFIRMED").increment();
        metrics.reservationCounter("CANCELLED").increment();
        metrics.reservationCounter("EXPIRED").increment();

        assertEquals(1.0, registry.find("fabt.reservation.count").tag("status", "CREATED").counter().count());
        assertEquals(1.0, registry.find("fabt.reservation.count").tag("status", "CONFIRMED").counter().count());
        assertEquals(1.0, registry.find("fabt.reservation.count").tag("status", "CANCELLED").counter().count());
        assertEquals(1.0, registry.find("fabt.reservation.count").tag("status", "EXPIRED").counter().count());
    }

    @Test
    void webhookDeliveryCounter_incrementsWithTags() {
        metrics.webhookDeliveryCounter("availability.updated", "success").increment();
        metrics.webhookDeliveryCounter("availability.updated", "failure").increment();

        assertEquals(1.0, registry.find("fabt.webhook.delivery.count")
                .tag("event_type", "availability.updated").tag("status", "success").counter().count());
        assertEquals(1.0, registry.find("fabt.webhook.delivery.count")
                .tag("event_type", "availability.updated").tag("status", "failure").counter().count());
    }

    @Test
    void webhookDeliveryTimer_recordsWithTags() {
        Timer timer = metrics.webhookDeliveryTimer("surge.activated");
        timer.record(() -> {});

        Timer found = registry.find("fabt.webhook.delivery.duration")
                .tag("event_type", "surge.activated")
                .timer();
        assertNotNull(found);
        assertEquals(1, found.count());
    }
}
