-- V97: dv-policy-tenant-flag — backfill tenant.config.dv_policy_enabled
--
-- Sets tenant.config.dv_policy_enabled = true on every tenant that already
-- has at least one shelter with dv_shelter = true at migration time. The
-- helper Tenant.isDvPolicyEnabled() defaults to false on absent key, so
-- tenants with zero DV shelters get no write and remain at the default
-- "false" reading.
--
-- Performance: SELECT DISTINCT tenant_id FROM shelter WHERE dv_shelter = true
-- uses the existing index on shelter(tenant_id) (V52 + V60); the EXISTS
-- subquery is fast even at 10K-shelter scale (Sam's review, warroom 2026-05-02).
--
-- Idempotency: jsonb_set(..., true) is safe to re-run — overwrites if the
-- key is already set to anything else, no-op if already true. The WHERE
-- clause already filters to "tenants that already have DV shelters", so
-- the migration produces the same end state under repeat application.
--
-- Sequencing: belongs to the dv-policy-tenant-flag OpenSpec change. Lands
-- before info-email-contact Slice B can resume (DvPolicyController + the
-- ShelterService invariant guard depend on this backfill having run so
-- existing DV-shelter rows do not get retroactively rejected).
UPDATE tenant
SET config = jsonb_set(
        COALESCE(config, '{}'::jsonb),
        '{dv_policy_enabled}',
        'true'::jsonb,
        true
    )
WHERE id IN (
    SELECT DISTINCT tenant_id
    FROM shelter
    WHERE dv_shelter = true
);
