-- Operational flag: inactive shelters are excluded from bed search but remain
-- in the database for DV referral safety checks (Marcus Webb / Elena Vasquez).
ALTER TABLE shelter ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
