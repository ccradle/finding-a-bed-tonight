package org.fabt.availability.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shared.config.JsonString;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AcceptsFeloniesEvaluator} — the three-way decision
 * tree from design D1 H1 (transitional-reentry-support, slice-2 warroom
 * 17.H2 extraction).
 *
 * <p>Each branch (a/b/c) is asserted with both the positive outcome and a
 * negative-control sibling that proves the test is not a tautology (e.g.
 * a branch (a) test with {@code requires_verification_call=true} confirms
 * that branch (a)'s EXCLUDE wins over the (c) sentinel, NOT that the
 * sentinel is being ignored entirely).
 */
@DisplayName("AcceptsFeloniesEvaluator — D1 H1 three-way decision tree")
class AcceptsFeloniesEvaluatorTest {

    private SimpleMeterRegistry meterRegistry;
    private ObservabilityMetrics metrics;
    private AcceptsFeloniesEvaluator evaluator;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(meterRegistry);
        evaluator = new AcceptsFeloniesEvaluator(objectMapper, metrics);
    }

    // ---------------------------------------------------------------------
    // Branch (a) — explicit accepts_felonies = false → EXCLUDE
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("(a) explicit accepts_felonies=false → EXCLUDE (sentinel cannot override)")
    void branchA_explicitFalse_excludesEvenWhenSentinelIsTrue() {
        // Sentinel set to TRUE on purpose: proves branch (a) wins, not branch (c).
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"accepts_felonies\": false}}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.EXCLUDE);
    }

    // ---------------------------------------------------------------------
    // Branch (b) — explicit accepts_felonies = true → INCLUDE
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("(b) explicit accepts_felonies=true → INCLUDE (sentinel state irrelevant)")
    void branchB_explicitTrue_includesEvenWhenSentinelIsFalse() {
        // Sentinel set to FALSE on purpose: proves branch (b) wins, not branch (c).
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"accepts_felonies\": true}}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    // ---------------------------------------------------------------------
    // Branch (c) — any-null path → sentinel-driven
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("(c) constraints null + sentinel=true → INCLUDE")
    void branchC_constraintsNull_sentinelTrue_includes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);

        assertThat(evaluator.evaluate(null, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    @Test
    @DisplayName("(c) constraints null + sentinel=false → EXCLUDE (negative control)")
    void branchC_constraintsNull_sentinelFalse_excludes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);

        assertThat(evaluator.evaluate(null, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.EXCLUDE);
    }

    @Test
    @DisplayName("(c) eligibility_criteria null + sentinel=true → INCLUDE")
    void branchC_eligibilityCriteriaNull_sentinelTrue_includes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        ShelterConstraints constraints = new ShelterConstraints();
        constraints.setEligibilityCriteria(null);

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    @Test
    @DisplayName("(c) criminal_record_policy missing + sentinel=true → INCLUDE")
    void branchC_policyMissing_sentinelTrue_includes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        // JSON with sibling keys but no criminal_record_policy → branch (c).
        ShelterConstraints constraints = constraintsWithJson(
                "{\"intake_hours\": \"9-17\", \"program_requirements\": []}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    @Test
    @DisplayName("(c) criminal_record_policy missing + sentinel=false → EXCLUDE (negative control)")
    void branchC_policyMissing_sentinelFalse_excludes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"intake_hours\": \"9-17\"}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.EXCLUDE);
    }

    @Test
    @DisplayName("(c) accepts_felonies key absent within policy + sentinel=true → INCLUDE")
    void branchC_acceptsFeloniesKeyAbsent_sentinelTrue_includes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        // Policy object exists with sibling keys but no accepts_felonies key.
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"individualized_assessment\": true,"
                + " \"excluded_offense_types\": [\"SEX_OFFENSE\"]}}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    @Test
    @DisplayName("(c) accepts_felonies non-boolean (string \"true\") + sentinel=false → EXCLUDE")
    void branchC_acceptsFeloniesIsString_sentinelFalse_excludes() {
        // String "true" must NOT be coerced to Boolean true — it's a data-quality
        // problem and should fall through to branch (c) per the contract.
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"accepts_felonies\": \"true\"}}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.EXCLUDE);
    }

    @Test
    @DisplayName("(c) accepts_felonies JSON null + sentinel=true → INCLUDE")
    void branchC_acceptsFeloniesIsNull_sentinelTrue_includes() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"accepts_felonies\": null}}");

        assertThat(evaluator.evaluate(constraints, shelter))
                .isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
    }

    // ---------------------------------------------------------------------
    // Parse failure — fail-open to branch (c) AND increments counter
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("malformed JSON + sentinel=true → INCLUDE + parse-failure counter ticks once")
    void parseFailure_sentinelTrue_includesAndIncrementsCounter() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ true);
        ShelterConstraints constraints = constraintsWithJson("{not valid json");

        double before = metrics.eligibilityCriteriaParseFailureCounter().count();
        AcceptsFeloniesEvaluator.Decision decision = evaluator.evaluate(constraints, shelter);
        double after = metrics.eligibilityCriteriaParseFailureCounter().count();

        assertThat(decision).isEqualTo(AcceptsFeloniesEvaluator.Decision.INCLUDE);
        assertThat(after - before)
                .as("malformed JSON must increment the parse-failure counter exactly once")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("malformed JSON + sentinel=false → EXCLUDE + counter ticks once (negative control)")
    void parseFailure_sentinelFalse_excludesAndIncrementsCounter() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);
        ShelterConstraints constraints = constraintsWithJson("}{");

        double before = metrics.eligibilityCriteriaParseFailureCounter().count();
        AcceptsFeloniesEvaluator.Decision decision = evaluator.evaluate(constraints, shelter);
        double after = metrics.eligibilityCriteriaParseFailureCounter().count();

        assertThat(decision).isEqualTo(AcceptsFeloniesEvaluator.Decision.EXCLUDE);
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("happy path does NOT increment parse-failure counter")
    void happyPath_doesNotIncrementCounter() {
        Shelter shelter = shelter(/*requiresVerificationCall*/ false);
        ShelterConstraints constraints = constraintsWithJson(
                "{\"criminal_record_policy\": {\"accepts_felonies\": true}}");

        double before = metrics.eligibilityCriteriaParseFailureCounter().count();
        evaluator.evaluate(constraints, shelter);
        double after = metrics.eligibilityCriteriaParseFailureCounter().count();

        assertThat(after - before).isZero();
    }

    // ---------------------------------------------------------------------
    // Defensive guards
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("null shelter → IllegalArgumentException")
    void nullShelter_throws() {
        assertThatThrownBy(() -> evaluator.evaluate(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shelter");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Shelter shelter(boolean requiresVerificationCall) {
        Shelter s = new Shelter();
        s.setRequiresVerificationCall(requiresVerificationCall);
        return s;
    }

    private static ShelterConstraints constraintsWithJson(String json) {
        ShelterConstraints c = new ShelterConstraints();
        c.setEligibilityCriteria(new JsonString(json));
        return c;
    }
}
