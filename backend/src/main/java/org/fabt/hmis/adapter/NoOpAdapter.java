package org.fabt.hmis.adapter;

import java.util.List;

import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default adapter when no HMIS vendor is configured.
 * Logs and succeeds — no external call made.
 */
@Component
public class NoOpAdapter implements HmisVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(NoOpAdapter.class);

    @Override
    public void push(List<HmisInventoryRecord> records, HmisVendorConfig config) {
        log.debug("NoOp HMIS adapter — {} records would be pushed (no vendor configured)", records.size());
    }

    @Override
    public String vendorType() {
        return "NOOP";
    }
}
