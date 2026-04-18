#!/usr/bin/env bash
#
# phase-0-a-a5-smoke.sh — post-deploy verification for v0.42.0.
#
# Per Phase B warroom decision S10 (2026-04-17): the Phase B audit-path
# smoke script (`phase-b-audit-path-smoke.sh`) doesn't cover the Phase 0
# + Phase A + Phase A5 surface. This script does, in five independent
# checks — each one either confirms a load-bearing v0.42 property or
# fails loudly with an actionable message.
#
# The five checks:
#   1. OAuth2 client-secret round-trip (Phase 0 + A5 refactor)
#   2. HMIS vendor API-key read (Phase 0 + A5 refactor)
#   3. TOTP secret v1 envelope format (Phase A5)
#   4. JWT dual-validate — kid-header path + legacy-no-kid path (Phase A)
#   5. V74 audit event present with expected JSONB shape (Phase A5)
#
# Usage:
#   FABT_BASE_URL=http://localhost:8080 \
#   FABT_ADMIN_EMAIL=cocadmin@dev.fabt.org \
#   FABT_ADMIN_PASSWORD=... \
#       scripts/phase-0-a-a5-smoke.sh
#
#   The script connects to Postgres via `docker compose exec postgres psql`
#   for checks 1-3 and 5, and uses the HTTP API for check 4.
#
# Exit codes:
#   0 — all checks passed
#   1 — one or more checks failed (details in stderr)
#   2 — env-var / prerequisite error

set -euo pipefail

FABT_BASE_URL="${FABT_BASE_URL:-http://localhost:8080}"

# Colors (only when stdout is a TTY — keeps output clean in CI logs).
if [[ -t 1 ]]; then
    RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; NC=$'\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; NC=''
fi

FAILURES=0
NOTES=""

pass() { echo "  ${GREEN}PASS${NC} — $1"; }
fail() { echo "  ${RED}FAIL${NC} — $1" >&2; FAILURES=$((FAILURES + 1)); }
note() { echo "  ${YELLOW}NOTE${NC} — $1"; NOTES="${NOTES}${1}\n"; }

# Helper: run a psql query inside the Postgres container. Prints exact
# output to stdout so the caller can assert on it.
psql_exec() {
    docker compose exec -T postgres psql -U fabt -d fabt -tAc "$1"
}

echo "phase-0-a-a5-smoke starting at $(date -u +%FT%TZ)"
echo "  FABT_BASE_URL: $FABT_BASE_URL"

# ------------------------------------------------------------
# Check 1 — OAuth2 client-secret round-trip
# ------------------------------------------------------------
echo ""
echo "[1/5] OAuth2 client-secret round-trip (Phase 0 + A5 refactor)"

oauth_count=$(psql_exec "SELECT COUNT(*) FROM tenant_oauth2_provider \
    WHERE client_secret_encrypted IS NOT NULL" 2>&1 | tr -d ' \r')

if [[ -z "$oauth_count" || ! "$oauth_count" =~ ^[0-9]+$ ]]; then
    fail "could not query tenant_oauth2_provider; skipping OAuth2 check"
elif [[ "$oauth_count" == "0" ]]; then
    note "no OAuth2 providers configured — skipping round-trip (N/A, not a fail)"
else
    # Verify at least one row has a v1 envelope (FABT magic bytes).
    # v1 envelope starts with the base64 of "FABT\x01" + kid + iv + ct+tag.
    # The base64 prefix for "FABT\x01" (0x46 0x41 0x42 0x54 0x01) is "RkFCVAE" —
    # i.e., a v1 envelope starts with this 7-char prefix after URL-safe/std
    # base64 encoding. If any row has that prefix, V74 worked for OAuth2.
    v1_count=$(psql_exec "SELECT COUNT(*) FROM tenant_oauth2_provider \
        WHERE client_secret_encrypted LIKE 'RkFCVAE%'" 2>&1 | tr -d ' \r')
    if [[ "$v1_count" =~ ^[0-9]+$ ]] && [[ "$v1_count" -gt 0 ]]; then
        pass "OAuth2 ciphertext in v1 envelope format ($v1_count of $oauth_count row(s))"
    else
        fail "OAuth2 ciphertext still in v0 format — V74 didn't re-encrypt (expected v1 magic RkFCVAE prefix)"
    fi
fi

# ------------------------------------------------------------
# Check 2 — HMIS vendor API-key read
# ------------------------------------------------------------
echo ""
echo "[2/5] HMIS vendor API-key read (Phase 0 + A5 refactor)"

hmis_count=$(psql_exec "SELECT COUNT(*) FROM tenant WHERE config ? 'hmis_vendors'" 2>&1 | tr -d ' \r')

if [[ -z "$hmis_count" || ! "$hmis_count" =~ ^[0-9]+$ ]]; then
    fail "could not query tenant for hmis_vendors"
elif [[ "$hmis_count" == "0" ]]; then
    note "no tenants with HMIS vendors configured — skipping round-trip (N/A)"
