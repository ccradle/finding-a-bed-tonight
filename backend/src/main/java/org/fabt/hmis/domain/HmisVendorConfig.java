package org.fabt.hmis.domain;

/**
 * Configuration for an HMIS vendor connection. Stored in tenant config JSONB.
 */
public record HmisVendorConfig(
        String id,
        HmisVendorType type,
        String baseUrl,
        String apiKeyEncrypted,
        boolean enabled,
        int pushIntervalHours
) {
    public HmisVendorConfig {
        if (pushIntervalHours <= 0) pushIntervalHours = 6;
    }
}
