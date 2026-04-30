package org.fabt.availability.service;

import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterConstraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides whether a shelter is included or excluded from results when the
 * caller passes {@code acceptsFelonies=true} on a bed search.
 *
 * <p>Captures the cross-table data dependency for the three-way logic per
 * design D1 H1 (transitional-reentry-support, warroom 2026-04-28). The
 * decision combines:
 * <ul>
 *   <li>{@link ShelterConstraints#getEligibilityCriteria()} JSONB —
 *       specifically {@code criminal_record_policy.accepts_felonies}.</li>
 *   <li>{@link Shelter#isRequiresVerificationCall()} — the sentinel that
 *       gates inclusion when the JSONB path is absent.</li>
 * </ul>
 *
 * <p>Three branches:
 * <ol type="a">
 *   <li>Explicit {@code accepts_felonies = false} → {@link Decision#EXCLUDE}.</li>
 *   <li>Explicit {@code accepts_felonies = true}  → {@link Decision#INCLUDE}.</li>
 *   <li>Any-null path (constraints null, eligibility_criteria null,
 *       criminal_record_policy null, accepts_felonies null/non-boolean,
 *       or JSONB parse failure) → {@link Decision#INCLUDE} iff
 *       {@code requires_verification_call=true} on the shelter, else
 *       {@link Decision#EXCLUDE}.</li>
 * </ol>
 *
 * <p>Slice-2 warroom 17.H2 (2026-04-29): extracted from
 * {@code BedSearchService} so the contract has a single named home and
 * unit tests target it directly without standing up the full search
 * graph. When slice 5 introduces the SQL containment filter (V92 GIN
 * index activation), both paths can be tested against the same
 * {@link Decision} contract for the same input.
 *
 * <p>Parse failures (malformed JSONB) are fail-open: they are counted on
 * {@link ObservabilityMetrics#eligibilityCriteriaParseFailureCounter()}
 * and routed to branch (c). This is intentional — we prefer to over-show
 * (with the verification-call sentinel as a UI annotation) rather than
 * silently drop a shelter from results because of a data-quality
 * regression on a single record.
 */
@Component
public class AcceptsFeloniesEvaluator {

    public enum Decision { INCLUDE, EXCLUDE }

    private static final Logger log = LoggerFactory.getLogger(AcceptsFeloniesEvaluator.class);

    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    public AcceptsFeloniesEvaluator(ObjectMapper objectMapper, ObservabilityMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Apply the three-way decision tree. {@code shelter} must not be null.
     * {@code constraints} may be null (treated as branch (c)).
     */
    public Decision evaluate(ShelterConstraints constraints, Shelter shelter) {
        if (shelter == null) {
            throw new IllegalArgumentException("shelter must not be null");
        }
        Boolean explicit = readAcceptsFelonies(constraints);
        if (Boolean.FALSE.equals(explicit)) return Decision.EXCLUDE;            // (a)
        if (Boolean.TRUE.equals(explicit))  return Decision.INCLUDE;            // (b)
        return shelter.isRequiresVerificationCall()                              // (c)
                ? Decision.INCLUDE
                : Decision.EXCLUDE;
    }

    /**
     * Parse {@code eligibility_criteria.criminal_record_policy.accepts_felonies}.
     * Returns:
     * <ul>
     *   <li>{@link Boolean#TRUE}  — explicit true at the leaf path</li>
     *   <li>{@link Boolean#FALSE} — explicit false at the leaf path</li>
     *   <li>{@code null}          — any node along the path is missing,
     *       parse fails, the leaf is non-boolean, or constraints is null</li>
     * </ul>
     */
    Boolean readAcceptsFelonies(ShelterConstraints constraints) {
        if (constraints == null || constraints.getEligibilityCriteria() == null) return null;
        try {
            JsonNode root = objectMapper.readTree(constraints.getEligibilityCriteria().value());
            JsonNode policy = root.get("criminal_record_policy");
            if (policy == null) return null;
            JsonNode accepts = policy.get("accepts_felonies");
            if (accepts == null || !accepts.isBoolean()) return null;
            return accepts.asBoolean();
        } catch (tools.jackson.core.JacksonException e) {
            metrics.eligibilityCriteriaParseFailureCounter().increment();
            log.debug("Failed to parse eligibility_criteria (parse-failure counter incremented): {}",
                    e.getMessage());
            return null;
        }
    }
}
