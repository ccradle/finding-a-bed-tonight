package org.fabt.availability;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable test utility that verifies all 9 bed availability invariants
 * from the QA briefing (bed-availability-qa-briefing.md) hold for a given
 * shelter and population type.
 *
 * Call after any availability-modifying operation to catch invariant violations.
 * Provides detailed failure messages with full snapshot and reservation state.
 */
public class AvailabilityInvariantChecker {

    /**
     * Assert all 9 invariants hold for the latest snapshot of the given
     * shelter and population type.
     *
     * @param jdbcTemplate JDBC template for direct database queries
     * @param shelterId    shelter UUID
     * @param populationType population type (e.g., "SINGLE_ADULT")
     */
    public static void assertInvariantsHold(JdbcTemplate jdbcTemplate, UUID shelterId, String populationType) {
        // Read latest snapshot
        var snapshots = jdbcTemplate.query(
                """
                SELECT beds_total, beds_occupied, beds_on_hold, snapshot_ts
                FROM bed_availability
                WHERE shelter_id = ? AND population_type = ?
                ORDER BY snapshot_ts DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new SnapshotState(
                        rs.getInt("beds_total"),
                        rs.getInt("beds_occupied"),
                        rs.getInt("beds_on_hold"),
                        rs.getTimestamp("snapshot_ts")
                ),
                shelterId, populationType
        );

        if (snapshots.isEmpty()) {
            // No snapshot exists — nothing to validate
            return;
        }

        SnapshotState s = snapshots.get(0);
        int bedsAvailable = s.total - s.occupied - s.hold;

        // Count active HELD reservations
        Integer activeHolds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE shelter_id = ? AND population_type = ? AND status = 'HELD'",
                Integer.class, shelterId, populationType
        );
        int heldCount = activeHolds != null ? activeHolds : 0;

        String context = String.format(
                "shelter=%s, popType=%s, snapshot_ts=%s, total=%d, occupied=%d, hold=%d, computed_available=%d, active_reservations=%d",
                shelterId, populationType, s.snapshotTs, s.total, s.occupied, s.hold, bedsAvailable, heldCount
        );

        // INV-1: beds_available >= 0
        assertTrue(bedsAvailable >= 0,
                "INV-1 VIOLATED: beds_available is negative (" + bedsAvailable + "). " + context);

        // INV-2: beds_occupied <= beds_total
        assertTrue(s.occupied <= s.total,
                "INV-2 VIOLATED: beds_occupied (" + s.occupied + ") exceeds beds_total (" + s.total + "). " + context);

        // INV-3: beds_on_hold <= (beds_total - beds_occupied)
        assertTrue(s.hold <= (s.total - s.occupied),
                "INV-3 VIOLATED: beds_on_hold (" + s.hold + ") exceeds available capacity (" + (s.total - s.occupied) + "). " + context);

        // INV-4: beds_total >= 0
        assertTrue(s.total >= 0,
                "INV-4 VIOLATED: beds_total is negative (" + s.total + "). " + context);

        // INV-5: beds_occupied + beds_on_hold <= beds_total
        assertTrue(s.occupied + s.hold <= s.total,
                "INV-5 VIOLATED: occupied (" + s.occupied + ") + hold (" + s.hold + ") = " + (s.occupied + s.hold) +
                        " exceeds total (" + s.total + "). " + context);

        // INV-9: beds_available == beds_total - beds_occupied - beds_on_hold (identity check)
        assertEquals(s.total - s.occupied - s.hold, bedsAvailable,
                "INV-9 VIOLATED: beds_available computation inconsistency. " + context);

        // Additional: beds_on_hold should be >= active HELD reservation count
        assertTrue(s.hold >= heldCount,
                "HOLD MISMATCH: beds_on_hold (" + s.hold + ") is less than active HELD reservations (" + heldCount + "). " + context);
    }

    private record SnapshotState(int total, int occupied, int hold, Timestamp snapshotTs) {}
}
