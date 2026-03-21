-- RLS for surge_event — tenant-scoped access control.
-- Unlike shelter child tables, surge_event uses direct tenant_id policy
-- (surge events are not shelter-specific, they're tenant-wide).

ALTER TABLE surge_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE surge_event FORCE ROW LEVEL SECURITY;

CREATE POLICY surge_event_tenant_access ON surge_event
    FOR ALL
    USING (true);
