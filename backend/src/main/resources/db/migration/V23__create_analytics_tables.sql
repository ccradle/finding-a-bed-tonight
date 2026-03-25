-- V23: CoC Analytics — bed search demand logging, pre-aggregated utilization
-- summary, and BRIN index for efficient time-range queries on bed_availability.
--
-- bed_search_log captures every bed search including zero-result searches —
-- the strongest unmet demand signal for grant applications and operational planning.
--
-- daily_utilization_summary is populated by a Spring Batch job (3 AM) — analytics
-- dashboard queries hit this table (365 rows/shelter/year) instead of raw snapshots.
--
-- BRIN index on snapshot_ts is ideal because bed_availability is append-only
-- (physical row order correlates with timestamp). Tiny (~0.1% of B-tree), near-zero
-- insert cost.

-- Bed search demand logging
CREATE TABLE bed_search_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    population_type VARCHAR(50),
    results_count   INTEGER NOT NULL,
    search_ts       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bed_search_log_tenant_ts ON bed_search_log(tenant_id, search_ts);

-- Pre-aggregated daily utilization summary
CREATE TABLE daily_utilization_summary (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    shelter_id       UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    population_type  VARCHAR(50) NOT NULL,
    summary_date     DATE NOT NULL,
    avg_utilization  DOUBLE PRECISION NOT NULL,
    max_occupied     INTEGER NOT NULL,
    min_available    INTEGER NOT NULL,
    snapshot_count   INTEGER NOT NULL,

    CONSTRAINT uq_daily_util_tenant_shelter_pop_date
        UNIQUE (tenant_id, shelter_id, population_type, summary_date)
);

-- BRIN index for analytics time-range queries on the append-only bed_availability table.
-- Existing B-tree indexes remain for OLTP unchanged.
CREATE INDEX idx_bed_avail_snapshot_brin
    ON bed_availability USING brin(snapshot_ts)
    WITH (pages_per_range = 128);
