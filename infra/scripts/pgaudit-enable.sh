#!/usr/bin/env bash
#
# pgaudit-enable.sh — one-time operator script to create the pgaudit
# PostgreSQL extension. Paired with Flyway V73 (which configures the
# per-database pgaudit session parameters, not the extension itself).
#
# Per Phase B warroom V1 decision (2026-04-18): extension creation is
# a SUPERUSER operation and must stay out of the Flyway migration
# chain. The `fabt_owner` role that Flyway connects as is NOT a
# superuser (by design — Principle of Least Privilege per D14 pool
# threat model).
#
# Prerequisites:
#   1. The Postgres container image MUST have pgaudit preloaded. For
#      the demo site + Oracle VM, this is `deploy/pgaudit.Dockerfile`
#      (Debian postgres:16 + PGDG postgresql-16-pgaudit package +
#      shared_preload_libraries = 'pgaudit' in postgresql.conf).
#   2. `CREATE EXTENSION pgaudit` MUST run while the server is up
#      AND pgaudit is preloaded (otherwise PostgreSQL raises
#      "pgaudit must be loaded via shared_preload_libraries").
#
# Usage:
#   FABT_PG_SUPERUSER_URL=postgresql://postgres:PASS@localhost/fabt \
#       ./pgaudit-enable.sh
#
#   FABT_PG_SUPERUSER_URL=...  ./pgaudit-enable.sh --dry-run
#   FABT_PG_SUPERUSER_URL=...  ./pgaudit-enable.sh --drop         # emergency only
#
# Required env var:
#   FABT_PG_SUPERUSER_URL — postgresql://postgres:PASS@host/fabt
#
# Exit codes:
#   0 — extension is present and healthy (or was just created)
#   1 — extension creation failed (preload missing, permission, etc.)
#   2 — operator-argument / env-var error

set -euo pipefail

MODE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) MODE="dry-run"; shift ;;
        --drop)    MODE="drop";    shift ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "${FABT_PG_SUPERUSER_URL:-}" ]]; then
    echo "FAIL: FABT_PG_SUPERUSER_URL unset. Retrieve superuser credentials from"
    echo "      1Password entry 'fabt-oracle-vm-postgres-superuser' (NOT the owner"
    echo "      connection string — CREATE EXTENSION requires SUPERUSER privilege)."
    exit 2
fi

run_psql() {
    psql "$FABT_PG_SUPERUSER_URL" -tA -P pager=off --set=ON_ERROR_STOP=1 -c "$1"
}

OPERATOR="${USER:-unknown}"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat <<EOF

======================================================================
pgaudit extension lifecycle
======================================================================
Operator:   $OPERATOR
Timestamp:  $TIMESTAMP
Mode:       ${MODE:-execute}
Database:   via FABT_PG_SUPERUSER_URL
======================================================================
EOF

# ---------- existence check ---------------------------------------------
existence_sql="SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgaudit')"
already_present=$(run_psql "$existence_sql" || echo "check-failed")

if [[ "$already_present" == "check-failed" ]]; then
    echo "FAIL: could not query pg_extension. Verify FABT_PG_SUPERUSER_URL is"
    echo "      reachable and points at the correct database."
    exit 1
fi

# ---------- --drop path (emergency only) --------------------------------
if [[ "$MODE" == "drop" ]]; then
    if [[ "$already_present" == "t" ]]; then
        if [[ "${FABT_CONFIRM_DROP:-}" != "yes" ]]; then
            echo ""
            echo "WARN: --drop is an emergency action. Running DROP EXTENSION pgaudit"
            echo "      removes the audit extension, silencing every pgaudit log line"
            echo "      including the NO FORCE RLS DDL tripwire. Confirm by re-running"
            echo "      with:"
            echo ""
            echo "          FABT_CONFIRM_DROP=yes ./pgaudit-enable.sh --drop"
            echo ""
            echo "      Document the reason in docs/security/phase-b-silent-audit-"
            echo "      write-failures-runbook.md under 'Incidents'."
            exit 2
        fi
        echo "Dropping pgaudit extension at $TIMESTAMP (operator: $OPERATOR)..."
        run_psql "DROP EXTENSION pgaudit"
        echo "pgaudit DROPPED. Alerting surface is now DEGRADED — FORCE RLS clears"
        echo "will no longer page. Restore pgaudit ASAP via ./pgaudit-enable.sh"
        echo "(without --drop)."
    else
        echo "pgaudit extension not present. Nothing to drop."
    fi
    exit 0
fi

# ---------- idempotent present-case -------------------------------------
if [[ "$already_present" == "t" ]]; then
    echo "pgaudit extension is already installed. No action required."
    run_psql "SELECT extname, extversion FROM pg_extension WHERE extname = 'pgaudit'"
    exit 0
fi

# ---------- preload check (explicit, helpful error) ---------------------
# CREATE EXTENSION pgaudit fails with a specific error if shared_preload_
# libraries doesn't include pgaudit. Detect that condition ahead of time
# so the operator gets a clear "fix the image" message rather than a
# cryptic PG error.
preload=$(run_psql "SHOW shared_preload_libraries")
if [[ "$preload" != *"pgaudit"* ]]; then
    echo ""
    echo "FAIL: shared_preload_libraries='$preload' does NOT include pgaudit."
    echo ""
    echo "      pgaudit requires preload-time library loading. Update the"
    echo "      PostgreSQL container image to include pgaudit in"
    echo "      shared_preload_libraries and restart the container before"
    echo "      running this script. See deploy/pgaudit.Dockerfile."
    echo ""
    echo "      Configuration check:"
    echo "        docker compose exec postgres grep shared_preload /etc/postgresql/postgresql.conf"
    echo ""
    exit 1
fi

# ---------- create --------------------------------------------------------
if [[ "$MODE" == "dry-run" ]]; then
    echo "DRY RUN: would execute \"CREATE EXTENSION pgaudit\" on database from"
    echo "         FABT_PG_SUPERUSER_URL. No changes made."
    exit 0
fi

echo "Creating pgaudit extension..."
run_psql "CREATE EXTENSION pgaudit"

echo ""
echo "pgaudit extension installed:"
run_psql "SELECT extname, extversion FROM pg_extension WHERE extname = 'pgaudit'"

# ---------- verify session params (from Flyway V73) ---------------------
echo ""
echo "Verifying V73-applied session parameters are honored by pgaudit:"
run_psql "SHOW pgaudit.log"
run_psql "SHOW pgaudit.log_level"
run_psql "SHOW pgaudit.log_parameter"
run_psql "SHOW pgaudit.log_relation"

echo ""
echo "======================================================================"
echo "pgaudit enabled. Next:"
echo "  1. Trigger a test DDL (e.g., CREATE TABLE ztest_pgaudit () ; DROP TABLE ztest_pgaudit;)"
echo "     and confirm the Postgres log emits a pgaudit AUDIT line."
echo "  2. Verify promtail is tailing the log + Loki has the AUDIT line."
echo "  3. Trigger FORCE RLS DDL on a NON-regulated throwaway table to verify"
echo "     the FabtPhaseBNoForceRlsDdl alert fires within 60s."
echo "======================================================================"
