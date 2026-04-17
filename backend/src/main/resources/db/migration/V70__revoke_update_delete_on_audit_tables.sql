-- V70 — Phase B (task 3.9): revoke UPDATE + DELETE on audit tables
-- from the application role fabt_app. Append-only audit posture:
-- forensic records cannot be mutated or deleted by a compromised
-- fabt_app credential.
--
-- Per design-b-rls-hardening §G2 + Marcus warroom: RLS alone doesn't
-- prevent a compromised fabt_app from DELETEing audit_events rows
-- for its own tenant — the RESTRICTIVE policy allows same-tenant
-- DELETE, which is the threat. REVOKE at the privilege layer
-- closes that.
--
-- Retention cleanup (AccessCodeCleanupScheduler-class paths) doesn't
-- touch audit_events or hmis_audit_log — those are append-only; older
-- rows are managed by Phase E partition drops (V71-to-be), not DELETE.
--
-- platform_admin_access_log is NOT revoked here — the table doesn't
-- exist yet (arrives in Phase E V65). When V65 lands, its own migration
-- appends the same REVOKE.
--
-- Migration numbering note: proposal.md reserves V70 for CREATE INDEX
-- CONCURRENTLY and V72 for this REVOKE. Shipping REVOKE at V70 instead
-- because the index migration is more invasive and deserves to land
-- last of the FORCE-RLS stack. Design-b-rls-hardening §4 re-ordered
-- V67→function, V68→policies, V69→FORCE. This continues that re-order:
-- V70→REVOKE, next migration→CREATE INDEX CONCURRENTLY.

REVOKE UPDATE, DELETE ON audit_events FROM fabt_app;
REVOKE UPDATE, DELETE ON hmis_audit_log FROM fabt_app;
