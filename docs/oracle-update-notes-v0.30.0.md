# Oracle Demo — Update to v0.30.0

**From:** v0.29.6 (admin panel extraction)
**To:** v0.30.0 (platform hardening — API key lifecycle, webhook management, rate limiting, retry, encryption)
**Date:** April 8, 2026

---

## What's New

- **API key lifecycle:** rotate with 24h grace period, revoke, 256-bit entropy, lastUsedAt tracking
- **Webhook management:** pause/resume, send test, delivery log (last 20, redacted), auto-disable at 5 failures
- **Per-IP rate limiting:** Bucket4j + Caffeine (1000 req/min default), nginx edge 1r/s burst=20
- **Server-side retry:** Spring Framework 7 native @Retryable on availability snapshots
- **AES-256-GCM encryption:** generalized SecretEncryptionService for webhook secrets
- **WCAG fix:** shelter detail + DV referral modals support Escape key dismiss
- **Admin UI:** revoke/rotate confirm dialogs, 3-state key badges, 5-state subscription badges, delivery log panel
- **Database:** V33 (api_key grace period columns), V34 (webhook_delivery_log table + consecutive_failures)

## Pre-Deploy Checklist

- [ ] CI scans green on main
- [ ] v0.30.0 tag created and pushed
- [ ] GitHub release created

## Step 1 — SSH to Oracle VM

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232
```

## Step 2 — Pull & Build

```bash
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.30.0

# Backup current frontend for rollback
cp -r frontend/dist frontend/dist-v0.29.6-backup

# Build backend + frontend
cd backend && mvn package -DskipTests -q && cd ..
cd frontend && npm ci --silent && npm run build && cd ..
```

## Step 3 — Docker Images & Restart

```bash
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

# Restart — Flyway V33+V34 auto-migrate
# GRANTs automatic via V16 ALTER DEFAULT PRIVILEGES — no manual GRANT needed
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend
```

**No new env vars required.** `FABT_TOTP_ENCRYPTION_KEY` already set — `SecretEncryptionService` reads it via fallback.

## Step 4 — Load Seed Data (additive, no --fresh)

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
  < ~/finding-a-bed-tonight/infra/scripts/seed-data.sql
```

Adds 3 demo API keys (Active/Grace Period/Revoked), 3 subscriptions (Active/Failing/Deactivated), 6 delivery log entries. Existing data untouched (`ON CONFLICT` safe).

## Step 5 — Automated Sanity Checks (from local machine)

### 5a. Version endpoint

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.30"}
```

### 5b. Post-deploy smoke tests

```bash
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test \
  --config=deploy/playwright.config.ts post-deploy-smoke \
  --reporter=list --trace on 2>&1 | tee ../../logs/post-deploy-smoke-v0.30.0.log
# Expected: 5 passed (version, login+search, hold, demo guard, SSE)
```

## Step 6 — Automated DB Verification (SSH)

### 6a. Migrations applied

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
  -c "\dt webhook_delivery_log"
# Expected: table exists
```

### 6b. Seed data loaded

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT label, active,
    CASE WHEN NOT active THEN 'revoked'
         WHEN old_key_expires_at > NOW() THEN 'grace'
         ELSE 'active' END as status
  FROM api_key
  WHERE label IN ('Mobile App Integration','Kiosk - Capital Blvd','Legacy HMIS Bridge');
"
# Expected: 3 rows — active, grace, revoked
```

### 6c. Subscriptions + delivery logs

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT event_type, status, consecutive_failures
  FROM subscription WHERE id::text LIKE 'f0000000%';
"
# Expected: 3 rows — ACTIVE/0, FAILING/3, DEACTIVATED/5

docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT COUNT(*) FROM webhook_delivery_log;
"
# Expected: 6
```

### 6d. Existing data preserved

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT COUNT(*) as users FROM app_user;
"
# Expected: 11 (unchanged — 5 seed + 6 manually created)
```

## Step 7 — Manual Sanity Checks (incognito browser on findabed.org)

| # | Check | What to verify |
|---|-------|----------------|
| 7a | API Keys tab | Login as admin → API Keys tab → 3 keys with Active, Grace Period, Revoked badges |
| 7b | Subscriptions tab | Subscriptions tab → 3 subs with Active, Failing, Deactivated badges |
| 7c | Delivery log | Click Deliveries on Active subscription → 3 entries with status codes |
| 7d | Demo guard | Click Revoke on any key → ErrorBox shows "disabled in the demo environment" |
| 7e | Escape on modal | Outreach search → click shelter card → press Escape → modal closes |
| 7f | Dark mode | Toggle OS dark mode → admin tabs render correctly (no broken colors) |
| 7g | SSE stable | Stay on search page 10 seconds → no "Reconnecting..." banner |
| 7h | All admin tabs | Click through all 10 tabs → no blank panels or errors |

## Step 8 — Cleanup (SSH, after all checks pass)

```bash
# Remove frontend rollback backup
rm -rf frontend/dist-v0.29.6-backup

# Remove old backend JARs
find backend/target -name "*.jar" ! -name "*0.30.0*" -delete 2>/dev/null

# Prune old Docker images
docker image prune -f
```

## Rollback Plan

### Frontend-only rollback (covers most UI issues)

```bash
rm -rf frontend/dist
mv frontend/dist-v0.29.6-backup frontend/dist
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate frontend
```

### Full rollback (if backend issue)

```bash
git checkout v0.29.6
cd backend && mvn package -DskipTests -q && cd ..
cd frontend && npm ci --silent && npm run build && cd ..
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend
```

> **Note:** V33/V34 migrations stay in place on rollback — they're additive (new columns, new table) and harmless to v0.29.6 code which simply ignores them.

## Post-Deploy Checklist

- [ ] Step 5a: Version shows 0.30
- [ ] Step 5b: Smoke tests 5/5 pass
- [ ] Step 6a: webhook_delivery_log table exists
- [ ] Step 6b: 3 API keys with correct statuses
- [ ] Step 6c: 3 subscriptions + 6 delivery logs
- [ ] Step 6d: 11 users (existing data preserved)
- [ ] Step 7a-h: Manual checks complete
- [ ] Step 8: Cleanup done
