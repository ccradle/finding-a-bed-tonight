#!/usr/bin/env bash
#
# dev-start.sh — Start the full Finding A Bed Tonight development stack
#
# Usage:
#   ./dev-start.sh                        Start everything (PostgreSQL, backend, seed data, frontend)
#   ./dev-start.sh backend                Start PostgreSQL + backend only (no frontend)
#   ./dev-start.sh --observability        Full stack + Prometheus + Grafana + Jaeger + OTel Collector
#   ./dev-start.sh backend --observability  Backend + observability (no frontend)
#   ./dev-start.sh stop                   Stop all services including observability containers
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[FABT]${NC} $1"; }
warn() { echo -e "${YELLOW}[FABT]${NC} $1"; }
err()  { echo -e "${RED}[FABT]${NC} $1"; }
info() { echo -e "${BLUE}[FABT]${NC} $1"; }

# --- Parse arguments ---
BACKEND_ONLY=false
OBSERVABILITY=false
OAUTH2=false

for arg in "$@"; do
    case "$arg" in
        stop)        ;; # handled below
        backend)     BACKEND_ONLY=true ;;
        --observability) OBSERVABILITY=true ;;
        --oauth2)    OAUTH2=true ;;
    esac
done

# --- Stop command ---
if [[ "${1:-}" == "stop" ]]; then
    log "Stopping services..."

    # Kill backend: spring-boot:run forks a child JVM, so .pid-backend holds
    # the Maven PID, not the JVM PID. Kill both the JVM (by port) and Maven.
    # Try graceful shutdown first via /actuator/shutdown, fall back to kill.
    BACKEND_STOPPED=false
    for port in 8080 9091; do
        JAVA_PID=$(netstat -ano 2>/dev/null | grep ":${port} .*LISTENING" | awk '{print $NF}' | head -1)
        if [[ -n "$JAVA_PID" && "$JAVA_PID" != "0" ]]; then
            kill "$JAVA_PID" 2>/dev/null || true
            # Wait up to 10s for graceful shutdown
            for i in $(seq 1 10); do
                if ! netstat -ano 2>/dev/null | grep ":${port} .*LISTENING" | grep -q "$JAVA_PID"; then
                    break
                fi
                sleep 1
            done
            # Force kill if still alive
            if netstat -ano 2>/dev/null | grep ":${port} .*LISTENING" | grep -q "$JAVA_PID"; then
                kill -9 "$JAVA_PID" 2>/dev/null || true
                warn "Backend PID $JAVA_PID force-killed (port $port)."
            fi
            BACKEND_STOPPED=true
        fi
    done
    # Also kill Maven wrapper if still around
    if [[ -f .pid-backend ]]; then
        kill "$(cat .pid-backend)" 2>/dev/null || true
        rm -f .pid-backend
    fi
    $BACKEND_STOPPED && log "Backend stopped." || true

    if [[ -f .pid-frontend ]]; then
        kill "$(cat .pid-frontend)" 2>/dev/null && log "Frontend stopped." || true
        rm -f .pid-frontend
    fi
    # Tear down all containers including optional profiles
    docker compose --profile observability --profile oauth2 down 2>/dev/null && log "All containers stopped." || true
    log "All services stopped."
    exit 0
fi

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║       Finding A Bed Tonight — Dev Stack      ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
echo ""

# --- Step 1: Check prerequisites ---
log "Checking prerequisites..."

if ! command -v docker &>/dev/null; then
    err "Docker not found. Install Docker Desktop and try again."
    exit 1
fi

if ! docker info &>/dev/null; then
    err "Docker is not running. Start Docker Desktop and try again."
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    err "Maven not found. Install Maven 3.9+ and try again."
    exit 1
fi

if ! command -v java &>/dev/null; then
    err "Java not found. Install Java 25+ and try again."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 25 ]]; then
    err "Java 25+ required. Found Java $JAVA_VERSION."
    exit 1
fi

