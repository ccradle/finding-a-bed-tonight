-- Issue #108: shelter activate/deactivate hold cascade.
-- 1. Widen reservation.status to accommodate CANCELLED_SHELTER_DEACTIVATED (30 chars).
--    V14 originally created as VARCHAR(20); the new status exceeds that.
-- 2. Drop and recreate CHECK constraint to include the new status value.
--    V14 defined: CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')).
ALTER TABLE reservation ALTER COLUMN status TYPE VARCHAR(50);
ALTER TABLE reservation DROP CONSTRAINT chk_reservation_status;
ALTER TABLE reservation ADD CONSTRAINT chk_reservation_status
    CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'CANCELLED_SHELTER_DEACTIVATED'));
