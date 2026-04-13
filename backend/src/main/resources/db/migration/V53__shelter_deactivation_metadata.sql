-- Deactivation metadata: tracks who deactivated a shelter and why.
-- All nullable — active shelters have null values.
-- cleared_on_reactivate: deactivatedAt, deactivatedBy, deactivationReason set to NULL on reactivate.
ALTER TABLE shelter
    ADD COLUMN deactivated_at TIMESTAMPTZ,
    ADD COLUMN deactivated_by UUID REFERENCES app_user(id),
    ADD COLUMN deactivation_reason VARCHAR(50);
