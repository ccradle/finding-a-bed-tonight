package org.fabt.analytics.service;

import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.analytics.repository.BedSearchLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logs every bed search to the bed_search_log table for demand analysis (Design D2).
 *
 * Zero-result searches are the strongest unmet demand signal:
 * "47 searches for SINGLE_ADULT beds with zero results last Tuesday night."
 */
@Service
public class BedSearchLogger {

    private static final Logger log = LoggerFactory.getLogger(BedSearchLogger.class);

    private final BedSearchLogRepository repository;
    private final Counter zeroResultCounter;

    public BedSearchLogger(BedSearchLogRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.zeroResultCounter = Counter.builder("fabt_search_zero_results_total")
                .description("Total bed searches returning zero results")
                .register(meterRegistry);
    }

    /**
     * Log a bed search event. Called after every search.
     *
     * @param tenantId       the tenant
     * @param populationType the population type searched (null if unfiltered)
     * @param resultsCount   number of shelters returned
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSearch(UUID tenantId, String populationType, int resultsCount) {
        try {
            repository.insert(tenantId, populationType, resultsCount);
            if (resultsCount == 0) {
                zeroResultCounter.increment();
            }
        } catch (Exception e) {
            // Demand logging must never block the search response
            log.warn("Failed to log bed search event", e);
        }
    }
}
