-- V38: Simplify notification RLS — replace per-user policies with unrestricted.
--
-- WHY: The per-user SELECT policy (recipient_id = current_setting('app.current_user_id'))
-- was the wrong pattern for this table. It caused 3 production bugs in the first 2 hours:
--   1. INSERT RETURNING fails (SELECT policy blocks RETURNING clause)
--   2. Cleanup DELETE does nothing (WHERE clause reads go through SELECT policy)
--   3. Escalation dedup can't see existing notifications (144 duplicates in 2 hours)
--
-- Every other RLS table uses a binary pattern (app.dv_access true/false) which has a
-- natural "see everything" value. Per-user RLS has no equivalent — nil UUID means "nobody."
--
-- SECURITY MODEL CHANGE:
--   Before: DB-level per-user isolation (RLS policy checks recipient_id)
--   After:  Service-level per-user filtering (controller extracts userId from JWT,
--           repository queries include WHERE recipient_id = :recipientId)
--
-- Notification payloads contain zero client PII (opaque IDs only). ArchUnit enforces
-- that only the notification module accesses the notification repository.
-- RLS remains ENABLED for future tenant-scoped policies if needed.

-- Drop per-user policies that caused production bugs
DROP POLICY IF EXISTS notification_recipient_read_policy ON notification;
DROP POLICY IF EXISTS notification_recipient_write_policy ON notification;

-- Unrestricted SELECT — service layer handles per-user filtering
CREATE POLICY notification_read_policy ON notification
    FOR SELECT
    USING (true);

-- Unrestricted UPDATE — service layer validates recipient via WHERE clause
-- (markRead/markActed queries include recipient_id = :recipientId)
CREATE POLICY notification_write_policy ON notification
    FOR UPDATE
    USING (true);
