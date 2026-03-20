package org.fabt.shared.config;

/**
 * Wrapper type for String values stored in PostgreSQL JSONB columns.
 * Used by Spring Data JDBC converters to distinguish JSONB from regular VARCHAR.
 */
public record JsonString(String value) {

    public static JsonString of(String json) {
        return new JsonString(json != null ? json : "{}");
    }

    public static JsonString empty() {
        return new JsonString("{}");
    }

    @Override
    public String toString() {
        return value;
    }
}
