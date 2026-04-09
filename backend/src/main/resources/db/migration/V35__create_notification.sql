-- V35: Persistent notification store for DV referral safety (#77).
-- Notifications survive logout/restart. Severity tiers drive UI treatment.
-- Zero client PII in payload — designed to support VAWA/FVPSA requirements.

CREATE TABLE notification (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    recipient_id  UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    type          VARCHAR(50) NOT NULL,
    severity      VARCHAR(20) NOT NULL DEFAULT 'INFO',
    payload       JSONB NOT NULL DEFAULT '{}',
    read_at       TIMESTAMPTZ,
    acted_at      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ
);

-- Fast unread queries for bell badge count and login catch-up.
-- Partial index: only unread rows are indexed (read notifications excluded).
CREATE INDEX idx_notification_unread
    ON notification (recipient_id, created_at DESC)
    WHERE read_at IS NULL;

-- Tenant-scoped queries (admin views, cleanup).
CREATE INDEX idx_notification_tenant
    ON notification (tenant_id, created_at DESC);

-- Row Level Security: users can only see their own notifications.
-- Enforced via fabt_app role (NOSUPERUSER) — same pattern as DV shelter access.
--
-- FOR SELECT only: read isolation at DB level, writes trusted to service layer.
-- NotificationPersistenceService.send() writes notifications for arbitrary recipients
-- (e.g., outreach worker's referral creates notification for coordinator).
-- Service layer validates all writes; DB enforces read isolation.
-- app.current_user_id defaults to nil UUID when no user context (scheduled jobs),
-- which returns zero rows — fails closed.
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;

CREATE POLICY notification_recipient_read_policy ON notification
    FOR SELECT
    USING (recipient_id = current_setting('app.current_user_id')::uuid);

-- UPDATE: scoped to own notifications (mark-read, mark-acted via REST API).
CREATE POLICY notification_recipient_write_policy ON notification
    FOR UPDATE
    USING (recipient_id = current_setting('app.current_user_id')::uuid);

-- DELETE: unrestricted for fabt_app — same rationale as INSERT.
-- Only the cleanup scheduled job calls DELETE (cross-user, system operation).
-- No user-facing API exposes DELETE. User-facing mutation uses UPDATE
-- (markRead, markActed), which IS recipient-scoped above.
CREATE POLICY notification_delete_policy ON notification
    FOR DELETE
    USING (true);

-- INSERT: unrestricted for fabt_app — service layer creates notifications
-- for any recipient (referral → coordinator, surge → all coordinators).
-- No INSERT policy = INSERT allowed for the role (PostgreSQL default when
-- RLS is enabled but no INSERT policy exists for the role with DML grants).
CREATE POLICY notification_insert_policy ON notification
    FOR INSERT
    WITH CHECK (true);

-- GRANTs handled by V16 ALTER DEFAULT PRIVILEGES — no manual grant needed.
