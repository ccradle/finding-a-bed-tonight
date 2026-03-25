-- V22: HMIS Bridge — outbox and audit tables for async push to HMIS vendors.
--
-- The outbox pattern ensures pushes survive application restarts.
-- The audit log satisfies HMIS security standards for transmission tracking.
-- No client PII flows through these tables — only project-level bed inventory.

CREATE TABLE hmis_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    shelter_id      UUID REFERENCES shelter(id) ON DELETE SET NULL,
    vendor_type     VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payload         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ,
    error_message   VARCHAR(1000),
    retry_count     INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT chk_hmis_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT chk_hmis_outbox_vendor CHECK (vendor_type IN ('CLARITY', 'WELLSKY', 'CLIENTTRACK'))
);

-- Outbox processor picks up PENDING entries
CREATE INDEX idx_hmis_outbox_status ON hmis_outbox(status, created_at);
-- Tenant-scoped queries
CREATE INDEX idx_hmis_outbox_tenant ON hmis_outbox(tenant_id, status);

CREATE TABLE hmis_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    vendor_type     VARCHAR(50) NOT NULL,
    push_timestamp  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    record_count    INTEGER NOT NULL,
    status          VARCHAR(20) NOT NULL,
    error_message   VARCHAR(1000),
    payload_hash    VARCHAR(64),

    CONSTRAINT chk_hmis_audit_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

-- Admin UI history queries
CREATE INDEX idx_hmis_audit_tenant_ts ON hmis_audit_log(tenant_id, push_timestamp DESC);
CREATE INDEX idx_hmis_audit_vendor ON hmis_audit_log(tenant_id, vendor_type, push_timestamp DESC);
