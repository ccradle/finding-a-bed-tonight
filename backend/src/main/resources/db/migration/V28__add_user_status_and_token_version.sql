-- V28: Add status and token_version to app_user for user deactivation and JWT invalidation.
-- status: ACTIVE (default) or DEACTIVATED. Deactivated users cannot log in.
-- token_version: incremented on role change, dvAccess change, or deactivation.
--   JwtAuthenticationFilter compares JWT 'ver' claim against this value.

ALTER TABLE app_user ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- Grant to fabt_app role (RLS-restricted application role)
GRANT SELECT, INSERT, UPDATE, DELETE ON app_user TO fabt_app;
