package org.fabt.shelter.county;

import java.util.List;

/**
 * Default seed list of all 100 North Carolina counties, used to populate
 * {@code tenant.config.active_counties} at tenant creation time when no
 * deployment-specific list is provided.
 *
 * <p>Per design D3 (transitional-reentry-support, warroom H2 revision
 * 2026-04-28): counties live in {@code tenant.config} (JSONB), NOT in a
 * DB-level enum. PLATFORM_OPERATOR can override pre- or post-creation
 * via the Phase G G-4.6 lifecycle endpoints. Setting
 * {@code active_counties = []} explicitly disables county validation
 * (free-text accepted) — useful for non-pilot deployments still
 * gathering their canonical list.
 *
 * <p>Source: U.S. Census Bureau county listing for North Carolina (FIPS
 * state code 37). Names match the canonical "<county name> County"
 * convention without the "County" suffix — the suffix is added in the
 * UI display layer to avoid duplicating "County County" when the value
 * is rendered verbatim.
 */
public final class NcCountyDefaults {

    /**
     * The full 100-county NC list, alphabetical. Immutable.
     */
    public static final List<String> COUNTIES = List.of(
        "Alamance", "Alexander", "Alleghany", "Anson", "Ashe",
        "Avery", "Beaufort", "Bertie", "Bladen", "Brunswick",
        "Buncombe", "Burke", "Cabarrus", "Caldwell", "Camden",
        "Carteret", "Caswell", "Catawba", "Chatham", "Cherokee",
        "Chowan", "Clay", "Cleveland", "Columbus", "Craven",
        "Cumberland", "Currituck", "Dare", "Davidson", "Davie",
        "Duplin", "Durham", "Edgecombe", "Forsyth", "Franklin",
        "Gaston", "Gates", "Graham", "Granville", "Greene",
        "Guilford", "Halifax", "Harnett", "Haywood", "Henderson",
        "Hertford", "Hoke", "Hyde", "Iredell", "Jackson",
        "Johnston", "Jones", "Lee", "Lenoir", "Lincoln",
        "Macon", "Madison", "Martin", "McDowell", "Mecklenburg",
        "Mitchell", "Montgomery", "Moore", "Nash", "New Hanover",
        "Northampton", "Onslow", "Orange", "Pamlico", "Pasquotank",
        "Pender", "Perquimans", "Person", "Pitt", "Polk",
        "Randolph", "Richmond", "Robeson", "Rockingham", "Rowan",
        "Rutherford", "Sampson", "Scotland", "Stanly", "Stokes",
        "Surry", "Swain", "Transylvania", "Tyrrell", "Union",
        "Vance", "Wake", "Warren", "Washington", "Watauga",
        "Wayne", "Wilkes", "Wilson", "Yadkin", "Yancey"
    );

    private NcCountyDefaults() {
        // Static-only.
    }
}
