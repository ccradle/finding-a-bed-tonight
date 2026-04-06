# Oracle Demo — Update to v0.29.4

**From:** v0.29.1 (live)
**Date:** April 6, 2026
**Changes:** WCAG accessibility fixes, SSE lifecycle fix, DemoGuard tunnel bypass, Vite security patch

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
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

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

# Restart BOTH containers
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend

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

### Part A: Version and basic health

```bash
# From the VM
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.29.4"}

curl -s localhost:8080/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"
# Expected: UP
```

### Part B: WCAG verification (browser — use incognito!)

1. Open **incognito** → https://findabed.org/login
2. **Version check**: footer shows v0.29.4
3. **Autocomplete**: click on Email field → browser should offer autocomplete suggestions
4. **Focus visible (light mode)**: Tab through login form → blue focus ring visible on each input and Sign In button
5. **Focus visible (dark mode)**: Enable dark mode in OS → refresh → Tab through form → lighter blue focus ring visible
6. **SSE check**: Login as outreach worker → wait 60 seconds → no "Reconnecting to live updates..." banner (v0.29.2 fix)

### Part C: DemoGuard tunnel bypass (v0.29.3)

```bash
# In a separate terminal:
ssh -i ~/.ssh/fabt-oracle -L 8081:localhost:8081 ubuntu@150.136.221.232 -N
```

In **incognito** browser: http://localhost:8081 → login as admin:
1. Create User → should succeed → delete the test user
2. Activate Surge → should succeed → deactivate
3. Wait 60 seconds → no "Reconnecting" banner

### Part D: Public still protected

1. Close SSH tunnel
2. New incognito → https://findabed.org → login as admin
3. Create User → should show "disabled in demo environment"
4. Verify all 4 demo credentials work (admin123)

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
