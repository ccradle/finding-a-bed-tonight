-- V36: Functional index for escalation dedup query (Elena, T-58a).
-- The escalation batch job checks: payload ->> 'referralId' = ?
-- Without this index, the query does a sequential scan on the entire
-- notification table. At NYC scale (100K+ rows), this degrades from
-- sub-millisecond to 100ms+ per dedup check.
--
-- GIN index is not needed — this is an exact-match lookup, not a
-- full-text or containment search. B-tree on the extracted text is optimal.

CREATE INDEX idx_notification_payload_referral_id
    ON notification ((payload ->> 'referralId'));
