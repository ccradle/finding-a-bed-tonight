-- V98 — platform_config singleton table
--
-- Houses platform-wide observability configuration that the
-- /admin#observability tab used to (incorrectly) store per-tenant.
-- Per the platform-observability-split openspec change (2026-05-02),
-- the 6 tenant-agnostic fields move to this single platform-level row:
--   - prometheus_enabled            (JVM-level Prometheus scrape gate)
--   - tracing_enabled               (JVM-level OTel exporter gate)
--   - tracing_endpoint              (OTLP collector URL)
--   - monitor_stale_interval_minutes      (scheduler cadence)
--   - monitor_dv_canary_interval_minutes  (security SLO cadence)
--   - monitor_temperature_interval_minutes (NOAA fetch cadence)
--
-- Single-row invariant (design D1): the table holds exactly one row,
-- enforced by a CHECK constraint on the canonical UUID. This avoids
-- "rows-per-environment drift on deploy" and keeps reads as a simple
-- WHERE-id lookup. The pattern mirrors the singleton-row convention
-- already used elsewhere (see also: surge_event active-row pattern).
--
-- The pre-existing per-tenant tenant.config.observability JSONB keys
-- are kept readable for one release cycle (design D3 backward-read).
-- A v0.58+ Flyway migration drops them once prod observability
-- confirms zero reads from the old locations.

CREATE TABLE platform_config (
    id          UUID PRIMARY KEY DEFAULT '00000000-0000-0000-0000-000000000001'::uuid,
    config      JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID,  -- nullable; null on initial seed row
    CONSTRAINT platform_config_singleton CHECK (id = '00000000-0000-0000-0000-000000000001'::uuid)
);

COMMENT ON TABLE platform_config IS
    'Platform-wide observability + scheduler config. Single-row table '
    'enforced by the platform_config_singleton CHECK constraint. Read by '
    'PlatformConfigService at startup + on every monitor reschedule; '
    'written by PUT /api/v1/platform/observability (PLATFORM_OPERATOR + '
    '@PlatformAdminOnly + X-Platform-Justification header).';

COMMENT ON COLUMN platform_config.config IS
    'JSONB containing 6 platform-wide observability settings. '
    'Schema (informally enforced by PlatformConfig record + endpoint '
    'validation): prometheus_enabled (bool), tracing_enabled (bool), '
    'tracing_endpoint (string URL), monitor_stale_interval_minutes (int 1..1440), '
    'monitor_dv_canary_interval_minutes (int 1..1440), '
    'monitor_temperature_interval_minutes (int 1..1440). Per design D4 '
    'bounds enforced by the controller, not at the DB layer.';

COMMENT ON COLUMN platform_config.updated_by IS
    'platform_user.id of the operator who last applied a change, or NULL '
    'for the initial-seed row. Cross-references the audit_events row for '
    'each PLATFORM_OBSERVABILITY_UPDATED emission.';

-- Initial seed row: defaults match the literal @Scheduled fixedRate
-- values that OperationalMonitorService used pre-refactor (5/15/60
-- minutes), plus the localhost OTel collector endpoint that
-- application.yml has shipped since the observability profile.
INSERT INTO platform_config (id, config) VALUES (
    '00000000-0000-0000-0000-000000000001'::uuid,
    '{
        "prometheus_enabled": true,
        "tracing_enabled": false,
        "tracing_endpoint": "http://localhost:4318/v1/traces",
        "monitor_stale_interval_minutes": 5,
        "monitor_dv_canary_interval_minutes": 15,
        "monitor_temperature_interval_minutes": 60
    }'::jsonb
);
