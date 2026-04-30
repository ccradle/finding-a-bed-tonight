-- V91 — transitional-reentry-support task 2.1
-- (See openspec/changes/transitional-reentry-support/proposal.md, design.md D2/D3/D13)
--
-- Adds the foundational shelter classification + supervision-geography fields
-- and the per-tenant reentry-mode feature flag. This migration is non-breaking:
-- all existing shelter rows get `shelter_type = 'EMERGENCY'` (default) or
-- `'DV'` (backfilled from `dv_shelter = true`); county is nullable; the
-- `features.reentryMode` flag defaults to false for every existing tenant.
--
-- Three additions on `shelter`:
--   1. `shelter_type VARCHAR(50) NOT NULL DEFAULT 'EMERGENCY'` — controlled
--      vocabulary (design D2). Allowed values are enforced at the application
--      layer via the `ShelterType` Java enum, NOT a DB CHECK — adding new
--      types must only require an enum add, not a migration.
--   2. `county VARCHAR(100) NULL` — supervision-geography boundary. Per
--      design D3 (warroom H2 revision 2026-04-28), there is intentionally NO
--      DB-level enum for allowed counties; validation against the per-tenant
--      `tenant.config.active_counties` list is enforced at the application
--      layer. The default seed for `active_counties` is the NC 100-county
--      list (constant `org.fabt.shelter.county.NcCountyDefaults`); other
--      deployments override at tenant creation via PLATFORM_OPERATOR.
--   3. CHECK (dv_shelter = FALSE OR shelter_type = 'DV') — the load-bearing
--      DB-level constraint per design D10 ("the database constraint is the
--      last line of defense"). Prevents the two representations of DV status
--      from diverging through any code path, including direct DB console
--      writes.
--
-- Plus tenant.config seed for the new `features.reentryMode` flag (design D13,
-- warroom B5). Frontend visibility gate; backend behavior is uniform across
-- tenants regardless of the flag (see D13 for full semantics).
--
-- Forward-only. No rollback path — all new columns are nullable or have safe
-- defaults so a rollback to v0.54.0 backend code works without DB action.

-- ----------------------------------------------------------------------
-- 1. shelter.shelter_type — controlled-vocabulary classification field
-- ----------------------------------------------------------------------
ALTER TABLE shelter
    ADD COLUMN IF NOT EXISTS shelter_type VARCHAR(50) NOT NULL DEFAULT 'EMERGENCY';

COMMENT ON COLUMN shelter.shelter_type IS
    'Controlled vocabulary classification (EMERGENCY/DV/TRANSITIONAL/SUBSTANCE_USE_TREATMENT/MENTAL_HEALTH_TREATMENT/REENTRY_TRANSITIONAL/PERMANENT_SUPPORTIVE/RAPID_REHOUSING). Enforced at app layer via ShelterType enum. Carries no implied compliance status — self-reported classification for filtering and display only.';

-- ----------------------------------------------------------------------
-- 2. shelter.county — supervision-geography boundary
-- ----------------------------------------------------------------------
ALTER TABLE shelter
    ADD COLUMN IF NOT EXISTS county VARCHAR(100);

COMMENT ON COLUMN shelter.county IS
    'Shelter county. Validated app-layer against tenant.config.active_counties (default: NC 100-county list). NO DB-level enum (design D3 / warroom H2 2026-04-28).';

-- Partial index: county is sparse at launch (most rows null until shelter
-- admins backfill); a full-column index wastes pages on null entries.
-- Mirrors the V55 partial-index precedent.
CREATE INDEX IF NOT EXISTS idx_shelter_tenant_county
    ON shelter (tenant_id, county)
    WHERE county IS NOT NULL;

-- ----------------------------------------------------------------------
-- 3. Backfill: dv_shelter=true → shelter_type='DV' (must run BEFORE the
--    CHECK constraint is added or the constraint will reject existing rows)
-- ----------------------------------------------------------------------
UPDATE shelter
   SET shelter_type = 'DV'
 WHERE dv_shelter = TRUE
   AND shelter_type <> 'DV';

-- ----------------------------------------------------------------------
-- 4. CHECK constraint: dv_shelter and shelter_type cannot diverge
-- ----------------------------------------------------------------------
-- DROP first (if a partial-apply recovery left the prior version) so the
-- ADD CONSTRAINT is idempotent across reruns.
ALTER TABLE shelter
    DROP CONSTRAINT IF EXISTS shelter_dv_implies_dv_type;
ALTER TABLE shelter
    ADD CONSTRAINT shelter_dv_implies_dv_type
    CHECK (dv_shelter = FALSE OR shelter_type = 'DV');

COMMENT ON CONSTRAINT shelter_dv_implies_dv_type ON shelter IS
    'Design D10: enforces dv_shelter=true implies shelter_type=DV at the DB layer. Last line of defense against divergence; application layer (ShelterService) ALSO enforces but a constraint surfaces a programming error loudly rather than silently masking it via auto-sync.';

-- ----------------------------------------------------------------------
-- 5. tenant.config seed: features.reentryMode = false for existing tenants
-- ----------------------------------------------------------------------
-- Design D13 (warroom B5): flag gates frontend visibility of the new
-- reentry surface. Existing tenants get the default (false). New tenants
-- get the value from TenantLifecycleService creation code (out of scope
-- for this migration). The WHERE filters tenants that already have an
-- explicit features.reentryMode value (regardless of true/false) so re-
-- running the migration does not reset operator-set values.
--
-- Pattern: jsonb || preserves existing keys; jsonb_build_object creates
-- the missing nested path. Avoids jsonb_set's awkward "create_missing
-- only works if parent exists" gotcha.
UPDATE tenant
   SET config = config || jsonb_build_object(
        'features',
        coalesce(config -> 'features', '{}'::jsonb) || jsonb_build_object('reentryMode', false)
     )
 WHERE (config #> '{features,reentryMode}') IS NULL;
