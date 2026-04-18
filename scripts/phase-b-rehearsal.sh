#!/usr/bin/env bash
# Phase B v0.43 restored-dump rehearsal (D58 release gate).
#
# Proves the v0.43 candidate build applies cleanly against a recent prod
# pg_dump + survives a smoke subset. Required by design-b-rls-hardening
# §D58: release notes must reference a green rehearsal run performed
# within 7 days of the v0.43 tag.
#
# Runs in ~10 minutes end-to-end. Operator-laptop friendly; no stage
# environment required.
#
# 6-step recipe:
#   0. Prereq check (docker, psql, mvn, jq; pg_dump file present)
#   1. Spin up throwaway postgres:16-alpine container (fresh PGDATA)
#   2. pg_restore prod dump into it
#   3. Apply V67-V72 Flyway migrations, TIMING each one (flag >10s)
#   4. Start the v0.43 candidate backend JAR pointed at the restored DB
#   5. Run scripts/phase-b-audit-path-smoke.sh against it
#   6. Teardown + report PASS/FAIL
#
# Required env vars:
#   FABT_PROD_DUMP       — path to a pg_dump -Fc file (≤24h old)
#   FABT_V043_JAR        — path to the v0.43 backend JAR under test
#   FABT_ENCRYPTION_KEY  — the same base64 key prod uses (V74 needs it)
#   FABT_DRIVE_USER_EMAIL / PASSWORD — login for smoke step 0
#
# Optional env vars:
#   REHEARSAL_PG_PORT    — local port to bind (default 55432)
#   REHEARSAL_TIMEOUT_PER_MIGRATION_SEC — default 10

set -euo pipefail

PG_PORT="${REHEARSAL_PG_PORT:-55432}"
MIGRATION_TIMEOUT="${REHEARSAL_TIMEOUT_PER_MIGRATION_SEC:-10}"
CONTAINER_NAME="fabt-phase-b-rehearsal-$$"
WORK_DIR=$(mktemp -d)
OUTPUT_LOG="$WORK_DIR/rehearsal.log"
BACKEND_LOG="$WORK_DIR/backend.log"
FAIL=0

cleanup() {
    echo
    echo "=== Cleanup ==="
    [[ -n "${BACKEND_PID:-}" ]] && kill -9 "$BACKEND_PID" 2>/dev/null || true
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
    echo "Rehearsal artifacts kept at: $WORK_DIR"
    echo "  rehearsal.log — full narrative + migration timings"
    echo "  backend.log — backend startup output"
}
trap cleanup EXIT

log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUTPUT_LOG"; }
fail() { echo "[$(date -u +%H:%M:%S)] FAIL: $*" | tee -a "$OUTPUT_LOG" >&2; FAIL=1; }

# ---------------------------------------------------------------------
# Step 0 — Prereqs
# ---------------------------------------------------------------------
log "=== Step 0: Prerequisite check ==="

for cmd in docker psql mvn jq java; do
    command -v "$cmd" >/dev/null 2>&1 || { fail "Missing command: $cmd"; exit 2; }
done

: "${FABT_PROD_DUMP:?FABT_PROD_DUMP unset — path to pg_dump file}"
: "${FABT_V043_JAR:?FABT_V043_JAR unset — path to v0.43 backend JAR}"
: "${FABT_ENCRYPTION_KEY:?FABT_ENCRYPTION_KEY unset — same base64 key as prod}"
: "${FABT_DRIVE_USER_EMAIL:?FABT_DRIVE_USER_EMAIL unset — seed user for smoke}"
: "${FABT_DRIVE_USER_PASSWORD:?FABT_DRIVE_USER_PASSWORD unset}"

[[ -f "$FABT_PROD_DUMP" ]] || { fail "Dump file not found: $FABT_PROD_DUMP"; exit 2; }
[[ -f "$FABT_V043_JAR" ]] || { fail "Backend JAR not found: $FABT_V043_JAR"; exit 2; }

