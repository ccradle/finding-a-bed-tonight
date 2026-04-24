-- V82 — multi-tenant-production-readiness Phase F task 7.8a
-- multi-tenant-production-readiness / design-f6-real-cryptoshred §4
--
-- Creates the `tenant_dek` table that holds per-tenant, per-purpose random
-- DEKs wrapped under a master-KEK-derived wrapping key via AES-KWP
-- (RFC 5649 / NIST SP 800-38F §6.3). This table is the SHRED SURFACE:
-- destroying rows here destroys the only copy of the DEK, which is what
-- makes NIST SP 800-88 Rev 2 §2.5 "Cryptographic Erase" actually true.
--
-- The TDD anchor test CryptoShredGapIntegrationTest (feature branch
-- commit b5672da) proves that the legacy §D11 design — deleting
-- tenant_key_material + kid_to_tenant_key — destroyed nothing an
-- adversary needed. DEKs were pure HKDF of (master_KEK, tenantId,
-- purpose); master_KEK + tenantId recovered plaintext post-shred.
-- V82 + the TenantDekService refactor (task 7.8b/c) close that gap.
--
-- Forward-only migration. CREATE TABLE IF NOT EXISTS for idempotency
-- under partial-apply recovery. V83 populates this table; V84 flips
-- the 18 peer child-table FKs to CASCADE so hardDelete's single
-- DELETE FROM tenant takes everything with it.
--
-- WARROOM 2026-04-24 pass-2 addressed

-- ----------------------------------------------------------------------
-- 1. tenant_dek — wrapped per-tenant, per-purpose random DEKs
-- ----------------------------------------------------------------------
--
-- One row per (tenant, purpose, generation). `wrapped_dek` holds the
-- AES-KWP-wrapped 32-byte AES-256 DEK (40 bytes output per RFC 5649).
-- TenantDekService.getOrCreateActiveDek inserts on first encrypt for a
-- purpose; AES-KWP-Unwraps on resolveDek for decrypt.
--
-- kid is the envelope's opaque identifier — same format as
-- kid_to_tenant_key's kid column (random UUID), but RESOLVES to a
-- DIFFERENT table. Data encryption kids live here; JWT signing kids
-- live in kid_to_tenant_key. The two pools are disjoint and won't
-- collide (UUID random).

