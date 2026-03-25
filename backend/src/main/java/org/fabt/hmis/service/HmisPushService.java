package org.fabt.hmis.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.fabt.hmis.adapter.HmisVendorAdapter;
import org.fabt.hmis.adapter.NoOpAdapter;
import org.fabt.hmis.domain.HmisAuditEntry;
import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisOutboxEntry;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.fabt.hmis.repository.HmisAuditRepository;
import org.fabt.hmis.repository.HmisOutboxRepository;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates HMIS push: read snapshots → transform → outbox → push → audit.
 * Circuit breaker and retry logic handled at the adapter level.
 */
@Service
public class HmisPushService {

    private static final Logger log = LoggerFactory.getLogger(HmisPushService.class);
    private static final int MAX_RETRIES = 3;

    private final HmisTransformer transformer;
    private final HmisConfigService configService;
    private final HmisOutboxRepository outboxRepository;
    private final HmisAuditRepository auditRepository;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, HmisVendorAdapter> adaptersByType;
    private final NoOpAdapter noOpAdapter;

    public HmisPushService(HmisTransformer transformer,
                           HmisConfigService configService,
                           HmisOutboxRepository outboxRepository,
                           HmisAuditRepository auditRepository,
                           TenantService tenantService,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           List<HmisVendorAdapter> adapters,
                           NoOpAdapter noOpAdapter) {
        this.transformer = transformer;
        this.configService = configService;
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.noOpAdapter = noOpAdapter;

        this.adaptersByType = adapters.stream()
                .filter(a -> !(a instanceof NoOpAdapter))
                .collect(Collectors.toMap(HmisVendorAdapter::vendorType, Function.identity()));

        // Dead letter gauge
        Gauge.builder("fabt.hmis.dead_letter_count", outboxRepository, HmisOutboxRepository::countDeadLetter)
                .description("Current count of HMIS dead letter entries")
                .register(meterRegistry);
    }

    /**
     * Create outbox entries for all enabled vendors for a tenant.
     */
    @Transactional
    public int createOutboxEntries(UUID tenantId) {
        List<HmisVendorConfig> vendors = configService.getEnabledVendors(tenantId);
        if (vendors.isEmpty()) {
            return 0;
        }

        List<HmisInventoryRecord> records = transformer.buildInventory(tenantId);
        if (records.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (HmisVendorConfig vendor : vendors) {
            try {
                String payload = objectMapper.writeValueAsString(records);
                HmisOutboxEntry entry = new HmisOutboxEntry(
                        tenantId, null, vendor.type().name(), payload);
                outboxRepository.insert(entry);
                created++;
            } catch (Exception e) {
                log.error("Failed to create outbox entry for vendor {} tenant {}: {}",
                        vendor.type(), tenantId, e.getMessage());
            }
        }
        return created;
    }

    /**
     * Process pending outbox entries — push to vendors, handle failures.
     */
    @Transactional
    public void processOutbox() {
        List<HmisOutboxEntry> pending = outboxRepository.findPending();
        for (HmisOutboxEntry entry : pending) {
            processEntry(entry);
        }
    }

    private void processEntry(HmisOutboxEntry entry) {
        HmisVendorAdapter adapter = adaptersByType.getOrDefault(entry.getVendorType(), noOpAdapter);

        // Find vendor config for this entry
        List<HmisVendorConfig> vendors = configService.getEnabledVendors(entry.getTenantId());
        HmisVendorConfig config = vendors.stream()
                .filter(v -> v.type().name().equals(entry.getVendorType()))
                .findFirst()
                .orElse(null);

        if (config == null) {
            log.warn("No enabled config for vendor {} tenant {} — skipping", entry.getVendorType(), entry.getTenantId());
            outboxRepository.updateStatus(entry.getId(), "DEAD_LETTER", "Vendor not configured or disabled");
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<HmisInventoryRecord> records = objectMapper.readValue(
                    entry.getPayload(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, HmisInventoryRecord.class));

            adapter.push(records, config);

            // Success
            outboxRepository.updateStatus(entry.getId(), "SENT", null);
            String payloadHash = sha256(entry.getPayload());
            auditRepository.insert(new HmisAuditEntry(
                    entry.getTenantId(), entry.getVendorType(), records.size(),
                    "SUCCESS", null, payloadHash));

            pushCounter(entry.getVendorType()).increment();
            recordsPushedCounter(entry.getVendorType()).increment(records.size());
            log.info("HMIS push succeeded: vendor={}, tenant={}, records={}",
                    entry.getVendorType(), entry.getTenantId(), records.size());

        } catch (Exception e) {
            log.error("HMIS push failed: vendor={}, tenant={}, attempt={}, error={}",
                    entry.getVendorType(), entry.getTenantId(), entry.getRetryCount() + 1, e.getMessage());

            pushFailureCounter(entry.getVendorType()).increment();

            if (entry.getRetryCount() + 1 >= MAX_RETRIES) {
                outboxRepository.updateStatus(entry.getId(), "DEAD_LETTER", e.getMessage());
                auditRepository.insert(new HmisAuditEntry(
                        entry.getTenantId(), entry.getVendorType(), 0,
                        "FAILED", e.getMessage(), null));
            } else {
                outboxRepository.updateStatus(entry.getId(), "PENDING", e.getMessage());
            }
        } finally {
            sample.stop(pushDurationTimer(entry.getVendorType()));
        }
    }

    /**
     * Get the data preview (what would be pushed) for the Admin UI.
     */
    public List<HmisInventoryRecord> getPreview(UUID tenantId) {
        return transformer.buildInventory(tenantId);
    }

    /**
     * Retry a dead-letter entry.
     */
    @Transactional
    public void retryDeadLetter(UUID outboxId) {
        outboxRepository.resetToPending(outboxId);
    }

    private Counter pushCounter(String vendor) {
        return Counter.builder("fabt.hmis.push.total").tag("vendor", vendor).register(meterRegistry);
    }

    private Counter pushFailureCounter(String vendor) {
        return Counter.builder("fabt.hmis.push.failures.total").tag("vendor", vendor).register(meterRegistry);
    }

    private Counter recordsPushedCounter(String vendor) {
        return Counter.builder("fabt.hmis.records.pushed.total").tag("vendor", vendor).register(meterRegistry);
    }

    private Timer pushDurationTimer(String vendor) {
        return Timer.builder("fabt.hmis.push.duration").tag("vendor", vendor)
                .description("HMIS push duration").register(meterRegistry);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