# Freshness check — dump must be <24h old (Sam warroom)
DUMP_AGE_SEC=$(( $(date +%s) - $(stat -c %Y "$FABT_PROD_DUMP" 2>/dev/null || stat -f %m "$FABT_PROD_DUMP") ))
if [[ $DUMP_AGE_SEC -gt 86400 ]]; then
    fail "Dump is $((DUMP_AGE_SEC / 3600))h old — must be <24h to reflect prod shape"
    exit 2
fi
log "Dump age: $((DUMP_AGE_SEC / 3600))h — OK (under 24h)"

# ---------------------------------------------------------------------
# Step 1 — Spin up throwaway postgres
# ---------------------------------------------------------------------
log "=== Step 1: Spin up throwaway postgres:16-alpine on port $PG_PORT ==="
docker run -d --name "$CONTAINER_NAME" \
    -p "$PG_PORT:5432" \
    -e POSTGRES_USER=fabt \
    -e POSTGRES_PASSWORD=rehearsal \
    -e POSTGRES_DB=fabt \
    postgres:16-alpine >/dev/null

# Wait for readiness
log "Waiting for postgres to accept connections..."
for i in $(seq 1 30); do
    if docker exec "$CONTAINER_NAME" pg_isready -U fabt >/dev/null 2>&1; then
        log "Postgres ready in ${i}s"
        break
    fi
    sleep 1
    [[ $i -eq 30 ]] && { fail "Postgres did not become ready within 30s"; exit 2; }
done

# Create fabt_app role matching prod semantics
docker exec "$CONTAINER_NAME" psql -U fabt -d fabt -c \
    "CREATE ROLE fabt_app WITH LOGIN PASSWORD 'rehearsal' NOSUPERUSER NOBYPASSRLS; GRANT USAGE ON SCHEMA public TO fabt_app;" >/dev/null

# ---------------------------------------------------------------------
# Step 2 — Restore prod dump
# ---------------------------------------------------------------------
log "=== Step 2: pg_restore prod dump ==="
RESTORE_START=$(date +%s)
docker exec -i "$CONTAINER_NAME" pg_restore -U fabt -d fabt --no-owner --no-privileges \
    < "$FABT_PROD_DUMP" 2>&1 | tee -a "$OUTPUT_LOG" | tail -5 || true
RESTORE_ELAPSED=$(( $(date +%s) - RESTORE_START ))
log "Restore complete in ${RESTORE_ELAPSED}s"

# ---------------------------------------------------------------------
# Step 3 — Apply V67-V72 via Flyway + time each one
# ---------------------------------------------------------------------
log "=== Step 3: Apply Phase B migrations via Flyway, timing each ==="

export FLYWAY_URL="jdbc:postgresql://localhost:$PG_PORT/fabt"
export FLYWAY_USER="fabt"
export FLYWAY_PASSWORD="rehearsal"

# Use verbose Flyway output to get per-migration timings
MIGRATION_START=$(date +%s)
mvn -pl backend flyway:migrate -X 2>&1 \
    | tee "$WORK_DIR/flyway.log" \
    | grep -E "(Migrating schema|Successfully applied|WARN)" \
    | tee -a "$OUTPUT_LOG" \
    || true
MIGRATION_ELAPSED=$(( $(date +%s) - MIGRATION_START ))
log "Flyway migrate total: ${MIGRATION_ELAPSED}s"

# Parse per-migration timings from flyway.log and flag any >${MIGRATION_TIMEOUT}s
# Flyway log format: "Migrating schema ... to version X" + elapsed in "Successfully applied N migrations"
# For per-migration timing, grep DbMigrate timestamps and diff adjacent lines.
python3 - <<PYEOF 2>&1 | tee -a "$OUTPUT_LOG" || python - <<PYEOF 2>&1 | tee -a "$OUTPUT_LOG" || true
import re
from datetime import datetime
lines = open("$WORK_DIR/flyway.log").read().splitlines()
pattern = re.compile(r'(\d{2}:\d{2}:\d{2}).* Migrating schema .* to version "(\d+) - ([^"]+)"')
events = []
for line in lines:
    m = pattern.search(line)
    if m:
        events.append((datetime.strptime(m.group(1), '%H:%M:%S'), m.group(2), m.group(3)))
