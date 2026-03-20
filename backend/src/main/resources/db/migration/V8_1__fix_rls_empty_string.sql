-- Fix RLS policy to handle empty string when app.dv_access is not set.
-- current_setting('app.dv_access', true) returns '' when missing, and ''::boolean fails.
-- Use NULLIF to convert '' to NULL, then COALESCE to default to false.

DROP POLICY IF EXISTS dv_shelter_access ON shelter;

CREATE POLICY dv_shelter_access ON shelter
    FOR ALL
    USING (
        dv_shelter = false
        OR COALESCE(NULLIF(current_setting('app.dv_access', true), '')::boolean, false) = true
    );
