-- RLS for reservation: same dv_shelter policy pattern — join through shelter table
-- DV shelter reservations are hidden unless the caller has app.dv_access = true

ALTER TABLE reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservation FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_reservation_access ON reservation
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM shelter s
            WHERE s.id = reservation.shelter_id
        )
    );
