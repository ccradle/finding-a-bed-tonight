#!/usr/bin/env bash
# deploy-rehearsal.sh — Operator-laptop prod-mirror rehearsal gate.
#
# Mirrors the EXACT prod deploy flow against a local docker-compose stack using
# stub credentials. Run this BEFORE tagging any release. A green run is required
# within 72h of a tag (see deploy/release-gate-pins.txt).
#
# Usage:
#   make rehearse-deploy           # Standard invocation
#   bash scripts/deploy-rehearsal.sh    # Direct invocation
#
# Optional env vars:
#   REHEARSAL_SKIP_BUILD=1         Skip mvn + docker build if JAR is fresh
#   REHEARSAL_REAL_EMAIL=1         DANGER: send to real Gmail (requires
#                                  REHEARSAL_SMTP_PASSWORD to be a real app pwd)
#
# Prerequisites (most already present from regular dev work):
#   docker, docker compose v5+, mvn, node + npx playwright, jq, envsubst, python3
#
# What this catches (~80% of the v0.49 issue class):
#   v0.49 #1  Trailing-space env var (KEY= value bash parsing trap)
#   v0.49 #2  Container UID vs host file perm mismatch (alertmanager 65534/0600)
#   v0.49 #3  Template-engine-incompatible functions (Sprig in alertmanager tmpl)
#   v0.49 #4  Missing service recreate (frontend not recreated with backend)
#   v0.49 #6  Bind-mount inode pitfall (prometheus.yml edit → --force-recreate)
#   v0.49 #8  Wrong actuator URL (public :8080 vs management :9091)
#
# What this does NOT catch:
#   v0.49 #5  VM-side git checkout working-tree drift (stateful-VM-only)
#   v0.49 #7  Cloudflare 429s on /api/v1/version (CDN-side rate limit)
#   v0.49 #9  Real Gmail SMTP deliverability or ntfy.sh push reliability
#   v0.49 #10 SSH tunnel / bastion auth issues

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARTIFACT_DIR="/tmp/deploy-rehearsal-${TIMESTAMP}"
LOG="$ARTIFACT_DIR/rehearsal.log"
COMPOSE_PROJECT="fabt-rehearsal"
ENV_FILE="$REPO_ROOT/.env.rehearsal"
NTFY_STUB_PID=""
FAIL=0
FAIL_GATE=""

# ── Colour helpers ─────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

mkdir -p "$ARTIFACT_DIR"

log()  { local msg="[$(date -u +%H:%M:%S)] $*"; echo -e "${BLUE}[REHEARSAL]${NC} $*"; echo "$msg" >> "$LOG"; }
ok()   { local msg="[$(date -u +%H:%M:%S)] ✓ $*"; echo -e "${GREEN}[REHEARSAL]${NC} ✓ $*"; echo "$msg" >> "$LOG"; }
warn() { local msg="[$(date -u +%H:%M:%S)] WARN: $*"; echo -e "${YELLOW}[REHEARSAL]${NC} WARN: $*"; echo "$msg" >> "$LOG"; }
fail() {
    local msg="[$(date -u +%H:%M:%S)] FAIL [$1]: $2"
    echo -e "${RED}[REHEARSAL]${NC} FAIL [$1]: $2" >&2
    echo "$msg" >> "$LOG"
    FAIL=1
    FAIL_GATE="${FAIL_GATE}${1} "
}

# ── Compose helper ─────────────────────────────────────────────────────────────
rehearsal_compose() {
    docker compose \
        -f "$REPO_ROOT/docker-compose.yml" \
        -f "$REPO_ROOT/deploy/rehearsal-prod-overlay.yml" \
        --env-file "$ENV_FILE" \
        -p "$COMPOSE_PROJECT" \
        "$@"
}

