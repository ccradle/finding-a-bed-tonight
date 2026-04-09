# Oracle Deploy Notes — v0.31.0 (Persistent Notifications)

**From:** v0.30.1 (surge banner contrast fix, currently live)
**To:** v0.31.0 (persistent notifications, DV referral escalation, coordinator banner)

## What's New in This Deploy

- **3 new Flyway migrations** (V35–V37): notification table + RLS + 2 indexes. Auto-applied on backend restart.
- **New notification module**: NotificationPersistenceService, NotificationEventListener, ReferralEscalationJobConfig (Spring Batch, every 5 min)
- **Frontend changes**: CoordinatorReferralBanner, CriticalNotificationBanner, updated useNotifications hook + NotificationBell
- **New seed user**: `dv-coordinator@dev.fabt.org` (COORDINATOR + dvAccess=true) + shelter assignments
- **Static content**: `demo/index.html` updated with new test user credentials

## Pre-Deploy Checklist

- [ ] Confirm v0.30.1 is running: `curl -s https://findabed.org/api/v1/version`
- [ ] SSH tunnel open: `ssh -i ~/.ssh/fabt-oracle -L 8081:localhost:8081 ubuntu@150.136.221.232`
- [ ] Docker is running on VM: `docker ps`

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# 2. Pull latest code
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.31.0

# 3. Backup current frontend (for rollback)
cp -r frontend/dist frontend/dist-v0.30.1-backup

# 4. Build backend
cd backend && mvn package -DskipTests -q && cd ..

# 5. Build frontend
cd frontend && npm ci --silent && npm run build && cd ..

# 6. Build Docker images
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

# 7. Restart backend + frontend (Flyway V35-V37 run automatically on backend start)
# GRANTs automatic via V16 ALTER DEFAULT PRIVILEGES
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend

# 8. Wait for backend to be healthy (~15-20 seconds)
echo "Waiting for backend..."
until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "Backend healthy."

# 9. Apply seed data (additive — new DV coordinator user + assignments + notifications)
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
  < infra/scripts/seed-data.sql

# 10. Cleanup old Docker images
docker image prune -f
```

## Static Content Update

Run from your LOCAL machine (not the VM):

```bash
# Copy updated demo/index.html to the VM's static content directory
scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/demo/index.html \
  ubuntu@150.136.221.232:/var/www/findabed-docs/demo/index.html
```

No nginx restart needed — static files are served directly.

## Post-Deploy Sanity Checks

### Automated (run from local machine)

```bash
cd e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test post-deploy-smoke \
  --config=deploy/playwright.config.ts --trace on --reporter=list \
  2>&1 | tee ../../logs/post-deploy-smoke-v0.31.0.log
```

Expected: 8/8 green.

### Manual Verification

1. `curl -s https://findabed.org/api/v1/version` → `{"version":"0.31"}`
2. Open https://findabed.org — credentials table shows `dv-coordinator@dev.fabt.org`
3. Login as `dv-coordinator@dev.fabt.org / admin123` → lands on `/coordinator`
4. Bell badge visible in header (may show 0 unread on fresh deploy — that's OK)
5. Click bell → panel opens, shows "No notifications" or seed notifications
6. Login as `dv-outreach@dev.fabt.org / admin123` → search → select DV_SURVIVOR → "Request Referral" button visible
7. Submit a referral → success (no error)
8. Login as `dv-coordinator@dev.fabt.org` → bell should show 1 unread → dropdown shows "New referral needs your review"

### Flyway Verification (via SSH tunnel)

```bash
# On the VM, verify V35-V37 applied:
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
  -c "SELECT version, description FROM flyway_schema_history WHERE version IN ('35','36','37') ORDER BY version"
```

Expected: 3 rows (V35 notification table, V36 payload index, V37 referral_token index).

## What Does NOT Change

- No new env vars required
- No manual GRANTs needed (V16 ALTER DEFAULT PRIVILEGES covers V35-V37 tables)
- No nginx config changes on the VM
- No Cloudflare changes
- No Docker compose file changes
- No `--fresh` / seed-reset needed — existing data preserved

## Rollback Plan

If issues are detected after deploy:

```bash
# On the VM:
git checkout v0.30.1

# Rebuild from v0.30.1
cd backend && mvn package -DskipTests -q && cd ..

# Restore frontend backup
rm -rf frontend/dist
cp -r frontend/dist-v0.30.1-backup frontend/dist

# Rebuild Docker images from v0.30.1 code
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

# Restart
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend
```

**V35-V37 migrations are additive** (new table + indexes). They are safe to leave in place on rollback — the v0.30.1 code simply won't reference the notification table.

**Seed data is additive** (ON CONFLICT safe). The new DV coordinator user persists on rollback but causes no harm.

**Static content rollback:**
```bash
# From local machine — restore the old demo/index.html
# (or just leave it — the extra credential line is harmless)
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Flyway V35 fails (notification table not created) | LOW | HIGH — notification endpoints return 500 | Smoke test 7 catches this. Rollback: leave V35 in flyway_schema_history, backend restarts cleanly. |
| RLS blocks notification inserts (RETURNING issue) | LOW | MEDIUM — notifications silently not created | Fixed in code (set_config before save). Backend integration tests verify. |
| Escalation batch job fails (dvAccess context) | LOW | MEDIUM — escalations don't fire, but base notifications still work | BatchJobScheduler wraps in TenantContext. Backend tests verify. Job failure is non-fatal — logs error, retries next 5-min tick. |
| Frontend build has stale service worker | MEDIUM | LOW — old cached JS served | Users: hard refresh or incognito. Per memory: old SW serves cached JS after deploy. |
| Seed data fails (DV coordinator not created) | LOW | MEDIUM — smoke test 5 catches it. Manual referral flow won't produce notifications. | Re-run seed script. ON CONFLICT is idempotent. |
