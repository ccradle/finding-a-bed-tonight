# Oracle Demo — Update to v0.29.3

**From:** v0.29.2
**Date:** April 5, 2026
**Changes:** DemoGuard SSH tunnel bypass via nginx traffic source detection

## What changed

| Layer | Changed? | Details |
|-------|----------|---------|
| Backend | Yes | DemoGuardFilter — header-first traffic source check |
| Frontend (nginx) | Yes | nginx.conf + 00-rate-limit.conf — map directive + X-FABT-Traffic-Source header |
| Static site | No | No changes |
| Database | No | No migrations |

## Pre-deploy checklist

- [ ] CI scans pass on merge commit
- [ ] Cloudflare HTTP/3 still OFF (Speed → Optimization → Protocol)
- [ ] Verify current version: `curl -s https://findabed.org/api/v1/version`

## Deploy steps

**BOTH containers restart — ~15 seconds backend downtime.**

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}

cd ~/finding-a-bed-tonight
git pull origin main

# Rebuild frontend (nginx config change)
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .

# Rebuild backend (DemoGuardFilter change)
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# Restart BOTH containers
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend

# Wait for backend startup
sleep 15
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.29"}

# Cleanup
docker image prune -f
rm -f backend/target/finding-a-bed-tonight-0.29.2.jar
```

## Post-Deploy Smoke Tests

### Part A: Public Path — DemoGuard blocks

```bash
# Login
TOKEN=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Blocked: create user
curl -s -w "\n%{http_code}" -X POST https://findabed.org/api/v1/users \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"Test12345!","displayName":"Test","roles":["OUTREACH_WORKER"]}'
# Expected: 403 demo_restricted

# Allowed: bed search
curl -s -o /dev/null -w "%{http_code}" -X POST https://findabed.org/api/v1/queries/beds \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"populationType":"SINGLE_ADULT"}'
# Expected: 200
```

Also run in browser: incognito → https://findabed.org → login as admin → Create User → verify "disabled in demo environment"

### Part B: Tunnel Path — Full admin access

```bash
# In a separate terminal:
ssh -i ~/.ssh/fabt-oracle -L 8081:localhost:8081 ubuntu@${FABT_VM_IP} -N
```

Then in **incognito** browser: http://localhost:8081 → login as admin → perform these operations:
1. Create User → should succeed → delete the test user
2. Activate Surge → should succeed → deactivate
3. Edit shelter name → should succeed → revert
4. Wait 60 seconds → no "Reconnecting" banner (SSE works via tunnel)

### Part C: Verify public still protected after tunnel

1. Close SSH tunnel
2. New incognito → https://findabed.org → login as admin
3. Create User → should still show "disabled in demo environment"
4. Verify all 4 demo credentials work (admin123)

## Backout Plan

If anything goes wrong — revert to v0.29.2:

```bash
cd ~/finding-a-bed-tonight
git checkout v0.29.2

# Rebuild BOTH containers
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# Restart both
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend
```

## Reference: Cloudflare SSE Configuration

See `oracle-update-notes-v0.29.1.md` for full Cloudflare SSE settings table.
Key: HTTP/3 (QUIC) must be **OFF**.
