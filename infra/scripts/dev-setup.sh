#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== Finding A Bed Tonight — Dev Setup ==="

# Start PostgreSQL (always needed)
echo "Starting PostgreSQL..."
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up -d postgres
echo "Waiting for PostgreSQL..."
until docker compose -f "$PROJECT_ROOT/docker-compose.yml" exec -T postgres pg_isready -U fabt 2>/dev/null; do
    sleep 1
done
echo "PostgreSQL ready."

# Run Flyway migrations (via Spring Boot)
echo "Running migrations..."
cd "$PROJECT_ROOT/backend"
mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/fabt -Dflyway.user=fabt -Dflyway.password=fabt -q 2>/dev/null || {
    echo "Flyway Maven plugin not configured — migrations will run on app startup."
}

# Load seed data
echo "Loading seed data..."
docker compose -f "$PROJECT_ROOT/docker-compose.yml" exec -T postgres psql -U fabt -d fabt < "$SCRIPT_DIR/seed-data.sql"
echo "Seed data loaded."

echo ""
echo "=== Setup complete ==="
echo "  Backend:  cd backend && mvn spring-boot:run"
echo "  Frontend: cd frontend && npm run dev"
echo "  Admin:    admin@dev.fabt.org / admin123"
echo "  Outreach: outreach@dev.fabt.org / outreach123"
echo "  CoC Admin: cocadmin@dev.fabt.org / cocadmin123"
