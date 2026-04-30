-- V93 — transitional-reentry-support task 2.3 (Option A per issue #152)
-- (See openspec/changes/transitional-reentry-support/design.md D4)
--
-- Two coupled steps in one Flyway-SQL migration:
--
--   (a) Extend `tenant_dek.purpose` CHECK constraint to permit the new
--       RESERVATION_PII purpose. V82 currently restricts to:
--           'TOTP', 'WEBHOOK_SECRET', 'OAUTH2_CLIENT_SECRET', 'HMIS_API_KEY'
--       We DROP the existing constraint and re-ADD with the wider set.
--       This is the same DROP/ADD pattern used elsewhere — NOT a plpgsql
--       function rewrite — to keep the migration purely declarative.
--
--   (b) Add three encrypted-PII columns to `reservation` for third-party
--       navigator hold attribution: `held_for_client_name_encrypted`,
--       `held_for_client_dob_encrypted`, `hold_notes_encrypted`. All
--       nullable. All TEXT (storing the base64 v1 EncryptionEnvelope
--       produced by SecretEncryptionService.encryptForTenant(tenantId,
--       KeyPurpose.RESERVATION_PII, plaintext)). The plaintext is never
--       persisted to disk.
--
-- Why coupled in one migration: the column adds depend on the constraint
-- update — if (b) shipped first, the very first attempt to encrypt with
-- KeyPurpose.RESERVATION_PII would fail to allocate a tenant_dek row
-- (CHECK violation). Coupling guarantees the schema is internally
-- consistent at any post-V93 HWM.
--
-- Defense in depth (design D4 two-layer posture):
--   1. At-rest ciphertext via tenant_dek — pg_dump exports unreadable
--      bytes; survives backup-retention windows; inherits crypto-shred
--      via tenant CASCADE on hardDelete.
--   2. 24h post-resolution Spring Batch purge — task 4.6 nulls these
--      ciphertext columns 24h after the reservation expires/confirms/
--      cancels. The job nulls bytes, not plaintext (NULL is NULL
--      regardless of what was encrypted).
--
-- Forward-only. Nullable columns + permissive constraint update = the
-- v0.54 backend (which never writes these columns) continues to work
-- against the post-V93 schema.

-- ----------------------------------------------------------------------
-- 1. tenant_dek.purpose CHECK constraint extension
-- ----------------------------------------------------------------------
ALTER TABLE tenant_dek
    DROP CONSTRAINT IF EXISTS tenant_dek_purpose_check;

ALTER TABLE tenant_dek
    ADD CONSTRAINT tenant_dek_purpose_check
    CHECK (purpose IN ('TOTP', 'WEBHOOK_SECRET', 'OAUTH2_CLIENT_SECRET', 'HMIS_API_KEY', 'RESERVATION_PII'));

COMMENT ON CONSTRAINT tenant_dek_purpose_check ON tenant_dek IS
    'Closed set of purposes matching org.fabt.shared.security.KeyPurpose. RESERVATION_PII added in V93 for transitional-reentry-support hold-attribution PII. Adding a new purpose requires updating BOTH the enum and this constraint.';

-- ----------------------------------------------------------------------
-- 2. reservation PII columns (storing base64 v1 EncryptionEnvelope)
-- ----------------------------------------------------------------------
ALTER TABLE reservation
    ADD COLUMN IF NOT EXISTS held_for_client_name_encrypted TEXT;

ALTER TABLE reservation
    ADD COLUMN IF NOT EXISTS held_for_client_dob_encrypted TEXT;

ALTER TABLE reservation
    ADD COLUMN IF NOT EXISTS hold_notes_encrypted TEXT;

COMMENT ON COLUMN reservation.held_for_client_name_encrypted IS
    'Base64 v1 EncryptionEnvelope of the third-party client name (when an outreach worker / navigator holds a bed on behalf of someone who is not a platform user). Encrypted via SecretEncryptionService.encryptForTenant(tenantId, KeyPurpose.RESERVATION_PII, name). Spring Batch purges 24h post-resolution per design D4.';

COMMENT ON COLUMN reservation.held_for_client_dob_encrypted IS
    'Base64 v1 EncryptionEnvelope of the third-party client date of birth (ISO-8601 string format before encryption). Used for shelter check-in confirmation. Same purge schedule as held_for_client_name_encrypted.';

COMMENT ON COLUMN reservation.hold_notes_encrypted IS
    'Base64 v1 EncryptionEnvelope of free-text coordination notes from navigator to shelter coordinator. May contain names and contact information of supervision officers. Same purge schedule as held_for_client_name_encrypted — NOT a permanent record.';
