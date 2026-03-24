package org.fabt.shelter.domain;

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

    public static DvAddressPolicy fromString(String value) {
        if (value == null || value.isBlank()) return ADMIN_AND_ASSIGNED;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return ADMIN_AND_ASSIGNED;
        }
    }
}
