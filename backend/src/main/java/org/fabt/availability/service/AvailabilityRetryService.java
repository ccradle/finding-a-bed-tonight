package org.fabt.availability.service;

import java.util.UUID;

import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Retry wrapper for AvailabilityService — in a SEPARATE class to ensure
 * Spring AOP proxy intercepts the @Retryable annotation.
 *
 * Self-invocation within the same class bypasses AOP proxies, which would
 * silently disable both @Retryable AND @Transactional on the inner call.
 * This class exists solely to hold the retry wrapper.
 *
 * Retries on transient DataAccessException (connection pool exhaustion,
 * lock contention). Business exceptions (AvailabilityInvariantViolation,
 * NoSuchElementException) propagate immediately without retry.
 */
@Service
public class AvailabilityRetryService {

    private final AvailabilityService availabilityService;

    public AvailabilityRetryService(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @Retryable(
            includes = DataAccessException.class,
            maxRetries = 2,
            delay = 100,
            multiplier = 2,
            maxDelay = 1000
    )
    public AvailabilitySnapshot createSnapshotWithRetry(UUID shelterId, String populationType,
                                                         int bedsTotal, int bedsOccupied, int bedsOnHold,
                                                         boolean acceptingNewGuests, String notes,
                                                         String updatedBy, int overflowBeds) {
        return availabilityService.createSnapshot(shelterId, populationType, bedsTotal, bedsOccupied,
                bedsOnHold, acceptingNewGuests, notes, updatedBy, overflowBeds);
    }
}
