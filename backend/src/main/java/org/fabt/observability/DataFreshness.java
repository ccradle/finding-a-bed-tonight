package org.fabt.observability;

public enum DataFreshness {
    FRESH,    // < 2 hours (7200s)
    AGING,    // 2-8 hours (7200-28800s)
    STALE,    // > 8 hours (28800s)
    UNKNOWN;  // no timestamp

    public static DataFreshness fromAgeSeconds(Long ageSeconds) {
        if (ageSeconds == null) return UNKNOWN;
        if (ageSeconds < 7200) return FRESH;
        if (ageSeconds < 28800) return AGING;
        return STALE;
    }
}
