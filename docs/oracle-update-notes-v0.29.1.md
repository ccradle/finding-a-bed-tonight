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
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}

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

## Reference: Cloudflare SSE Configuration

SSE (Server-Sent Events) requires specific Cloudflare settings to work reliably:

| Setting | Location | Value | Why |
|---------|----------|-------|-----|
| **HTTP/3 (QUIC)** | Speed → Optimization → Protocol | **Off** | QUIC kills long-lived SSE streams with `ERR_QUIC_PROTOCOL_ERROR`. HTTP/2 over TLS 1.3 is equally secure and improves WAF consistency. Fixed 2026-04-04. |
| **Proxy status** | DNS → findabed.org A record | **Proxied (orange cloud)** | Required for CDN/WAF. SSE works through proxy with HTTP/2. |
| **SSL mode** | SSL/TLS → Overview | **Full (Strict)** | Origin has valid Let's Encrypt cert. |
| **Always Use HTTPS** | SSL/TLS → Edge Certificates | **On** | Prevents mixed-content SSE connections. |

**sw.js caching:** Served with `no-cache` headers from nginx since v0.28.2. Cloudflare respects these — no manual purge needed after deploys.

**If SSE breaks again:** Check browser DevTools Console for `ERR_QUIC_PROTOCOL_ERROR` — means HTTP/3 got re-enabled (Cloudflare may re-enable on plan changes or dashboard updates). Fix: Speed → Optimization → Protocol → HTTP/3 → Off.

## Reference: Static content deployment

**Location on VM:** `/var/www/findabed-docs/`
**Deploy method:** `scp` from local machine — no nginx restart needed
**Not needed for v0.29.1** — no static content changes in this release.
