package org.fabt.shelter.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenant-level policy controlling DV shelter address visibility in API responses.
 * Configurable via tenant config JSONB field "dv_address_visibility".
 */
public enum DvAddressPolicy {

    /** PLATFORM_ADMIN, COC_ADMIN, and coordinators assigned to the shelter see the address. Default. */
    ADMIN_AND_ASSIGNED,

    /** Only PLATFORM_ADMIN and COC_ADMIN see the address. */
    ADMIN_ONLY,

    /** Any user with dvAccess=true sees the address (legacy behavior). */
    ALL_DV_ACCESS,

    /** No one sees the address in API responses. Address shared verbally only. */
    NONE;

    private static final Logger log = LoggerFactory.getLogger(DvAddressPolicy.class);

    public static DvAddressPolicy fromString(String value) {
        if (value == null || value.isBlank()) return ADMIN_AND_ASSIGNED;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown DV address policy '{}', using default ADMIN_AND_ASSIGNED", e.getMessage());
            return ADMIN_AND_ASSIGNED;
        }
    }
}
