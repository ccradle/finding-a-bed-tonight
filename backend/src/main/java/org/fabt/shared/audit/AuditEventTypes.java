package org.fabt.shared.audit;

/**
 * Canonical audit event action constants.
 *
 * <p>Centralizes string literals used as the {@code action} field of
 * {@link AuditEventRecord}. New audit actions should be added here rather than
 * passed as raw strings, so that test pins (e.g., {@code AuditEventTypesTest})
 * can guarantee a constant exists with a stable, non-empty value.</p>
 */
public final class AuditEventTypes {

    private AuditEventTypes() {}

    /**
     * Written by the bed-holds reconciliation tasklet whenever a corrective
     * snapshot is fired to bring {@code bed_availability.beds_on_hold} back into
     * agreement with the actual count of HELD reservations for that
     * shelter+population.
     *
     * <p>Payload: {@code {shelter_id, population_type, snapshot_value_before,
     * actual_count, delta}}.</p>
     *
     * <p>Actor: {@code null} (system-driven).</p>
     */
    public static final String BED_HOLDS_RECONCILED = "BED_HOLDS_RECONCILED";
}
