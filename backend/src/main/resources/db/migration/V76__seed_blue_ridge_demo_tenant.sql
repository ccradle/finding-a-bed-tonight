-- V76: Seed Blue Ridge CoC (demo) — second demo tenant (slug=dev-coc-west)
--
-- Phase M-light pull-forward per openspec multi-tenant-production-readiness
-- task 14.4. Extracted from infra/scripts/seed-data.sql so that prod picks up
-- the tenant on deploy without relying on the dev-only seed script.
--
-- Idempotency: every row uses ON CONFLICT DO UPDATE so a re-run (or a prior
-- seed-data.sql load) is safely absorbed. Matches the seed-data.sql upsert
-- pattern verbatim; if the two ever diverge, seed-data.sql wins on dev
-- because it runs after Flyway.
--
-- UUID convention: tenant a0000000-…-000002; users b0000001-…; shelters
-- d0000001-… (mirrors the dev-coc shape in the same seed file).
--
-- Branding: "Blue Ridge" is a multi-state mountain range, not a
-- HUD-registered CoC — no real-jurisdiction collision. The "(demo)" suffix
-- on tenant.name is the visual audit signal per Elena/Casey. Street
-- addresses use "Fictional Way" per D12; city names use real NC mountain
-- towns (Boone, Waynesville) so the map/search surface feels realistic.
-- DV shelter address is intentionally undisclosed.
--
-- NOAA station KAVL = Asheville regional airport — covers Boone +
-- Waynesville within a few miles. Tenant-scoped per the
-- per-tenant-weather-station spec requirement shipped in v0.47.1 code.

INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'Blue Ridge CoC (demo)',
    'dev-coc-west',
    '{"api_key_auth_enabled": true, "default_locale": "en", "hold_duration_minutes": 90, "dv_referral_expiry_minutes": 240, "dv_address_visibility": "ADMIN_AND_ASSIGNED", "hmis_vendors": [], "observability": {"prometheus_enabled": true, "tracing_enabled": false, "tracing_endpoint": "http://localhost:4318/v1/traces", "monitor_stale_interval_minutes": 5, "monitor_dv_canary_interval_minutes": 15, "monitor_temperature_interval_minutes": 60, "temperature_threshold_f": 32, "noaa_station_id": "KAVL"}}',
    NOW(), NOW()
) ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    config = EXCLUDED.config,
    updated_at = NOW();

-- 6-role user matrix (all passwords: admin123). bcrypt hash reused from
-- seed-data.sql — shared dev/demo credential, not a secret.
INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES
    ('b0000001-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002',
     'admin@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge Admin', ARRAY['PLATFORM_ADMIN'], true, NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002',
     'cocadmin@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge CoC Admin', ARRAY['COC_ADMIN'], true, NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002',
     'coordinator@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge Coordinator', ARRAY['COORDINATOR'], false, NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002',
     'outreach@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge Outreach', ARRAY['OUTREACH_WORKER'], false, NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002',
     'dv-coordinator@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge DV Coordinator', ARRAY['COORDINATOR'], true, NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002',
     'dv-outreach@blueridge.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Blue Ridge DV Outreach', ARRAY['OUTREACH_WORKER'], true, NOW(), NOW())
ON CONFLICT (tenant_id, email) DO UPDATE SET
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

-- 3 shelters: 2 real-city (Boone, Waynesville) + 1 DV (undisclosed)
INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, phone, latitude, longitude, dv_shelter, created_at, updated_at)
VALUES
    ('d0000001-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002',
     'Example House North (demo)', '101 Fictional Way', 'Boone', 'NC', '28607', '000-555-0101',
     36.22, -81.67, false, NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002',
     'Blue Ridge Example Shelter (demo)', '102 Fictional Way', 'Waynesville', 'NC', '28786', '000-555-0102',
     35.49, -82.99, false, NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002',
     'Safe Haven Demo DV West', '999 Undisclosed Demo', 'Undisclosed', 'NC', '00000', '000-555-0103',
     35.7, -82.7, true, NOW(), NOW())
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
    updated_at = NOW();

INSERT INTO shelter_constraints (shelter_id, sobriety_required, id_required, referral_required, pets_allowed, wheelchair_accessible, curfew_time, max_stay_days, population_types_served)
VALUES
    ('d0000001-0000-0000-0000-000000000001', false, false, false, true,  true, '21:00', 180, ARRAY['FAMILY_WITH_CHILDREN']),
    ('d0000001-0000-0000-0000-000000000002', false, false, false, false, true, '22:00', 90,  ARRAY['SINGLE_ADULT']),
    ('d0000001-0000-0000-0000-000000000003', false, false, true,  true,  true, NULL,    NULL, ARRAY['DV_SURVIVOR', 'WOMEN_ONLY', 'FAMILY_WITH_CHILDREN'])
ON CONFLICT (shelter_id) DO UPDATE SET
    sobriety_required = EXCLUDED.sobriety_required,
    id_required = EXCLUDED.id_required,
    referral_required = EXCLUDED.referral_required,
    pets_allowed = EXCLUDED.pets_allowed,
    wheelchair_accessible = EXCLUDED.wheelchair_accessible,
    curfew_time = EXCLUDED.curfew_time,
    max_stay_days = EXCLUDED.max_stay_days,
    population_types_served = EXCLUDED.population_types_served;

-- Bed availability for demo tour. Moderate occupancy (~66%) so search
-- surfaces return results but not "empty zone". Mirrors dev-coc profile.
INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes) VALUES
    ('d0000001-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002', 'FAMILY_WITH_CHILDREN', 20, 13, 0, true, NOW() - INTERVAL '15 minutes', 'seed', 'Evening update'),
    ('d0000001-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'SINGLE_ADULT',         40, 28, 0, true, NOW() - INTERVAL '20 minutes', 'seed', NULL),
    ('d0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 'DV_SURVIVOR',          12,  7, 0, true, NOW() - INTERVAL '45 minutes', 'seed', NULL),
    ('d0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 'WOMEN_ONLY',           10,  6, 0, true, NOW() - INTERVAL '45 minutes', 'seed', NULL),
    ('d0000001-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', 'FAMILY_WITH_CHILDREN',  8,  5, 0, true, NOW() - INTERVAL '45 minutes', 'seed', NULL)
ON CONFLICT DO NOTHING;