if [[ "$BACKEND_ONLY" == false ]]; then
    if ! command -v node &>/dev/null; then
        err "Node.js not found. Install Node.js 20+ and try again."
        exit 1
    fi
fi

log "Prerequisites OK (Java $JAVA_VERSION, Docker running)"

# --- Step 2: Start PostgreSQL (+ observability stack if requested) ---
log "Starting PostgreSQL..."
docker compose up -d postgres

if [[ "$OBSERVABILITY" == true ]]; then
    log "Starting observability stack (Prometheus, Grafana, Jaeger, OTel Collector)..."
    docker compose --profile observability up -d
fi

if [[ "$OAUTH2" == true ]]; then
    log "Starting Keycloak (OAuth2 identity provider)..."
    docker compose --profile oauth2 up -d
fi

log "Waiting for PostgreSQL to accept connections..."
RETRIES=30
until docker compose exec -T postgres pg_isready -U fabt -q 2>/dev/null; do
    RETRIES=$((RETRIES - 1))
    if [[ $RETRIES -le 0 ]]; then
        err "PostgreSQL failed to start after 30 seconds."
        exit 1
    fi
    sleep 1
done
log "PostgreSQL ready."

# --- Step 3: Build + start backend ---
log "Building backend (first run may take a few minutes)..."
cd backend

# Compile first (fast feedback if there's a build error)
mvn compile -q 2>&1 || {
    err "Backend build failed. Check the output above."
    exit 1
}

log "Starting backend on http://localhost:8080 ..."
SPRING_ARGS=""
if [[ "$OBSERVABILITY" == true ]]; then
    # Separate management port so Prometheus can scrape without JWT auth
    # Port 9091 avoids conflict with Prometheus on 9090
    SPRING_ARGS="-Dspring-boot.run.arguments=--management.server.port=9091"
    # Enable OTel tracing (default is 0.0 = disabled)
    export TRACING_SAMPLING_PROBABILITY=1.0
    log "Management port: 9091 (Prometheus scrape target)"
    log "Tracing: enabled (sampling=1.0, endpoint=localhost:4318)"
fi
# Activate 'dev' profile for dev-only features (e.g., test reset endpoint).
# This profile does NOT exist in production deployments.
export SPRING_PROFILES_ACTIVE=lite,dev
# Create logs directory BEFORE starting backend (fresh clones don't have it)
mkdir -p ../logs
mvn spring-boot:run $SPRING_ARGS -q > ../logs/backend.log 2>&1 &
BACKEND_PID=$!
echo "$BACKEND_PID" > ../.pid-backend
cd ..

# Wait for backend to be ready (Flyway migrations run during startup)
# When --observability is set, health endpoints move to the management port
HEALTH_PORT=8080
if [[ "$OBSERVABILITY" == true ]]; then
    HEALTH_PORT=9091
fi
log "Waiting for backend to start (Flyway migrations + Spring context)..."
RETRIES=60
until curl -sf http://localhost:${HEALTH_PORT}/actuator/health/liveness >/dev/null 2>&1; do
    # Check if backend process died
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
        err "Backend process died. Check logs/backend.log for details."
        tail -20 logs/backend.log 2>/dev/null
        exit 1
    fi
    RETRIES=$((RETRIES - 1))
    if [[ $RETRIES -le 0 ]]; then
        err "Backend failed to start after 60 seconds. Check logs/backend.log"
        tail -20 logs/backend.log 2>/dev/null
        exit 1
    fi
    sleep 1
done
log "Backend ready."

# --- Step 4: Grant permissions to fabt_app + load seed data ---
log "Granting permissions to fabt_app role..."
docker compose exec -T postgres psql -U fabt -d fabt -c "
    GRANT USAGE ON SCHEMA public TO fabt_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fabt_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fabt_app;
" >/dev/null 2>&1
log "Loading seed data..."
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-data.sql >/dev/null 2>&1
log "Seed data loaded (10 shelters, 3 users, 1 tenant)."

