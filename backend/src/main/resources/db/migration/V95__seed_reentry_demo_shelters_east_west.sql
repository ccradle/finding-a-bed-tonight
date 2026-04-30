-- V95: Seed reentry-mode demo shelters for dev-coc-east + dev-coc-west.
--
-- transitional-reentry-support post-§14, pre-deploy demo expansion. The
-- reentry-spec UI (slice 4 §7-§12) is unusable on demo without realistic
-- coverage of the new V91 shelter_type values: TRANSITIONAL and
-- REENTRY_TRANSITIONAL. V76/V77 only seeded EMERGENCY + DV.
--
-- OVERFLOW shelter_type was considered but is a DEFERRED feature
-- requiring its own OpenSpec (per project memory
-- `project_deferred_openspecs_required.md`) — the ShelterType Java enum
-- does not include OVERFLOW yet, so persisting OVERFLOW rows would
-- crash the row mapper at read time (caught locally as a 400 on
-- cocadmin GET /api/v1/shelters before deploy). The "surge capacity"
-- demo story rides the existing `bed_availability.overflow_beds`
-- mechanism instead — an EMERGENCY shelter with overflow_beds > 0
-- during an active surge event. The 2 surge sites in this migration
-- are labeled EMERGENCY accordingly.
--
-- Why a Flyway migration (not seed-data.sql): seed-data.sql has a
-- runtime guard refusing to run on prod-named DBs because it ships
-- dev-only credentials. Flyway is the established channel for demo
-- content per the V76/V77 precedent ("Extracted from seed-data.sql so
-- prod picks up the tenant on deploy without relying on the dev-only
-- seed script.").
--
-- Idempotency: every row uses ON CONFLICT DO UPDATE so re-runs (disaster
-- recovery, reseed) are safely absorbed. Mirrors V76/V77 pattern.
--
-- Address convention (D12): "Fictional Way" street + real NC city/zip
-- so the map/search surface feels realistic; no real-jurisdiction
-- collision since "Fictional Way" doesn't exist anywhere in NC. Display
-- names carry the "(demo)" suffix per Elena/Casey audit signal.
--
-- County assignments are real for the city, so the §8/§9 county filter
-- exercises the geography correctly:
--   west tenant: Watauga (Boone), Haywood (Waynesville), Buncombe
--                (Asheville), Henderson (Hendersonville)
--   east tenant: Craven (New Bern), Beaufort (Washington), Pitt
--                (Greenville), Onslow (Jacksonville)
--
-- UUID convention: extends V76/V77.
--   west new shelters: d0000001-…004/005/006
--   east new shelters: d0000002-…004/005/006
--
-- eligibility_criteria JSONB shape per design D1
-- (frontend/src/types/eligibilityCriteria.ts):
--   - criminal_record_policy: { accepts_felonies, excluded_offense_types,
--       individualized_assessment, vawa_protections_apply, notes }
--   - program_requirements: string[]
--   - documentation_required: string[]
--   - intake_hours: string
-- The V92 GIN partial index covers query performance.
--
-- Excluded offense type vocabulary is the controlled OFFENSE_TYPES set
-- (frontend/src/types/eligibilityCriteria.ts:115-122):
--   SEX_OFFENSE, ARSON, DRUG_MANUFACTURING, VIOLENT_FELONY,
--   PENDING_CHARGES, OPEN_WARRANTS.
-- REENTRY_TRANSITIONAL shelters in this demo exclude the safety-critical
-- subset (SEX_OFFENSE, ARSON, VIOLENT_FELONY) and accept the rest with
-- individualized_assessment per real-world reentry program practice.

-- ---------------------------------------------------------------------
-- 1. Backfill county on existing shelters (V91 doesn't backfill county;
--    only shelter_type='DV' was backfilled). Without this, the §8/§9
--    county dropdown would show no existing shelters.
-- ---------------------------------------------------------------------
UPDATE shelter SET county = 'Watauga'
 WHERE id = 'd0000001-0000-0000-0000-000000000001' AND county IS NULL;
UPDATE shelter SET county = 'Haywood'
 WHERE id = 'd0000001-0000-0000-0000-000000000002' AND county IS NULL;
-- DV row d0000001-...003 stays county=NULL (Undisclosed)

UPDATE shelter SET county = 'Craven'
 WHERE id = 'd0000002-0000-0000-0000-000000000001' AND county IS NULL;
UPDATE shelter SET county = 'Beaufort'
 WHERE id = 'd0000002-0000-0000-0000-000000000002' AND county IS NULL;
-- DV row d0000002-...003 stays county=NULL (Undisclosed)

-- ---------------------------------------------------------------------
-- 2. Add active_counties to tenant.config so the §8/§9 dropdown
--    surfaces these counties. Without active_counties the dropdown
--    falls back to the NC default 100-county list, which is too noisy
--    for a 6-shelter demo.
-- ---------------------------------------------------------------------
UPDATE tenant
   SET config = jsonb_set(
           config,
           '{active_counties}',
           '["Watauga", "Haywood", "Buncombe", "Henderson"]'::jsonb,
           true),
       updated_at = NOW()
 WHERE id = 'a0000000-0000-0000-0000-000000000002';

UPDATE tenant
   SET config = jsonb_set(
           config,
           '{active_counties}',
           '["Craven", "Beaufort", "Pitt", "Onslow"]'::jsonb,
           true),
       updated_at = NOW()
 WHERE id = 'a0000000-0000-0000-0000-000000000003';

-- ---------------------------------------------------------------------
-- 3. New shelters: dev-coc-west (Blue Ridge)
-- ---------------------------------------------------------------------

INSERT INTO shelter (id, tenant_id, name, address_street, address_city,
                     address_state, address_zip, phone, latitude, longitude,
                     dv_shelter, shelter_type, county,
                     requires_verification_call, created_at, updated_at)
VALUES
    -- TRANSITIONAL: long-term structured housing in Asheville/Buncombe.
    -- 12-24 month program for adults transitioning out of homelessness.
    ('d0000001-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002',
     'Mountain View Transitional (demo)', '201 Fictional Way', 'Asheville', 'NC', '28801',
     '000-555-0104', 35.5951, -82.5515, false, 'TRANSITIONAL', 'Buncombe', false,
     NOW(), NOW()),

    -- REENTRY_TRANSITIONAL: justice-involved residents in
    -- Hendersonville/Henderson. Accepts felonies with safety-critical
    -- exclusions; sober-living component; parole-coordinated intake.
    ('d0000001-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002',
     'Henderson Reentry House (demo)', '202 Fictional Way', 'Hendersonville', 'NC', '28792',
     '000-555-0105', 35.3187, -82.4610, false, 'REENTRY_TRANSITIONAL', 'Henderson', true,
     NOW(), NOW()),

    -- OVERFLOW: surge-only emergency capacity in Boone/Watauga (same
    -- town as Example House North; demo story is "second site activates
    -- during cold-weather surge"). Drop-in intake, short stay.
    ('d0000001-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002',
     'Boone Overflow Site (demo)', '203 Fictional Way', 'Boone', 'NC', '28607',
     '000-555-0106', 36.2168, -81.6745, false, 'EMERGENCY', 'Watauga', false,
     NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    address_street = EXCLUDED.address_street,
    address_city = EXCLUDED.address_city,
    address_state = EXCLUDED.address_state,
    address_zip = EXCLUDED.address_zip,
    phone = EXCLUDED.phone,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    dv_shelter = EXCLUDED.dv_shelter,
    shelter_type = EXCLUDED.shelter_type,
    county = EXCLUDED.county,
    requires_verification_call = EXCLUDED.requires_verification_call,
    updated_at = NOW();

-- ---------------------------------------------------------------------
-- 4. New shelters: dev-coc-east (Pamlico Sound)
-- ---------------------------------------------------------------------

INSERT INTO shelter (id, tenant_id, name, address_street, address_city,
                     address_state, address_zip, phone, latitude, longitude,
                     dv_shelter, shelter_type, county,
                     requires_verification_call, created_at, updated_at)
VALUES
    -- TRANSITIONAL: family-targeted long-term housing in
    -- Greenville/Pitt. School enrollment + case management.
    ('d0000002-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000003',
     'Greenville Family Transitional (demo)', '204 Fictional Way', 'Greenville', 'NC', '27834',
     '000-555-0204', 35.6127, -77.3664, false, 'TRANSITIONAL', 'Pitt', false,
     NOW(), NOW()),

    -- REENTRY_TRANSITIONAL: women-focused reentry in
    -- Jacksonville/Onslow. VAWA note applies because the demo profile
    -- is trafficking-survivor population whose criminal records may
    -- relate to coerced criminal activity. Individualized assessment.
    ('d0000002-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003',
     'Onslow Womens Reentry (demo)', '205 Fictional Way', 'Jacksonville', 'NC', '28540',
     '000-555-0205', 34.7541, -77.4302, false, 'REENTRY_TRANSITIONAL', 'Onslow', true,
     NOW(), NOW()),

    -- OVERFLOW: surge-only in Greenville/Pitt. No eligibility criteria
    -- (intentionally NULL JSONB) to exercise the §10 "Not specified"
    -- empty-state UI.
    ('d0000002-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003',
     'Pitt County Overflow Site (demo)', '206 Fictional Way', 'Greenville', 'NC', '27858',
     '000-555-0206', 35.6010, -77.3680, false, 'EMERGENCY', 'Pitt', false,
     NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    address_street = EXCLUDED.address_street,
    address_city = EXCLUDED.address_city,
    address_state = EXCLUDED.address_state,
    address_zip = EXCLUDED.address_zip,
    phone = EXCLUDED.phone,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    dv_shelter = EXCLUDED.dv_shelter,
    shelter_type = EXCLUDED.shelter_type,
    county = EXCLUDED.county,
    requires_verification_call = EXCLUDED.requires_verification_call,
    updated_at = NOW();

-- ---------------------------------------------------------------------
-- 5. shelter_constraints with realistic eligibility_criteria JSONB
--    per shelter type. JSON keys match the design D1 schema enforced
--    by frontend/src/types/eligibilityCriteria.ts.
-- ---------------------------------------------------------------------

INSERT INTO shelter_constraints (
    shelter_id, sobriety_required, id_required, referral_required,
    pets_allowed, wheelchair_accessible, curfew_time, max_stay_days,
    population_types_served, eligibility_criteria
) VALUES
    -- WEST TRANSITIONAL: 24mo program, ID + referral, pets allowed
    -- (family-friendly), structured intake.
    ('d0000001-0000-0000-0000-000000000004',
     false, true, true, true, true, '22:00', 730,
     ARRAY['SINGLE_ADULT'],
     '{
        "program_requirements": [
            "Weekly case management meetings",
            "Active employment search or vocational training",
            "Monthly progress review with case manager"
        ],
        "documentation_required": [
            "Government-issued ID",
            "Proof of income (if any)",
            "Recent housing history (last 12 months)"
        ],
        "intake_hours": "Monday-Friday 9:00am-5:00pm; appointment required"
     }'::jsonb),

    -- WEST REENTRY: 12mo program, ID + referral + sobriety, structured
    -- offense-type exclusions, parole-coordinated intake.
    ('d0000001-0000-0000-0000-000000000005',
     true, true, true, false, true, '21:00', 365,
     ARRAY['SINGLE_ADULT'],
     '{
        "criminal_record_policy": {
            "accepts_felonies": true,
            "excluded_offense_types": ["SEX_OFFENSE", "ARSON", "VIOLENT_FELONY"],
            "individualized_assessment": true,
            "vawa_protections_apply": false,
            "notes": "Open felony charges considered case-by-case after probation officer consultation."
        },
        "program_requirements": [
            "Active parole or probation reporting",
            "Weekly sobriety check-ins",
            "Active employment search within 30 days of intake",
            "Monthly counseling session"
        ],
        "documentation_required": [
            "Government-issued ID",
            "Probation/parole officer contact information",
            "Release-of-information for case coordination"
        ],
        "intake_hours": "Monday-Friday 8:00am-4:00pm; pre-screening required (verification call to shelter)"
     }'::jsonb),

    -- WEST OVERFLOW: surge-only, drop-in, no eligibility_criteria so
    -- the §10 read-side UI exercises the "Not specified" empty-state.
    ('d0000001-0000-0000-0000-000000000006',
     false, false, false, false, true, NULL, 14,
     ARRAY['SINGLE_ADULT'],
     NULL),

    -- EAST TRANSITIONAL: family-focused, longer stay, school-enrollment
    -- requirement reflecting kids in residence.
    ('d0000002-0000-0000-0000-000000000004',
     false, true, true, true, true, '21:00', 730,
     ARRAY['FAMILY_WITH_CHILDREN'],
     '{
        "program_requirements": [
            "Weekly family case management",
            "School enrollment for all school-age children within 14 days",
            "Active employment search by primary adult",
            "Monthly progress review"
        ],
        "documentation_required": [
            "Government-issued ID for each adult",
            "Birth certificate or school records for each child",
            "Proof of guardianship (if applicable)"
        ],
        "intake_hours": "Monday-Saturday 9:00am-6:00pm; appointment required"
     }'::jsonb),

    -- EAST REENTRY: women-focused, VAWA flag set, individualized
    -- assessment for trafficking-survivor profile per the demo story.
    ('d0000002-0000-0000-0000-000000000005',
     true, true, true, false, true, '21:00', 365,
     ARRAY['WOMEN_ONLY'],
     '{
        "criminal_record_policy": {
            "accepts_felonies": true,
            "excluded_offense_types": ["SEX_OFFENSE", "ARSON"],
            "individualized_assessment": true,
            "vawa_protections_apply": true,
            "notes": "VAWA-protected residents whose offense relates to coerced activity are evaluated individually."
        },
        "program_requirements": [
            "Weekly trauma-informed case management",
            "Monthly counseling (individual or group)",
            "Active employment search or education enrollment within 60 days"
        ],
        "documentation_required": [
            "Government-issued ID (alternatives accepted via case-manager attestation)",
            "Release-of-information for advocacy coordination"
        ],
        "intake_hours": "Monday-Friday 9:00am-5:00pm; survivor advocate present at intake"
     }'::jsonb),

    -- EAST OVERFLOW: same shape as west overflow — surge-only,
    -- minimal constraints, NULL eligibility_criteria.
    ('d0000002-0000-0000-0000-000000000006',
     false, false, false, false, true, NULL, 14,
     ARRAY['SINGLE_ADULT'],
     NULL)
ON CONFLICT (shelter_id) DO UPDATE SET
    sobriety_required = EXCLUDED.sobriety_required,
    id_required = EXCLUDED.id_required,
    referral_required = EXCLUDED.referral_required,
    pets_allowed = EXCLUDED.pets_allowed,
    wheelchair_accessible = EXCLUDED.wheelchair_accessible,
    curfew_time = EXCLUDED.curfew_time,
    max_stay_days = EXCLUDED.max_stay_days,
    population_types_served = EXCLUDED.population_types_served,
    eligibility_criteria = EXCLUDED.eligibility_criteria;

-- ---------------------------------------------------------------------
-- 6. bed_availability rows so search surfaces return realistic counts.
--    Moderate occupancy (~50-66%) per V76/V77 precedent so the demo
--    tour shows non-empty results without "everything is full".
-- ---------------------------------------------------------------------

INSERT INTO bed_availability (
    shelter_id, tenant_id, population_type, beds_total, beds_occupied,
    beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes
) VALUES
    -- WEST TRANSITIONAL: 24 beds, 16 occupied (~67%)
    ('d0000001-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002',
     'SINGLE_ADULT', 24, 16, 0, true,
     NOW() - INTERVAL '20 minutes', 'seed', 'Evening update'),

    -- WEST REENTRY: 16 beds, 11 occupied (~69%)
    ('d0000001-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002',
     'SINGLE_ADULT', 16, 11, 0, true,
     NOW() - INTERVAL '30 minutes', 'seed', NULL),

    -- WEST OVERFLOW: 30 beds, 0 occupied (surge-not-active default)
    ('d0000001-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002',
     'SINGLE_ADULT', 30, 0, 0, true,
     NOW() - INTERVAL '10 minutes', 'seed', 'Activated only during cold-weather surge'),

    -- EAST TRANSITIONAL: 18 beds (family-housing), 12 occupied (~67%)
    ('d0000002-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000003',
     'FAMILY_WITH_CHILDREN', 18, 12, 0, true,
     NOW() - INTERVAL '25 minutes', 'seed', 'Evening update'),

    -- EAST REENTRY: 14 beds, 9 occupied (~64%)
    ('d0000002-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003',
     'WOMEN_ONLY', 14, 9, 0, true,
     NOW() - INTERVAL '40 minutes', 'seed', NULL),

    -- EAST OVERFLOW: 25 beds, 0 occupied (surge default)
    ('d0000002-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003',
     'SINGLE_ADULT', 25, 0, 0, true,
     NOW() - INTERVAL '15 minutes', 'seed', 'Activated only during weather emergencies')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 7. coordinator_assignment rows so the demo coordinator user can see
--    these new shelters on their dashboard (and §11.5 hold-list works).
--    Mirrors V78 hotfix pattern.
--    west coordinator: b0000001-...003 (coordinator@blueridge.fabt.org)
--    east coordinator: b0000002-...003 (coordinator@pamlico.fabt.org)
-- ---------------------------------------------------------------------

INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES
    -- west coordinator -> 3 new west shelters (TRANSITIONAL, REENTRY, OVERFLOW)
    ('b0000001-0000-0000-0000-000000000003', 'd0000001-0000-0000-0000-000000000004'),
    ('b0000001-0000-0000-0000-000000000003', 'd0000001-0000-0000-0000-000000000005'),
    ('b0000001-0000-0000-0000-000000000003', 'd0000001-0000-0000-0000-000000000006'),
    -- east coordinator -> 3 new east shelters
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000004'),
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000005'),
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000006'),
    -- cocadmin -> all 6 new shelters (manages tenant end-to-end, mirrors V78 pattern)
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000004'),
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000005'),
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000006'),
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000004'),
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000005'),
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000006')
ON CONFLICT DO NOTHING;
