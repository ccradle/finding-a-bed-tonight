# Oracle Demo — Update to v0.29.4

**From:** v0.29.1 (live)
**Date:** April 6, 2026
**Changes:** WCAG accessibility fixes, SSE lifecycle fix, DemoGuard tunnel bypass, Vite security patch

> **Connection:** Set `FABT_VM_IP` before running commands. Value is in your
> local `~/fabt-secrets/connection.md` or password manager — not stored in git.

## What changed

This deploy covers v0.29.2 + v0.29.3 + v0.29.4 (three versions, never individually deployed).

| Layer | Changed? | Details |
|-------|----------|---------|
| Backend | Yes | SSE emitter lifecycle fix (v0.29.2), DemoGuardFilter tunnel bypass (v0.29.3), version bump (v0.29.4) |
| Frontend (nginx) | Yes | nginx.conf map directive (v0.29.3), focus-visible CSS + autocomplete attrs + Vite 7.3.2 (v0.29.4) |
| Static site | No | No changes to /var/www/findabed-docs/ |
| Database | No | No Flyway migrations |
| Env vars | No | No new env vars |
| nginx host config | No | /etc/nginx/sites-enabled/fabt unchanged |

## Pre-deploy checklist

- [ ] CI scans pass on v0.29.4 tag
- [ ] GitHub release created: https://github.com/ccradle/finding-a-bed-tonight/releases/tag/v0.29.4
- [ ] Cloudflare HTTP/3 still OFF (Speed → Optimization → Protocol)
- [ ] Verify current live version: `curl -s https://findabed.org/api/v1/version`
- [ ] Expected: v0.29.1

## Deploy steps

**BOTH containers restart — ~15 seconds backend downtime.**

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}

cd ~/finding-a-bed-tonight
git fetch origin
git checkout v0.29.4

# Rebuild frontend (CSS changes + Vite bump + nginx.conf)
npm --prefix frontend ci
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .

# Rebuild backend (SSE fix + DemoGuard + version bump)
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# Restart BOTH containers (with observability profile)
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --profile observability up -d backend frontend

# Wait for backend startup
sleep 15
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.29.4"}

# Cleanup old artifacts
docker image prune -f
rm -f backend/target/finding-a-bed-tonight-0.29.1.jar
rm -f backend/target/finding-a-bed-tonight-0.29.2.jar
rm -f backend/target/finding-a-bed-tonight-0.29.3.jar
```

## Post-Deploy Smoke Tests

### Automated verification (run from local machine)

```bash
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test tests/deploy-verify-v0.29.4.spec.ts \
  --reporter=list --trace on 2>&1 | tee ../../logs/deploy-verify-v0.29.4.log
```

Covers: version, health API, WCAG focus-visible + autocomplete, SSE stability,
DV canary, DemoGuard blocks, user workflows (search, hold, coordinator update).

### Manual checks (after automated passes)

**WCAG visual spot check (incognito):**
1. https://findabed.org/login → Tab through form → blue focus ring visible
2. Dark mode → refresh → lighter blue focus ring on inputs

**DemoGuard tunnel bypass (v0.29.3 — requires SSH tunnel):**
```bash
ssh -i ~/.ssh/fabt-oracle -L 8081:localhost:8081 ubuntu@${FABT_VM_IP} -N
```
Incognito → http://localhost:8081 → login as admin:
1. Create User → should succeed → delete the test user
2. Activate Surge → should succeed → deactivate
3. Close tunnel → public site → Create User → should show "disabled in demo environment"

## Backout Plan

If anything goes wrong — revert to v0.29.1:

```bash
cd ~/finding-a-bed-tonight
git checkout v0.29.1

npm --prefix frontend ci
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend
```

## Reference

- Cloudflare SSE settings: see `oracle-update-notes-v0.29.1.md`
- DemoGuard tunnel mechanism: see `oracle-update-notes-v0.29.3.md`
- WCAG ACR: `docs/WCAG-ACR.md`
