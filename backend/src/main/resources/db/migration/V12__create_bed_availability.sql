CREATE TABLE bed_availability (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shelter_id           UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    tenant_id            UUID NOT NULL REFERENCES tenant(id),
    population_type      VARCHAR(50) NOT NULL,
    beds_total           INTEGER NOT NULL,
    beds_occupied        INTEGER NOT NULL DEFAULT 0,
    beds_on_hold         INTEGER NOT NULL DEFAULT 0,
    accepting_new_guests BOOLEAN NOT NULL DEFAULT TRUE,
    snapshot_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by           VARCHAR(255),
    notes                VARCHAR(500),
    CONSTRAINT chk_beds_total_non_negative CHECK (beds_total >= 0),
    CONSTRAINT chk_beds_occupied_non_negative CHECK (beds_occupied >= 0),
    CONSTRAINT chk_beds_on_hold_non_negative CHECK (beds_on_hold >= 0)
);

-- Latest-snapshot query: DISTINCT ON (shelter_id, population_type) ORDER BY snapshot_ts DESC
CREATE INDEX idx_bed_avail_latest ON bed_availability(shelter_id, population_type, snapshot_ts DESC);

-- Tenant-scoped queries
CREATE INDEX idx_bed_avail_tenant ON bed_availability(tenant_id, snapshot_ts DESC);

-- Concurrent insert safety: if two coordinators submit at the exact same millisecond
-- for the same shelter/population type, one insert is silently dropped via ON CONFLICT
-- DO NOTHING. This is per the HSDS extension spec requirement — no error is surfaced,
-- and the latest snapshot remains consistent and queryable.
ALTER TABLE bed_availability
    ADD CONSTRAINT uq_bed_avail_shelter_pop_ts UNIQUE (shelter_id, population_type, snapshot_ts);
