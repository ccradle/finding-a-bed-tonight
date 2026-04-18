-- V69 — Phase B (task 3.5): FORCE ROW LEVEL SECURITY on the 8 regulated
-- tables from V68. Per Elena's non-negotiable: RLS without FORCE is theater
-- — owner sessions (including the fabt migration role) bypass regular RLS
-- silently. FORCE applies policies to owners too.
--
-- Per design-b-rls-hardening §D54: an integration test asserts that fabt
-- owner CANNOT update another tenant's audit_events row post-V69.

ALTER TABLE audit_events         FORCE ROW LEVEL SECURITY;
ALTER TABLE hmis_audit_log       FORCE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token FORCE ROW LEVEL SECURITY;
ALTER TABLE one_time_access_code FORCE ROW LEVEL SECURITY;
ALTER TABLE hmis_outbox          FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_key_material  FORCE ROW LEVEL SECURITY;
ALTER TABLE kid_to_tenant_key    FORCE ROW LEVEL SECURITY;