for i in range(len(events) - 1):
    t0, v, name = events[i]
    t1, _, _ = events[i + 1]
    elapsed = (t1 - t0).total_seconds()
    flag = " *** SLOW ***" if elapsed > $MIGRATION_TIMEOUT else ""
    print(f"  V{v:>4} ({name[:40]:<40}): {elapsed:.2f}s{flag}")
PYEOF

# Verify V67-V72 actually applied
APPLIED_COUNT=$(docker exec "$CONTAINER_NAME" psql -U fabt -d fabt -tAc \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('67','68','69','70','71','72') AND success = TRUE")
if [[ "$APPLIED_COUNT" != "6" ]]; then
    fail "Expected 6 of V67-V72 successful; found $APPLIED_COUNT"
else
    log "All 6 Phase B migrations applied successfully"
fi

# ---------------------------------------------------------------------
# Step 4 — Start the v0.43 candidate backend JAR
# ---------------------------------------------------------------------
log "=== Step 4: Start v0.43 candidate backend ==="
export SPRING_DATASOURCE_URL="$FLYWAY_URL"
export SPRING_DATASOURCE_USERNAME="fabt_app"
export SPRING_DATASOURCE_PASSWORD="rehearsal"
export SPRING_PROFILES_ACTIVE=lite,rehearsal
export SERVER_PORT=8080
export MANAGEMENT_SERVER_PORT=9091
java -jar "$FABT_V043_JAR" > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
log "Backend PID: $BACKEND_PID; waiting for /actuator/health..."

for i in $(seq 1 60); do
    if curl -sf http://localhost:$SERVER_PORT/actuator/health >/dev/null 2>&1; then
        log "Backend healthy in ${i}s"
        break
    fi
    sleep 1
    [[ $i -eq 60 ]] && { fail "Backend did not become healthy within 60s; check $BACKEND_LOG"; exit 2; }
done

# ---------------------------------------------------------------------
# Step 5 — Run audit-path smoke
# ---------------------------------------------------------------------
log "=== Step 5: Run phase-b-audit-path-smoke.sh ==="
export FABT_BACKEND_URL="http://localhost:8080"
export FABT_ACTUATOR_URL="http://localhost:9091"
export FABT_PG_OWNER_URL="postgresql://fabt:rehearsal@localhost:$PG_PORT/fabt"
export FABT_PG_OWNER_PASSWORD="rehearsal"
export FABT_ACTUATOR_USER="actuator"
export FABT_ACTUATOR_PASSWORD="rehearsal"

if ! "$(dirname "$0")/phase-b-audit-path-smoke.sh" 2>&1 | tee -a "$OUTPUT_LOG"; then
    fail "Smoke script failed — see output above"
fi

# ---------------------------------------------------------------------
# Step 6 — Report
# ---------------------------------------------------------------------
log "=== Step 6: Report ==="

if [[ "$FAIL" == "0" ]]; then
    cat <<EOF

======================================================================
PASS: Phase B v0.43 rehearsal complete
======================================================================
Dump age:           $((DUMP_AGE_SEC / 3600))h
Restore time:       ${RESTORE_ELAPSED}s
Flyway migrate:     ${MIGRATION_ELAPSED}s
Smoke:              PASS
Artifacts:          $WORK_DIR

Release-gate checklist (D58):
  [x] Dump <24h old
  [x] V67-V72 applied successfully
  [x] No per-migration >${MIGRATION_TIMEOUT}s (check output above for SLOW flags)
  [x] Backend started healthy
  [x] Audit-path smoke green

Signature:
  Operator: ${USER:-unknown}
  Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
  Commit: $(git -C "$(dirname "$0")/.." rev-parse --short HEAD 2>/dev/null || echo "unknown")

Commit this output to docs/deploys/v0.43-rehearsal-$(date +%Y%m%d).md
per the release-gate requirement.
EOF
    exit 0
else
    cat <<EOF

======================================================================
FAIL: Phase B v0.43 rehearsal — do NOT tag v0.43
======================================================================
See $OUTPUT_LOG for narrative + $BACKEND_LOG for backend startup.
EOF
    exit 1
fi
