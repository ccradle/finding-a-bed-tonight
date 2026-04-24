-- V84 — multi-tenant-production-readiness Phase F task 7.8e
-- design-f6-real-cryptoshred §6 + Appendix A (child-table CASCADE audit)
--
-- Flips 18 per-tenant child-table FKs from the implicit NO ACTION rule to
-- ON DELETE CASCADE. With V82's CASCADE already in place for tenant_dek
-- plus V61's CASCADE for tenant_key_material + kid_to_tenant_key plus
-- V80's CASCADE for tenant_audit_chain_head, post-V84 every per-tenant
-- child row gets destroyed by a single `DELETE FROM tenant WHERE id = ?`.
--
-- That single DELETE is what `TenantLifecycleService.hardDelete` will
-- fire (task 7.8f). The CASCADE chain is what makes the §D11 crypto-
-- shred claim verifiable — nothing per-tenant survives the parent row
-- removal, which is also what the TDD anchor
-- `CryptoShredGapIntegrationTest` will flip from @Disabled+red to
-- @Enabled+green after task 7.8g.
--
-- ─── Why a DO-block instead of static ALTER TABLE per constraint ───
--
-- Warroom 2026-04-24 pass-2 Sam blocker: the original draft used
-- `ALTER TABLE ... DROP CONSTRAINT <table>_<col>_fkey` — a hard-coded
-- naming assumption that Postgres only honors for inline-FK creations
-- in V2–V4. A future rename of any of these constraints would silently
-- DROP the wrong thing. The DO-block looks up the actual `conname` from
-- pg_constraint at migration time and fails loud (RAISE EXCEPTION) if
-- zero or more-than-one match — catches name drift before it corrupts
-- a migration.
--
-- ─── Why NOT VALID + VALIDATE CONSTRAINT ───
--
-- Sam pass-2 blocker #1: `ALTER TABLE ... ADD CONSTRAINT FOREIGN KEY`
-- WITHOUT `NOT VALID` scans the child table to validate every existing
-- row satisfies the FK, holding ACCESS EXCLUSIVE on the child table for
-- the duration. On the pilot (≤hundreds of rows per table) that's sub-
-- second; at real scale it blocks writes. `NOT VALID` makes the ADD
-- catalog-only + instant; a separate `VALIDATE CONSTRAINT` pass then
-- walks existing rows under SHARE UPDATE EXCLUSIVE (concurrent writes
-- unblocked). All existing rows DO satisfy the FK at this point, so
-- VALIDATE is a no-op-verify; the shape is future-scale-correct.
--
-- ─── Timeouts (V74 C-A5-N1 pattern) ───
--
-- SET LOCAL lock_timeout + statement_timeout prevents a stray background
-- VACUUM or long-running read from pinning the migration indefinitely.
-- 10s for lock acquisition, 60s per statement — generous at pilot scale,
-- but bounded so a pathological test container doesn't hang CI forever.
--
-- ─── What's NOT flipped ───
--
-- audit_events (V57 added nullable tenant_id without a FK, deliberately,
-- per Q-F6-5 Riley pass-1: preserves the shred-audit tombstone pattern —
-- on hardDelete, the TENANT_HARD_DELETED row with NULL tenant_id outlives
-- the tenant so compliance auditors can see "tenant X was deleted on Y
-- by Z"). audit_events is on the MUST_NOT_FK_TO_TENANT allowlist in
-- TenantChildCascadeAuditTest.
--
-- ─── Rollback path ───
--
-- No automated rollback. If a future deploy needs to revert CASCADE to
-- RESTRICT (e.g., a bug surfaces in hardDelete that makes CASCADE
-- dangerous), the operator writes a V85 migration that runs the inverse
-- ALTERs. The runbook captures this per warroom pass-2 Riley
-- recommendation (task 7.8i).

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

DO $$
DECLARE
    -- Allowlist of (owning_table, intended_delete_rule=CASCADE).
    -- Must stay in sync with
    -- TenantChildCascadeAuditTest.MUST_CASCADE_FROM_TENANT — new per-tenant
    -- child tables added post-V84 need BOTH an entry here AND in the CI
    -- check's constant. Pair-enforcement catches drift at build time.
    target_tables text[] := ARRAY[
        'app_user', 'api_key', 'shelter', 'import_log',
        'tenant_oauth2_provider', 'subscription', 'bed_availability',
        'reservation', 'surge_event', 'referral_token',
        'hmis_outbox', 'hmis_audit_log', 'bed_search_log',
        'daily_utilization_summary', 'one_time_access_code',
        'notification', 'password_reset_token', 'escalation_policy'
    ];
    tbl text;
    cname text;
    match_count int;
BEGIN
    FOREACH tbl IN ARRAY target_tables LOOP
        -- Look up the FK's actual constraint name. tenant is confrelid,
        -- tbl is conrelid. Zero or multiple matches = schema drift = fail.
        SELECT COUNT(*), MIN(conname) INTO match_count, cname
        FROM pg_catalog.pg_constraint
        WHERE contype = 'f'
          AND conrelid = format('public.%I', tbl)::regclass
          AND confrelid = 'public.tenant'::regclass;

        IF match_count = 0 THEN
            RAISE EXCEPTION 'V84: no FK from %.tenant_id to tenant(id) found on table %', tbl, tbl;
        ELSIF match_count > 1 THEN
            RAISE EXCEPTION 'V84: multiple FKs from % to tenant(id) — ambiguous drop (count=%)', tbl, match_count;
        END IF;

        -- 1. DROP the existing FK. Catalog-only; fast; ACCESS EXCLUSIVE
        --    for the metadata write, released on statement completion.
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I', tbl, cname);

        -- 2. ADD NOT VALID. Catalog-only (no row scan). New writes are
        --    immediately blocked against the CASCADE rule; existing rows
        --    aren't re-checked here — VALIDATE does that in step 3.
        EXECUTE format(
            'ALTER TABLE public.%I '
            'ADD CONSTRAINT %I FOREIGN KEY (tenant_id) '
            'REFERENCES public.tenant(id) ON DELETE CASCADE NOT VALID',
            tbl, cname);

        -- 3. VALIDATE. Scans existing rows under SHARE UPDATE EXCLUSIVE —
        --    does NOT block concurrent reads/writes. All existing rows
        --    already satisfy the FK (they passed the old NO ACTION check),
        --    so this is a no-op-verify; runs full table scan anyway to
        --    satisfy Postgres's catalog invariant that VALIDATE has run
        --    before the constraint is marked CONVALIDATED.
        EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I', tbl, cname);
    END LOOP;
END;
$$;

COMMENT ON COLUMN tenant.id IS
    'Parent of CASCADE FKs (V84 + V61 + V80 + V82). hardDelete(tenant_id) chain-removes every per-tenant child row in a single DELETE. audit_events preserves its tenant_id without FK per shred-auditability contract (Q-F6-5).';
