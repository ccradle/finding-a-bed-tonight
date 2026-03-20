CREATE TABLE shelter_capacity (
    shelter_id      UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    population_type VARCHAR(50) NOT NULL,
    beds_total      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (shelter_id, population_type),
    CONSTRAINT chk_beds_total_non_negative CHECK (beds_total >= 0)
);
