-- V80 — Phase F slice F-4: per-tenant audit-chain head pointer.
--
-- Creates the table that Phase G's audit hash-chain (§G1) will maintain. F-4
-- creates the table + seeds one row per tenant at create-time so G1's
-- on-audit-INSERT UPDATE has a pre-existing row to bump — avoids the
-- chicken-and-egg where every tenant's first audit row would have to create
-- the chain head as a special case.
--
-- This F-4 migration:
--   * creates the schema
--   * backfills one row per existing tenant with a placeholder hash
--     (all-zero SHA-256 prefix) so the chain-verifier has a defined start
--     state when G1 lands
--
-- The ON DELETE CASCADE is load-bearing for F-6's crypto-shred (§D11):
-- hardDelete(tenant) cascades to remove the chain head along with
-- tenant_key_material etc., so the destructive path is a single DELETE on
-- tenant rather than N ordered DELETEs.

CREATE TABLE tenant_audit_chain_head (
    tenant_id   UUID        PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    -- SHA-256 hash of the most recent audit row in this tenant's chain.
    -- 32 bytes exactly; G1's hasher will UPDATE this on every insert. On
    -- create (F-4) we seed with 32 zero bytes as a sentinel meaning
    -- "chain not yet started" — G1's first insert computes hash(prev=zero || row)
    -- which is distinguishable from hash(row) alone should forensic review
    -- need to pin the chain-start event.
    last_hash   BYTEA       NOT NULL,
    -- PK of the most recent audit_events row in this tenant's chain. NULL
    -- until G1's first audit insert for this tenant.
    last_row_id UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_audit_chain_head_hash_length
        CHECK (octet_length(last_hash) = 32)
);

COMMENT ON TABLE tenant_audit_chain_head IS 'Per-tenant hash-chain pointer for tamper-evident audit (Phase F-4 seed, Phase G-1 writer). Single row per tenant — UPDATEd on every audit INSERT by the future AuditChainHasher component.';

-- Seed one row per existing tenant with the 32-byte all-zero sentinel.
-- decode('...', 'hex') returns BYTEA; 64 zero hex chars = 32 zero bytes.
INSERT INTO tenant_audit_chain_head (tenant_id, last_hash, last_row_id)
SELECT id, decode('0000000000000000000000000000000000000000000000000000000000000000', 'hex'), NULL
FROM tenant
ON CONFLICT (tenant_id) DO NOTHING;

-- Phase B RLS posture: audit writes are FORCE-RLS'd on audit_events (V69);
-- this head table doesn't itself contain audit payload (only a hash pointer),
-- so RLS is not enabled here. The chain-verifier job will read across tenants
-- under the fabt owner role per Phase B pattern. G1 may add RLS if tenant-
-- scoped read queries land; F-4 does not.

-- Grants for the app role. UPDATE only — INSERT is service-layer only via
-- TenantLifecycleService.create; DELETE only via cascade from tenant.
GRANT SELECT, INSERT, UPDATE ON tenant_audit_chain_head TO fabt_app;
