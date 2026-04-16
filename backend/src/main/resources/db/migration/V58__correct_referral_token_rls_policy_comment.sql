-- Phase 4.1 of cross-tenant-isolation-audit (design D5).
--
-- Corrects a misleading comment on the referral_token RLS policy
-- (originally added in V21). The prior comment implied the policy
-- enforces tenant isolation; it does not — it enforces dv_access
-- inheritance through the shelter FK join. Tenant isolation is
-- enforced at the service layer via findByIdAndTenantId (design D1).
--
-- See openspec/changes/cross-tenant-isolation-audit/design.md §D5
-- and docs/security/rls-coverage.md for the full coverage map.

COMMENT ON POLICY dv_referral_token_access ON referral_token IS
    'Enforces dv_access inheritance through the shelter FK join — '
    'DOES NOT enforce tenant isolation. Tenant isolation is enforced '
    'at the service layer via findByIdAndTenantId. See '
    'openspec/changes/cross-tenant-isolation-audit for rationale. '
    'This corrects a misleading comment in V21.';
