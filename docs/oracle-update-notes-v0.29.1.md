# Oracle Demo — Update to v0.29.1

**From:** v0.29.0
**Date:** April 4, 2026
**Changes:** Mobile header kebab menu (#55), 88 dark mode WCAG contrast fixes

## What changed

| Layer | Changed? | Details |
|-------|----------|---------|
| Backend | No | Version bump in pom.xml only — no code changes |
| Frontend | Yes | Layout.tsx (kebab menu), global.css (textMuted), AdminPanel/OutreachSearch/CoordinatorDashboard (contrast), ConnectionStatusBanner (banner contrast) |
| Static site | No | No changes to index.html, audience pages, or sitemap |
| Database | No | No new Flyway migrations |
| Docker/Nginx | No | No config changes |

## Pre-deploy checklist

- [ ] Verify current version: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.29"}`
- [ ] CI scans pass on merge commit

## Deploy steps

**Frontend-only — no backend restart needed.**

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

cd ~/finding-a-bed-tonight
git pull origin main

# Rebuild frontend only
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .

# Restart frontend container only (backend stays running)
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d frontend

# Cleanup
docker image prune -f
```

**Note:** No static content scp needed — no changes to `/var/www/findabed-docs/`.
**Note:** No Cloudflare sw.js purge needed — no-cache headers since v0.28.2.

## Verification

```bash
# 1. Version (unchanged — pom.xml bumped but backend not restarted)
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.29"} (backend still running v0.29.0 binary)

# 2. Mobile header — test in incognito with DevTools mobile emulation
# Open https://findabed.org in incognito, toggle device toolbar to 412px
# Login → verify kebab menu visible, dropdown works

# 3. Dark mode — DevTools → Rendering → prefers-color-scheme: dark
# Check admin panel tabs, role badges — text should be readable (#78a9ff blue, not dim)
```

## Rollback

```bash
# Frontend-only rollback
cd ~/finding-a-bed-tonight
git checkout v0.29.0
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d frontend
```

## Reference: Static content deployment

**Location on VM:** `/var/www/findabed-docs/`
**Deploy method:** `scp` from local machine — no nginx restart needed
**Not needed for v0.29.1** — no static content changes in this release.