# ── Cleanup / teardown ─────────────────────────────────────────────────────────
cleanup() {
    log "=== Teardown ==="
    # Stop ntfy stub if running
    if [[ -n "$NTFY_STUB_PID" ]] && kill -0 "$NTFY_STUB_PID" 2>/dev/null; then
        kill "$NTFY_STUB_PID" 2>/dev/null || true
        log "ntfy stub stopped (PID $NTFY_STUB_PID)"
    fi
    # Stop rehearsal containers
    rehearsal_compose down --volumes --remove-orphans 2>>"$LOG" || true
    log "Rehearsal artifacts preserved at: $ARTIFACT_DIR"
    log "  rehearsal.log     — full narrative"
    log "  smoke/            — Playwright trace + log (if smoke ran)"
}
trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════════
log "╔══════════════════════════════════════════════════════════════╗"
log "║     Finding A Bed Tonight — Deploy Rehearsal Harness        ║"
log "║     Run: $TIMESTAMP                              ║"
log "╚══════════════════════════════════════════════════════════════╝"
log ""

# ── Step 0: Prereqs ────────────────────────────────────────────────────────────
log "=== Step 0: Prerequisite check ==="
for cmd in docker mvn npx jq envsubst python3; do
    if ! command -v "$cmd" &>/dev/null; then
        fail "PREREQ" "Missing command: $cmd — install it and retry"
    else
        ok "$cmd found"
    fi
done

if ! docker info &>/dev/null; then
    fail "PREREQ" "Docker daemon not running — start Docker Desktop"
fi

if [[ ! -f "$ENV_FILE" ]]; then
    fail "PREREQ" ".env.rehearsal not found — copy deploy/rehearsal.env.example → .env.rehearsal"
fi

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }
ok "All prerequisites satisfied"

# ── Step 1: Trailing-space env-var lint (catches v0.49 issue #1) ───────────────
log "=== Step 1: Trailing-space env-var lint ==="
# Pattern: KEY= value (KEY= followed by a space then non-empty content).
# bash sources this as: set KEY empty, then run 'value' as a command → silent data loss.
BAD_LINES=$(grep -nE "^[A-Z_]+= [^ ]" "$ENV_FILE" || true)
if [[ -n "$BAD_LINES" ]]; then
    fail "ENV_LINT" "Trailing space after '=' in .env.rehearsal — bash would silently discard the value:"
    echo "$BAD_LINES" | while read -r line; do
        echo "  $line" | tee -a "$LOG" >&2
    done
    echo "  Fix: remove the space between '=' and the value (e.g. KEY=value, not KEY= value)" | tee -a "$LOG" >&2
    exit 1
fi
ok "env file format OK — no trailing-space traps"

# ── Step 2: envsubst render of alertmanager config ────────────────────────────
log "=== Step 2: envsubst render of alertmanager.yml.tmpl ==="
REHEARSAL_CONFIG_DIR="$HOME/.fabt-rehearsal"
mkdir -p "$REHEARSAL_CONFIG_DIR"
RENDERED_AM="$REHEARSAL_CONFIG_DIR/alertmanager.yml"

# Source the env file so envsubst can see the vars
set -a; source "$ENV_FILE"; set +a

# Render only the FABT_ALERT_* vars (mirror the prod runbook whitelist exactly)
envsubst '${FABT_ALERT_SMTP_HOST}${FABT_ALERT_SMTP_PORT}${FABT_ALERT_SMTP_USER}${FABT_ALERT_SMTP_PASSWORD}${FABT_ALERT_SMTP_REQUIRE_TLS}${FABT_ALERT_EMAIL_FROM}${FABT_ALERT_EMAIL_TO}${FABT_ALERT_NTFY_URL}${FABT_ALERT_NTFY_TOPIC}' \
    < "$REPO_ROOT/deploy/alertmanager.yml.tmpl" \
    > "$RENDERED_AM"

# Set 644 (not 600) so alertmanager UID 65534 can read it (v0.49 issue #2)
chmod 644 "$RENDERED_AM"
ok "alertmanager.yml rendered to $RENDERED_AM (chmod 644)"