CREATE TABLE IF NOT EXISTS tenant_dek (
    kid          UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    purpose      VARCHAR(32) NOT NULL,
    generation   INT NOT NULL DEFAULT 1,
    wrapped_dek  BYTEA NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    rotated_at   TIMESTAMPTZ,

    -- Closed set of purposes matching org.fabt.shared.security.KeyPurpose.
    -- Adding a new purpose requires updating BOTH the enum and this check.
    CONSTRAINT tenant_dek_purpose_check
        CHECK (purpose IN ('TOTP', 'WEBHOOK_SECRET', 'OAUTH2_CLIENT_SECRET', 'HMIS_API_KEY')),

    -- Temporal consistency — same shape as tenant_key_material constraints
    -- in V61. rotated_at is stamped on generation flip; a row is either
    -- active (rotated_at NULL) or inactive (rotated_at NOT NULL).
    CONSTRAINT tenant_dek_rotated_after_created
        CHECK (rotated_at IS NULL OR rotated_at >= created_at),
    CONSTRAINT tenant_dek_active_implies_no_rotated
        CHECK ((active = TRUE AND rotated_at IS NULL)
            OR (active = FALSE AND rotated_at IS NOT NULL)),

    -- Key-shred FK: the SINGLE most load-bearing line in this migration.
    -- Without ON DELETE CASCADE, hardDelete(tenant) would raise an FK
    -- violation and the wrapped DEKs would survive the "shred" — making
    -- the entire Option A refactor a no-op. V84 flips the 18 peer FKs
    -- to CASCADE so the whole chain unwinds from a single DELETE.
    CONSTRAINT tenant_dek_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

-- Each (tenant, purpose, generation) yields exactly one wrapped DEK.
CREATE UNIQUE INDEX IF NOT EXISTS tenant_dek_tenant_purpose_gen_uq
    ON tenant_dek (tenant_id, purpose, generation);

-- At most one ACTIVE generation per (tenant, purpose). Rotation bumps
-- generation + flips old row's active to FALSE atomically in one tx.
CREATE UNIQUE INDEX IF NOT EXISTS tenant_dek_active_per_tenant_purpose_uq
    ON tenant_dek (tenant_id, purpose) WHERE active = TRUE;

-- Index for the decrypt-path kid lookup (kid is already PK, so this is
-- implicit) and for tenant-scoped sweeps (cache invalidation, audits).
CREATE INDEX IF NOT EXISTS tenant_dek_by_tenant
    ON tenant_dek (tenant_id);

COMMENT ON TABLE tenant_dek IS
    'Per-tenant, per-purpose random 256-bit DEKs wrapped via AES-KWP (RFC 5649) under a master-KEK-derived wrapping key. Shred surface per design-f6-real-cryptoshred §3: ON DELETE CASCADE from tenant means hardDelete destroys every wrapped DEK for the tenant in a single DB statement, rendering any surviving ciphertext permanently unrecoverable.';

COMMENT ON COLUMN tenant_dek.kid IS
    'Opaque random UUID emitted in the v1 EncryptionEnvelope kid field. Disjoint pool from kid_to_tenant_key (which holds JWT-signing kids).';

COMMENT ON COLUMN tenant_dek.wrapped_dek IS
    'AES-KWP output (40 bytes for a 32-byte DEK). Unwrap requires the master-KEK-derived wrapping key from KeyDerivationService.deriveKekWrappingKey(tenant_id). Without this row the DEK cannot be reconstructed — that is the whole point.';

COMMENT ON COLUMN tenant_dek.purpose IS
    'Canonical purpose string matching org.fabt.shared.security.KeyPurpose. Purpose binding prevents a TOTP ciphertext from decrypting under the webhook-secret DEK even if both DEKs exist for the same tenant.';

COMMENT ON COLUMN tenant_dek.active IS
    'TRUE for the current generation per (tenant, purpose); partial unique index enforces at most one active row. Old generations linger during the rotation grace window so pre-rotation ciphertexts still decrypt.';

-- ----------------------------------------------------------------------
-- 2. Row-Level Security — PERMISSIVE + RESTRICTIVE pair
-- ----------------------------------------------------------------------
--
-- Mirrors V68 tenant_key_material + kid_to_tenant_key pattern (V68 lines
-- 112-161). Warroom 2026-04-24 pass-1 (Sam) explicitly required the full
-- pair — PERMISSIVE writes ALONE would silently bypass tenant scoping
-- because RESTRICTIVE policies can only NARROW a PERMISSIVE set, not
-- authorize on their own.
--
-- Read path: TenantDekService.resolveDek runs BEFORE TenantContext is
-- bound (decrypt happens in JwtAuthenticationFilter before the filter
-- chain establishes the tenant). PERMISSIVE SELECT is defensible —
-- tenant_dek.kid is an opaque random UUID; enumeration yields zero
-- tenant-identifying information.
--
-- Write paths: INSERT/UPDATE/DELETE all tenant-scoped via RESTRICTIVE
-- policies checking tenant_id = fabt_current_tenant_id(). The one
-- exception is DELETE via FK CASCADE from hardDelete — see §3 trigger
-- below, which uses the fabt.shred_in_progress GUC to authorize the
-- cascade path without needing to set app.tenant_id.

ALTER TABLE tenant_dek ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_dek FORCE ROW LEVEL SECURITY;

-- PERMISSIVE (read-all + write baseline)
CREATE POLICY tenant_dek_select_all ON tenant_dek
    FOR SELECT USING (true);
CREATE POLICY tenant_dek_insert_permissive ON tenant_dek
    FOR INSERT WITH CHECK (true);
CREATE POLICY tenant_dek_update_permissive ON tenant_dek
    FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY tenant_dek_delete_permissive ON tenant_dek
    FOR DELETE USING (true);

-- RESTRICTIVE (the actual tenant-scoping; ANDs with PERMISSIVE).
CREATE POLICY tenant_dek_insert_restrictive ON tenant_dek
    AS RESTRICTIVE
    FOR INSERT WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY tenant_dek_update_restrictive ON tenant_dek
    AS RESTRICTIVE
    FOR UPDATE
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY tenant_dek_delete_restrictive ON tenant_dek
    AS RESTRICTIVE
    FOR DELETE USING (tenant_id = fabt_current_tenant_id());

-- ----------------------------------------------------------------------
-- 3. BEFORE DELETE trigger — shred-path guard (Q-F6-6, warroom pass-2)
-- ----------------------------------------------------------------------
--
-- The RESTRICTIVE DELETE policy above only authorizes DELETEs from a
-- session with app.tenant_id bound to the target tenant. That covers
-- normal app-layer operation, but NOT the FK CASCADE path fired by
-- hardDelete: the cascade runs under the caller's session, and the
-- caller is NOT binding app.tenant_id to the dying tenant (it's
-- binding a different GUC, fabt.shred_in_progress, per the warroom
-- trigger mechanism resolution).
--
-- This trigger closes the gap: any DELETE on tenant_dek — whether
-- app-layer via the RLS policy or cascade via the FK — must have
-- fabt.shred_in_progress set to the row's tenant_id. Otherwise raise.
--
-- Defense-in-depth layered over ArchUnit Family F rule 7.8j, which
-- forbids any non-hardDelete caller from writing this GUC. ArchUnit
-- catches the app-layer bypass at build time; the trigger catches
-- the DB-console / ad-hoc DELETE at runtime.
--
-- Also catches a cross-tenant-GUC-poisoning attack where an attacker
-- sets fabt.shred_in_progress to tenant A but tries to DELETE tenant
-- B's rows — the equality check fails on OLD.tenant_id, trigger raises.
--
-- SECURITY_DEFINER is intentional: the trigger must read the session
-- GUC regardless of the invoking role's search_path. Trigger body is
-- 3 lines of pure SQL — no expansion surface.

CREATE OR REPLACE FUNCTION tenant_dek_shred_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    -- current_setting with the second-arg missing_ok=true returns NULL
    -- when the GUC was never set in this session (the common case).
    -- IS DISTINCT FROM treats NULL as "not equal," so the trigger raises
    -- for both "GUC unset" and "GUC set to wrong tenant" with the same
    -- generic message — an attacker probing the trigger can't
    -- distinguish "no GUC" from "wrong GUC."
    IF current_setting('fabt.shred_in_progress', true) IS DISTINCT FROM OLD.tenant_id::text THEN
        RAISE EXCEPTION 'tenant_dek row deletion attempted outside hardDelete shred path'
            USING ERRCODE = 'P0001',
                  HINT = 'Set fabt.shred_in_progress = <tenant_id> via hardDelete before DELETE.';
    END IF;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION tenant_dek_shred_guard() IS
    'BEFORE DELETE trigger on tenant_dek. Raises unless fabt.shred_in_progress = OLD.tenant_id::text. Paired with ArchUnit rule 7.8j (only TenantLifecycleService.hardDelete may set this GUC) for belt-and-braces enforcement of the shred path. See design-f6-real-cryptoshred §3 and Q-F6-6 resolution.';

CREATE TRIGGER tenant_dek_shred_guard_trigger
    BEFORE DELETE ON tenant_dek
    FOR EACH ROW
    EXECUTE FUNCTION tenant_dek_shred_guard();

-- ----------------------------------------------------------------------
-- 4. Grants — same pattern as tenant_key_material (V61 was pre-grants
--    model; V68 era added the fabt_app role posture). tenant_dek is
--    read/written by the app service; only the shred path needs DELETE.
-- ----------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_dek TO fabt_app;