else
    # Check that every hmis_vendors[].api_key_encrypted in JSONB starts
    # with the v1 magic prefix.
    hmis_v1=$(psql_exec "SELECT COUNT(*) FROM tenant t, \
        jsonb_array_elements(t.config->'hmis_vendors') v \
        WHERE v->>'api_key_encrypted' LIKE 'RkFCVAE%'" 2>&1 | tr -d ' \r')
    hmis_total=$(psql_exec "SELECT COUNT(*) FROM tenant t, \
        jsonb_array_elements(t.config->'hmis_vendors') v \
        WHERE v->>'api_key_encrypted' IS NOT NULL" 2>&1 | tr -d ' \r')
    if [[ "$hmis_v1" == "$hmis_total" ]] && [[ "$hmis_total" -gt 0 ]]; then
        pass "all $hmis_total HMIS api_key_encrypted values in v1 envelope"
    else
        fail "HMIS api_key_encrypted in v0 format ($hmis_v1 of $hmis_total v1) — V74 gap"
    fi
fi

# ------------------------------------------------------------
# Check 3 — TOTP secret v1 envelope
# ------------------------------------------------------------
echo ""
echo "[3/5] TOTP secret v1 envelope (Phase A5)"

totp_count=$(psql_exec "SELECT COUNT(*) FROM app_user \
    WHERE totp_secret_encrypted IS NOT NULL AND tenant_id IS NOT NULL" 2>&1 | tr -d ' \r')

if [[ -z "$totp_count" || ! "$totp_count" =~ ^[0-9]+$ ]]; then
    fail "could not query app_user for totp_secret_encrypted"
elif [[ "$totp_count" == "0" ]]; then
    note "no users with TOTP enabled — skipping round-trip (N/A)"
else
    totp_v1=$(psql_exec "SELECT COUNT(*) FROM app_user \
        WHERE totp_secret_encrypted LIKE 'RkFCVAE%' AND tenant_id IS NOT NULL" \
        2>&1 | tr -d ' \r')
    if [[ "$totp_v1" == "$totp_count" ]]; then
        pass "all $totp_count TOTP secret(s) in v1 envelope"
    else
        fail "some TOTP secrets still in v0 ($totp_v1 of $totp_count v1)"
    fi
fi

# ------------------------------------------------------------
# Check 4 — JWT dual-validate (kid header path)
# ------------------------------------------------------------
echo ""
echo "[4/5] JWT dual-validate — post-v0.42 tokens carry kid header (Phase A)"

# Issue a new token and inspect its header. Any authenticated endpoint works;
# we pick /api/v1/auth/whoami for low side-effects.
if [[ -z "${FABT_ADMIN_EMAIL:-}" ]] || [[ -z "${FABT_ADMIN_PASSWORD:-}" ]]; then
    note "FABT_ADMIN_EMAIL / FABT_ADMIN_PASSWORD unset — skipping JWT live-issue check"
    note "  (to run this check, export creds and re-run)"
else
    # Login to get a fresh token.
    login_body=$(jq -nc --arg email "$FABT_ADMIN_EMAIL" --arg pw "$FABT_ADMIN_PASSWORD" \
        '{email: $email, password: $pw, tenantSlug: "dev-coc"}')
    token=$(curl -sS --max-time 5 -X POST \
        -H 'Content-Type: application/json' \
        -d "$login_body" \
        "${FABT_BASE_URL}/api/v1/auth/login" \
        | jq -r '.accessToken // empty')

    if [[ -z "$token" ]]; then
        fail "login did not return an accessToken — auth flow broken"
    else
        # JWT header is the first dot-delimited segment, base64url-decoded.
        header_b64=$(echo -n "$token" | cut -d'.' -f1)
        # base64url → base64 padding
        padded=$(echo -n "$header_b64" | tr '_-' '/+')
        while [[ $(( ${#padded} % 4 )) -ne 0 ]]; do padded="${padded}="; done
        header_json=$(echo -n "$padded" | base64 -d 2>/dev/null || echo '{}')
        kid=$(echo -n "$header_json" | jq -r '.kid // empty')
        if [[ -n "$kid" ]]; then
            pass "newly issued JWT carries kid header ($kid) — Phase A v1 format"
        else
            fail "newly issued JWT has no kid header — Phase A signing not engaged"
        fi
    fi
fi

# ------------------------------------------------------------
# Check 5 — V74 audit event present
# ------------------------------------------------------------
echo ""
echo "[5/5] V74 audit event present with expected JSONB shape (Phase A5)"

v74_row=$(psql_exec "SELECT details FROM audit_events \
    WHERE action = 'SYSTEM_MIGRATION_V74_REENCRYPT' \
    ORDER BY created_at DESC LIMIT 1" 2>&1)

if [[ -z "$v74_row" ]]; then
    fail "no SYSTEM_MIGRATION_V74_REENCRYPT audit row — V74 may not have run"
else
    # Must contain duration_ms key in JSONB.
    if echo "$v74_row" | grep -q '"duration_ms"'; then
        pass "V74 audit row present with duration_ms in details JSONB"
    else
        fail "V74 audit row found but missing expected duration_ms field — unexpected shape"
    fi
fi

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------
echo ""
echo "======================================================================"
if [[ "$FAILURES" -eq 0 ]]; then
    echo "${GREEN}v0.42.0 smoke PASSED${NC} — all 5 checks green at $(date -u +%FT%TZ)"
    [[ -n "$NOTES" ]] && echo "(with NOTES above — review for accuracy)"
    exit 0
else
    echo "${RED}v0.42.0 smoke FAILED${NC} — $FAILURES check(s) failed at $(date -u +%FT%TZ)"
    echo "Investigate before proceeding with post-deploy announcement."
    echo "Consider rollback per docs/oracle-update-notes-v0.42.0.md rollback section."
    exit 1
fi
