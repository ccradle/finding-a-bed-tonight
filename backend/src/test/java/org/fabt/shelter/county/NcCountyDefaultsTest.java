package org.fabt.shelter.county;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the {@link NcCountyDefaults#COUNTIES} list shape so a future edit
 * (typo, accidental dupe, missed county on a copy-paste) is caught at build
 * time. Without this, every new tenant created via the seeder would silently
 * inherit a corrupted {@code active_counties} default.
 *
 * <p>Warroom M3 — slice 1 follow-up.
 */
class NcCountyDefaultsTest {

    @Test
    void counties_list_has_exactly_100_entries() {
        assertThat(NcCountyDefaults.COUNTIES)
            .as("North Carolina has exactly 100 counties (Census Bureau FIPS 37). "
                + "If this fails, the constant has gained or lost a county.")
            .hasSize(100);
    }

    @Test
    void counties_list_contains_no_duplicates() {
        Set<String> unique = new HashSet<>(NcCountyDefaults.COUNTIES);
        assertThat(unique)
            .as("Duplicates would silently corrupt the active_counties seed for new tenants. "
                + "Each county must appear exactly once.")
            .hasSameSizeAs(NcCountyDefaults.COUNTIES);
    }

    @Test
    void counties_list_is_alphabetically_sorted() {
        // The class JavaDoc claims alphabetical ordering. Locking it avoids
        // accidental insertion in the wrong slot during a future edit, and
        // makes the dropdown UI predictable.
        for (int i = 1; i < NcCountyDefaults.COUNTIES.size(); i++) {
            String prev = NcCountyDefaults.COUNTIES.get(i - 1);
            String curr = NcCountyDefaults.COUNTIES.get(i);
            assertThat(prev.compareTo(curr))
                .as("NcCountyDefaults.COUNTIES must be alphabetically sorted; out of order at index %d (%s -> %s)",
                    i, prev, curr)
                .isLessThan(0);
        }
    }

    @Test
    void counties_list_includes_canonical_pilot_counties() {
        // Lock the entries the reentry-spec change references explicitly
        // (Johnston is the warroom test scenario county; Buncombe is the
        // largest reentry-population catchment in the pilot region per
        // design D3 commentary). If either is removed, the change's
        // documented test scenarios become non-functional.
        assertThat(NcCountyDefaults.COUNTIES)
            .contains("Johnston", "Buncombe", "Wake", "Mecklenburg");
    }

    @Test
    void counties_list_is_immutable() {
        // List.of() guarantees immutability per its contract. Lock the
        // contract so a refactor to ArrayList (mutable) is caught.
        assertThat(NcCountyDefaults.COUNTIES.getClass().getName())
            .as("Must remain an immutable List (List.of()). Mutability would let runtime "
                + "code accidentally mutate the seed for all future tenants.")
            .startsWith("java.util.ImmutableCollections");
    }
}
