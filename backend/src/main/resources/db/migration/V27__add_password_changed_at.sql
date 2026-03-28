-- Add password_changed_at to track last password change for JWT invalidation.
-- NULL means the user has never changed their password (original hash still valid).
ALTER TABLE app_user ADD COLUMN password_changed_at TIMESTAMPTZ;
