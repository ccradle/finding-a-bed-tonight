package org.fabt.shared.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("full")
public class KafkaEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    // TODO: Inject KafkaTemplate when Kafka profile dependencies are active

    @Override
    public void publish(DomainEvent event) {
        log.debug("Publishing event via Kafka: type={}, tenant={}",
                event.type(), event.tenantId());
        // TODO: kafkaTemplate.send(event.type(), event.tenantId().toString(), event);
    }
}
