# Oracle Demo — Update to v0.29.0

**From:** v0.28.3
**Date:** April 4, 2026
**Changes:** DV referral expiration UI (#31), README accuracy (#40), audience HTML pages (#39)

## What changed

| Layer | Changed? | Details |
|-------|----------|---------|
| Backend | Yes | `expireTokens()` publishes SSE event, `NotificationService` handles `dv-referral.expired` |
| Frontend | Yes | Countdown timer, expired badge, disabled buttons, SSE listener, i18n |
| Static site | Yes | 3 new audience pages, index.html link updates |
| Database | No | No new Flyway migrations |
| Docker/Nginx | No | No config changes |

## Pre-deploy checklist

- [ ] Verify SSH access: `ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232`
- [ ] Verify current version: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.28"}`
- [ ] Verify all 4 demo credentials work (`admin123`)

## Deploy steps

### Step 1: Static content (docs site)

Static content is served from `/var/www/findabed-docs/` on the Oracle VM.
Nginx serves these via `try_files` — no restart needed after copying.

```bash
# From your local machine (Windows / Git Bash):

# Audience pages + for-cities.html (resync)
scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/demo/for-coordinators.html \
  /c/Development/findABed/demo/for-coc-admins.html \
  /c/Development/findABed/demo/for-funders.html \
  /c/Development/findABed/demo/for-cities.html \
  ubuntu@150.136.221.232:/var/www/findabed-docs/demo/

# Homepage + sitemap (updated links and new page entries)
scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/index.html \
  /c/Development/findABed/sitemap.xml \
  ubuntu@150.136.221.232:/var/www/findabed-docs/

# Verify on VM:
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232 \
  "ls -la /var/www/findabed-docs/demo/for-*.html && echo '---' && grep 'for-' /var/www/findabed-docs/sitemap.xml"
# Expected: 4 for-*.html files, 4 sitemap entries
```

### Step 2: Application code (backend + frontend)

```bash
# SSH into the VM
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# Pull latest main
cd ~/finding-a-bed-tonight
git pull origin main

# Rebuild backend (new JAR: finding-a-bed-tonight-0.29.0.jar)
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .

# Rebuild frontend (CoordinatorDashboard, useNotifications, i18n changes)
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .

# Restart both containers
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend

# Wait for backend startup (~15 seconds)
sleep 15
curl -s localhost:8080/api/v1/version
# Expected: {"version":"0.29"}
```

### Step 3: Post-deploy cache + cleanup

```bash
# On the VM — remove old artifacts (Oracle Always Free has limited disk)
docker image prune -f
rm -f backend/target/finding-a-bed-tonight-0.28.3.jar

# Cloudflare sw.js purge is NOT needed — since v0.28.2, nginx serves sw.js
# with no-cache headers and Cloudflare respects them. Edge caching is prevented
# at the origin. Only purge if you suspect Cloudflare is ignoring cache-control.
```

## Verification

```bash
# 1. Version check
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.29"}

# 2. Static content — new audience pages load
curl -s -o /dev/null -w "%{http_code}" https://findabed.org/demo/for-coordinators.html
curl -s -o /dev/null -w "%{http_code}" https://findabed.org/demo/for-coc-admins.html
curl -s -o /dev/null -w "%{http_code}" https://findabed.org/demo/for-funders.html
# Expected: 200 200 200

# 3. Homepage links updated (no GitHub markdown links)
curl -s https://findabed.org/index.html | grep -c "github.com/ccradle.*FOR-"
# Expected: 0

# 4. Sitemap includes new audience pages
curl -s https://findabed.org/sitemap.xml | grep -c "for-"
# Expected: 4 (coordinators, coc-admins, cities, funders)

# 4. DV referral expiration — verify SSE event type is registered
# (functional test: create referral via UI, wait for countdown to appear)

# 5. DV canary — non-DV user should NOT see DV shelters
TOKEN=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"outreach@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -s -H "Authorization: Bearer $TOKEN" https://findabed.org/api/v1/shelters \
  | python3 -c "import sys,json; d=json.load(sys.stdin); dv=[s for s in d if s.get('isDomesticViolence')]; print(f'DV visible: {len(dv)} — {\"FAIL\" if dv else \"PASS\"}')"

# 6. Test in incognito browser — verify no stale SW caching old UI
```

## Rollback

If something goes wrong:

```bash
# Revert to previous git commit
cd ~/finding-a-bed-tonight
git checkout v0.28.3

# Rebuild and restart
cd backend && mvn package -DskipTests -q && cd ..
docker build -t fabt-backend:latest -f infra/docker/Dockerfile.backend .
npm --prefix frontend run build
docker build -t fabt-frontend:latest -f infra/docker/Dockerfile.frontend .
docker compose --env-file ~/fabt-secrets/.env.prod \
  -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  up -d backend frontend

# Static content rollback (restore from git):
# git checkout v0.28.3 -- (does not apply — static is in docs repo)
# Manual: scp old index.html back, remove new audience pages
```

---

## Reference: Cloudflare SSE Configuration

SSE (Server-Sent Events) requires specific Cloudflare settings to work reliably:

| Setting | Location | Value | Why |
|---------|----------|-------|-----|
| **HTTP/3 (QUIC)** | Speed → Optimization → Protocol | **Off** | QUIC kills long-lived SSE streams with `ERR_QUIC_PROTOCOL_ERROR`. HTTP/2 over TLS 1.3 is equally secure and improves WAF consistency. Fixed 2026-04-04. |
| **Proxy status** | DNS → findabed.org A record | **Proxied (orange cloud)** | Required for CDN/WAF. SSE works through proxy with HTTP/2. |
| **SSL mode** | SSL/TLS → Overview | **Full (Strict)** | Origin has valid Let's Encrypt cert. |
| **Always Use HTTPS** | SSL/TLS → Edge Certificates | **On** | Prevents mixed-content SSE connections. |

**If SSE breaks again:** Check browser DevTools Console for `ERR_QUIC_PROTOCOL_ERROR` — means HTTP/3 got re-enabled. Fix: Speed → Optimization → Protocol → HTTP/3 → Off.

## Reference: Cloudflare cache purge

**When needed:** Only if Cloudflare starts caching static HTML (currently `cf-cache-status: DYNAMIC` — not cached).
**Method:** Cloudflare Dashboard → findabed.org → Caching → Purge Cache → Custom Purge → enter URLs.
**sw.js:** No longer needs purging — nginx serves with `no-cache` headers since v0.28.2.

## Reference: Static content deployment

**Location on VM:** `/var/www/findabed-docs/`
**Nginx config:** `/etc/nginx/sites-enabled/default` — `location / { root /var/www/findabed-docs; try_files $uri $uri/ @app; }`
**Deploy method:** `scp` from local machine to VM
**No nginx restart needed** — files are picked up immediately via `try_files`

**Current contents:**
- `index.html` — findabed.org homepage
- `favicon.svg`, `robots.txt`, `sitemap.xml`, `404.html` — standard assets
- `demo/` — walkthrough pages, screenshots, audience pages (`for-cities.html`, `for-coordinators.html`, etc.)
- `.nojekyll`, `BingSiteAuth.xml`, `google*.html` — verification/config files
