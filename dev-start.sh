#!/usr/bin/env bash
#
# dev-start.sh — Start the full Finding A Bed Tonight development stack
#
# Usage:
#   ./dev-start.sh                        Start everything (PostgreSQL, backend, seed data, frontend)
#   ./dev-start.sh backend                Start PostgreSQL + backend only (no frontend)
#   ./dev-start.sh --observability        Full stack + Prometheus + Grafana + Jaeger + OTel Collector
#   ./dev-start.sh backend --observability  Backend + observability (no frontend)
#   ./dev-start.sh --fresh                 Reset seed data before loading (use when shelter structure changes)
#   ./dev-start.sh --nginx                 Frontend via nginx proxy (port 8081) instead of Vite dev server
#   ./dev-start.sh --nginx --no-build      Restart nginx without rebuilding frontend (quick nginx.conf iteration)
#   ./dev-start.sh --nginx --observability Full stack with nginx proxy + observability
#   ./dev-start.sh stop                   Stop all services including observability containers
#
# Note: --nginx mode is for integration testing, not active frontend development.
#       Use the default Vite mode for HMR during frontend dev.
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
FRESH_SEED=false
NGINX_MODE=false
NO_BUILD=false

for arg in "$@"; do
    case "$arg" in
        stop)        ;; # handled below
        backend)     BACKEND_ONLY=true ;;
        --observability) OBSERVABILITY=true ;;
        --oauth2)    OAUTH2=true ;;
        --fresh)     FRESH_SEED=true ;;
        --nginx)     NGINX_MODE=true ;;
        --no-build)  NO_BUILD=true ;;
    esac
done

