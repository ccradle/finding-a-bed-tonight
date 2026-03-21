-- Docker PostgreSQL init script — runs before Flyway migrations.
-- Creates the fabt_app restricted role that the application connects as.
-- This script runs as the postgres superuser during container initialization.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt_app') THEN
        CREATE ROLE fabt_app WITH LOGIN PASSWORD 'fabt_app' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- Grant connect to the fabt database
GRANT CONNECT ON DATABASE fabt TO fabt_app;
