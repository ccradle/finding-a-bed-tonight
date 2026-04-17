-- V61 — multi-tenant-production-readiness Phase A tasks 2.2 + 2.3 + 2.4
--
-- Three new tables for per-tenant key derivation + JWT lifecycle. Bundled
-- in one migration because all three are required simultaneously by the
-- KeyDerivationService + JwtService refactor (tasks 2.5–2.11) and shipping
-- them in separate migrations would create three half-functional intermediate
-- states for Flyway to traverse.
--
-- Forward-only; CREATE TABLE IF NOT EXISTS for idempotency under partial-
-- apply recovery. Future Phase A migrations (V74 re-encrypt) and Phase F
-- (tenant lifecycle) populate / consume these tables; this migration only
-- creates the schema.
--
-- DEFERRED CONCERN: Marcus warroom flagged that fabt_app could in principle
-- INSERT into tenant_key_material / kid_to_tenant_key to escalate (associate
-- a kid with another tenant's key material, then use that kid to validate a
-- forged JWT). The clean RLS fix is non-trivial because the JWT validate
-- path looks up kid_to_tenant_key BEFORE TenantContext is bound (the kid
-- lookup IS the binding step). Deferring to Phase B task 3.4 (V67 D14 RLS
-- rollout). Two interim defenses keep this acceptable in Phase A:
--
--   1. opaque-random kid (UUID) — attacker cannot guess valid kids
--   2. JwtService.validate cross-checks claim.tenantId against kid-resolved
--      tenant (task 2.10) — kid-confusion attack rejected with audit event
--
-- jwt_revocations is intrinsically platform-scoped (no tenant_id column);
-- protected by kid-secrecy + revocations being additive-only via the
-- service layer.
--
-- ----------------------------------------------------------------------
-- 1. tenant_key_material — per-tenant DEK generation history
-- ----------------------------------------------------------------------
--
-- One row per (tenant, generation) pair tracking when the per-tenant DEK
-- was first derived and (if rotated) when superseded. KeyDerivationService
-- (task 2.5) reads the active row to resolve the current DEK; JwtService
-- and the encryption helpers can derive prior generations during the
-- dual-key-accept grace window after a rotation.
--
-- Why not just compute the generation from tenant.jwt_key_generation? Two
-- reasons: (a) DEK rotation is independent of JWT rotation (TOTP / webhook
-- secret keys may rotate without invalidating JWTs), and (b) the
-- created_at / rotated_at audit trail is a compliance requirement (Phase H
-- per-tenant audit log).

CREATE TABLE IF NOT EXISTS tenant_key_material (
    tenant_id   UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    generation  INT  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    rotated_at  TIMESTAMPTZ,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, generation),
    CONSTRAINT tenant_key_material_rotated_after_created
        CHECK (rotated_at IS NULL OR rotated_at >= created_at),
    CONSTRAINT tenant_key_material_active_implies_no_rotated
        CHECK ((active = TRUE AND rotated_at IS NULL)
            OR (active = FALSE AND rotated_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS tenant_key_material_active_per_tenant
    ON tenant_key_material (tenant_id)
    WHERE active = TRUE;

COMMENT ON TABLE tenant_key_material IS
    'Per-tenant DEK generation history. Exactly one active row per tenant; old generations preserved for dual-key-accept decryption during rotation grace.';
COMMENT ON COLUMN tenant_key_material.generation IS
    'Monotonic generation counter per tenant. Starts at 1, bumps on rotation. Combined with tenant_id, identifies a unique HKDF-derived DEK.';
COMMENT ON COLUMN tenant_key_material.active IS
    'TRUE for the current generation per tenant; partial unique index enforces at most one active row. Old generations linger for the grace window then can be GCd.';

-- ----------------------------------------------------------------------
-- 2. kid_to_tenant_key — opaque kid resolution
-- ----------------------------------------------------------------------
--
-- Per design D1, the JWT `kid` claim is an opaque UUID — never embeds
-- tenant_id, generation, or any structural information that could leak
-- tenant identity to a client inspecting headers. This table is the
-- server-side mapping from kid → (tenant_id, generation), populated when
-- a JWT is signed (task 2.8) and read on every validate (task 2.9).
--
-- Why a separate table instead of (tenant_id, generation) → kid as a
-- column on tenant_key_material? The reverse direction is the hot path:
-- validate sees the kid first and needs to resolve it back. A direct
-- PK-keyed lookup avoids the index hop. Plus, multiple kids can map to
-- the same (tenant, generation) — for example, scheduled rotations may
-- pre-create the next-gen kid before activating it.
--
-- Cache: a Caffeine in-memory cache (~100k entries, 1-hour TTL) sits in
-- front of this table for sub-microsecond validate (task 2.11).

CREATE TABLE IF NOT EXISTS kid_to_tenant_key (
    kid          UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    generation   INT  NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT kid_to_tenant_key_fk_material
        FOREIGN KEY (tenant_id, generation)
        REFERENCES tenant_key_material(tenant_id, generation)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS kid_to_tenant_key_by_tenant
    ON kid_to_tenant_key (tenant_id, generation);

-- Per A3 D20 + warroom E2: enforce exactly one kid per (tenant, generation).
-- KidRegistryService.firstEncryptForGeneration uses
-- "INSERT ... ON CONFLICT (tenant_id, generation) DO NOTHING RETURNING kid"
-- to atomically lazy-register; this UNIQUE index is the constraint that makes
-- the conflict-resolution path correct under concurrent first-encrypts.
--
-- Schema amendment from the original A1 V61: in-place edit per
-- feedback_flyway_immutable_after_apply.md exemption window (V61 only ever
-- applied to ephemeral Testcontainers DBs to date, never persistent).
-- DEV ENVIRONMENT NOTE: any developer who already pulled a pre-A3 V61 must
-- run ./dev-start.sh --fresh once to apply the new index.
CREATE UNIQUE INDEX IF NOT EXISTS kid_to_tenant_key_unique_per_generation
    ON kid_to_tenant_key (tenant_id, generation);

COMMENT ON TABLE kid_to_tenant_key IS
    'Server-side opaque-kid → (tenant, generation) registry per design D1. Populated on JWT sign, read on validate. Cached in-memory at the application layer.';
COMMENT ON COLUMN kid_to_tenant_key.kid IS
    'Random UUID emitted in the JWT kid header. Carries no structural information about the tenant or generation it resolves to.';

-- ----------------------------------------------------------------------
-- 3. jwt_revocations — fast-path revoked-kid blocklist
-- ----------------------------------------------------------------------
--
-- When a tenant is suspended (Phase F) or its JWT generation is bumped
-- (task 2.12), all outstanding kids of the prior generation get inserted
-- here with their natural JWT expiry as the row's expires_at. JwtService.
-- validate (task 2.9) checks this table early — a kid present here is
-- rejected without further decryption work.
--
-- Pruning: a daily scheduled task (task 2.4 Java side, lands with
-- KeyDerivationService) deletes rows where expires_at < now(). Without
-- pruning, the table grows linearly with rotation cadence.

CREATE TABLE IF NOT EXISTS jwt_revocations (
    kid         UUID PRIMARY KEY,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS jwt_revocations_by_expires_at
    ON jwt_revocations (expires_at);

COMMENT ON TABLE jwt_revocations IS
    'Fast-path blocklist for revoked JWT kids (suspend, generation bump). Checked early in validate. Pruned daily where expires_at < now().';
COMMENT ON COLUMN jwt_revocations.expires_at IS
    'Mirrors the natural exp claim of the revoked JWT. After this timestamp the row is safe to GC because the JWT itself is already expired.';
