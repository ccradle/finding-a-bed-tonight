package org.fabt.shelter.service;

import java.util.Optional;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShelterService#isValidCounty(UUID, String)} —
 * the 4-branch state machine per design D3 (transitional-reentry-support,
 * slice-2 warroom 17.H3).
 *
 * <p>Branches under test:
 * <ol>
 *   <li>{@code county == null} → always TRUE (no constraint).</li>
 *   <li>{@code active_counties} key present, value {@code []} →
 *       TRUE (validation explicitly disabled per design D3).</li>
 *   <li>{@code active_counties} present and non-empty → match-or-miss
 *       against the listed strings (case-sensitive equality).</li>
 *   <li>{@code active_counties} key absent → fall back to
 *       {@link org.fabt.shelter.county.NcCountyDefaults#COUNTIES}.</li>
 * </ol>
 *
 * <p>Test pattern: each branch has a positive case (county SHOULD pass)
 * and a negative case (county SHOULD fail) where the input differs only
 * in the dimension being tested. Boundary cases are explicit:
 * <ul>
 *   <li>"county NOT in NC defaults but config explicitly empty → accept"
 *       (proves branch 2 overrides branch 4).</li>
 *   <li>"county IN NC defaults but config explicitly excludes → reject"
 *       (proves branch 3 overrides branch 4).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShelterService.isValidCounty — D3 4-branch state machine")
class ShelterServiceIsValidCountyTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private TenantService tenantService;

    private ShelterService shelterService;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        // The remaining 10 ShelterService deps are not exercised by isValidCounty —
        // pass null so the test fails loudly if a future change introduces a new
        // hidden dependency on isValidCounty's path.
        shelterService = new ShelterService(
                /*shelterRepository*/             null,
                /*constraintsRepository*/         null,
                /*availabilityService*/           null,
                tenantService,
                objectMapper,
                /*jdbcTemplate*/                  null,
                /*eventPublisher*/                null,
                /*cacheService*/                  null,
                /*reservationService*/            null,
                /*referralTokenService*/          null,
                /*notificationPersistenceService*/null,
                /*userService*/                   null);
    }

    // ---------------------------------------------------------------------
    // Branch 1 — county null
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Branch 1: county=null → TRUE without consulting tenant config")
    void nullCounty_isAlwaysValid_andSkipsTenantLookup() {
        boolean valid = shelterService.isValidCounty(TENANT_ID, null);

        assertThat(valid).isTrue();
        // Performance-meaningful guarantee: don't fetch tenant config when there's
        // no county to validate.
        verify(tenantService, never()).findById(TENANT_ID);
    }

    // ---------------------------------------------------------------------
    // Branch 2 — explicit empty array → validation disabled
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Branch 2: active_counties=[] accepts any non-null county (validation disabled)")
    void explicitEmptyArray_acceptsAnyCounty() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig("{\"active_counties\": []}")));

        // Wake IS in NC defaults — but the empty array means we skip that fallback too.
        // To prove the empty array specifically (not a coincidence), use a county
        // that is NOT in the NC list — would fail under branches 3 OR 4.
        assertThat(shelterService.isValidCounty(TENANT_ID, "Manhattan"))
                .as("Manhattan is not an NC county, but [] disables validation")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // Branch 3 — explicit list, match-or-miss
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Branch 3: county in active_counties → TRUE")
    void countyInExplicitList_isValid() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"active_counties\": [\"Wake\", \"Durham\", \"Mecklenburg\"]}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake")).isTrue();
        assertThat(shelterService.isValidCounty(TENANT_ID, "Durham")).isTrue();
    }

    @Test
    @DisplayName("Branch 3: county NOT in active_counties → FALSE (overrides NC default fallback)")
    void countyNotInExplicitList_isInvalid_evenIfInNcDefaults() {
        // Wake IS in NC defaults — proves branch 3 wins over branch 4 fallback.
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"active_counties\": [\"Durham\", \"Mecklenburg\"]}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake"))
                .as("Wake is in NC defaults but excluded from this tenant's active_counties")
                .isFalse();
    }

    @Test
    @DisplayName("Branch 3: case-sensitive match — \"wake\" rejected when list has \"Wake\"")
    void caseMismatch_isInvalid() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"active_counties\": [\"Wake\"]}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "wake"))
                .as("isValidCounty uses case-sensitive equality (matches NcCountyDefaults canonical casing)")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Branch 4 — key absent → fall back to NcCountyDefaults
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Branch 4: active_counties key absent → match against NcCountyDefaults (positive)")
    void keyAbsent_fallsBackToNcDefaults_positive() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"holdDurationMinutes\": 90}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake"))
                .as("Wake is in NcCountyDefaults; key-absent must fall back to defaults")
                .isTrue();
    }

    @Test
    @DisplayName("Branch 4: active_counties key absent + non-NC county → FALSE")
    void keyAbsent_fallsBackToNcDefaults_negative() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"holdDurationMinutes\": 90}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Manhattan"))
                .as("Manhattan is not an NC county — must be rejected under default fallback")
                .isFalse();
    }

    @Test
    @DisplayName("Branch 4: tenant config null → fall back to NcCountyDefaults")
    void tenantConfigNull_fallsBackToNcDefaults() {
        Tenant t = tenantWithConfig(null);
        when(tenantService.findById(TENANT_ID)).thenReturn(Optional.of(t));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake")).isTrue();
        assertThat(shelterService.isValidCounty(TENANT_ID, "Manhattan")).isFalse();
    }

    @Test
    @DisplayName("Tenant not found → fall back to NcCountyDefaults (defensive)")
    void tenantNotFound_fallsBackToNcDefaults() {
        when(tenantService.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake"))
                .as("missing-tenant fallback must accept NC counties — never silently reject all")
                .isTrue();
        assertThat(shelterService.isValidCounty(TENANT_ID, "Manhattan")).isFalse();
    }

    // ---------------------------------------------------------------------
    // Defensive: malformed JSON config → log+fall back
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Malformed tenant config JSON → fall back to NcCountyDefaults")
    void malformedConfigJson_fallsBackToNcDefaults() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig("{not valid json")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake")).isTrue();
        assertThat(shelterService.isValidCounty(TENANT_ID, "Manhattan")).isFalse();
    }

    // ---------------------------------------------------------------------
    // Boundary cases (explicit per spec 17.H3)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Boundary: county NOT in NC defaults but config explicitly [] → accept")
    void nonNcCounty_butConfigEmpty_accepts() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig("{\"active_counties\": []}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Brooklyn"))
                .as("non-NC county must be accepted when validation explicitly disabled")
                .isTrue();
    }

    @Test
    @DisplayName("Boundary: county IN NC defaults but config explicitly excludes → reject")
    void ncCounty_butConfigExcludes_rejects() {
        when(tenantService.findById(TENANT_ID))
                .thenReturn(Optional.of(tenantWithConfig(
                        "{\"active_counties\": [\"Durham\"]}")));

        assertThat(shelterService.isValidCounty(TENANT_ID, "Wake"))
                .as("Wake is in NC defaults; explicit list excludes it; must reject")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------

    private static Tenant tenantWithConfig(String configJson) {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        if (configJson != null) {
            t.setConfig(new JsonString(configJson));
        }
        return t;
    }
}
