package org.fabt.surge.service;

import java.util.List;

import org.fabt.surge.domain.SurgeEvent;
import org.fabt.surge.repository.SurgeEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SurgeExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SurgeExpiryService.class);

    private final SurgeEventRepository repository;
    private final SurgeEventService surgeEventService;

    public SurgeExpiryService(SurgeEventRepository repository, SurgeEventService surgeEventService) {
        this.repository = repository;
        this.surgeEventService = surgeEventService;
    }

    @Scheduled(fixedRate = 60_000)
    public void expireScheduledSurges() {
        List<SurgeEvent> expired = repository.findExpired();
        for (SurgeEvent event : expired) {
            try {
                surgeEventService.expireSurge(event.getId());
                log.info("Surge event {} expired (scheduled_end passed)", event.getId());
            } catch (Exception e) {
                log.error("Failed to expire surge event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
