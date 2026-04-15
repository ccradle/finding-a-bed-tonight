-- V57 — cross-tenant-isolation-audit Phase 2.12
--
-- Adds tenant_id to audit_events to close the LIVE VULN-HIGH cross-tenant read leak
-- on AuditEventController.getAuditEvents. Pre-fix, a CoC admin in Tenant A could query
-- GET /api/v1/audit-events?targetUserId=<tenantB-user-uuid> and receive Tenant B's audit
-- history — VAWA per-tenant audit-integrity violation (Casey Drummond concern).
--
-- Post-fix, the service filters by tenant_id pulled from TenantContext, so an admin
-- can only query audit events for users in their own tenant.
--
-- Backfill strategy:
--   1. target_user_id → app_user.tenant_id (most rows — user-scoped audit actions)
--   2. actor_user_id → app_user.tenant_id (fallback — actor-only rows)
--   3. remaining NULL rows are orphans (shouldn't exist; if they do, operator review)
--
-- Column starts nullable to allow backfill. Application layer enforces non-null for
-- new rows via AuditEventService.onAuditEvent sourcing from TenantContext.
-- A future migration can enforce NOT NULL once operator verifies zero-null state.

ALTER TABLE audit_events
  ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Backfill pass 1: rows with a target user → use target's tenant
UPDATE audit_events ae
SET tenant_id = u.tenant_id
FROM app_user u
WHERE ae.target_user_id = u.id
  AND ae.tenant_id IS NULL;

-- Backfill pass 2: rows with no target but with an actor → use actor's tenant
UPDATE audit_events ae
SET tenant_id = u.tenant_id
FROM app_user u
WHERE ae.actor_user_id = u.id
  AND ae.tenant_id IS NULL;

-- Index for the new tenant-scoped query pattern
CREATE INDEX IF NOT EXISTS idx_audit_events_tenant_target
  ON audit_events (tenant_id, target_user_id, timestamp DESC);

-- Comment documenting the tenant-isolation contract
COMMENT ON COLUMN audit_events.tenant_id IS
  'Tenant isolation (cross-tenant-isolation-audit Phase 2.12). '
  'Set by AuditEventService.onAuditEvent from TenantContext on INSERT. '
  'Queries filter on tenant_id via AuditEventRepository.findByTargetUserIdAndTenantId.';