log "Loading demo activity data (28 days of snapshots, searches, reservations)..."
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/demo-activity-seed.sql >/dev/null 2>&1
log "Demo activity data loaded."

# --- Step 5: Start frontend (unless backend-only mode) ---
if [[ "$BACKEND_ONLY" == false ]]; then
    log "Starting frontend on http://localhost:5173 ..."
    cd frontend
    npm install --silent 2>/dev/null
    npm run dev > ../logs/frontend.log 2>&1 &
    FRONTEND_PID=$!
    echo "$FRONTEND_PID" > ../.pid-frontend
    cd ..

    # Wait for frontend dev server
    RETRIES=30
    until curl -sf http://localhost:5173 >/dev/null 2>&1; do
        if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
            err "Frontend process died. Check logs/frontend.log"
            tail -20 logs/frontend.log 2>/dev/null
            exit 1
        fi
        RETRIES=$((RETRIES - 1))
        if [[ $RETRIES -le 0 ]]; then
            warn "Frontend didn't respond in 30s — it may still be starting. Check http://localhost:5173"
            break
        fi
        sleep 1
    done
    log "Frontend ready."
fi

# --- Wait for observability stack if requested ---
if [[ "$OBSERVABILITY" == true ]]; then
    log "Waiting for Grafana to be ready..."
    RETRIES=30
    until curl -sf http://localhost:3000/api/health >/dev/null 2>&1; do
        RETRIES=$((RETRIES - 1))
        if [[ $RETRIES -le 0 ]]; then
            warn "Grafana didn't respond in 30s — it may still be starting. Check http://localhost:3000"
            break
        fi
        sleep 1
    done
    log "Observability stack ready."
fi

# --- Wait for Keycloak if requested ---
if [[ "$OAUTH2" == true ]]; then
    log "Waiting for Keycloak to be ready (realm import may take 30s)..."
    RETRIES=60
    until curl -sf http://localhost:8180/realms/fabt-dev/.well-known/openid-configuration >/dev/null 2>&1; do
        RETRIES=$((RETRIES - 1))
        if [[ $RETRIES -le 0 ]]; then
            warn "Keycloak didn't respond in 60s — realm may still be importing. Check http://localhost:8180"
            break
        fi
        sleep 1
    done
    log "Keycloak ready."
    # Enable the keycloak seed provider for this tenant
    docker compose exec -T postgres psql -U fabt -d fabt -c "
        UPDATE tenant_oauth2_provider SET enabled = true
        WHERE provider_name = 'keycloak' AND tenant_id = 'a0000000-0000-0000-0000-000000000001';
    " >/dev/null 2>&1
    log "Keycloak provider enabled for dev-coc tenant."
fi

# --- Done ---
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║             Stack is running!                ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""
info "Backend:  http://localhost:8080"
info "Swagger:  http://localhost:8080/api/v1/docs"
if [[ "$BACKEND_ONLY" == false ]]; then
    info "Frontend: http://localhost:5173"
fi
info "Health:   http://localhost:8080/actuator/health"
if [[ "$OBSERVABILITY" == true ]]; then
    info "Grafana:  http://localhost:3000 (admin/admin)"
    info "Jaeger:   http://localhost:16686"
    info "Prometheus: http://localhost:9090"
fi
if [[ "$OAUTH2" == true ]]; then
    info "Keycloak: http://localhost:8180 (admin/admin)"
fi
echo ""
info "Login credentials (tenant slug: dev-coc):"
echo -e "  ${YELLOW}Admin:${NC}     admin@dev.fabt.org    / admin123"
echo -e "  ${YELLOW}CoC Admin:${NC} cocadmin@dev.fabt.org / admin123"
echo -e "  ${YELLOW}Outreach:${NC}  outreach@dev.fabt.org / admin123"
echo ""
info "Logs:     logs/backend.log, logs/frontend.log"
info "Stop:     ./dev-start.sh stop"
echo ""
