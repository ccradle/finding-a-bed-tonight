-- RLS for bed_availability: same dv_shelter policy pattern as shelter_constraints/shelter_capacity
-- DV shelter availability rows are hidden unless the caller has app.dv_access = true

ALTER TABLE bed_availability ENABLE ROW LEVEL SECURITY;
ALTER TABLE bed_availability FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_bed_availability_access ON bed_availability
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM shelter s
            WHERE s.id = bed_availability.shelter_id
        )
    );
