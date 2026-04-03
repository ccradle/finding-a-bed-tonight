# Oracle Demo — Update to v0.28.0

**From:** v0.27.0
**Date:** April 2, 2026
**Changes:** DemoGuardFilter, 29 error handling fixes, SSE Cloudflare header, --demo flag

## Pre-deploy checklist

- [ ] Verify `demo` is already in `SPRING_PROFILES_ACTIVE` in `~/fabt-secrets/.env.prod`
- [ ] Verify all 4 demo credentials work (`admin123`)

## Deploy steps

```bash
# 1. Pull latest main
cd ~/finding-a-bed-tonight
git pull origin main

# 2. Rebuild backend
cd backend && mvn package -DskipTests -q
cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# 3. Rebuild frontend (catch block fixes in AdminPanel, CoordinatorDashboard, ShelterEditPage, api.ts)
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .

# 4. Restart containers
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend

# 5. Wait for backend startup (~15 seconds)
sleep 15
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.28"}
```

## Verification

```bash
# Version
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.28"}

# Demo guard blocks destructive operation
TOKEN=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"outreach@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -s -X PUT https://findabed.org/api/v1/auth/password \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"admin123","newPassword":"hacked12345!"}'
# Expected: {"error":"demo_restricted","message":"Password changes are disabled in the demo environment. ..."}

# DV canary
curl -s -H "Authorization: Bearer $TOKEN" https://findabed.org/api/v1/shelters \
  | python3 -c "import sys,json; d=json.load(sys.stdin); dv=[s for s in d if s.get('isDomesticViolence')]; print(f'DV visible: {len(dv)} — {\"FAIL\" if dv else \"PASS\"}')"
```

## Post-deploy cleanup

```bash
# Remove old Docker images
docker image prune -f

# Remove old JARs
rm -f backend/target/finding-a-bed-tonight-0.27.0.jar
```

## What changed (summary)

1. **DemoGuardFilter** — blocks admin mutations for public traffic, allows SSH tunnel admin bypass
2. **29 catch block fixes** — frontend now shows actual API error messages instead of generic "Couldn't load your shelters"
3. **SSE X-Accel-Buffering** — already deployed in earlier session, now in codebase
4. **dev-start.sh --demo** — local testing flag
