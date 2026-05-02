package org.fabt.dataimport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.dataimport.service.ShelterImportService;
import org.fabt.dataimport.service.ImportResult;
import org.fabt.dataimport.service.ShelterImportService.ShelterImportRow;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for the dv-policy-tenant-flag invariant on the 211 CSV bulk-import path
 * (task §5.6). Verifies Demetrius's per-row-reject semantics are preserved
 * end-to-end: when a tenant has {@code dv_policy_enabled=false}, an import
 * containing a mix of DV and non-DV rows results in:
 * <ul>
 *   <li>Non-DV rows persisted successfully (created counter increments).</li>
 *   <li>DV rows rejected with {@link ErrorCodes#SHELTER_DV_SHELTER_REQUIRES_DV_POLICY}
 *       captured per-row in {@code ImportResult.errorDetails}.</li>
 *   <li>Import does NOT fail wholesale — the loop continues past the rejected rows.</li>
 * </ul>
 *
 * <p>Behavior is captured implicitly today by the existing per-row try/catch
 * at {@code ShelterImportService.importShelters:162}; this IT locks it in
 * against future refactors (e.g., a switch to bulk-insert that bypasses the
 * service-layer guard would silently regress the behavior without this test).
 */
@DisplayName("ShelterImportService — dv-policy-tenant-flag invariant per-row reject")
class ShelterImportDvPolicyInvariantTest extends BaseIntegrationTest {

    @Autowired private ShelterImportService importService;
    @Autowired private JdbcTemplate jdbc;

    /** Inserts a fresh test tenant with explicit dv_policy_enabled state. */
    private UUID createTenant(String slugSuffix, boolean dvPolicyEnabled) {
        UUID id = UUID.randomUUID();
        String configJson = dvPolicyEnabled
                ? "{\"dv_policy_enabled\":true}"
                : "{}";
        jdbc.update(
                "INSERT INTO tenant (id, name, slug, config) VALUES (?, ?, ?, ?::jsonb)",
                id, "Import IT " + slugSuffix, "importit-" + slugSuffix, configJson);
        return id;
    }

    private ShelterImportRow row(String name, boolean dvShelter) {
        Map<String, Integer> capacity = new HashMap<>();
        capacity.put("SINGLE_ADULT", 10);
        return new ShelterImportRow(
                name,
                "100 Test Way",
                "Raleigh",
                "NC",
                "27601",
                "919-555-0000",
                35.78,
                -78.64,
                dvShelter,
                false,  // sobrietyRequired
                false,  // idRequired
                false,  // referralRequired
                false,  // petsAllowed
                false,  // wheelchairAccessible
                null,   // curfewTime
                null,   // maxStayDays
                new String[]{"SINGLE_ADULT"},
                capacity,
                0       // bedsOccupied
        );
    }

    @Test
    @DisplayName("Mixed import on flag-OFF tenant — DV rows rejected per-row, non-DV rows succeed")
    void mixedImportFlagOff() throws Exception {
        UUID tenantId = createTenant("mixed-off-" + UUID.randomUUID().toString().substring(0, 8), false);

        List<ShelterImportRow> rows = List.of(
                row("Non-DV A", false),
                row("DV Reject 1", true),
                row("Non-DV B", false),
                row("DV Reject 2", true),
                row("Non-DV C", false));

        ImportResult result = TenantContext.callWithContext(tenantId, true,
                () -> importService.importShelters("211_CSV", "test.csv", rows));

        // 3 non-DV rows persisted; 2 DV rows rejected; loop continued
        assertThat(result.created()).isEqualTo(3);
        assertThat(result.errors()).isEqualTo(2);

        // Per-row error details mention the structured code
        long dvRejectionCount = result.errorDetails().stream()
                .filter(e -> e.message() != null
                        && e.message().contains("DV shelter operations are not enabled"))
                .count();
        assertThat(dvRejectionCount)
                .as("Each DV row MUST surface the dv-policy invariant rejection in its per-row error")
                .isEqualTo(2);

        // Confirm DB state — only 3 non-DV rows landed
        Long shelterCount = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Long.class, tenantId));
        assertThat(shelterCount).isEqualTo(3);
    }

    @Test
    @DisplayName("All-DV import on flag-OFF tenant — every row rejected, no shelters created")
    void allDvImportFlagOff() throws Exception {
        UUID tenantId = createTenant("all-dv-off-" + UUID.randomUUID().toString().substring(0, 8), false);

        List<ShelterImportRow> rows = List.of(
                row("DV A", true),
                row("DV B", true),
                row("DV C", true));

        ImportResult result = TenantContext.callWithContext(tenantId, true,
                () -> importService.importShelters("211_CSV", "test.csv", rows));

        assertThat(result.created()).isZero();
        assertThat(result.errors()).isEqualTo(3);
        Long shelterCount = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Long.class, tenantId));
        assertThat(shelterCount).isZero();
    }

    @Test
    @DisplayName("Mixed import on flag-ON tenant — all rows succeed regardless of DV flag")
    void mixedImportFlagOn() throws Exception {
        UUID tenantId = createTenant("mixed-on-" + UUID.randomUUID().toString().substring(0, 8), true);

        List<ShelterImportRow> rows = List.of(
                row("Non-DV X", false),
                row("DV Y", true),
                row("DV Z", true));

        ImportResult result = TenantContext.callWithContext(tenantId, true,
                () -> importService.importShelters("211_CSV", "test.csv", rows));

        assertThat(result.created()).isEqualTo(3);
        assertThat(result.errors()).isZero();
        Long shelterCount = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Long.class, tenantId));
        assertThat(shelterCount).isEqualTo(3);
    }
}
