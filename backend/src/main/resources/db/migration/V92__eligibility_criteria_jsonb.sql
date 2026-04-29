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
