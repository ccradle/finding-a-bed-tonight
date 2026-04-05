# Oracle Demo — Update to v0.29.2

**From:** v0.29.1
**Date:** April 5, 2026
**Changes:** SSE emitter lifecycle fix — eliminates cascading errors and "Reconnecting" banner

## What changed

| Layer | Changed? | Details |
|-------|----------|---------|
| Backend | Yes | NotificationService (emitter error handling), SecurityConfig (async dispatch), application.yml (async timeout) |
| Frontend | No | No changes |
| Static site | No | No changes |
| Database | No | No new migrations |
| Docker/Nginx | No | No config changes |

## Pre-deploy checklist

- [ ] Verify current version: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.29"}`
- [ ] CI scans pass
- [ ] Cloudflare HTTP/3 still OFF (Speed → Optimization → Protocol)

## Deploy steps

**Backend-only — no frontend restart needed.**

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

cd ~/finding-a-bed-tonight
git pull origin main

# Rebuild backend
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# Restart backend only
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend

# Wait for startup
sleep 15
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.29"}

# Cleanup
docker image prune -f
rm -f backend/target/finding-a-bed-tonight-0.29.1.jar
```

**Note:** No frontend rebuild, no static content scp, no Cloudflare purge.

## Verification

```bash
# 1. Version
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.29"}

# 2. Monitor logs for 5 minutes — ZERO AsyncContext/Security errors expected
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232 \
  "docker logs -f fabt-backend 2>&1 | grep -i 'AsyncContext\|response has already been committed\|Cannot render error page'"
# Expected: no output (previously 195+ errors)

# 3. Verify in incognito browser — NO "Reconnecting" banner
# Open https://findabed.org in incognito, login, wait 60 seconds
# The "Reconnecting to live updates..." banner should NOT appear

# 4. Check WARN logs for expected emitter lifecycle messages
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232 \
  "docker logs fabt-backend 2>&1 | grep 'Heartbeat failed\|emitter error\|emitter timed out' | tail -5"
# Expected: WARN-level messages with userId (healthy cleanup, not ERROR cascades)
```

## Rollback

```bash
cd ~/finding-a-bed-tonight
git checkout v0.29.1
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend
```

## Reference: Cloudflare SSE Configuration

SSE requires specific Cloudflare settings — see `oracle-update-notes-v0.29.1.md` for the full table.
Key: HTTP/3 (QUIC) must be **OFF**. If SSE breaks, check DevTools Console for `ERR_QUIC_PROTOCOL_ERROR`.
