#!/usr/bin/env bash
# Phase B RLS emergency rollback (D61 panic script).
#
# USE ONLY WHEN: FORCE RLS is producing quiet 404s / 500s in prod that are
# unfixable by other means AND the release cannot be reverted immediately.
# This script DOES NOT fix audit-path failures (use the runbook for those);
# it only rolls back the FORCE RLS enforcement so application traffic can
# breathe while a proper fix is being engineered.
#
# Atomic behavior: a single BEGIN/COMMIT block DROPs all Phase B policies
# AND reverses FORCE ROW LEVEL SECURITY on all 7 regulated tables. Either
# all 8 operations succeed or none persist — no partial-rollback state
# where some tables are FORCE'd and others aren't.
#
# The script writes a SYSTEM_PHASE_B_ROLLBACK audit row (attributed to
# SYSTEM_TENANT_ID per D55) before closing the transaction so there is a
# forensic record of the emergency action with operator + reason.
#
# WARRANTY: running this re-opens the pre-V68/V69 attack surface — owner-
# session cross-tenant DELETEs become possible again AND fabt_app-session
# cross-tenant reads return rows. Treat as equivalent to disabling a
# safety interlock: always accompanied by incident-commander sign-off
# and a time-boxed plan to restore Phase B.
#
# Usage:
#   ./phase-b-rls-panic.sh --reason "<short operator note>"
#     [--dry-run]  Show what would run without executing
#
# Required env vars (sourced from 1Password):
#   FABT_PG_OWNER_URL — postgresql://fabt:PASS@host/db owner connection string
#   FABT_PANIC_ALERT_WEBHOOK (optional) — Slack/PagerDuty webhook for alerts

set -euo pipefail

REASON=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reason) REASON="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$REASON" ]]; then
    echo "FAIL: --reason is required (operator note for the audit row)"
    echo "  Example: ./phase-b-rls-panic.sh --reason 'Sev-1 incident #1234, 404 storm on /api/v1/audit-events'"
    exit 2
fi

if [[ -z "${FABT_PG_OWNER_URL:-}" ]]; then
    echo "FAIL: FABT_PG_OWNER_URL unset — retrieve from 1Password entry 'fabt-oracle-vm-postgres-owner'"
    exit 2
fi

OPERATOR="${USER:-unknown}"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat <<EOF

======================================================================
Phase B RLS PANIC ROLLBACK
======================================================================
Operator:   $OPERATOR
Timestamp:  $TIMESTAMP
Reason:     $REASON
Mode:       $([ "$DRY_RUN" == "1" ] && echo "DRY RUN" || echo "EXECUTE")
======================================================================

This will atomically:
  1. NO FORCE ROW LEVEL SECURITY on 7 regulated tables
  2. DROP POLICY on all Phase B tenant-isolation + kid-table policies
  3. INSERT audit row SYSTEM_PHASE_B_ROLLBACK (tenant=SYSTEM_TENANT_ID)

Post-rollback:
  - FORCE RLS is OFF on audit_events, hmis_audit_log, password_reset_token,
    one_time_access_code, hmis_outbox, tenant_key_material, kid_to_tenant_key
  - Owner sessions (fabt) regain unrestricted DML on all 7 tables
  - fabt_app sessions can see cross-tenant rows (V68 policies deleted)
  - V70 + V72 REVOKE on audit_events + hmis_audit_log still applies
    (fabt_app cannot UPDATE/DELETE/TRUNCATE the audit tables)

EOF

# The atomic SQL block.
#
# Phase B warroom W-GAUGE-1: the SYSTEM_PHASE_B_ROLLBACK audit row is
# emitted FIRST (while FORCE RLS is still enforcing) so the rollback
# intent is witnessed by a committed audit row even if a later statement
# fails. Emitting it after the NO FORCE block would leave a window
# where the operator could be subverted (racing owner-session DML OR a
# trailing statement failure that rolls back the audit INSERT but leaves
# NO FORCE in effect). Since Phase B policies still accept SYSTEM_TENANT_ID
# INSERTs under the enforcing policies (D55), the audit write succeeds
# without relaxing RLS first.
SQL='BEGIN;
SELECT set_config('"'"'app.tenant_id'"'"', '"'"'00000000-0000-0000-0000-000000000001'"'"', true);
INSERT INTO audit_events (action, tenant_id, details)
VALUES (
    '"'"'SYSTEM_PHASE_B_ROLLBACK'"'"',
    '"'"'00000000-0000-0000-0000-000000000001'"'"',
    jsonb_build_object(
        '"'"'operator'"'"', CURRENT_USER,
        '"'"'reason'"'"',   :'"'"'reason'"'"',
        '"'"'timestamp'"'"', now()
    )
);

