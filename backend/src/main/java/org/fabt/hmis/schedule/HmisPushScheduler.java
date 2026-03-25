package org.fabt.hmis.schedule;

import java.util.UUID;

import org.fabt.hmis.service.HmisConfigService;
import org.fabt.hmis.service.HmisPushService;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled HMIS push — creates outbox entries and processes them.
 * Runs every hour; actual push frequency is controlled by vendor config pushIntervalHours.
 */
@Component
public class HmisPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(HmisPushScheduler.class);

    private final HmisPushService pushService;
    private final TenantService tenantService;

    public HmisPushScheduler(HmisPushService pushService, TenantService tenantService) {
        this.pushService = pushService;
        this.tenantService = tenantService;
    }

    /**
     * Run every hour. Creates outbox entries for all tenants with enabled vendors,
     * then processes pending outbox entries.
     */
    @Scheduled(fixedRate = 3_600_000)
    public void scheduledPush() {
        log.debug("HMIS push scheduler running");

        // Create outbox entries for each tenant
        for (Tenant tenant : tenantService.findAll()) {
            try {
                int created = pushService.createOutboxEntries(tenant.getId());
                if (created > 0) {
                    log.info("Created {} HMIS outbox entries for tenant {}", created, tenant.getId());
                }
            } catch (Exception e) {
                log.error("Failed to create outbox entries for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }

        // Process all pending entries
        try {
            pushService.processOutbox();
        } catch (Exception e) {
            log.error("Failed to process HMIS outbox: {}", e.getMessage());
        }
    }
}
