package org.fabt.hmis.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HMIS push scheduling has been migrated to Spring Batch (Design D13).
 *
 * The batch job {@code hmisPush} in {@code org.fabt.analytics.batch.HmisPushJobConfig}
 * now handles scheduling, retry, and execution history. Business logic remains in
 * {@code HmisPushService}.
 *
 * This class is retained as a marker for the migration. The @Scheduled annotation
 * has been removed — the BatchJobScheduler manages the cron schedule.
 *
 * @see org.fabt.analytics.batch.HmisPushJobConfig
 */
public class HmisPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(HmisPushScheduler.class);

    // Intentionally empty — scheduling moved to Spring Batch.
    // Retained for backward compatibility of any references.
}
