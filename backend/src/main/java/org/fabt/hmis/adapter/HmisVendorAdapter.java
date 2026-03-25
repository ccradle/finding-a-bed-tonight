package org.fabt.hmis.adapter;

import java.util.List;

import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisVendorConfig;

/**
 * Strategy interface for HMIS vendor push.
 * Each vendor implements its own transport (REST API, CSV, etc).
 */
public interface HmisVendorAdapter {

    /**
     * Push inventory records to the vendor.
     * @throws Exception on failure (will be retried via outbox)
     */
    void push(List<HmisInventoryRecord> records, HmisVendorConfig config) throws Exception;

    /**
     * Which vendor type this adapter handles.
     */
    String vendorType();
}
