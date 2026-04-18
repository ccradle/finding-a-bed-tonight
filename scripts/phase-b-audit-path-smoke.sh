#!/usr/bin/env bash
# Phase B audit-path pre-tag smoke test (Sam warroom).
#
# Run against a v0.43 candidate build pointing at a throwaway Postgres
# (e.g., docker compose up + run the backend JAR against it). MUST pass
# all 6 steps before tagging v0.43.
#
# Per design-b-rls-hardening §D58: release notes require a documented
# successful rehearsal run + commit hash + operator signature before the
# tag publishes.
#
# Assumptions:
#   - FABT_BACKEND_URL (default: http://localhost:8080)
#   - FABT_ACTUATOR_URL (default: http://localhost:9091)
#   - FABT_PG_OWNER_URL (default: postgresql://fabt:$FABT_PG_OWNER_PASSWORD@localhost:5432/fabt)
#   - FABT_PG_OWNER_PASSWORD — source from vault / 1Password entry
#     "fabt-oracle-vm-postgres-owner" (NEVER hardcode)
#   - FABT_ACTUATOR_USER / FABT_ACTUATOR_PASSWORD — source from vault entry
#     "fabt-actuator-basic-auth"
#   - FABT_DRIVE_USER_EMAIL + FABT_DRIVE_USER_PASSWORD — existing seed user
#     used to drive a known audit-generating action (login+logout)
#   - psql, jq, curl on PATH

set -euo pipefail

BACKEND_URL="${FABT_BACKEND_URL:-http://localhost:8080}"
ACTUATOR_URL="${FABT_ACTUATOR_URL:-http://localhost:9091}"
PG_OWNER_URL="${FABT_PG_OWNER_URL:-postgresql://fabt:${FABT_PG_OWNER_PASSWORD:-}@localhost:5432/fabt}"
ACTUATOR_USER="${FABT_ACTUATOR_USER:-actuator}"
ACTUATOR_PASSWORD="${FABT_ACTUATOR_PASSWORD:-}"

# Sanity-check required env vars so the script fails fast instead of
# emitting confusing psql errors later.
if [[ -z "${FABT_PG_OWNER_PASSWORD:-}" ]]; then
    echo "FAIL: FABT_PG_OWNER_PASSWORD unset — retrieve from 1Password entry 'fabt-oracle-vm-postgres-owner'"
    exit 1
fi
if [[ -z "$ACTUATOR_PASSWORD" ]]; then
    echo "FAIL: FABT_ACTUATOR_PASSWORD unset — retrieve from 1Password entry 'fabt-actuator-basic-auth'"
    exit 1
fi

FAIL=0
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAIL=1; }
step() { echo; echo "=== $* ==="; }

