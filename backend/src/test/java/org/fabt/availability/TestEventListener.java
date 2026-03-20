package org.fabt.availability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fabt.shared.event.DomainEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Test helper that captures DomainEvents published via Spring's ApplicationEventPublisher.
 * Used by AvailabilityIntegrationTest to verify event publishing.
 */
@Component
public class TestEventListener {

    private final List<DomainEvent> events = Collections.synchronizedList(new ArrayList<>());

    @EventListener
    public void onDomainEvent(DomainEvent event) {
        events.add(event);
    }

    public List<DomainEvent> getEvents() {
        return List.copyOf(events);
    }

    public List<DomainEvent> getEventsByType(String type) {
        return events.stream()
                .filter(e -> type.equals(e.type()))
                .toList();
    }

    public void clear() {
        events.clear();
    }
}
