-- ─────────────────────────────────────────────────────────────────────────
-- V96 — third reentry-eligible shelter for the dev-coc-east demo tenant
--
-- Why this migration:
--   The demo/reentry-story.html capability deep-dive narrates "Three
--   matches. Two have at least one bed available right now; the third has
--   its 'requires verification call' flag set." V95 seeded only TWO
--   reentry-eligible shelters in dev-coc-east (Greenville Family
--   Transitional + Onslow Womens Reentry), so the capture spec was
--   producing only 1-2 results — narrative-vs-screenshot drift.
--
-- This migration adds ONE more REENTRY_TRANSITIONAL shelter in Beaufort
-- county with beds available + requires_verification_call=false. Combined
-- with V95 + the §16.E seed flips, the dev-coc-east tenant now has the
-- three-shelter shape the page narrates:
--   1. Greenville Family Transitional (Pitt) — TRANSITIONAL, family-focused
--   2. Onslow Womens Reentry (Onslow) — REENTRY_TRANSITIONAL, women-only,
--      requires_verification_call=true (the "third" in the narrative)
--   3. Beaufort Reentry Annex (Beaufort) — REENTRY_TRANSITIONAL, single-
--      adult, requires_verification_call=false (this migration)
--
-- Per `feedback_flyway_immutable_after_apply` — V95 is applied to local +
-- demo so we cannot modify it. V96 is the correct path.
--
-- Per Open Question 4 in reentry-release-readiness/design.md, we
-- explicitly chose "amend via V96" over "construct dynamically in test"
-- because the seed amendment makes the demo + the screenshot capture
-- reproducible from fresh dev-start without test-spec gymnastics.
-- ─────────────────────────────────────────────────────────────────────────

-- 1. Shelter row (REENTRY_TRANSITIONAL, Beaufort county, no verification-
--    call requirement so it shows beds available without a phone gate).
INSERT INTO shelter (id, tenant_id, name,
                     address_street, address_city, address_state, address_zip, phone,
                     latitude, longitude,
                     dv_shelter, shelter_type, county,
                     requires_verification_call, created_at, updated_at)
VALUES
    ('d0000002-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000003',
     'Beaufort Reentry Annex (demo)', '207 Fictional Way', 'Washington', 'NC', '27889',
     '000-555-0207', 35.5465, -77.0524, false, 'REENTRY_TRANSITIONAL', 'Beaufort', false,
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

-- 2. shelter_constraints with eligibility_criteria. Single-adult, more
--    permissive than Onslow (only excludes SEX_OFFENSE; not ARSON), no
--    VAWA framing. Operationally distinct from the Onslow profile so
--    the demo story shows VARIETY across reentry-eligible options.
INSERT INTO shelter_constraints (
    shelter_id, sobriety_required, id_required, referral_required,
    pets_allowed, wheelchair_accessible, curfew_time, max_stay_days,
    population_types_served, eligibility_criteria
) VALUES
    ('d0000002-0000-0000-0000-000000000007',
     false, true, false, false, true, '22:00', 365,
     ARRAY['SINGLE_ADULT'],
     '{
        "criminal_record_policy": {
            "accepts_felonies": true,
            "excluded_offense_types": ["SEX_OFFENSE"],
            "individualized_assessment": true,
            "vawa_protections_apply": false,
            "notes": "Open felony charges considered case-by-case after a 30-minute intake interview with the case manager."
        },
        "program_requirements": [
            "Weekly check-in with case manager",
            "Active employment search within 45 days of intake",
            "Sobriety check-ins three times weekly (self-report)"
        ],
        "documentation_required": [
            "Government-issued ID or birth certificate",
            "Probation/parole officer contact information"
        ],
        "intake_hours": "Tuesday-Saturday 10:00am-6:00pm; case manager on call after hours"
     }'::jsonb)
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

-- 3. bed_availability — 12 total / 8 occupied = 4 available. Realistic
--    occupancy that shows "beds available tonight" without overstating
--    capacity for the demo profile.
INSERT INTO bed_availability (
    shelter_id, tenant_id, population_type, beds_total, beds_occupied,
    beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes
) VALUES
    ('d0000002-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000003',
     'SINGLE_ADULT', 12, 8, 0, true,
     NOW() - INTERVAL '15 minutes', 'seed', 'Recent intake update')
ON CONFLICT DO NOTHING;

-- 4. coordinator_assignment — east coordinator + east cocadmin both see
--    this shelter so the §11.5 hold-list and admin shelter-edit flows
--    work end-to-end.
INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000007'), -- east coordinator
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000007')  -- east cocadmin
ON CONFLICT DO NOTHING;
