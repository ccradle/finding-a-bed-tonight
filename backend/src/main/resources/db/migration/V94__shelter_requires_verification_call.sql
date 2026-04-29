-- V94 — transitional-reentry-support task 2.4
-- (See openspec/changes/transitional-reentry-support/design.md D1 sentinel paragraph)
--
-- Adds the `requires_verification_call` BOOLEAN sentinel to `shelter`.
-- Top-level column (not in eligibility_criteria JSONB) per Alex Chen
-- warroom: the search UI's "call to verify" badge needs to be queryable
-- without JSONB extraction, AND the H1 acceptsFelonies three-way logic
-- (design D1, warroom 2026-04-28) joins on this column to decide
-- whether to include null-eligibility shelters in `acceptsFelonies=true`
-- results.
--
-- Default FALSE — opting in is an explicit shelter-admin decision.
-- Existing rows get FALSE (the safe default; means "we have eligibility
-- data, no verify call required" or, for null-eligibility shelters,
-- "filter as if data is unknown rather than as a verify-call sentinel").
--
-- Forward-only. Single non-breaking column add. v0.54 backend ignores
-- this column; works against the post-V94 schema unchanged.

ALTER TABLE shelter
    ADD COLUMN IF NOT EXISTS requires_verification_call BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN shelter.requires_verification_call IS
    'Sentinel flag: shelter requires a direct call before eligibility can be assumed, regardless of what eligibility_criteria says. Used by the search UI to render the "call to verify" badge AND by BedSearchService H1 three-way logic — when acceptsFelonies=true is queried and a shelter has null eligibility_criteria, the shelter is included only if requires_verification_call=true (annotated with the badge). See design D1 acceptsFelonies-with-sentinel section.';
