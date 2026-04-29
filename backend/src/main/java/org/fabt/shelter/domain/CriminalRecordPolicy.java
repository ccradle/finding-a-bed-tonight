package org.fabt.shelter.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Self-reported criminal-record-screening policy for a shelter (transitional-
 * reentry-support task 3.4 / design D1 / D11).
 *
 * <p>Lives inside {@link EligibilityCriteria} as a sub-object. All fields
 * nullable — partial population is the normal state. The platform represents
 * what shelter programs report; it does not certify, verify, or endorse any
 * particular policy.
 *
 * <p>Field meanings:
 * <ul>
 *   <li>{@code acceptsFelonies}: shelter accepts individuals with prior felony
 *       convictions in general (subject to {@code excludedOffenseTypes}).</li>
 *   <li>{@code excludedOffenseTypes}: controlled vocabulary list of offense
 *       categories the shelter does NOT accept. Values per design D11:
 *       {@code SEX_OFFENSE}, {@code ARSON}, {@code DRUG_MANUFACTURING},
 *       {@code VIOLENT_FELONY}, {@code PENDING_CHARGES}, {@code OPEN_WARRANTS}.
 *       Free-text values are accepted on read for forward compatibility but
 *       admin UI uses the controlled vocabulary multi-select.</li>
 *   <li>{@code individualizedAssessment}: shelter performs case-by-case
 *       evaluation rather than categorical exclusion (relevant for
 *       VIOLENT_FELONY especially).</li>
 *   <li>{@code vawaProtectionsApply}: shelter recognizes VAWA protections —
 *       survivors of domestic violence whose criminal record relates to the
 *       violence may be admitted regardless of categorical exclusions.
 *       The platform does not adjudicate; the navigator-facing disclaimer
 *       (Casey-reviewed strings) flags this nuance for case-by-case
 *       investigation. See design D6 H5 revision.</li>
 *   <li>{@code notes}: free-text shelter-supplied context (max 500 chars per
 *       open question #1 resolution).</li>
 * </ul>
 *
 * <p>JSON shape (preserves snake_case keys per the JSONB schema in design D1
 * — the shelter_constraints JSONB column already exists with that key shape
 * in V92, do not break it). {@code @JsonInclude(NON_NULL)} omits unset keys
 * from the serialized form so partial JSONB stays compact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CriminalRecordPolicy(
    @JsonProperty("accepts_felonies") Boolean acceptsFelonies,
    @JsonProperty("excluded_offense_types") List<String> excludedOffenseTypes,
    @JsonProperty("individualized_assessment") Boolean individualizedAssessment,
    @JsonProperty("vawa_protections_apply") Boolean vawaProtectionsApply,
    @JsonProperty("notes") String notes
) {
    /**
     * Convenience constructor for the all-null shape (e.g., when the JSONB
     * has the {@code criminal_record_policy} key but all sub-fields absent).
     */
    public static CriminalRecordPolicy empty() {
        return new CriminalRecordPolicy(null, null, null, null, null);
    }
}
