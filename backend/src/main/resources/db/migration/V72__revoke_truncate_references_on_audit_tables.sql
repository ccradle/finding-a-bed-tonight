-- V72 — Checkpoint-2 warroom amendment (Marcus + Jordan converged): V70
-- revoked UPDATE + DELETE, but TRUNCATE and REFERENCES are separate
-- Postgres privileges NOT implied by DELETE. Per PG docs, TRUNCATE
-- BYPASSES row-level security policies entirely — so a compromised
-- fabt_app with `TRUNCATE audit_events` could wipe every forensic
-- row in one statement, silently defeating both V69 FORCE RLS and
-- V70's DELETE revoke.
--
-- REFERENCES is added as zero-cost defense-in-depth: without it, a
-- future schema evolution that allowed fabt_app to add a FK from a
-- lower-privilege table could anchor cascading deletes back into
-- audit_events via ON DELETE semantics.
--
-- V70 was not amended because Flyway migrations are immutable after
-- apply (per feedback_flyway_immutable_after_apply.md) — any checksum
-- change would reject migration application on upgraded environments.
-- Additive V72 closes the gap cleanly.

REVOKE TRUNCATE, REFERENCES ON audit_events FROM fabt_app;
REVOKE TRUNCATE, REFERENCES ON hmis_audit_log FROM fabt_app;