# ── Step 3: Container UID vs host file perm check (catches v0.49 issue #2) ────
log "=== Step 3: Container UID vs host file perm verification ==="
# For each service that bind-mounts a host file:
#   alertmanager UID = 65534 (nobody) → needs read access to rendered alertmanager.yml
# Use parallel indexed arrays rather than associative arrays — bash 3.2 (macOS
# system default) does not support declare -A.
UID_CHECK_SVCS=(alertmanager prometheus)
UID_CHECK_IMAGES=(prom/alertmanager:v0.27.0 prom/prometheus:v2.51.0)
UID_CHECK_FILES=("$RENDERED_AM" "$REPO_ROOT/prometheus.yml")

for i in 0 1; do
    svc="${UID_CHECK_SVCS[$i]}"
    image="${UID_CHECK_IMAGES[$i]}"
    host_file="${UID_CHECK_FILES[$i]}"

    if [[ ! -f "$host_file" ]]; then
        warn "Host file for $svc not found: $host_file — skipping UID check"
        continue
    fi

    log "  Checking $svc ($image) against $host_file"
    CONTAINER_UID=$(docker run --rm "$image" id -u 2>/dev/null || echo "unknown")
    if [[ "$CONTAINER_UID" == "unknown" ]]; then
        warn "  Could not determine UID for $image — skipping (image pull may be needed)"
        continue
    fi

    # stat -c on Linux; stat -f on macOS — try both
    FILE_MODE=$(stat -c '%a' "$host_file" 2>/dev/null || stat -f '%Lp' "$host_file" 2>/dev/null || echo "unknown")
    FILE_OWNER=$(stat -c '%u' "$host_file" 2>/dev/null || stat -f '%u' "$host_file" 2>/dev/null || echo "unknown")

    log "  $svc: container UID=$CONTAINER_UID, file owner=$FILE_OWNER, mode=$FILE_MODE"

    # Check: if container is not the owner AND mode is 600 or 700, it can't read
    if [[ "$CONTAINER_UID" != "$FILE_OWNER" ]]; then
        MODE_WORLD="${FILE_MODE: -1}"   # last octet = world permissions
        MODE_GROUP="${FILE_MODE: -2:1}" # middle octet = group permissions
        if [[ "$MODE_WORLD" -lt 4 && "$MODE_GROUP" -lt 4 ]]; then
            fail "UID_PERM" "$svc: container UID $CONTAINER_UID cannot read $host_file (mode $FILE_MODE, owner $FILE_OWNER). Fix: chmod 644 $host_file (parent dir 700 still protects host-side access)"
        fi
    fi
    ok "  $svc: UID/perm OK"
done

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Step 4: Compose merge dry-render (catches volume conflicts, override errors) ──
log "=== Step 4: Compose merge dry-render ==="
# Use 'docker compose alpha dry-run -- up' (Compose v5 syntax; '--' separates subcommand)
# Fall back to 'compose config' if alpha subcommand is unavailable
if docker compose alpha 2>&1 | grep -q "dry-run"; then
    log "  Using docker compose alpha dry-run -- up (Compose v5)"
    if rehearsal_compose alpha dry-run -- up --profile alerting alertmanager backend frontend prometheus >> "$ARTIFACT_DIR/dry-run.log" 2>&1; then
        ok "compose dry-render OK"
    else
        fail "COMPOSE_DRY_RUN" "compose dry-render failed — see $ARTIFACT_DIR/dry-run.log"
    fi
else
    log "  'docker compose alpha dry-run' not available — falling back to compose config"
    if rehearsal_compose config --profile alerting > "$ARTIFACT_DIR/compose-config.yml" 2>&1; then
        ok "compose config rendered OK (see $ARTIFACT_DIR/compose-config.yml)"
    else
        fail "COMPOSE_CONFIG" "compose config failed — see $ARTIFACT_DIR/compose-config.yml"
    fi
fi

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Step 5: Build (honours REHEARSAL_SKIP_BUILD=1) ────────────────────────────
log "=== Step 5: Build ==="
SKIP_BUILD="${REHEARSAL_SKIP_BUILD:-0}"

