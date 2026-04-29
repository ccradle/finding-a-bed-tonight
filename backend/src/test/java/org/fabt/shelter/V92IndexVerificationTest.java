package org.fabt.shelter;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V92 GIN index verification — warroom H4.
 *
 * <p>The {@code idx_shelter_constraints_eligibility} GIN index is design D9's
 * load-bearing performance guarantee for the {@code acceptsFelonies} bed-search
 * filter at deployment scale. This test asserts the index EXISTS with the
 * expected shape; it does NOT assert the query planner USES it (at test scale
 * Postgres correctly chooses sequential scan over a GIN scan, so a meaningful
 * EXPLAIN-asserting test is impractical until pilot scale).
 *
 * <p><strong>Critical query-syntax constraint (warroom H4):</strong> the GIN
 * index uses Postgres's default {@code jsonb_ops} operator class, which
 * supports {@code @>}, {@code ?}, {@code ?|}, and {@code ?&} operators —
 * but does NOT speed up {@code ->} or {@code ->>} extraction. The
 * {@code BedSearchService} {@code acceptsFelonies=true} filter MUST use
 * containment syntax to leverage the index:
 *
 * <pre>{@code
 * -- USES the GIN index:
 * WHERE eligibility_criteria @> '{"criminal_record_policy": {"accepts_felonies": true}}'::jsonb
 *
 * -- BYPASSES the GIN index (sequential scan):
 * WHERE (eligibility_criteria->'criminal_record_policy'->>'accepts_felonies')::boolean = TRUE
 * }</pre>
 *
 * <p>Slice 2 task 4.2 must use the containment form. This test explicitly
 * documents the constraint so a future engineer who sees the GIN index can
 * understand why the query syntax matters — without this note, the index
 * looks correct but achieves nothing.
 */
class V92IndexVerificationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v92_gin_index_exists_with_expected_definition() {
        // pg_indexes is the authoritative source for index metadata.
        String indexDef = jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_shelter_constraints_eligibility'",
            String.class);

        assertThat(indexDef)
            .as("V92 must register idx_shelter_constraints_eligibility in pg_indexes")
            .isNotNull()
            .contains("USING gin")
            .contains("eligibility_criteria")
            // The partial WHERE clause is the minor space optimization — sparse
            // column at launch (most rows will have null eligibility_criteria).
            .contains("WHERE (eligibility_criteria IS NOT NULL)");
    }

    @Test
    void v92_gin_index_uses_default_jsonb_ops_operator_class() {
        // Operator class controls which operators the index can answer. The
        // DEFAULT for GIN on jsonb is jsonb_ops which supports @>, ?, ?|, ?&.
        // If a future migration explicitly switches to jsonb_path_ops (smaller
        // index, supports only @>), this test catches the change so we can
        // update BedSearchService accordingly.
        String opClass = jdbc.queryForObject(
            "SELECT opc.opcname "
            + "FROM pg_index ix "
            + "JOIN pg_class c ON c.oid = ix.indexrelid "
            + "JOIN pg_opclass opc ON opc.oid = ANY (ix.indclass) "
            + "WHERE c.relname = 'idx_shelter_constraints_eligibility' "
            + "LIMIT 1",
            String.class);

        assertThat(opClass)
            .as("V92 GIN index must use default jsonb_ops operator class so containment "
                + "(@>) AND key-existence (?, ?|, ?&) queries are both indexable; "
                + "if a future migration switches to jsonb_path_ops, BedSearchService "
                + "queries that rely on key-existence would silently sequential-scan")
            .isEqualTo("jsonb_ops");
    }

    /**
     * <p><strong>Why no EXPLAIN-asserts-the-planner-USES-it test:</strong>
     * at testcontainer scale (0 or near-0 rows in {@code shelter_constraints})
     * Postgres correctly chooses {@code Seq Scan} even when
     * {@code enable_seqscan=OFF} — there's nothing to index. Plus the
     * V8 RLS policy on {@code shelter_constraints} forces a {@code SubPlan}
     * join to {@code shelter}, which the planner evaluates BEFORE the
     * containment filter; this further suppresses GIN consideration at
     * low row counts. Producing a row count that would force GIN is heavy
     * (>>10k rows) and slow for the CI suite.
     *
     * <p>The two introspection tests above (index exists with correct
     * definition + uses {@code jsonb_ops} operator class) prove the GIN
     * is structurally correct for the {@code @>} containment query.
     * Whether the planner actually uses it at deployment scale is a
     * <strong>pilot-scale verification</strong> belonging to slice 5
     * release prep — use {@code pg_stat_statements} (per
     * {@code feedback_pgstat_for_index_validation.md}) on the prod-shaped
     * dataset to confirm the {@code BedSearchService.acceptsFelonies}
     * query actually touches the GIN index.
     */
}
