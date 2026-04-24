-- V85 — Phase G slice G-1: audit_events hash-chain columns.
--
-- Adds two BYTEA columns to audit_events:
--   * prev_hash — the immediately-preceding row's row_hash in this tenant's
--                 chain at the time this row was inserted. Reads from
--                 tenant_audit_chain_head.last_hash (set by G-1's
--                 AuditChainHasher during the INSERT tx). First row in a
--                 tenant's chain picks up the 32-byte zero sentinel that
--                 V80 / TenantLifecycleService.create seeded.
--   * row_hash  — SHA-256(prev_hash || canonical_json(row)), 32 bytes. Stored
--                 on the row itself so a verifier can re-compute on read.
--
-- Both columns are NULLABLE. Rationale:
--   * Historical rows written before V85 have no hash. The verifier (G-2)
--     treats NULL-hash rows as "chain starts after this point" and validates
--     forward from the first non-null row_hash per tenant.
--   * Orphan audits that land under TenantContext.SYSTEM_TENANT_ID have no
--     corresponding tenant_audit_chain_head row (SYSTEM_TENANT_ID is a
--     sentinel, not a real tenant). The hasher skips them cleanly — they
--     keep NULL hashes. Orphans are already a WARN signal per Phase B D55;
--     the chain simply doesn't cover them.
--
-- Length invariant: when non-null, both columns must be exactly 32 bytes
-- (SHA-256 digest size). Enforced via CHECK constraint.
--
-- Crypto-shred compatibility: tenant hardDelete CASCADE on the parent tenant
-- row destroys audit_events via the existing FK (or, if audit_events has no
-- direct FK to tenant, the verifier simply stops finding rows for that
-- tenant). tenant_audit_chain_head ON DELETE CASCADE from V80 takes care of
-- the chain head itself. No change to the shred path.

ALTER TABLE audit_events
    ADD COLUMN prev_hash BYTEA,
    ADD COLUMN row_hash  BYTEA;

ALTER TABLE audit_events
    ADD CONSTRAINT audit_events_prev_hash_length
        CHECK (prev_hash IS NULL OR octet_length(prev_hash) = 32);

ALTER TABLE audit_events
    ADD CONSTRAINT audit_events_row_hash_length
        CHECK (row_hash IS NULL OR octet_length(row_hash) = 32);

COMMENT ON COLUMN audit_events.prev_hash IS
    'Phase G-1 hash chain: 32-byte SHA-256 of the immediately-preceding row in this tenant''s chain (pulled from tenant_audit_chain_head.last_hash at INSERT time). First row in a tenant''s chain picks up the 32-byte zero sentinel. NULL for pre-V85 historical rows and for orphan audits under SYSTEM_TENANT_ID.';

COMMENT ON COLUMN audit_events.row_hash IS
    'Phase G-1 hash chain: 32-byte SHA-256(prev_hash || canonical_json(row)) stored on the row itself. Verifier (G-2) re-computes on read and alerts on drift. NULL for pre-V85 historical rows and for orphan audits under SYSTEM_TENANT_ID.';
