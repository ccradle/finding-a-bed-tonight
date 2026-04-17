-- V60 — multi-tenant-production-readiness Phase A task 2.1
--
-- Adds four columns to the `tenant` table that the rest of Phase A (and later
-- phases F, H, M) depends on. Forward-only, idempotent (`IF NOT EXISTS`).
--
-- Columns added:
--
-- 1. `state TENANT_STATE NOT NULL DEFAULT 'ACTIVE'`
--      Lifecycle FSM state per design D8. Phase A only relies on the column
--      existing; the actual state-machine wiring (transition guards, audit
--      hooks) lands in Phase F. Default 'ACTIVE' preserves existing tenant
--      behavior — every legacy row reads as ACTIVE without any backfill.
--
-- 2. `jwt_key_generation INT NOT NULL DEFAULT 1`
--      Per-tenant JWT signing key generation counter (per design D1, A2).
--      Bumped on tenant suspend (atomic JWT invalidation) and on operator
--      key-rotation. The JwtService.validate path (task 2.9) reads this
--      column when resolving an opaque kid back to a (tenant, generation)
--      pair. Default 1 means every existing tenant starts at gen 1; the
--      first JWT issued under per-tenant keys will carry a kid that
--      resolves to (tenant_uuid, generation=1).
--
-- 3. `data_residency_region VARCHAR(50) NOT NULL DEFAULT 'us-any'`
--      Per design F7 / Phase H. Standard pooled tier defaults to 'us-any'
--      (no residency pin); regulated CoCs requiring a specific jurisdiction
--      override at tenant creation time. Phase A doesn't activate any
--      residency-aware code path; the column exists so Phase F's silo
--      routing can read it without a follow-up migration.
--
-- 4. `oncall_email VARCHAR(255)` (nullable)
--      Per-tenant on-call email for tenant-tagged Grafana alerts (per design
--      G5 / Phase G). Nullable because not every tenant has a dedicated
--      on-call rotation; alert routing falls back to the platform-default
--      address when null. Phase A doesn't read this column; Phase G's
--      Alertmanager config consumes it.
--
-- The TENANT_STATE enum is created here because PostgreSQL requires the type
-- to exist before a column can reference it. Phase F will add the
-- service-layer enforcement of allowed transitions; this migration only
-- materializes the type.

CREATE TYPE tenant_state AS ENUM (
    'ACTIVE',
    'SUSPENDED',
    'OFFBOARDING',
    'ARCHIVED',
    'DELETED'
);

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS state tenant_state NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS jwt_key_generation INT NOT NULL DEFAULT 1;

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS data_residency_region VARCHAR(50) NOT NULL DEFAULT 'us-any';

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS oncall_email VARCHAR(255);

COMMENT ON COLUMN tenant.state IS
    'Lifecycle FSM state. Phase A column-only; Phase F adds the transition state machine.';
COMMENT ON COLUMN tenant.jwt_key_generation IS
    'Generation counter for per-tenant JWT signing key. Bumped on suspend (atomic invalidation) and operator rotation. Read by JwtService.validate when resolving kid → (tenant, generation).';
COMMENT ON COLUMN tenant.data_residency_region IS
    'Region pin (e.g. us-ashburn, eu-frankfurt). Standard pooled tier uses us-any. Read by Phase F silo routing.';
COMMENT ON COLUMN tenant.oncall_email IS
    'Per-tenant on-call email for tenant-tagged Grafana alerts. Nullable; falls back to platform-default routing when absent.';
