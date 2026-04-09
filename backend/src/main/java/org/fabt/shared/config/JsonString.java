package org.fabt.shared.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wrapper type for String values stored in PostgreSQL JSONB columns.
 * Used by Spring Data JDBC converters to distinguish JSONB from regular VARCHAR.
 *
 * <p>{@code @JsonValue} on {@link #value()} ensures Jackson serializes the raw
 * JSON string, not the record wrapper. Without this, entities returned directly
 * from REST endpoints (without a DTO) would serialize as {@code {"value":"..."}}
 * instead of the raw JSON content.</p>
 */
public record JsonString(@JsonValue String value) {

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
