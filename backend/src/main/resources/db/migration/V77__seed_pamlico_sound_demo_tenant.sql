-- V77: Seed Pamlico Sound CoC (demo) — third demo tenant (slug=dev-coc-east)
--
-- Phase M-light pull-forward per openspec multi-tenant-production-readiness
-- task 14.4b. Parallel to V76 (Blue Ridge); see that file's header for the
-- full design rationale.
--
-- UUID convention: tenant a0000000-…-000003; users b0000002-…; shelters
-- d0000002-…
--
-- Branding: "Pamlico Sound" is a coastal NC lagoon — geographic feature,
-- not a jurisdiction. Cities use real coastal-NC towns (New Bern,
-- Washington) for realism; street addresses stay fictional; DV shelter
-- address undisclosed.
--
-- NOAA station KEWN = Craven County Regional (New Bern) — covers Pamlico
-- Sound coastal region.

INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'Pamlico Sound CoC (demo)',
    'dev-coc-east',
    '{"api_key_auth_enabled": true, "default_locale": "en", "hold_duration_minutes": 90, "dv_referral_expiry_minutes": 240, "dv_address_visibility": "ADMIN_AND_ASSIGNED", "hmis_vendors": [], "observability": {"prometheus_enabled": true, "tracing_enabled": false, "tracing_endpoint": "http://localhost:4318/v1/traces", "monitor_stale_interval_minutes": 5, "monitor_dv_canary_interval_minutes": 15, "monitor_temperature_interval_minutes": 60, "temperature_threshold_f": 32, "noaa_station_id": "KEWN"}}',
    NOW(), NOW()
) ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    config = EXCLUDED.config,
    updated_at = NOW();

INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
VALUES
    ('b0000002-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000003',
     'admin@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound Admin', ARRAY['PLATFORM_ADMIN'], true, NOW(), NOW()),
    ('b0000002-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000003',
     'cocadmin@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound CoC Admin', ARRAY['COC_ADMIN'], true, NOW(), NOW()),
    ('b0000002-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003',
     'coordinator@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound Coordinator', ARRAY['COORDINATOR'], false, NOW(), NOW()),
    ('b0000002-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000003',
     'outreach@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound Outreach', ARRAY['OUTREACH_WORKER'], false, NOW(), NOW()),
    ('b0000002-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003',
     'dv-coordinator@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound DV Coordinator', ARRAY['COORDINATOR'], true, NOW(), NOW()),
    ('b0000002-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003',
     'dv-outreach@pamlico.fabt.org', '$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
     'Pamlico Sound DV Outreach', ARRAY['OUTREACH_WORKER'], true, NOW(), NOW())
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

INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, phone, latitude, longitude, dv_shelter, created_at, updated_at)
VALUES
    ('d0000002-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000003',
     'Example Coastal House (demo)', '201 Fictional Way', 'New Bern', 'NC', '28560', '000-555-0201',
     35.11, -77.04, false, NOW(), NOW()),
    ('d0000002-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000003',
     'Pamlico Example Shelter (demo)', '202 Fictional Way', 'Washington', 'NC', '27889', '000-555-0202',
     35.55, -77.05, false, NOW(), NOW()),
    ('d0000002-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003',
     'Safe Haven Demo DV East', '999 Undisclosed Demo', 'Undisclosed', 'NC', '00000', '000-555-0203',
     35.4, -76.4, true, NOW(), NOW())
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
    ('d0000002-0000-0000-0000-000000000001', false, false, false, true,  true, '21:00', 180, ARRAY['FAMILY_WITH_CHILDREN']),
    ('d0000002-0000-0000-0000-000000000002', false, false, false, false, true, '22:00', 90,  ARRAY['SINGLE_ADULT']),
    ('d0000002-0000-0000-0000-000000000003', false, false, true,  false, true, NULL,    NULL, ARRAY['DV_SURVIVOR', 'WOMEN_ONLY'])
ON CONFLICT (shelter_id) DO UPDATE SET
    sobriety_required = EXCLUDED.sobriety_required,
    id_required = EXCLUDED.id_required,
    referral_required = EXCLUDED.referral_required,
    pets_allowed = EXCLUDED.pets_allowed,
    wheelchair_accessible = EXCLUDED.wheelchair_accessible,
    curfew_time = EXCLUDED.curfew_time,
    max_stay_days = EXCLUDED.max_stay_days,
    population_types_served = EXCLUDED.population_types_served;

INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes) VALUES
    ('d0000002-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000003', 'FAMILY_WITH_CHILDREN', 25, 17, 0, true, NOW() - INTERVAL '10 minutes', 'seed', NULL),
    ('d0000002-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000003', 'SINGLE_ADULT',         50, 38, 0, true, NOW() - INTERVAL '25 minutes', 'seed', 'Recently updated'),
    ('d0000002-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003', 'DV_SURVIVOR',          15,  9, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL),
    ('d0000002-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000003', 'WOMEN_ONLY',           12,  7, 0, true, NOW() - INTERVAL '1 hour', 'seed', NULL)
ON CONFLICT DO NOTHING;
