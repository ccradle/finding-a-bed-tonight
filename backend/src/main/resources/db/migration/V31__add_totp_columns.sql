-- TOTP two-factor authentication columns on app_user.
-- totp_secret_encrypted: AES-256-GCM ciphertext (base64). NEVER stored plaintext.
-- totp_enabled: whether 2FA is active for this user.
-- recovery_codes: JSON array of bcrypt-hashed single-use backup codes.

ALTER TABLE app_user ADD COLUMN totp_secret_encrypted VARCHAR(255);
ALTER TABLE app_user ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN recovery_codes TEXT;

-- Grant to fabt_app role (RLS-restricted application role)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fabt_app;
