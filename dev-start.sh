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

for arg in "$@"; do
    case "$arg" in
        stop)        ;; # handled below
        backend)     BACKEND_ONLY=true ;;
        --observability) OBSERVABILITY=true ;;
    esac
done

# --- Stop command ---
if [[ "${1:-}" == "stop" ]]; then
    log "Stopping services..."
    # Kill backend and frontend if running
    if [[ -f .pid-backend ]]; then
        kill "$(cat .pid-backend)" 2>/dev/null && log "Backend stopped." || true
        rm -f .pid-backend
    fi
    if [[ -f .pid-frontend ]]; then
        kill "$(cat .pid-frontend)" 2>/dev/null && log "Frontend stopped." || true
        rm -f .pid-frontend
    fi
    # Tear down all containers including observability profile
    docker compose --profile observability down 2>/dev/null && log "All containers stopped." || true
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
    err "Java not found. Install Java 21+ and try again."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 21 ]]; then
    err "Java 21+ required. Found Java $JAVA_VERSION."
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
    log "Management port: 9091 (Prometheus scrape target)"
fi
mvn spring-boot:run $SPRING_ARGS -q > ../logs/backend.log 2>&1 &
BACKEND_PID=$!
echo "$BACKEND_PID" > ../.pid-backend
cd ..

# Create logs directory
mkdir -p logs

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
echo ""
info "Login credentials (tenant slug: dev-coc):"
echo -e "  ${YELLOW}Admin:${NC}     admin@dev.fabt.org    / admin123"
echo -e "  ${YELLOW}CoC Admin:${NC} cocadmin@dev.fabt.org / admin123"
echo -e "  ${YELLOW}Outreach:${NC}  outreach@dev.fabt.org / admin123"
echo ""
info "Logs:     logs/backend.log, logs/frontend.log"
info "Stop:     ./dev-start.sh stop"
echo ""
