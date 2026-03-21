-- Add overflow_beds to bed_availability for temporary surge capacity.
-- Coordinators report overflow beds (cots, mats, emergency space) during active surges.
-- Separate from beds_total — overflow capacity exists only during the surge.

ALTER TABLE bed_availability ADD COLUMN overflow_beds INTEGER DEFAULT 0;