ALTER TABLE audit_events         NO FORCE ROW LEVEL SECURITY;
ALTER TABLE hmis_audit_log       NO FORCE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token NO FORCE ROW LEVEL SECURITY;
ALTER TABLE one_time_access_code NO FORCE ROW LEVEL SECURITY;
ALTER TABLE hmis_outbox          NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_key_material  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE kid_to_tenant_key    NO FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_audit_events      ON audit_events;
DROP POLICY IF EXISTS tenant_isolation_hmis_audit_log    ON hmis_audit_log;
DROP POLICY IF EXISTS tenant_isolation_hmis_outbox       ON hmis_outbox;

DROP POLICY IF EXISTS prt_select_all                     ON password_reset_token;
DROP POLICY IF EXISTS prt_insert_permissive              ON password_reset_token;
DROP POLICY IF EXISTS prt_update_permissive              ON password_reset_token;
DROP POLICY IF EXISTS prt_delete_permissive              ON password_reset_token;
DROP POLICY IF EXISTS prt_insert_restrictive             ON password_reset_token;
DROP POLICY IF EXISTS prt_update_restrictive             ON password_reset_token;
DROP POLICY IF EXISTS prt_delete_restrictive             ON password_reset_token;

DROP POLICY IF EXISTS otac_select_all                    ON one_time_access_code;
DROP POLICY IF EXISTS otac_insert_permissive             ON one_time_access_code;
DROP POLICY IF EXISTS otac_update_permissive             ON one_time_access_code;
DROP POLICY IF EXISTS otac_delete_permissive             ON one_time_access_code;
DROP POLICY IF EXISTS otac_insert_restrictive            ON one_time_access_code;
DROP POLICY IF EXISTS otac_update_restrictive            ON one_time_access_code;
DROP POLICY IF EXISTS otac_delete_restrictive            ON one_time_access_code;

DROP POLICY IF EXISTS kid_material_select_all            ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_insert_permissive     ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_update_permissive     ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_delete_permissive     ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_insert_restrictive    ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_update_restrictive    ON tenant_key_material;
DROP POLICY IF EXISTS kid_material_delete_restrictive    ON tenant_key_material;

DROP POLICY IF EXISTS kid_select_all                     ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_insert_permissive              ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_update_permissive              ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_delete_permissive              ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_insert_restrictive             ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_update_restrictive             ON kid_to_tenant_key;
DROP POLICY IF EXISTS kid_delete_restrictive             ON kid_to_tenant_key;

COMMIT;'

if [[ "$DRY_RUN" == "1" ]]; then
    echo "--- SQL that would execute (reason placeholder shown as :reason) ---"
    echo "$SQL"
    echo "--- End dry run ---"
    exit 0
fi

echo "Executing atomic rollback transaction..."
echo "$SQL" | psql "$FABT_PG_OWNER_URL" \
    -v ON_ERROR_STOP=1 \
    -v "reason=$REASON" \
    -q

echo
echo "======================================================================"
echo "Phase B RLS rollback COMMITTED."
echo "======================================================================"

# Alert if configured
if [[ -n "${FABT_PANIC_ALERT_WEBHOOK:-}" ]]; then
    echo "Posting to alert webhook..."
    curl -sS -X POST -H 'Content-Type: application/json' \
        -d "{\"text\":\"Phase B RLS rolled back by $OPERATOR at $TIMESTAMP. Reason: $REASON\"}" \
        "$FABT_PANIC_ALERT_WEBHOOK" || echo "WARN: webhook POST failed (non-fatal)"
fi

# Verify post-state
echo
echo "Post-rollback verification:"
psql "$FABT_PG_OWNER_URL" -tAc "
    SELECT COUNT(*) || ' regulated tables still FORCE-RLS enabled (expected: 0)'
    FROM pg_class c
    WHERE c.relname IN (
        'audit_events', 'hmis_audit_log', 'password_reset_token',
        'one_time_access_code', 'hmis_outbox',
        'tenant_key_material', 'kid_to_tenant_key'
    )
    AND c.relforcerowsecurity = true
"

echo
echo "Phase B is now OFF. Restore plan:"
echo "  1. Root-cause the incident that forced this rollback"
echo "  2. Patch, test against Testcontainers with V67-V72 applied"
echo "  3. Release hotfix that re-runs V68-V72 via Flyway-repair"
echo "  4. Re-verify via scripts/phase-b-audit-path-smoke.sh"
echo "  5. Document incident in docs/security/phase-b-rls-panic-incidents.md"