# --- Stop command ---
if [[ "${1:-}" == "stop" ]]; then
    log "Stopping services..."

    # Detect platform: Git Bash kill can't signal Windows PIDs, so use PowerShell.
    # On Linux/macOS, use standard POSIX signals.
    IS_WINDOWS=false
    if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; then
        IS_WINDOWS=true
    fi

    # Find the actual JVM PID by port. spring-boot:run forks a child JVM, so
    # .pid-backend holds the Maven PID, not the JVM PID.
    if $IS_WINDOWS; then
        JAVA_PID=$(netstat -ano 2>/dev/null | grep ":8080 .*LISTENING" | awk '{print $NF}' | head -1)
    else
        JAVA_PID=$(lsof -ti:8080 2>/dev/null | head -1)
    fi

    BACKEND_STOPPED=false
    if [[ -n "$JAVA_PID" && "$JAVA_PID" != "0" ]]; then
        log "Stopping backend (PID $JAVA_PID)..."
        # Graceful stop
        if $IS_WINDOWS; then
            powershell.exe -NoProfile -Command "Stop-Process -Id $JAVA_PID" 2>/dev/null || true
        else
            kill "$JAVA_PID" 2>/dev/null || true
        fi
        # Wait up to 15s for graceful shutdown
        for i in $(seq 1 15); do
            if $IS_WINDOWS; then
                netstat -ano 2>/dev/null | grep ":8080 .*LISTENING" | grep -q "$JAVA_PID" || { BACKEND_STOPPED=true; break; }
            else
                kill -0 "$JAVA_PID" 2>/dev/null || { BACKEND_STOPPED=true; break; }
            fi
            sleep 1
        done
        if $BACKEND_STOPPED; then
            log "Backend stopped gracefully (${i}s)."
        else
            # Force kill if still alive
            warn "Graceful shutdown timed out, force-killing PID $JAVA_PID..."
            if $IS_WINDOWS; then
                powershell.exe -NoProfile -Command "Stop-Process -Id $JAVA_PID -Force" 2>/dev/null || true
            else
                kill -9 "$JAVA_PID" 2>/dev/null || true
            fi
            BACKEND_STOPPED=true
        fi
    fi
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
    # Tear down all containers including optional profiles (including nginx dev proxy)
    docker compose -f docker-compose.yml -f docker-compose.dev-nginx.yml \
        --profile observability --profile oauth2 --profile nginx down 2>/dev/null && log "All containers stopped." || true
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
# spring-boot:build-info generates META-INF/build-info.properties so the
# /api/v1/version endpoint registers (BuildProperties bean requires it).
# The build-info goal binds to generate-resources which spring-boot:run skips.
mvn spring-boot:build-info compile -q 2>&1 || {
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
# TOTP 2FA encryption key for dev (D16 — enables local TOTP testing).
# This is a dev-only key. Production uses a different key in .env.prod.
export FABT_TOTP_ENCRYPTION_KEY="s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA="
# CORS: allow both Vite (:5173) and nginx (:8081) origins in dev
if [[ "$NGINX_MODE" == true ]]; then
    export FABT_CORS_ALLOWED_ORIGINS="http://localhost:5173,http://localhost:8081"
fi
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
if [[ "$FRESH_SEED" == true ]]; then
    warn "Resetting seed data (--fresh flag)..."
    docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-reset.sql >/dev/null 2>&1
    log "Seed data reset complete."
fi
log "Loading seed data..."
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-data.sql >/dev/null 2>&1
log "Seed data loaded (13 shelters, 4 users, 1 tenant)."

log "Loading demo activity data (28 days of snapshots, searches, reservations)..."
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/demo-activity-seed.sql >/dev/null 2>&1
log "Demo activity data loaded."

# --- Step 5: Start frontend (unless backend-only mode) ---
if [[ "$BACKEND_ONLY" == false ]]; then
    if [[ "$NGINX_MODE" == true ]]; then
        # --- Nginx mode: serve frontend through real nginx proxy ---
        # Check Docker version >= 20.10 (required for host-gateway)
        DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null | cut -d. -f1-2)
        DOCKER_MAJOR=$(echo "$DOCKER_VERSION" | cut -d. -f1)
        DOCKER_MINOR=$(echo "$DOCKER_VERSION" | cut -d. -f2)
        if [[ "$DOCKER_MAJOR" -lt 20 ]] || [[ "$DOCKER_MAJOR" -eq 20 && "$DOCKER_MINOR" -lt 10 ]]; then
            err "Docker >= 20.10 required for --nginx mode (host-gateway). Found: $DOCKER_VERSION"
            exit 1
        fi

        if [[ "$NO_BUILD" == false ]]; then
            log "Building frontend for nginx mode..."
            cd frontend
            npm install --silent 2>/dev/null
            npm run build > ../logs/frontend-build.log 2>&1
            cd ..
            log "Frontend built."
        else
            log "Skipping frontend build (--no-build)"
            if [[ ! -d frontend/dist ]]; then
                err "frontend/dist does not exist. Run without --no-build first."
                exit 1
            fi
        fi

        log "Starting frontend via nginx on http://localhost:8081 ..."
        docker compose -f docker-compose.yml -f docker-compose.dev-nginx.yml \
            --profile nginx up -d frontend-nginx > /dev/null 2>&1

        RETRIES=15
        until curl -sf http://localhost:8081 >/dev/null 2>&1; do
            RETRIES=$((RETRIES - 1))
            if [[ $RETRIES -le 0 ]]; then
                warn "Nginx didn't respond in 15s. Check: docker logs fabt-frontend-nginx"
                break
            fi
            sleep 1
        done
        log "Frontend (nginx) ready."
    else
        # --- Default: Vite dev server ---
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
    if [[ "$NGINX_MODE" == true ]]; then
        info "Frontend: http://localhost:8081 (nginx proxy)"
    else
        info "Frontend: http://localhost:5173"
    fi
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
echo -e "  ${YELLOW}DV Outreach:${NC} dv-outreach@dev.fabt.org / admin123"
echo ""
info "Logs:     logs/backend.log, logs/frontend.log"
info "Stop:     ./dev-start.sh stop"
echo ""
