# Oracle Demo — v0.25.0 Update Notes

**Previous version:** v0.24.0 (Offline Honesty)
**This version:** v0.25.0 (Sprint 2 Quick Wins)
**Date:** 2026-04-01
**Base runbook:** `oracle-demo-runbook-v0.21.0.md` — all sections still apply

---

## What Changed

### New features
- **App version endpoint** — `GET /api/v1/version` returns `{"version":"0.25"}` (major.minor only)
- **Version footer** — visible on login page (inside card) and authenticated layout
- **Nginx rate limiting** — 10 req/min/IP on `/api/v1/version` via `00-rate-limit.conf`
- **DV outreach worker** — new seed user `dv-outreach@dev.fabt.org` (OUTREACH_WORKER, dvAccess=true)

### Infrastructure changes
- `Dockerfile.frontend` now copies `00-rate-limit.conf` into nginx conf.d
- `nginx.conf` has a new rate-limited location block for `/api/v1/version`
- No new Flyway migrations (still 30)
- No database schema changes

### Security
- Version endpoint returns major.minor only (OWASP WSTG-INFO-02 mitigation)
- Rate limit zone `public_api` is reusable for future public endpoints

---

## Update Procedure

From the Oracle VM, follow the standard Part 13 procedure from the base runbook:

```bash
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.25.0
```

Build backend and frontend:
```bash
cd backend && mvn package -DskipTests -q && cd ..
cd frontend && npm ci --silent && npm run build && cd ..
```

Rebuild Docker images:
```bash
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .
```

Restart containers:
```bash
docker compose \
  -f docker-compose.yml \
  -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod \
  --profile observability \
  up -d --force-recreate backend frontend
```

### Add DV outreach worker to existing database

The seed data uses `ON CONFLICT DO NOTHING`, so reseeding works. But if you only want to add the new user without resetting demo activity:

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
  "INSERT INTO app_user (id, tenant_id, email, password_hash, display_name, roles, dv_access, created_at, updated_at)
   VALUES ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001',
   'dv-outreach@dev.fabt.org', '\$2b\$10\$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva',
   'DV Outreach Worker', ARRAY['OUTREACH_WORKER'], true, NOW(), NOW())
   ON CONFLICT (tenant_id, email) DO NOTHING;"
```

---

## Post-Update Verification

```bash
# Version endpoint (the key new feature)
curl -s https://YOUR_IP.nip.io/api/v1/version
# Expected: {"version":"0.25"}

# Rate limiting (should 429 after 6 rapid requests)
for i in $(seq 1 8); do
  echo "$(curl -s -o /dev/null -w '%{http_code}' https://YOUR_IP.nip.io/api/v1/version)"
done

# Health
curl -s https://YOUR_IP.nip.io/actuator/health/liveness
# Expected: {"status":"UP"}

# DV outreach worker login
curl -s https://YOUR_IP.nip.io/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"dev-coc","email":"dv-outreach@dev.fabt.org","password":"admin123"}'
# Expected: JSON with accessToken

# Nginx security headers still present
curl -sI https://YOUR_IP.nip.io/ | grep -i "x-frame-options"
# Expected: X-Frame-Options: DENY
```

---

## SSH Session Drops

Long-running commands over SSH to the Oracle VM may drop due to idle timeouts. Use `nohup` for builds:

```bash
# Pattern for long commands
nohup bash -c 'cd ~/finding-a-bed-tonight/backend && mvn package -DskipTests -q > /tmp/build.log 2>&1 && echo OK >> /tmp/build.log' &

# Check result
tail -1 /tmp/build.log
```

Or use `-o ServerAliveInterval=5` on your SSH connection.

---

## Stale Service Worker

After deploy, the first visit may serve cached JS from the old service worker. The nginx config sets `Cache-Control: no-cache, no-store, must-revalidate` on `index.html`, so a page refresh picks up the new bundle. If testing immediately after deploy, use incognito or clear site data.

---

## Demo Credentials

| Role | Email | Password |
|------|-------|----------|
| Platform Admin | `admin@dev.fabt.org` | `admin123` |
| CoC Admin | `cocadmin@dev.fabt.org` | `admin123` |
| Outreach Worker | `outreach@dev.fabt.org` | `admin123` |
| DV Outreach Worker | `dv-outreach@dev.fabt.org` | `admin123` |

Tenant slug: `dev-coc`

---

## Known Gaps (v0.25.0)

### DV referral requests do not work offline

**Behavior:** When offline, the "Request Referral" button remains enabled. The modal opens, the user fills the form (including callback number), and taps Submit. The submit fails silently — the modal stays open with no visible error. The error banner renders behind the modal.

**Why this is intentional (for now):** DV referrals contain sensitive operational data (callback number, household size, special needs). Queuing this in IndexedDB on the device creates a security risk that doesn't exist for bed holds (which contain only a shelter ID and population type). Casey Drummond (legal persona) notes that the zero-PII design depends on data living on the server briefly and being hard-deleted within 24 hours — persisting it on-device undermines that threat model.

**What needs to change (future):**
1. Disable the "Request Referral" button when offline with a tooltip: "Referral requests require a connection"
2. Or show a clear message in the modal if submit fails due to network error
3. Update the offline banner to explicitly state: "DV referral requests require a connection"

**Workaround:** Move to a location with signal, or call the DV shelter directly.

---

## Changes Since Base Runbook (v0.21.0)

Since the base runbook was written at v0.21.0, these versions have shipped:

| Version | Date | Key Changes |
|---------|------|-------------|
| v0.22.x | 2026-03-28–31 | SSE stability, nginx security headers, ZAP baseline |
| v0.23.0 | 2026-03-31 | SSE production race condition fix |
| v0.24.0 | 2026-04-01 | Offline honesty: real offline queue, idempotency keys, injectManifest SW |
| v0.25.0 | 2026-04-01 | DV outreach test coverage, app version display, nginx rate limiting |

Infrastructure has not changed — same Lite tier, same 7 containers, same Flyway migration count (30). The base runbook's Part 13 update procedure still works for all versions.

Test counts have grown: 332 backend (was 296), 193 Playwright (was 167).
