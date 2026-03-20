package org.fabt.shared.event;

public interface EventBus {

    void publish(DomainEvent event);
}
