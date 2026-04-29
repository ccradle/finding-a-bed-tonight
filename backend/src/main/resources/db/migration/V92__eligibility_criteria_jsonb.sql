-- V92 — transitional-reentry-support task 2.2
-- (See openspec/changes/transitional-reentry-support/design.md D1, D9)
--
-- Adds the structured eligibility-criteria JSONB column to
-- `shelter_constraints`. Schema is documented in design D1 and includes
-- `criminal_record_policy` (sub-object), `program_requirements`,
-- `documentation_required`, `intake_hours`, `custom_tags`. All keys
-- nullable — partial population is the normal state.
--
-- Index strategy: a GIN index on the JSONB column supports the
-- `acceptsFelonies` filter's containment + key-existence queries
-- (BedSearchService task 4.1). Per design D9 + Elena Vasquez warroom,
-- the index is critical for performance at deployment scale; at demo
-- scale (dozens of shelters) it is imperceptible.
--
-- ** STATUS — currently un-consumed (slice-2 warroom H4, 2026-04-29) **
--
-- Slice 2B's BedSearchService implements the `acceptsFelonies` three-way
-- filter logic in-memory (loads all-shelters-per-tenant from cache, then
-- filters in Java). Nothing in the SQL path queries `eligibility_criteria`
-- via `@>` containment yet, so this index sits idle in prod, paying
-- write-side overhead with zero read-side benefit.
--
-- The index becomes load-bearing in slice 5 when BedSearchService refactors
-- to a SQL-side filter at pilot-scale. Per warroom H4 + V92IndexVerificationTest
-- JavaDoc, the canonical SQL form MUST use containment:
--
--   WHERE eligibility_criteria @> '{"criminal_record_policy": {"accepts_felonies": true}}'::jsonb
--
-- (NOT `eligibility_criteria->'criminal_record_policy'->>'accepts_felonies'` —
-- the default `jsonb_ops` op-class doesn't index extraction operators.)
--
-- Slice 5 verification: `pg_stat_statements` shows GIN bitmap-index-scan
-- hits on the canonical query at pilot-shape data volumes, per
-- `feedback_pgstat_for_index_validation.md`.
--
-- We keep the index now (rather than ship it in slice 5) for two reasons:
-- (1) write-side overhead is small (sparse partial index, mostly null at
-- launch); (2) re-adding it later means a CONCURRENTLY-or-not migration
-- on populated shelter_constraints data, which is more risk than carrying
-- the unused index from V92 onward.
--
-- Why NOT CONCURRENTLY:
--   The change spec (task 2.2) suggested CONCURRENTLY with `mixed=true`,
--   but FABT's established precedent (V55 lines 54-59) is the opposite:
--   "CONCURRENTLY is NOT used here: Flyway wraps migrations in a
--   transaction and CREATE INDEX CONCURRENTLY is incompatible with
--   transactions. At the target table sizes, a plain CREATE INDEX
--   completes in seconds with no user-visible impact."
--   We follow V55 — `shelter_constraints` is a small lookup table
--   (one row per shelter, ~tens to low-thousands at any plausible
--   deployment scale). Plain CREATE INDEX is safe.
--
-- Forward-only. Nullable column means the existing application code
-- (which doesn't read this column yet) is unaffected.

-- ----------------------------------------------------------------------
-- 1. shelter_constraints.eligibility_criteria — structured JSONB schema
-- ----------------------------------------------------------------------
ALTER TABLE shelter_constraints
    ADD COLUMN IF NOT EXISTS eligibility_criteria JSONB;

COMMENT ON COLUMN shelter_constraints.eligibility_criteria IS
    'Structured eligibility criteria per design D1. Schema includes criminal_record_policy (sub-object: accepts_felonies bool, excluded_offense_types string[], individualized_assessment bool, vawa_protections_apply bool, notes), program_requirements string[], documentation_required string[], intake_hours string, custom_tags string[]. All keys nullable. NULL means "not specified" — UI shows the requires_verification_call sentinel as fallback.';

-- ----------------------------------------------------------------------
-- 2. GIN index on the JSONB for eligibility-criteria filter performance
-- ----------------------------------------------------------------------
-- Partial: only rows with non-null eligibility_criteria. Most rows will be
-- null at launch (mirrors county sparseness in V91). Saves index pages.
CREATE INDEX IF NOT EXISTS idx_shelter_constraints_eligibility
    ON shelter_constraints USING GIN (eligibility_criteria)
    WHERE eligibility_criteria IS NOT NULL;

-- Refresh planner stats so the new index is considered immediately.
ANALYZE shelter_constraints;
