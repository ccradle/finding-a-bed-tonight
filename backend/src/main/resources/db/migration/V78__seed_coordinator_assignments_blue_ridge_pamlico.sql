-- V78: Seed coordinator_assignment for Blue Ridge + Pamlico Sound tenants
--
-- Gap-fix for V76 + V77 — those migrations created the tenant / user /
-- shelter / shelter_constraints / bed_availability rows but missed the
-- `coordinator_assignment` rows that map each coordinator to the shelters
-- they oversee. Consequence: the `GET /api/v1/dv-referrals/pending/count`
-- endpoint (which filters by the caller's assigned shelters via
-- `ReferralTokenController.countPending` + `countPendingByShelterIds`)
-- returns `{count: 0, firstPending: null}` for every coordinator in the
-- new tenants, so the `CoordinatorReferralBanner` never renders and
-- DV-referral wayfinding is dead end-to-end.
--
-- Caught live on 2026-04-20 during the v0.48 post-deploy 3-tenant
-- walkthrough — dv-coordinator@pamlico.fabt.org created a PENDING
-- referral against Safe Haven Demo DV East and saw no banner.
--
-- Mirror of dev-coc's assignment pattern (seed-data.sql:247-258): the
-- DV coordinator is assigned to ALL DV shelters in the tenant (required
-- for receive-referral notifications), and the regular coordinator is
-- assigned to the non-DV general shelters.
--
-- Idempotent via ON CONFLICT DO NOTHING (coordinator_assignment has a
-- PK on (user_id, shelter_id)).

-- Assignment pattern mirrors dev-coc's seed-data.sql:247-258:
--   admin    → DV shelter only (required for DV capture views)
--   cocadmin → all shelters (manages the tenant end-to-end)
--   coordinator → non-DV shelters
--   dv-coordinator → DV shelters (receives referral notifications — banner)
INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES
    -- Blue Ridge (dev-coc-west)
    -- admin → DV West
    ('b0000001-0000-0000-0000-000000000001', 'd0000001-0000-0000-0000-000000000003'),
    -- cocadmin → all 3 Blue Ridge shelters
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000001'),
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000002'),
    ('b0000001-0000-0000-0000-000000000002', 'd0000001-0000-0000-0000-000000000003'),
    -- coordinator → 2 non-DV Blue Ridge shelters
    ('b0000001-0000-0000-0000-000000000003', 'd0000001-0000-0000-0000-000000000001'),
    ('b0000001-0000-0000-0000-000000000003', 'd0000001-0000-0000-0000-000000000002'),
    -- dv-coordinator → DV West (banner recipient)
    ('b0000001-0000-0000-0000-000000000005', 'd0000001-0000-0000-0000-000000000003'),

    -- Pamlico Sound (dev-coc-east) — parallel pattern
    -- admin → DV East
    ('b0000002-0000-0000-0000-000000000001', 'd0000002-0000-0000-0000-000000000003'),
    -- cocadmin → all 3 Pamlico shelters
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000001'),
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000002'),
    ('b0000002-0000-0000-0000-000000000002', 'd0000002-0000-0000-0000-000000000003'),
    -- coordinator → 2 non-DV Pamlico shelters
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000001'),
    ('b0000002-0000-0000-0000-000000000003', 'd0000002-0000-0000-0000-000000000002'),
    -- dv-coordinator → DV East (banner recipient)
    ('b0000002-0000-0000-0000-000000000005', 'd0000002-0000-0000-0000-000000000003')
ON CONFLICT DO NOTHING;
