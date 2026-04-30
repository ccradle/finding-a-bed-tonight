package org.fabt.shelter.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured eligibility criteria for a shelter (transitional-reentry-support
 * task 3.4 / design D1).
 *
 * <p>Persisted to the {@code shelter_constraints.eligibility_criteria} JSONB
 * column added in V92. The service layer ser/deserializes via {@code ObjectMapper};
 * the entity layer holds the raw JSON as a {@link org.fabt.shared.config.JsonString}
 * wrapper (matches existing JSONB-handling pattern, e.g.
 * {@code tenant.config}).
 *
 * <p>All fields nullable — partial population is the normal state. The
 * {@code criminal_record_policy} sub-object is the primary reentry use case
 * ({@link CriminalRecordPolicy}); {@code program_requirements},
 * {@code documentation_required}, {@code intake_hours} serve the transitional
 * housing navigator use case; {@code custom_tags} is the escape valve for
 * program-specific labels not covered by the controlled vocabulary.
 *
 * <p>JSON shape preserves snake_case keys to match the JSONB schema documented
 * in design D1 — do not change without a coordinated migration of stored data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EligibilityCriteria(
    @JsonProperty("criminal_record_policy") CriminalRecordPolicy criminalRecordPolicy,
    @JsonProperty("program_requirements") List<String> programRequirements,
    @JsonProperty("documentation_required") List<String> documentationRequired,
    @JsonProperty("intake_hours") String intakeHours,
    @JsonProperty("custom_tags") List<String> customTags
) {
    /** Convenience constructor for an entirely empty criteria object. */
    public static EligibilityCriteria empty() {
        return new EligibilityCriteria(null, null, null, null, null);
    }
}
