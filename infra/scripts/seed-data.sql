-- Seed data for local development
-- Run after Flyway migrations: psql -U fabt -d fabt -f seed-data.sql

-- Default tenant
INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'Development CoC',
    'dev-coc',
    '{"api_key_auth_enabled": true, "default_locale": "en"}',
    NOW(), NOW()
) ON CONFLICT (slug) DO NOTHING;

-- Admin user (dvAccess=true, password: admin123)
-- BCrypt hash of 'admin123'
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'admin@dev.fabt.org',
    '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBi/PLKH.V1GjCxwW7r0v4zJLSG',
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
    '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBi/PLKH.V1GjCxwW7r0v4zJLSG',
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
    '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBi/PLKH.V1GjCxwW7r0v4zJLSG',
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
