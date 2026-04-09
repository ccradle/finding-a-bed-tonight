-- Seed data for local development
-- Run after Flyway migrations: psql -U fabt -d fabt -f seed-data.sql

-- Default tenant
INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'Development CoC',
    'dev-coc',
    '{"api_key_auth_enabled": true, "default_locale": "en", "hold_duration_minutes": 90, "dv_referral_expiry_minutes": 240, "dv_address_visibility": "ADMIN_AND_ASSIGNED", "hmis_vendors": [], "observability": {"prometheus_enabled": true, "tracing_enabled": false, "tracing_endpoint": "http://localhost:4318/v1/traces", "monitor_stale_interval_minutes": 5, "monitor_dv_canary_interval_minutes": 15, "monitor_temperature_interval_minutes": 60, "temperature_threshold_f": 32}}',
    NOW(), NOW()
) ON CONFLICT (slug) DO NOTHING;

-- Admin user (dvAccess=true, password: admin123)
-- BCrypt hash of 'admin123'
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'admin@dev.fabt.org',
    '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
    'Dev Admin',
    ARRAY['PLATFORM_ADMIN'],
    true,
    NOW(), NOW()
) ON CONFLICT (tenant_id, email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    roles = EXCLUDED.roles,
    dv_access = EXCLUDED.dv_access,
    status = COALESCE(EXCLUDED.status, 'ACTIVE'),
    totp_enabled = false,
    totp_secret_encrypted = NULL,
    recovery_codes = NULL,
    password_changed_at = NULL,
    token_version = 0,
    updated_at = NOW();

-- Outreach worker (dvAccess=false, password: admin123)
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'outreach@dev.fabt.org',
    '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
    'Dev Outreach Worker',
    ARRAY['OUTREACH_WORKER'],
    false,
    NOW(), NOW()
) ON CONFLICT (tenant_id, email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    roles = EXCLUDED.roles,
    dv_access = EXCLUDED.dv_access,
    status = COALESCE(EXCLUDED.status, 'ACTIVE'),
    totp_enabled = false,
    totp_secret_encrypted = NULL,
    recovery_codes = NULL,
    password_changed_at = NULL,
    token_version = 0,
    updated_at = NOW();

-- CoC Admin (password: admin123)
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    'cocadmin@dev.fabt.org',
    '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
    'Dev CoC Admin',
    ARRAY['COC_ADMIN'],
    false,
    NOW(), NOW()
) ON CONFLICT (tenant_id, email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    roles = EXCLUDED.roles,
    dv_access = EXCLUDED.dv_access,
    status = COALESCE(EXCLUDED.status, 'ACTIVE'),
    totp_enabled = false,
    totp_secret_encrypted = NULL,
    recovery_codes = NULL,
    password_changed_at = NULL,
    token_version = 0,
    updated_at = NOW();

-- DV outreach worker (dvAccess=true, password: admin123)
-- Persona: DV-certified outreach worker who can see DV shelters with redacted addresses
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000001',
    'dv-outreach@dev.fabt.org',
    '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
    'DV Outreach Worker',
    ARRAY['OUTREACH_WORKER'],
    true,
    NOW(), NOW()
) ON CONFLICT (tenant_id, email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    roles = EXCLUDED.roles,
    dv_access = EXCLUDED.dv_access,
    status = COALESCE(EXCLUDED.status, 'ACTIVE'),
    totp_enabled = false,
    totp_secret_encrypted = NULL,
    recovery_codes = NULL,
    password_changed_at = NULL,
    token_version = 0,
    updated_at = NOW();

-- Deactivated user (for admin panel screenshots and testing)
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, status, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000001',
    'former@dev.fabt.org',
    '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
    'Former Staff Member',
    ARRAY['OUTREACH_WORKER'],
    false,
    'DEACTIVATED',
    NOW(), NOW()
) ON CONFLICT (tenant_id, email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    roles = EXCLUDED.roles,
    dv_access = EXCLUDED.dv_access,
    status = COALESCE(EXCLUDED.status, 'ACTIVE'),
    totp_enabled = false,
    totp_secret_encrypted = NULL,
    recovery_codes = NULL,
    password_changed_at = NULL,
    token_version = 0,
    updated_at = NOW();

-- Sample OAuth2 provider (Google, for local testing — replace client ID/secret with real values)
INSERT INTO tenant_oauth2_provider (id, tenant_id, provider_name, client_id, client_secret_encrypted, issuer_uri, enabled, created_at)
VALUES (
    'c0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'google',
    'REPLACE_WITH_GOOGLE_CLIENT_ID',
    'REPLACE_WITH_GOOGLE_CLIENT_SECRET',
    'https://accounts.google.com',
    false,
    NOW()
) ON CONFLICT (tenant_id, provider_name) DO NOTHING;

-- Keycloak provider (local dev — enabled when using --oauth2 profile)
INSERT INTO tenant_oauth2_provider (id, tenant_id, provider_name, client_id, client_secret_encrypted, issuer_uri, enabled, created_at)
VALUES (
    'c0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'keycloak',
    'fabt-ui',
    'not-used-public-client',
    'http://localhost:8180/realms/fabt-dev',
    false,
    NOW()
) ON CONFLICT (tenant_id, provider_name) DO NOTHING;

-- ============================================================================
-- SHELTERS — 11 shelters in a Wake County CoC network
-- Designed as a coherent "day in the life" scenario for demo walkthroughs.
-- Story: Darius (outreach worker) searches for beds at 11:14 PM for a family
-- of five. Sandra (coordinator) updated her shelters earlier in the evening.
-- ============================================================================

INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, phone, latitude, longitude, dv_shelter, created_at, updated_at) VALUES
-- Family shelters (3) — Darius's search for FAMILY_WITH_CHILDREN returns these
('d0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Crabtree Valley Family Haven', '4325 Glenwood Ave', 'Raleigh', 'NC', '27612', '919-555-0101', 35.8401, -78.6807, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'Capital Boulevard Family Center', '1420 Capital Blvd', 'Raleigh', 'NC', '27603', '919-555-0102', 35.7942, -78.6295, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'New Beginnings Family Shelter', '3000 Poole Rd', 'Raleigh', 'NC', '27610', '919-555-0103', 35.7717, -78.5857, false, NOW(), NOW()),
-- General shelters
('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'Oak City Community Shelter', '314 E Hargett St', 'Raleigh', 'NC', '27601', '919-555-0104', 35.7796, -78.6382, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'South Wilmington Haven', '820 S Wilmington St', 'Raleigh', 'NC', '27601', '919-555-0105', 35.7723, -78.6362, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'Downtown Warming Station', '128 E Davie St', 'Raleigh', 'NC', '27601', '919-555-0106', 35.7769, -78.6371, false, NOW(), NOW()),
-- Specialized shelters
('d0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'Wake County Veterans Home', '200 Fayetteville St', 'Raleigh', 'NC', '27601', '919-555-0107', 35.7780, -78.6389, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'Youth Hope Center', '110 S McDowell St', 'Raleigh', 'NC', '27601', '919-555-0108', 35.7748, -78.6406, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'Women of Hope Shelter', '501 W Cabarrus St', 'Raleigh', 'NC', '27603', '919-555-0109', 35.7822, -78.6477, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'Helping Hand Recovery Center', '615 Chapanoke Rd', 'Raleigh', 'NC', '27603', '919-555-0110', 35.7504, -78.6566, false, NOW(), NOW()),
-- DV shelters (invisible to non-DV-authorized users)
-- 3 DV shelters required: dual-threshold suppression (DV_MIN_SHELTER_COUNT=3, DV_MIN_CELL_SIZE=5)
-- prevents small-cell inference in HMIS export and analytics aggregation
('d0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000001', 'Safe Haven DV Shelter', '999 Undisclosed Ave', 'Raleigh', 'NC', '27601', '919-555-0199', 35.7800, -78.6400, true, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000001', 'Harbor House', '998 Undisclosed Ave', 'Raleigh', 'NC', '27601', '919-555-0198', 35.7810, -78.6410, true, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000001', 'Bridges to Safety', '997 Undisclosed Ave', 'Raleigh', 'NC', '27601', '919-555-0197', 35.7820, -78.6420, true, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Shelter constraints
-- Caption 4: Crabtree Valley must have pets_allowed=true, wheelchair_accessible=true
INSERT INTO shelter_constraints (shelter_id, sobriety_required, id_required, referral_required, pets_allowed, wheelchair_accessible, curfew_time, max_stay_days, population_types_served) VALUES
('d0000000-0000-0000-0000-000000000001', false, false, false, true,  true,  '21:00', 180, ARRAY['FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000002', false, false, false, true,  true,  '21:00', 180, ARRAY['FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000003', false, false, false, true,  true,  '21:00', 90,  ARRAY['FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000004', false, false, false, false, true,  '22:00', 90,  ARRAY['SINGLE_ADULT']),
('d0000000-0000-0000-0000-000000000005', false, true,  false, false, false, '23:00', 30,  ARRAY['SINGLE_ADULT', 'VETERAN']),
('d0000000-0000-0000-0000-000000000006', false, false, false, false, true,  NULL,    1,   ARRAY['SINGLE_ADULT']),
('d0000000-0000-0000-0000-000000000007', true,  true,  true,  false, true,  '22:00', 365, ARRAY['VETERAN']),
('d0000000-0000-0000-0000-000000000008', false, false, false, false, false, NULL,    90,  ARRAY['YOUTH_18_24', 'YOUTH_UNDER_18']),
('d0000000-0000-0000-0000-000000000009', false, false, false, false, true,  '22:00', 180, ARRAY['WOMEN_ONLY']),
('d0000000-0000-0000-0000-000000000010', true,  true,  true,  false, false, '20:00', 365, ARRAY['SINGLE_ADULT']),
('d0000000-0000-0000-0000-000000000011', false, false, true,  true,  true,  NULL,    NULL,ARRAY['DV_SURVIVOR', 'WOMEN_ONLY', 'FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000012', false, false, true,  false, true,  NULL,    NULL,ARRAY['DV_SURVIVOR', 'WOMEN_ONLY']),
('d0000000-0000-0000-0000-000000000013', false, false, true,  true,  true,  NULL,    NULL,ARRAY['DV_SURVIVOR', 'FAMILY_WITH_CHILDREN'])
ON CONFLICT DO NOTHING;

-- NOTE: shelter_capacity table dropped in V20 — beds_total is now single-sourced
-- from bed_availability snapshots below. No separate capacity INSERT needed.

-- Coordinator assignments
-- Sandra (cocadmin) manages the 3 family shelters + Oak City + Women of Hope
-- Admin assigned to DV shelter (required for DV capture script coordinator views)
INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000001'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000002'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000003'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000004'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000009'),
('b0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000011')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- BED AVAILABILITY SNAPSHOTS — "Day in the life" timeline
--
-- Story timeline (all times relative to NOW()):
--   Sandra updated Oak City + Women of Hope at ~10:45 PM (30 min ago)
--   Crabtree Valley coordinator updated at ~11:00 PM (12 min ago) — caption 4
--   Capital Blvd coordinator updated at ~11:02 PM (10 min ago)
--   New Beginnings coordinator updated at ~11:05 PM (7 min ago)
--   Downtown Warming updated at ~11:07 PM (5 min ago) — always fresh
--   At 11:14 PM, Darius searches for FAMILY_WITH_CHILDREN → 3 green results
--
-- Total beds: 415 | Occupied: 324 | Utilization: 78.1%
-- ============================================================================
INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes) VALUES
-- Crabtree Valley Family Haven (20 family, 14 occupied = 6 available) — Darius taps this one
('d0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 20, 14, 0, true, NOW() - INTERVAL '12 minutes', 'seed', 'Evening update'),
-- Capital Blvd Family (30 family, 27 occupied, 1 held = 2 available)
('d0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 30, 27, 1, true, NOW() - INTERVAL '10 minutes', 'seed', 'Near capacity'),
-- New Beginnings Family (25 family, 12 occupied = 13 available)
('d0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 25, 12, 0, true, NOW() - INTERVAL '7 minutes', 'seed', 'Plenty of room'),
-- Oak City Community (50 single, 42 occupied = 8 available)
('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 50, 42, 0, true, NOW() - INTERVAL '30 minutes', 'seed', 'Evening count'),
-- South Wilmington (25 single, 25 occupied = FULL | 10 veteran, 5 occupied = 5 available)
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 25, 25, 0, false, NOW() - INTERVAL '2 hours', 'seed', 'Full since 6pm'),
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'VETERAN', 10, 5, 0, true, NOW() - INTERVAL '2 hours', 'seed', NULL),
-- Downtown Warming Station (100 single, 82 occupied, 3 held = 15 available)
('d0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 100, 82, 3, true, NOW() - INTERVAL '5 minutes', 'seed', 'Recently updated'),
-- Wake County Veterans Home (40 veteran, 33 occupied = 7 available)
('d0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'VETERAN', 40, 33, 0, true, NOW() - INTERVAL '1 hour', 'seed', 'Shift change update'),
-- Youth Hope (20 youth 18-24, 16 occupied = 4 available | 15 youth <18, 15 occupied = FULL)
('d0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'YOUTH_18_24', 20, 16, 0, true, NOW() - INTERVAL '20 minutes', 'seed', NULL),
('d0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'YOUTH_UNDER_18', 15, 15, 0, false, NOW() - INTERVAL '20 minutes', 'seed', 'Full — waitlist active'),
-- Women of Hope (35 women, 24 occupied = 11 available)
('d0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'WOMEN_ONLY', 35, 24, 0, true, NOW() - INTERVAL '30 minutes', 'seed', NULL),
-- Helping Hand Recovery (20 single, 18 occupied, 1 held = 1 available — sobriety required)
('d0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 20, 18, 1, true, NOW() - INTERVAL '3 hours', 'seed', 'Stale — coordinator off shift'),
-- Safe Haven DV (15 dv_survivor, 8 occupied = 7 available | 10 family, 5 occupied = 5 available)
('d0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000001', 'DV_SURVIVOR', 15, 8, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL),
('d0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 10, 5, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL),
-- Harbor House DV (8 dv_survivor, 6 occupied = 2 available)
('d0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000001', 'DV_SURVIVOR', 8, 6, 0, true, NOW() - INTERVAL '2 hours', 'seed', NULL),
-- Bridges to Safety DV (10 dv_survivor, 7 occupied = 3 available | 6 family, 4 occupied = 2 available)
('d0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000001', 'DV_SURVIVOR', 10, 7, 0, true, NOW() - INTERVAL '90 minutes', 'seed', NULL),
('d0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 6, 4, 0, true, NOW() - INTERVAL '90 minutes', 'seed', NULL)
ON CONFLICT DO NOTHING;
-- Utilization check: total=415+8+10+6=439, occupied=326+6+7+4=343, on_hold=5
-- 343/439 = 78.1% ✓ | DV shelters: 3 distinct, 49 total DV beds > suppression thresholds ✓

-- =====================================================================
-- API Keys — demo keys for admin screenshots (platform-hardening)
-- DEV ONLY — these keys have known hashes committed to the repo.
-- NEVER use these keys in production. The demo guard blocks all
-- API key mutations on the public demo site.
--
-- Key plaintext: fabt_demo_key_12345678901234567890123456789012
-- SHA-256: 5a84da3158eb5d8b6d28fc979310ac9f2301c583c6678b97a07fdf0ed6bd6e8f
-- =====================================================================
INSERT INTO api_key (id, tenant_id, shelter_id, key_hash, key_suffix, label, role, active, created_at, last_used_at, old_key_hash, old_key_expires_at)
VALUES
    -- Active key with recent usage
    ('e0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', NULL,
     '5a84da3158eb5d8b6d28fc979310ac9f2301c583c6678b97a07fdf0ed6bd6e8f', '9012',
     'Mobile App Integration', 'COC_ADMIN', true, NOW() - INTERVAL '14 days', NOW() - INTERVAL '2 hours',
     NULL, NULL),
    -- Active key in grace period (recently rotated, old key valid for 24h)
    ('e0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001',
     'd0000000-0000-0000-0000-000000000002',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'ab12',
     'Kiosk - Capital Blvd', 'COORDINATOR', true, NOW() - INTERVAL '7 days', NOW() - INTERVAL '30 minutes',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', NOW() + INTERVAL '20 hours'),
    -- Revoked key
    ('e0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', NULL,
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'cd34',
     'Legacy HMIS Bridge', 'COC_ADMIN', false, NOW() - INTERVAL '60 days', NOW() - INTERVAL '45 days',
     NULL, NULL)
ON CONFLICT (id) DO UPDATE SET
    last_used_at = EXCLUDED.last_used_at,
    active = EXCLUDED.active,
    old_key_hash = EXCLUDED.old_key_hash,
    old_key_expires_at = EXCLUDED.old_key_expires_at;

-- =====================================================================
-- Subscriptions — demo webhooks for admin screenshots (platform-hardening)
-- callback_secret_hash is a placeholder (encrypted secret would need the encryption key)
-- =====================================================================
INSERT INTO subscription (id, tenant_id, event_type, callback_url, callback_secret_hash, status, consecutive_failures, created_at, last_error)
VALUES
    ('f0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
     'shelter.availability.updated', 'https://api.partner-coc.org/webhooks/fabt', 'placeholder-encrypted',
     'ACTIVE', 0, NOW() - INTERVAL '21 days', NULL),
    ('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001',
     'shelter.updated', 'https://hmis.county.gov/inbound/fabt', 'placeholder-encrypted',
     'FAILING', 3, NOW() - INTERVAL '14 days', 'Connection timed out after 10000ms'),
    ('f0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001',
     'reservation.created', 'https://old-system.example.com/hooks', 'placeholder-encrypted',
     'DEACTIVATED', 5, NOW() - INTERVAL '30 days', '502 Bad Gateway')
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    consecutive_failures = EXCLUDED.consecutive_failures,
    last_error = EXCLUDED.last_error;

-- Delivery log entries for the active and failing subscriptions
INSERT INTO webhook_delivery_log (id, subscription_id, event_type, status_code, response_time_ms, attempted_at, attempt_number, response_body)
VALUES
    ('f1000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-000000000001',
     'shelter.availability.updated', 200, 145, NOW() - INTERVAL '2 hours', 1, '{"status":"ok"}'),
    ('f1000000-0000-0000-0000-000000000002', 'f0000000-0000-0000-0000-000000000001',
     'shelter.availability.updated', 200, 132, NOW() - INTERVAL '4 hours', 1, '{"status":"ok"}'),
    ('f1000000-0000-0000-0000-000000000003', 'f0000000-0000-0000-0000-000000000001',
     'shelter.availability.updated', 200, 189, NOW() - INTERVAL '6 hours', 1, '{"status":"ok"}'),
    ('f1000000-0000-0000-0000-000000000004', 'f0000000-0000-0000-0000-000000000002',
     'shelter.updated', 502, 10023, NOW() - INTERVAL '1 hour', 1, '502 Bad Gateway'),
    ('f1000000-0000-0000-0000-000000000005', 'f0000000-0000-0000-0000-000000000002',
     'shelter.updated', 502, 10015, NOW() - INTERVAL '2 hours', 1, '502 Bad Gateway'),
    ('f1000000-0000-0000-0000-000000000006', 'f0000000-0000-0000-0000-000000000002',
     'shelter.updated', 200, 98, NOW() - INTERVAL '3 hours', 1, '{"status":"ok"}')
ON CONFLICT (id) DO NOTHING;

-- =====================================================================
-- Notifications — demo data for bell badge and notification dropdown
-- RLS note: INSERT policy is WITH CHECK (true), so plain INSERTs work
-- without set_config. Only SELECT/UPDATE are recipient-scoped.
-- =====================================================================
INSERT INTO notification (id, tenant_id, recipient_id, type, severity, payload, read_at, acted_at, created_at, expires_at)
VALUES
    -- Coordinator (cocadmin): new DV referral needs review — ACTION_REQUIRED, unread
    ('a1000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
     'b0000000-0000-0000-0000-000000000003', 'referral.requested', 'ACTION_REQUIRED',
     '{"referralId": "00000000-0000-0000-0000-000000000099", "shelterId": "c0000000-0000-0000-0000-000000000001"}',
     NULL, NULL, NOW() - INTERVAL '15 minutes', NULL),
    -- Coordinator (cocadmin): surge event activated — CRITICAL, unread, requires acknowledgement
    ('a1000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001',
     'b0000000-0000-0000-0000-000000000003', 'surge.activated', 'CRITICAL',
     '{"surgeEventId": "00000000-0000-0000-0000-000000000088", "reason": "White Flag — temperature below 32°F"}',
     NULL, NULL, NOW() - INTERVAL '5 minutes', NULL),
    -- Outreach worker: referral accepted — ACTION_REQUIRED, unread
    ('a1000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001',
     'b0000000-0000-0000-0000-000000000002', 'referral.responded', 'ACTION_REQUIRED',
     '{"referralId": "00000000-0000-0000-0000-000000000097", "status": "ACCEPTED"}',
     NULL, NULL, NOW() - INTERVAL '10 minutes', NULL)
ON CONFLICT (id) DO NOTHING;
