-- Create a restricted application role for runtime queries.
-- This role is NOSUPERUSER so PostgreSQL RLS policies are enforced.
-- The fabt (owner) role continues to run Flyway DDL migrations.
--
-- Why: PostgreSQL superusers and table owners bypass RLS entirely.
-- The application must connect as a non-owner role for DV shelter
-- protection to work. See DvAccessRlsTest.java for the proof.

DO $$
BEGIN
    -- Create role if it doesn't exist (idempotent)
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt_app') THEN
        CREATE ROLE fabt_app WITH LOGIN PASSWORD 'fabt_app' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- Grant DML permissions on all existing tables
GRANT USAGE ON SCHEMA public TO fabt_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fabt_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fabt_app;

-- Ensure future tables also get permissions (for subsequent migrations)
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fabt_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO fabt_app;
