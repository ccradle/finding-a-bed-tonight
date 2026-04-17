-- V68 — Phase B (task 3.4): D14 tenant-RLS policies on 7 regulated tables.
--
-- Per design-b-rls-hardening §D44 + §D45:
--
-- Three fully-scoped tables (audit_events, hmis_audit_log, hmis_outbox) use
-- the canonical FOR ALL USING...WITH CHECK shape with identical expressions.
--
-- Four pre-authentication tables (password_reset_token, one_time_access_code,
-- tenant_key_material, kid_to_tenant_key) require a PERMISSIVE-SELECT +
-- RESTRICTIVE-WRITE pattern because they are queried BEFORE TenantContext
-- is bound:
--
--   - kid_to_tenant_key / tenant_key_material: JWT validate resolves kid →
--     tenant_id before TenantContextFilter runs (design D45)
--   - password_reset_token: user clicks email link → backend looks up token
--     before any session exists
--   - one_time_access_code: same flow for admin-created user onboarding
--
-- Read-permissive is safe because each of these tables is looked up by an
-- opaque high-entropy value (random kid UUID, SHA-256 hashed token/code)
-- — cross-tenant probing is zero-information. Writes are always
-- tenant-scoped.
--
-- (The proposal originally listed totp_recovery as a regulated table;
-- it turned out to be a JSONB column on app_user, not a standalone
-- table — scope adjusted accordingly.)
--
-- Two kid tables (tenant_key_material, kid_to_tenant_key) require a
-- PERMISSIVE-per-command + RESTRICTIVE-write pattern because JWT validate
-- queries them BEFORE TenantContext is bound (chicken-and-egg). Read is
-- permissive (opaque 128-bit random kid UUIDs make enumeration attacks
-- zero-information); writes are restricted to the tenant matching the
-- current session.
--
-- RLS enforcement (FORCE ROW LEVEL SECURITY) is applied separately in V69.
-- V68 creates policies; V69 makes them mandatory for owner sessions.

-- ============================================================================
-- Section 1 — Non-kid regulated tables (canonical policy shape)
-- ============================================================================

-- audit_events (tenant_id added by V57)
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audit_events ON audit_events
    FOR ALL
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());

-- hmis_audit_log
ALTER TABLE hmis_audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_hmis_audit_log ON hmis_audit_log
    FOR ALL
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());

-- password_reset_token — pre-auth lookup by hashed token; PERMISSIVE SELECT + RESTRICTIVE WRITE
ALTER TABLE password_reset_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY prt_select_all ON password_reset_token
    FOR SELECT USING (true);
CREATE POLICY prt_insert_permissive ON password_reset_token
    FOR INSERT WITH CHECK (true);
CREATE POLICY prt_update_permissive ON password_reset_token
    FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY prt_delete_permissive ON password_reset_token
    FOR DELETE USING (true);
CREATE POLICY prt_insert_restrictive ON password_reset_token
    AS RESTRICTIVE
    FOR INSERT WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY prt_update_restrictive ON password_reset_token
    AS RESTRICTIVE
    FOR UPDATE
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY prt_delete_restrictive ON password_reset_token
    AS RESTRICTIVE
    FOR DELETE USING (tenant_id = fabt_current_tenant_id());

-- one_time_access_code — pre-auth lookup by hashed code; PERMISSIVE SELECT + RESTRICTIVE WRITE
ALTER TABLE one_time_access_code ENABLE ROW LEVEL SECURITY;
CREATE POLICY otac_select_all ON one_time_access_code
    FOR SELECT USING (true);
CREATE POLICY otac_insert_permissive ON one_time_access_code
    FOR INSERT WITH CHECK (true);
CREATE POLICY otac_update_permissive ON one_time_access_code
    FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY otac_delete_permissive ON one_time_access_code
    FOR DELETE USING (true);
CREATE POLICY otac_insert_restrictive ON one_time_access_code
    AS RESTRICTIVE
    FOR INSERT WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY otac_update_restrictive ON one_time_access_code
    AS RESTRICTIVE
    FOR UPDATE
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY otac_delete_restrictive ON one_time_access_code
    AS RESTRICTIVE
    FOR DELETE USING (tenant_id = fabt_current_tenant_id());

-- hmis_outbox
ALTER TABLE hmis_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_hmis_outbox ON hmis_outbox
    FOR ALL
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());

-- ============================================================================
-- Section 2 — Kid tables (chicken-and-egg: PERMISSIVE SELECT + split writes)
-- ============================================================================

-- tenant_key_material
ALTER TABLE tenant_key_material ENABLE ROW LEVEL SECURITY;
-- PERMISSIVE read: JWT validate reads this BEFORE TenantContext is bound.
-- Safe because the composite (tenant_id, generation) doesn't leak enumerable
-- tenant data — it's looked up by a kid that is itself opaque.
CREATE POLICY kid_material_select_all ON tenant_key_material
    FOR SELECT USING (true);
-- PERMISSIVE write companions — required for Postgres to even consider the
-- RESTRICTIVE policy (restrictive alone cannot authorize; it can only narrow
-- the permissive set to zero).
CREATE POLICY kid_material_insert_permissive ON tenant_key_material
    FOR INSERT WITH CHECK (true);
CREATE POLICY kid_material_update_permissive ON tenant_key_material
    FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY kid_material_delete_permissive ON tenant_key_material
    FOR DELETE USING (true);
-- RESTRICTIVE write: the actual tenant-scoping. ANDed with the permissive
-- above → only writes where tenant_id matches current session survive.
CREATE POLICY kid_material_insert_restrictive ON tenant_key_material
    AS RESTRICTIVE
    FOR INSERT WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY kid_material_update_restrictive ON tenant_key_material
    AS RESTRICTIVE
    FOR UPDATE
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY kid_material_delete_restrictive ON tenant_key_material
    AS RESTRICTIVE
    FOR DELETE USING (tenant_id = fabt_current_tenant_id());

-- kid_to_tenant_key — identical pattern
ALTER TABLE kid_to_tenant_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY kid_select_all ON kid_to_tenant_key
    FOR SELECT USING (true);
CREATE POLICY kid_insert_permissive ON kid_to_tenant_key
    FOR INSERT WITH CHECK (true);
CREATE POLICY kid_update_permissive ON kid_to_tenant_key
    FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY kid_delete_permissive ON kid_to_tenant_key
    FOR DELETE USING (true);
CREATE POLICY kid_insert_restrictive ON kid_to_tenant_key
    AS RESTRICTIVE
    FOR INSERT WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY kid_update_restrictive ON kid_to_tenant_key
    AS RESTRICTIVE
    FOR UPDATE
    USING (tenant_id = fabt_current_tenant_id())
    WITH CHECK (tenant_id = fabt_current_tenant_id());
CREATE POLICY kid_delete_restrictive ON kid_to_tenant_key
    AS RESTRICTIVE
    FOR DELETE USING (tenant_id = fabt_current_tenant_id());
