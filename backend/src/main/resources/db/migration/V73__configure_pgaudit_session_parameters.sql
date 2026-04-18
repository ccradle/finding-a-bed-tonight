-- V73 — pgaudit session configuration (config-only, no CREATE EXTENSION).
--
-- Per Phase B warroom V1 decision (2026-04-18): V73 is intentionally
-- config-only. The pgaudit extension itself is created out-of-band by
-- `infra/scripts/pgaudit-enable.sh` as a superuser step — Flyway runs as
-- `fabt_owner` which does not have the SUPERUSER or CREATEROLE privileges
-- required by `CREATE EXTENSION pgaudit`. Splitting the install from the
-- config (a) keeps Flyway least-privilege, (b) lets the DDL-audit alerts
-- stay on a pgaudit install that predates V73, and (c) survives rollback
-- to the pre-pgaudit image without requiring a dropping migration.
--
-- What this migration does:
--   * Writes four `pgaudit.*` parameters onto the current database's
--     per-database settings via ALTER DATABASE.
--   * Uses a DO block with `current_database()` so this migration is
--     portable across `fabt`, `fabt_test`, and any other database name
--     the operator provisions — including the B-baseline prod install.
--
-- What this migration does NOT do:
--   * Create the pgaudit extension. That's `infra/scripts/pgaudit-enable.sh`.
--   * Load pgaudit into `shared_preload_libraries`. That's the container
--     image (`deploy/pgaudit.Dockerfile`).
--   * Fail if pgaudit isn't loaded. PostgreSQL accepts custom namespaced
--     GUC settings (`pgaudit.*`) regardless of whether the extension is
--     present — they are stored as opaque strings and only honored once
--     pgaudit reads them at session start. Rolling back to the Alpine
--     image (no pgaudit) does NOT break existing sessions; the stored
--     ALTER DATABASE values become orphaned strings that do nothing.
--
-- Parameters set:
--
--   pgaudit.log = 'write,ddl'
--     Audit every data-modifying statement (INSERT/UPDATE/DELETE/COPY
--     write-side/TRUNCATE) and every DDL statement (CREATE/ALTER/DROP/
--     GRANT/REVOKE). Read paths are NOT audited — the write/DDL surface
--     is the one that tenant-isolation forensics actually cares about.
--     Using `write,ddl` (not `all`) bounds pgaudit disk cost; per warroom
--     Risk R3, the 30-day projection + CPU rehearsal informs whether
--     we drop to `ddl`-only under load pressure.
--
--   pgaudit.log_level = 'log'
--     Emit at LOG level (not WARNING/NOTICE). LOG routes to the standard
--     Postgres log destination where promtail + Loki pick it up for the
--     `FabtPhaseBNoForceRlsDdl` Alertmanager rule.
--
--   pgaudit.log_parameter = 'off'
--     Do NOT include SQL-statement parameter values in the audit log.
--     Parameter values can carry PHI, SSNs, encrypted-secret plaintext
--     (briefly, before encryption in the same statement), and DV-survivor
--     identifiers. Casey Drummond's compliance-posture-matrix requires
--     audit records to capture WHICH operation happened (statement text,
--     table, action) but NOT the bound values. Logging parameter values
--     would push pgaudit from an audit trail into a data-residency
--     concern that we don't want to answer for.
--
--   pgaudit.log_relation = 'on'
--     Emit the target relation name on every audited statement. Without
--     this, an alert rule scanning for `ALTER TABLE ... NO FORCE ROW
--     LEVEL SECURITY` would have to parse the SQL text. With it, the
--     relation name appears in a dedicated field and the match is
--     unambiguous.
--
-- Alert integration: an Alertmanager rule tails Postgres logs via
-- promtail→Loki, matches pgaudit lines where the class=DDL AND the
-- statement contains `NO FORCE ROW LEVEL SECURITY`, and pages to
-- `FABT_PANIC_ALERT_WEBHOOK` within 60 seconds. Per warroom V4 decision.

DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET pgaudit.log = %L',
                   current_database(), 'write,ddl');
    EXECUTE format('ALTER DATABASE %I SET pgaudit.log_level = %L',
                   current_database(), 'log');
    EXECUTE format('ALTER DATABASE %I SET pgaudit.log_parameter = %L',
                   current_database(), 'off');
    EXECUTE format('ALTER DATABASE %I SET pgaudit.log_relation = %L',
                   current_database(), 'on');
END
$$;