# --------------------------------------------------------------------------
# Step 0 (NEW per Sam checkpoint) — DRIVE a known audit-generating action so
# the following steps assert against a REAL signal, not ambient background.
# Without this, step 1's "0 NULL rows in last 10m" trivially passes on a
# freshly-loaded rehearsal DB.
# --------------------------------------------------------------------------
step "Step 0 — Drive a known audit event (login + logout)"
if [[ -n "${FABT_DRIVE_USER_EMAIL:-}" ]] && [[ -n "${FABT_DRIVE_USER_PASSWORD:-}" ]]; then
    LOGIN_JSON=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$FABT_DRIVE_USER_EMAIL\",\"password\":\"$FABT_DRIVE_USER_PASSWORD\"}" \
        || echo '{}')
    TOKEN=$(echo "$LOGIN_JSON" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        fail "Driver login failed — cannot verify audit path. Response: $LOGIN_JSON"
    else
        pass "Driver login produced token (audit event LOGIN_SUCCESS expected)"
        # Sleep briefly so the async listener (if any) settles before we check.
        sleep 1
    fi
else
    echo "  SKIP — FABT_DRIVE_USER_EMAIL / FABT_DRIVE_USER_PASSWORD unset; step 1's"
    echo "         window will only prove ambient behavior, not the audit path itself."
fi

# --------------------------------------------------------------------------
# Step 1 — recent audit rows all have tenant_id populated
# Window tightened to 2 minutes so it verifies signal from step 0's driver,
# not ambient hours-old activity. Plus: require at least 1 row exists,
# otherwise "0 NULLs in 0 rows" trivially passes.
# --------------------------------------------------------------------------
step "Step 1 — Every recent audit row has tenant_id populated (no NULLs)"
RECENT=$(psql "$PG_OWNER_URL" -tAc "
    SELECT COUNT(*)
    FROM audit_events
    WHERE timestamp > now() - interval '2 minutes'
")
NULLS=$(psql "$PG_OWNER_URL" -tAc "
    SELECT COUNT(*)
    FROM audit_events
    WHERE timestamp > now() - interval '2 minutes'
      AND tenant_id IS NULL
")
if [[ "$RECENT" == "0" ]]; then
    fail "0 audit rows in the last 2 minutes — did step 0 drive an event? Re-check FABT_DRIVE_USER_* env vars"
elif [[ "$NULLS" == "0" ]]; then
    pass "$RECENT recent audit rows, 0 with NULL tenant_id"
else
    fail "$NULLS of $RECENT recent audit rows have tenant_id=NULL — D55 sentinel path broken"
fi

# --------------------------------------------------------------------------
# Step 2 — AuditEventPersister bean is proxied (Bug A+D regression guard)
# --------------------------------------------------------------------------
step "Step 2 — AuditEventPersister is a Spring proxy (@Transactional live)"
RESOURCE=$(curl -s -u "${ACTUATOR_USER}:${ACTUATOR_PASSWORD}" "$ACTUATOR_URL/actuator/beans" | \
    jq -r '.contexts.application.beans.auditEventPersister.resource // "MISSING"')
if echo "$RESOURCE" | grep -qE 'CGLIB|EnhancerBySpringCGLIB'; then
    pass "AuditEventPersister is proxied: $RESOURCE"
else
    fail "AuditEventPersister NOT proxied: $RESOURCE — @Transactional is inert"
fi

# --------------------------------------------------------------------------
# Step 3 — RLS enforcement smoke: fabt_app cannot see other tenants
# Requires a seeded 2-tenant test DB. Skipped if <2 tenants or <2 rows
# (distinct-count=0 used to PASS; now an explicit SKIP when insufficient
# data exists to actually prove anything).
#
# Uses a single explicit BEGIN/COMMIT so RESET ROLE runs even on error
# (prior version leaked SET ROLE into the pooled connection on failure).
# --------------------------------------------------------------------------
step "Step 3 — RLS enforces cross-tenant isolation on audit_events"
TENANT_COUNT=$(psql "$PG_OWNER_URL" -tAc "SELECT COUNT(*) FROM tenant")
AUDIT_TENANT_COUNT=$(psql "$PG_OWNER_URL" -tAc "SELECT COUNT(DISTINCT tenant_id) FROM audit_events WHERE tenant_id IS NOT NULL")
if [[ "$TENANT_COUNT" -lt 2 ]]; then
    echo "  SKIP — need >=2 tenants seeded (have $TENANT_COUNT)"
elif [[ "$AUDIT_TENANT_COUNT" -lt 2 ]]; then
    echo "  SKIP — need >=2 distinct tenant_ids in audit_events (have $AUDIT_TENANT_COUNT);"
    echo "         drive an audit event under each tenant before this step can verify isolation"
else
    T1=$(psql "$PG_OWNER_URL" -tAc "SELECT id FROM tenant LIMIT 1")
    # Single transaction with explicit begin/commit so RESET ROLE always runs.
    # ON_ERROR_ROLLBACK ensures any statement error aborts to a clean state
    # instead of leaving the connection role-switched.
    T1_ONLY=$(psql "$PG_OWNER_URL" -v ON_ERROR_ROLLBACK=on -v ON_ERROR_STOP=1 -tAc "
        BEGIN;
        SET LOCAL ROLE fabt_app;
        SELECT set_config('app.tenant_id', '$T1', true);
        SELECT COUNT(DISTINCT tenant_id)
        FROM audit_events
        WHERE tenant_id IS NOT NULL;
        COMMIT;
    " | tail -1)
    if [[ "$T1_ONLY" == "1" ]]; then
        pass "fabt_app session bound to T1 sees only T1 rows (distinct-count=1)"
    else
        fail "fabt_app session bound to T1 sees $T1_ONLY distinct tenants — RLS broken"
    fi
fi

# --------------------------------------------------------------------------
# Step 4 — WARN log signal check (should be zero under healthy request mix)
# Supports both journalctl (systemd deploys) AND docker compose (current
# Oracle VM reality — we run backend via docker compose, not systemd).
# --------------------------------------------------------------------------
step "Step 4 — No 'published without TenantContext bound' WARNs in last 5m"
WARN_LOG_SOURCE=""
if command -v docker &>/dev/null && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^fabt-backend$'; then
    WARN_LOG_SOURCE="docker"
    WARN_COUNT=$(docker logs --since 5m fabt-backend 2>&1 | \
        grep -c "published without TenantContext bound" || true)
elif command -v journalctl &>/dev/null && systemctl is-active --quiet fabt-backend 2>/dev/null; then
    WARN_LOG_SOURCE="journalctl"
    WARN_COUNT=$(journalctl -u fabt-backend --since "5 minutes ago" | \
        grep -c "published without TenantContext bound" || true)
fi

if [[ -n "$WARN_LOG_SOURCE" ]]; then
    if [[ "$WARN_COUNT" == "0" ]]; then
        pass "0 unbound-context WARNs in the last 5 minutes ($WARN_LOG_SOURCE)"
    else
        fail "$WARN_COUNT unbound-context WARNs ($WARN_LOG_SOURCE) — publisher missing TenantContext.runWithContext"
    fi
else
    echo "  SKIP — neither 'docker fabt-backend' container nor 'fabt-backend' systemd service found"
fi

# --------------------------------------------------------------------------
# Step 5 — Prometheus counters exist (D62)
# --------------------------------------------------------------------------
step "Step 5 — fabt.audit.system_insert.count + rls_rejected.count metrics registered"
METRICS=$(curl -s -u "${ACTUATOR_USER}:${ACTUATOR_PASSWORD}" "$ACTUATOR_URL/actuator/prometheus" 2>/dev/null || echo "")
if echo "$METRICS" | grep -q "fabt_audit_system_insert_count"; then
    pass "fabt_audit_system_insert_count metric present"
else
    fail "fabt_audit_system_insert_count NOT registered — counter wiring broken"
fi
if echo "$METRICS" | grep -q "fabt_audit_rls_rejected_count"; then
    pass "fabt_audit_rls_rejected_count metric present"
else
    fail "fabt_audit_rls_rejected_count NOT registered — counter wiring broken"
fi

# --------------------------------------------------------------------------
echo
if [[ "$FAIL" == "0" ]]; then
    echo "PASS: Phase B audit-path smoke — v0.43 tag eligible"
    exit 0
else
    echo "FAIL: Phase B audit-path smoke — do NOT tag v0.43"
    exit 1
fi
