#!/usr/bin/env bash
# Phase B audit-path pre-tag smoke test (Sam warroom).
#
# Run against a v0.43 candidate build pointing at a throwaway Postgres
# (e.g., docker compose up + run the backend JAR against it). MUST pass
# all 5 steps before tagging v0.43.
#
# Per design-b-rls-hardening §D58: release notes require a documented
# successful rehearsal run + commit hash + operator signature before the
# tag publishes.
#
# Assumptions:
#   - FABT_BACKEND_URL (default: http://localhost:8080)
#   - FABT_ACTUATOR_URL (default: http://localhost:9091)
#   - FABT_PG_OWNER_URL (default: postgresql://fabt:***@localhost:5432/fabt)
#   - psql on PATH
#   - jq on PATH

set -euo pipefail

BACKEND_URL="${FABT_BACKEND_URL:-http://localhost:8080}"
ACTUATOR_URL="${FABT_ACTUATOR_URL:-http://localhost:9091}"
PG_OWNER_URL="${FABT_PG_OWNER_URL:-postgresql://fabt:CHANGEME@localhost:5432/fabt}"

FAIL=0
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAIL=1; }
step() { echo; echo "=== $* ==="; }

# --------------------------------------------------------------------------
# Step 1 — audit rows all have tenant_id populated
# Proves: no orphan INSERT silently succeeded with tenant_id=NULL (pre-D55
# behavior); no recent ERROR-swallowed INSERT failure.
# --------------------------------------------------------------------------
step "Step 1 — Every recent audit row has tenant_id populated (no NULLs)"
NULLS=$(psql "$PG_OWNER_URL" -tAc "
    SELECT COUNT(*)
    FROM audit_events
    WHERE timestamp > now() - interval '10 minutes'
      AND tenant_id IS NULL
")
if [[ "$NULLS" == "0" ]]; then
    pass "0 NULL-tenant_id rows in the last 10 minutes"
else
    fail "$NULLS audit rows with tenant_id=NULL — D55 sentinel path broken"
fi

# --------------------------------------------------------------------------
# Step 2 — AuditEventPersister bean is proxied (Bug A+D regression guard)
# --------------------------------------------------------------------------
step "Step 2 — AuditEventPersister is a Spring proxy (@Transactional live)"
RESOURCE=$(curl -s -u actuator:CHANGEME "$ACTUATOR_URL/actuator/beans" | \
    jq -r '.contexts.application.beans.auditEventPersister.resource // "MISSING"')
if echo "$RESOURCE" | grep -qE 'CGLIB|EnhancerBySpringCGLIB'; then
    pass "AuditEventPersister is proxied: $RESOURCE"
else
    fail "AuditEventPersister NOT proxied: $RESOURCE — @Transactional is inert"
fi

# --------------------------------------------------------------------------
# Step 3 — RLS enforcement smoke: fabt_app cannot see other tenants
# Requires a seeded 2-tenant test DB. Skipped if <2 tenants present.
# --------------------------------------------------------------------------
step "Step 3 — RLS enforces cross-tenant isolation on audit_events"
TENANT_COUNT=$(psql "$PG_OWNER_URL" -tAc "SELECT COUNT(*) FROM tenant")
if [[ "$TENANT_COUNT" -lt 2 ]]; then
    echo "  SKIP — need >=2 tenants seeded (have $TENANT_COUNT)"
else
    T1=$(psql "$PG_OWNER_URL" -tAc "SELECT id FROM tenant LIMIT 1")
    T1_ONLY=$(psql "$PG_OWNER_URL" -tAc "
        SET ROLE fabt_app;
        SELECT set_config('app.tenant_id', '$T1', false);
        SELECT COUNT(DISTINCT tenant_id)
        FROM audit_events
        WHERE tenant_id IS NOT NULL;
        RESET ROLE;
    ")
    if [[ "$T1_ONLY" == "1" ]] || [[ "$T1_ONLY" == "0" ]]; then
        pass "fabt_app session bound to T1 sees only T1 rows (distinct-count=$T1_ONLY)"
    else
        fail "fabt_app session bound to T1 sees $T1_ONLY distinct tenants — RLS broken"
    fi
fi

# --------------------------------------------------------------------------
# Step 4 — WARN log signal check (should be zero under healthy request mix)
# --------------------------------------------------------------------------
step "Step 4 — No 'published without TenantContext bound' WARNs in last 5m"
if command -v journalctl &>/dev/null && systemctl is-active --quiet fabt-backend 2>/dev/null; then
    WARN_COUNT=$(journalctl -u fabt-backend --since "5 minutes ago" | \
        grep -c "published without TenantContext bound" || true)
    if [[ "$WARN_COUNT" == "0" ]]; then
        pass "0 unbound-context WARNs in the last 5 minutes"
    else
        fail "$WARN_COUNT unbound-context WARNs — publisher missing TenantContext.runWithContext"
    fi
else
    echo "  SKIP — journalctl / fabt-backend service not available"
fi

# --------------------------------------------------------------------------
# Step 5 — Prometheus counters exist (D62)
# --------------------------------------------------------------------------
step "Step 5 — fabt.audit.system_insert.count + rls_rejected.count metrics registered"
METRICS=$(curl -s "$ACTUATOR_URL/actuator/prometheus" 2>/dev/null || echo "")
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
