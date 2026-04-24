-- V81 — Phase F slice F-5: archive retention metadata on tenant.
--
-- Adds two columns the offboard/archive flow needs:
--
--   offboard_export_receipt_uri TEXT — filesystem path (or future S3 URI)
--     where TenantOffboardExportService wrote the schema'd JSON dump of the
--     tenant's user-facing data. Set by offboard(); required to be non-null
--     before archive() will proceed (GDPR Art. 20 data-portability contract
--     — a tenant cannot be archived without first receiving its data export).
--
--   archived_at TIMESTAMPTZ — stamped when archive() transitions state to
--     ARCHIVED. Starts the 30-day retention clock that hardDelete() (F-6)
--     gates on: DELETE only permitted when archived_at < NOW() - 30 days.
--
-- Both NULL for ACTIVE and SUSPENDED tenants (the normal operating state).
-- Migration is a pure ALTER ADD COLUMN with NULLs — takes ACCESS EXCLUSIVE
-- briefly on tenant for the metadata change but no row rewrite, so
-- sub-millisecond on any realistic tenant count.

ALTER TABLE tenant
    ADD COLUMN offboard_export_receipt_uri TEXT,
    ADD COLUMN archived_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant.offboard_export_receipt_uri IS 'Filesystem path (v0.51.0) or S3 URI (Phase H) where TenantOffboardExportService wrote the GDPR Art. 20 data-portability dump. Set by offboard(); required non-null before archive() proceeds.';

COMMENT ON COLUMN tenant.archived_at IS 'Timestamp when archive() flipped state to ARCHIVED. Starts the 30-day retention clock for hardDelete() (F-6).';
