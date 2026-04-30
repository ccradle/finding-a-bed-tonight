package org.fabt.shelter.fixtures;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared test helper for inserting shelter rows directly via JDBC.
 *
 * <p>Tests that bypass {@link org.fabt.shelter.service.ShelterService} (e.g.,
 * fixtures in {@code DvAccessRlsTest}, {@code DvShelterConcurrentIsolationTest},
 * {@code AnalyticsIntegrationTest}) must satisfy the V91
 * {@code shelter_dv_implies_dv_type} CHECK constraint themselves: any
 * {@code dv_shelter=true} INSERT also requires {@code shelter_type='DV'}.
 *
 * <p>Centralizing this here means a future test author writing a fixture
 * doesn't have to remember the V91 coupling — they just call
 * {@link #insertShelter}. Without this helper, every fixture INSERT site
 * is a potential foot-gun (constraint will reject at runtime, but the test
 * author may not understand why immediately).
 *
 * <p>Caller is responsible for binding {@link org.fabt.shared.web.TenantContext}
 * before calling these methods (the {@code shelter} table is RLS-protected).
 *
 * <p>Created during slice-1 warroom round (H3) for transitional-reentry-support.
 */
public final class TestShelterFixture {

    private TestShelterFixture() {
        // Static-only.
    }

    /**
     * Insert a shelter with the V91 invariant (dv_shelter ↔ shelter_type='DV')
     * automatically satisfied. Returns the new shelter's UUID.
     */
    public static UUID insertShelter(JdbcTemplate jdbc, UUID tenantId, String name, boolean dvShelter) {
        UUID shelterId = UUID.randomUUID();
        String shelterType = dvShelter ? "DV" : "EMERGENCY";
        jdbc.update(
            "INSERT INTO shelter (id, tenant_id, name, dv_shelter, shelter_type, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?::varchar, NOW(), NOW())",
            shelterId, tenantId, name, dvShelter, shelterType);
        return shelterId;
    }

    /**
     * Insert a shelter at a specific UUID (for tests that need deterministic
     * IDs). Same V91-invariant guarantee as {@link #insertShelter}.
     */
    public static void insertShelterWithId(JdbcTemplate jdbc, UUID shelterId, UUID tenantId,
                                            String name, boolean dvShelter) {
        String shelterType = dvShelter ? "DV" : "EMERGENCY";
        jdbc.update(
            "INSERT INTO shelter (id, tenant_id, name, dv_shelter, shelter_type, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?::varchar, NOW(), NOW())",
            shelterId, tenantId, name, dvShelter, shelterType);
    }

    /**
     * Insert a shelter with full address fields (for tests that exercise
     * geographic / address-related behavior). Same V91-invariant guarantee.
     */
    public static UUID insertShelterWithAddress(JdbcTemplate jdbc, UUID tenantId, String name,
                                                  boolean dvShelter, String street, String city,
                                                  String state, String zip,
                                                  Double latitude, Double longitude) {
        UUID shelterId = UUID.randomUUID();
        String shelterType = dvShelter ? "DV" : "EMERGENCY";
        jdbc.update(
            "INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, "
            + "address_zip, dv_shelter, shelter_type, latitude, longitude, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::varchar, ?, ?, NOW(), NOW())",
            shelterId, tenantId, name, street, city, state, zip, dvShelter, shelterType, latitude, longitude);
        return shelterId;
    }
}
