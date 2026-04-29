package org.fabt.shelter.domain;

/**
 * Controlled vocabulary for shelter classification (transitional-reentry-support
 * task 3.1, design D2).
 *
 * <p>Values map 1:1 to the {@code shelter_type} VARCHAR(50) column added in V91.
 * Default for new shelters is {@link #EMERGENCY}. The {@link #DV} value is
 * coupled to the {@code dv_shelter} boolean: V91's {@code shelter_dv_implies_dv_type}
 * CHECK constraint enforces that {@code dv_shelter = TRUE} requires
 * {@code shelter_type = 'DV'} at the DB layer. {@code ShelterService} keeps the
 * two fields in lockstep at the application layer (slice 1 of the change;
 * slice 2 task 4.3 expands this with full request-side validation).
 *
 * <p>Per design D2: this enum carries no implied compliance status — it is a
 * self-reported classification for filtering and display only.
 */
public enum ShelterType {
    EMERGENCY,
    DV,
    TRANSITIONAL,
    SUBSTANCE_USE_TREATMENT,
    MENTAL_HEALTH_TREATMENT,
    REENTRY_TRANSITIONAL,
    PERMANENT_SUPPORTIVE,
    RAPID_REHOUSING
}
