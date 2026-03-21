-- Seed data for local development
-- Run after Flyway migrations: psql -U fabt -d fabt -f seed-data.sql

-- Default tenant
INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'Development CoC',
    'dev-coc',
    '{"api_key_auth_enabled": true, "default_locale": "en", "hold_duration_minutes": 45}',
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
) ON CONFLICT (tenant_id, email) DO NOTHING;

-- Outreach worker (dvAccess=false, password: outreach123)
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
) ON CONFLICT (tenant_id, email) DO NOTHING;

-- CoC Admin (password: cocadmin123)
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
) ON CONFLICT (tenant_id, email) DO NOTHING;

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

-- 10 synthetic shelters with varied constraints
-- (Include addresses modeled on Raleigh, NC area patterns)

INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, phone, latitude, longitude, dv_shelter, created_at, updated_at) VALUES
('d0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Oak City Emergency Shelter', '314 E Hargett St', 'Raleigh', 'NC', '27601', '919-555-0101', 35.7796, -78.6382, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'Capital Boulevard Family Center', '1420 Capital Blvd', 'Raleigh', 'NC', '27603', '919-555-0102', 35.7942, -78.6295, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'South Wilmington Haven', '820 S Wilmington St', 'Raleigh', 'NC', '27601', '919-555-0103', 35.7723, -78.6362, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'Wake County Veterans Shelter', '200 Fayetteville St', 'Raleigh', 'NC', '27601', '919-555-0104', 35.7780, -78.6389, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'Youth Hope Center', '110 S McDowell St', 'Raleigh', 'NC', '27601', '919-555-0105', 35.7748, -78.6406, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'Women of Hope Shelter', '501 W Cabarrus St', 'Raleigh', 'NC', '27603', '919-555-0106', 35.7822, -78.6477, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'Helping Hand Recovery Center', '615 Chapanoke Rd', 'Raleigh', 'NC', '27603', '919-555-0107', 35.7504, -78.6566, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'New Beginnings Family Shelter', '3000 Poole Rd', 'Raleigh', 'NC', '27610', '919-555-0108', 35.7717, -78.5857, false, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'Safe Haven DV Shelter', '999 Undisclosed Ave', 'Raleigh', 'NC', '27601', '919-555-0109', 35.7800, -78.6400, true, NOW(), NOW()),
('d0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'Downtown Warming Station', '128 E Davie St', 'Raleigh', 'NC', '27601', '919-555-0110', 35.7769, -78.6371, false, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Shelter constraints
INSERT INTO shelter_constraints (shelter_id, sobriety_required, id_required, referral_required, pets_allowed, wheelchair_accessible, curfew_time, max_stay_days, population_types_served) VALUES
('d0000000-0000-0000-0000-000000000001', false, false, false, false, true, '22:00', 90, ARRAY['SINGLE_ADULT']),
('d0000000-0000-0000-0000-000000000002', false, false, false, true, true, '21:00', 180, ARRAY['FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000003', false, true, false, false, false, '23:00', 30, ARRAY['SINGLE_ADULT', 'VETERAN']),
('d0000000-0000-0000-0000-000000000004', true, true, true, false, true, '22:00', 365, ARRAY['VETERAN']),
('d0000000-0000-0000-0000-000000000005', false, false, false, false, false, NULL, 90, ARRAY['YOUTH_18_24', 'YOUTH_UNDER_18']),
('d0000000-0000-0000-0000-000000000006', false, false, false, false, true, '22:00', 180, ARRAY['WOMEN_ONLY']),
('d0000000-0000-0000-0000-000000000007', true, true, true, false, false, '20:00', 365, ARRAY['SINGLE_ADULT']),
('d0000000-0000-0000-0000-000000000008', false, false, false, true, true, '21:00', 90, ARRAY['FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000009', false, false, true, true, true, NULL, NULL, ARRAY['DV_SURVIVOR', 'WOMEN_ONLY', 'FAMILY_WITH_CHILDREN']),
('d0000000-0000-0000-0000-000000000010', false, false, false, false, true, NULL, 1, ARRAY['SINGLE_ADULT'])
ON CONFLICT DO NOTHING;

-- Shelter capacities
INSERT INTO shelter_capacity (shelter_id, population_type, beds_total) VALUES
('d0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 50),
('d0000000-0000-0000-0000-000000000002', 'FAMILY_WITH_CHILDREN', 30),
('d0000000-0000-0000-0000-000000000003', 'SINGLE_ADULT', 25),
('d0000000-0000-0000-0000-000000000003', 'VETERAN', 10),
('d0000000-0000-0000-0000-000000000004', 'VETERAN', 40),
('d0000000-0000-0000-0000-000000000005', 'YOUTH_18_24', 20),
('d0000000-0000-0000-0000-000000000005', 'YOUTH_UNDER_18', 15),
('d0000000-0000-0000-0000-000000000006', 'WOMEN_ONLY', 35),
('d0000000-0000-0000-0000-000000000007', 'SINGLE_ADULT', 20),
('d0000000-0000-0000-0000-000000000008', 'FAMILY_WITH_CHILDREN', 25),
('d0000000-0000-0000-0000-000000000009', 'DV_SURVIVOR', 15),
('d0000000-0000-0000-0000-000000000009', 'FAMILY_WITH_CHILDREN', 10),
('d0000000-0000-0000-0000-000000000010', 'SINGLE_ADULT', 100)
ON CONFLICT DO NOTHING;

-- Coordinator assignments (cocadmin assigned to first 5 shelters)
INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000001'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000002'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000003'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000004'),
('b0000000-0000-0000-0000-000000000003', 'd0000000-0000-0000-0000-000000000005')
ON CONFLICT DO NOTHING;

-- Bed availability snapshots (realistic occupancy — some beds available, some full)
INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes) VALUES
-- Oak City Emergency (50 single adult, 38 occupied = 12 available)
('d0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 50, 38, 0, true, NOW() - INTERVAL '30 minutes', 'seed', 'Evening count'),
-- Capital Blvd Family (30 family, 28 occupied, 1 held = 1 available)
('d0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 30, 28, 1, true, NOW() - INTERVAL '45 minutes', 'seed', 'Near capacity'),
-- South Wilmington (25 single, 25 occupied = FULL)
('d0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 25, 25, 0, false, NOW() - INTERVAL '2 hours', 'seed', 'Full since 6pm'),
-- South Wilmington veteran (10 veteran, 4 occupied = 6 available)
('d0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'VETERAN', 10, 4, 0, true, NOW() - INTERVAL '2 hours', 'seed', NULL),
-- Wake County Veterans (40 veteran, 31 occupied = 9 available)
('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'VETERAN', 40, 31, 0, true, NOW() - INTERVAL '1 hour', 'seed', 'Shift change update'),
-- Youth Hope (20 youth 18-24, 14 occupied = 6 available)
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'YOUTH_18_24', 20, 14, 0, true, NOW() - INTERVAL '20 minutes', 'seed', NULL),
-- Youth Hope (15 youth under 18, 15 occupied = FULL)
('d0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'YOUTH_UNDER_18', 15, 15, 0, false, NOW() - INTERVAL '20 minutes', 'seed', 'Full — waitlist active'),
-- Women of Hope (35 women, 22 occupied = 13 available)
('d0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'WOMEN_ONLY', 35, 22, 0, true, NOW() - INTERVAL '15 minutes', 'seed', NULL),
-- Helping Hand Recovery (20 single, 18 occupied, 1 held = 1 available — sobriety required)
('d0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 20, 18, 1, true, NOW() - INTERVAL '3 hours', 'seed', 'Stale data — coordinator off shift'),
-- New Beginnings Family (25 family, 10 occupied = 15 available)
('d0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 25, 10, 0, true, NOW() - INTERVAL '10 minutes', 'seed', 'Plenty of room'),
-- DV Shelter (15 dv_survivor, 8 occupied = 7 available)
('d0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'DV_SURVIVOR', 15, 8, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL),
-- DV Shelter family (10 family, 6 occupied = 4 available)
('d0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'FAMILY_WITH_CHILDREN', 10, 6, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL),
-- Downtown Warming Station (100 single, 72 occupied, 3 held = 25 available)
('d0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'SINGLE_ADULT', 100, 72, 3, true, NOW() - INTERVAL '5 minutes', 'seed', 'Recently updated')
ON CONFLICT DO NOTHING;