if [[ "$SKIP_BUILD" == "1" ]]; then
    # Fast-iteration path: skip if JAR is fresher than last backend git commit
    LAST_COMMIT_TS=$(git -C "$REPO_ROOT" log -1 --format="%ct" -- backend/ 2>/dev/null || echo "0")
    JAR_TS=$(stat -c '%Y' "$REPO_ROOT"/backend/target/*.jar 2>/dev/null | head -1 || echo "0")
    if [[ "$JAR_TS" -gt "$LAST_COMMIT_TS" ]]; then
        log "  REHEARSAL_SKIP_BUILD=1 and JAR is fresh — skipping mvn + docker build"
    else
        warn "  REHEARSAL_SKIP_BUILD=1 but JAR is older than last backend commit — building anyway"
        SKIP_BUILD="0"
    fi
fi

if [[ "$SKIP_BUILD" != "1" ]]; then
    log "  Building backend JAR (mvn clean package -DskipTests)..."
    cd "$REPO_ROOT/backend"
    mvn clean package -DskipTests -q 2>&1 | tee -a "$ARTIFACT_DIR/maven.log"
    cd "$REPO_ROOT"
    ok "Backend JAR built"

    log "  Building backend Docker image (no-cache — per feedback_prod_docker_build_pattern.md)..."
    docker build --no-cache -f "$REPO_ROOT/infra/docker/Dockerfile.backend" -t fabt-backend:rehearsal "$REPO_ROOT" \
        2>&1 | tee -a "$ARTIFACT_DIR/docker-build-backend.log"
    ok "Backend image built: fabt-backend:rehearsal"

    log "  Building frontend Docker image (no-cache)..."
    docker build --no-cache -f "$REPO_ROOT/infra/docker/Dockerfile.frontend" -t fabt-frontend:rehearsal "$REPO_ROOT" \
        2>&1 | tee -a "$ARTIFACT_DIR/docker-build-frontend.log"
    ok "Frontend image built: fabt-frontend:rehearsal"
fi

# ── Step 6: Start ntfy stub (Python http.server) ──────────────────────────────
log "=== Step 6: Start ntfy stub ==="
NTFY_STUB_LOG="$ARTIFACT_DIR/ntfy-stub.log"
python3 -c "
import http.server, socketserver, sys

class NtfyHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8', errors='replace')
        print(f'[ntfy-stub] POST {self.path}: {body[:200]}', flush=True)
        self.send_response(200)
        self.end_headers()
    def log_message(self, fmt, *args):
        print(f'[ntfy-stub] {fmt % args}', flush=True)

with socketserver.TCPServer(('0.0.0.0', 8888), NtfyHandler) as httpd:
    print('[ntfy-stub] Listening on :8888', flush=True)
    httpd.serve_forever()
" >> "$NTFY_STUB_LOG" 2>&1 &
NTFY_STUB_PID=$!
sleep 1
if ! kill -0 "$NTFY_STUB_PID" 2>/dev/null; then
    fail "NTFY_STUB" "ntfy stub failed to start — check $NTFY_STUB_LOG"
fi
ok "ntfy stub running on :8888 (PID $NTFY_STUB_PID)"

# ── Step 7: Start with service-recreate matrix (catches v0.49 issues #4, #6) ──
log "=== Step 7: Start rehearsal stack with force-recreate ==="
# Matrix row a: backend recreate MUST trigger frontend recreate (docker network coupling)
# Matrix row b: prometheus.yml change MUST use --force-recreate (inode bind-mount pitfall)
# Both services recreated here to exercise the full matrix.
rehearsal_compose --profile alerting up -d \
    --force-recreate \
    --build=false \
    backend frontend alertmanager prometheus mailpit \
    2>&1 | tee -a "$ARTIFACT_DIR/compose-up.log"
ok "Rehearsal stack started (project: $COMPOSE_PROJECT)"

# ── Step 8: Health checks (catches v0.49 issue #8 — wrong actuator URL) ───────
log "=== Step 8: Health checks ==="
# Backend: management port 9091 (observability profile) NOT public :8080
# Ports are offset +10000 in rehearsal overlay: host:19091 → container:9091
HEALTH_TARGETS=(
    "backend|http://localhost:19091/actuator/health|90"
    "alertmanager|http://localhost:19093/-/healthy|30"
    "prometheus|http://localhost:19090/-/ready|30"
)

for target_spec in "${HEALTH_TARGETS[@]}"; do
    IFS='|' read -r svc url timeout_s <<< "$target_spec"
    log "  Waiting for $svc at $url (timeout ${timeout_s}s)..."
    elapsed=0
    until curl -sf "$url" > /dev/null 2>&1; do
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge $timeout_s ]]; then
            fail "HEALTHCHECK_$svc" "$svc did not become healthy at $url within ${timeout_s}s"
            break
        fi
        sleep 2
    done
    if [[ $FAIL -eq 0 ]] || ! echo "$FAIL_GATE" | grep -q "HEALTHCHECK_$svc"; then
        ok "$svc healthy ($elapsed s)"
    fi
done

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Step 8.5: Load dev seed data (mirrors dev-start.sh behavior) ─────────────
# seed-data.sql creates the dev-coc tenant + @dev.fabt.org users with admin123.
# These are NOT in Flyway migrations (they're dev/demo-only seed data loaded
# manually on the Oracle VM and by dev-start.sh locally). The smoke spec in
# step 10 targets dev-coc credentials, so this seed must run before smoke.
log "=== Step 8.5: Load dev seed data ==="
POSTGRES_CONTAINER="$(docker ps --filter "name=${COMPOSE_PROJECT}-postgres" --format '{{.Names}}' | head -1)"
if [[ -z "$POSTGRES_CONTAINER" ]]; then
    fail "SEED_DATA" "Could not find rehearsal postgres container"
else
    docker exec -i -e PGOPTIONS='-c fabt.seed_force=1' "$POSTGRES_CONTAINER" psql -U fabt -d fabt \
        < "$REPO_ROOT/infra/scripts/seed-data.sql" \
        >> "$ARTIFACT_DIR/seed-data.log" 2>&1 || fail "SEED_DATA" "seed-data.sql failed — see $ARTIFACT_DIR/seed-data.log"
    ok "Dev seed data loaded (dev-coc tenant + @dev.fabt.org users)"
fi

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Step 9: Synthetic alert routing (catches v0.49 issue #3 — template errors) ─
log "=== Step 9: Synthetic alert routing ==="
# amtool is bundled in the alertmanager image — no Windows install needed
ALERTMANAGER_CONTAINER="$(docker ps --filter "name=fabt-rehearsal-alertmanager" --format '{{.Names}}' | head -1)"
if [[ -z "$ALERTMANAGER_CONTAINER" ]]; then
    fail "ALERT_ROUTING" "Could not find rehearsal alertmanager container"
else
    # Use a timestamp-based alert name so each rehearsal run creates a fresh
    # notification group. Alertmanager's repeat_interval (4h) is scoped to
    # groups; if the same name is reused across runs (e.g. on a recreated
    # container whose nflog persists in the image layer), the first-dispatch
    # flag blocks re-dispatch within the window.
    # amtool v0.27.0 silently drops labels with unquoted spaces — keep values
    # to no-space strings only.
    REHEARSAL_ALERT_NAME="FabtRehearsal${TIMESTAMP//[^0-9]/}"
    log "  Firing $REHEARSAL_ALERT_NAME CRITICAL alert via docker exec amtool..."
    MSYS_NO_PATHCONV=1 docker exec "$ALERTMANAGER_CONTAINER" \
        /bin/amtool --alertmanager.url http://127.0.0.1:9093 \
        alert add "$REHEARSAL_ALERT_NAME" \
        severity=critical \
        "alertname=$REHEARSAL_ALERT_NAME" \
        2>&1 | tee -a "$ARTIFACT_DIR/amtool.log" || fail "ALERT_ROUTING" "amtool alert add failed"

    # group_wait is 15s — email dispatches at t+15s. Poll up to 60s so a
    # cold-start alertmanager (which needs a few extra seconds after /-/healthy)
    # doesn't race with the dispatch window.
    log "  Waiting for Mailpit to receive alert email (up to 60s)..."
    MAILPIT_RECEIVED=0
    for i in $(seq 1 30); do
        MSG_COUNT=$(curl -sf "http://localhost:18025/api/v1/messages" 2>/dev/null | jq '.total // 0' 2>/dev/null || true)
        if [[ "$MSG_COUNT" -gt 0 ]]; then
            MAILPIT_RECEIVED=1
            ok "Mailpit received $MSG_COUNT message(s)"
            break
        fi
        sleep 2
    done
    [[ $MAILPIT_RECEIVED -eq 0 ]] && fail "ALERT_ROUTING" "Mailpit received no messages within 60s — check alertmanager template/config (v0.49 issue #3 class)"

    # Check ntfy stub received POST
    NTFY_RECEIVED=$(grep -c "\[ntfy-stub\] POST" "$NTFY_STUB_LOG" 2>/dev/null || true)
    if [[ "$NTFY_RECEIVED" -gt 0 ]]; then
        ok "ntfy stub received $NTFY_RECEIVED POST(s)"
    else
        warn "ntfy stub received no POSTs within 30s — check alertmanager ntfy_urgent receiver config"
        # Not a hard fail — ntfy delivery depends on route config; Mailpit pass is the primary gate
    fi
fi

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Step 10: Playwright smoke against nginx proxy ─────────────────────────────
log "=== Step 10: Playwright smoke ==="
# post-deploy-smoke.spec.ts reads FABT_BASE_URL (NOT BASE_URL) directly.
# Spec lives in ./deploy/, not ./tests/ (testDir=./tests in playwright.config.ts).
# Rehearsal nginx is on port 18081.
mkdir -p "$ARTIFACT_DIR/smoke"
SMOKE_LOG="$ARTIFACT_DIR/smoke/smoke-${TIMESTAMP}.log"

log "  Running post-deploy smoke against http://localhost:18081"
cd "$REPO_ROOT/e2e/playwright"
# Temporarily disable set -e so a Playwright exit-1 doesn't trigger bash's
# early-exit before PIPESTATUS is captured and the gate message is printed.
set +e
FABT_BASE_URL=http://localhost:18081 \
    BASE_URL=http://localhost:18081 \
    npx playwright test --config=deploy/playwright.config.ts \
    post-deploy-smoke \
    --project chromium \
    --trace on \
    --reporter=list \
    --output "$ARTIFACT_DIR/smoke" \
    2>&1 | tee "$SMOKE_LOG"
SMOKE_EXIT=${PIPESTATUS[0]}
set -e
cd "$REPO_ROOT"

if [[ $SMOKE_EXIT -ne 0 ]]; then
    fail "PLAYWRIGHT_SMOKE" "Post-deploy smoke failed — see $SMOKE_LOG and $ARTIFACT_DIR/smoke/trace.zip"
else
    ok "Playwright smoke passed"
fi

[[ $FAIL -eq 1 ]] && { echo -e "${RED}REHEARSAL FAIL — DO NOT TAG${NC} (gate: $FAIL_GATE) — artifacts: $ARTIFACT_DIR" >&2; exit 1; }

# ── Final verdict ──────────────────────────────────────────────────────────────
log ""
log "═══════════════════════════════════════════════════════════════"
echo -e "${GREEN}REHEARSAL PASS — safe to tag [${TIMESTAMP}]${NC}"
echo -e "Artifacts: $ARTIFACT_DIR"
log "REHEARSAL PASS — safe to tag [$TIMESTAMP]"
log "Artifacts: $ARTIFACT_DIR"
log "NOTE: Receivers are stubbed. This run does NOT validate real-Gmail deliverability."
log "═══════════════════════════════════════════════════════════════"
