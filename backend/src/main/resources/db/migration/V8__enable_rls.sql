-- Row Level Security for DV shelter data protection
-- Enforcement at the data layer, not application routing (Design D4)

ALTER TABLE shelter ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelter FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_shelter_access ON shelter
    FOR ALL
    USING (
        dv_shelter = false
        OR current_setting('app.dv_access', true)::boolean = true
    );

-- Extend RLS to shelter child tables via shelter_id join
ALTER TABLE shelter_constraints ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelter_constraints FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_shelter_constraints_access ON shelter_constraints
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM shelter s
            WHERE s.id = shelter_constraints.shelter_id
        )
    );

ALTER TABLE shelter_capacity ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelter_capacity FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_shelter_capacity_access ON shelter_capacity
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM shelter s
            WHERE s.id = shelter_capacity.shelter_id
        )
    );
