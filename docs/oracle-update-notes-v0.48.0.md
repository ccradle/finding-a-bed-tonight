# Oracle Deploy Notes — v0.48.0 (Phase D core + two demo tenants live in prod)

**From:** v0.47.0 (currently live at `findabed.org` — single `dev-coc` tenant; Phase C cache isolation active; all 9 Phase C alert rules loaded; per-tenant NOAA weather-station code present but unexercised because only one tenant exists)
**To:** v0.48.0 (three tenants live — `dev-coc` + `dev-coc-west` (Blue Ridge) + `dev-coc-east` (Pamlico Sound); `TenantPathGuard` D11 controller-layer 404 on URL-path cross-tenant writes; per-tenant NOAA stations `KAVL` + `KEWN` exercised)
**Release-prep commit SHA:** filled in at tag time (see pre-deploy step 5.5 — `git rev-parse HEAD` on `main` immediately before `git tag -a`). The tag attaches to the release-prep commit (pom bump + CHANGELOG + this runbook), NOT to PR #143's merge commit.

> **Jordan's note (SRE):** Low-friction release relative to v0.43 / v0.44 / v0.47.
> Two Flyway migrations (V76 + V77) add **rows** to existing tables — no
> DDL, no index rebuild, no collation concern, no column change. Idempotent
> `ON CONFLICT DO UPDATE` on identity rows (tenant / user / shelter /
> shelter_constraints) + `DO NOTHING` on bed_availability (no natural
> unique key).
>
> **No base compose change.** Unlike v0.47 which edited the Prometheus
> service block, v0.48 leaves `docker-compose.yml` untouched. The 4-file
> override chain from v0.45+ stays. No `--force-recreate` needed. No
> Prometheus reload. No Postgres restart.
>
> **No new Prometheus alerts.** Unlike v0.47's 4 Phase C alerts, v0.48
> adds zero rules. The 9 rules from Phase B (5) + Phase C (4) carry
> forward unchanged. Alertmanager routing remains pending per task #155
> — rules fire in the Prometheus UI but do not page Slack / email. On-call
> eyeballs the Prometheus alert dashboard manually. Same posture as v0.47.
>
> **ROLLBACK CAVEAT** — V76 + V77 insert two new rows into the `tenant`
> table plus six users + three shelters each. If rolled back to v0.47.0,
> the older code will happily read the additional tenants (tenant lookup
> is runtime-dynamic via DB) and the new tenants are simply invisible to
> users who don't know their slugs. Flyway on startup will detect V76/V77
> rows in `flyway_schema_history` not present in the v0.47.0 classpath and
> either fail validation or skip, depending on `ignoreMigrationPatterns`
> config. The safer rollback is **forward-roll to v0.48.1 hotfix** rather
> than revert to v0.47.0. Full revert requires a pg_dump restore to clear
> V76/V77 schema_history rows. See "Rollback" section for the decision
> tree.

**Bake window math:** v0.47.0 shipped 2026-04-20 ~01:08 UTC → v0.48.0
can tag after **2026-04-21 ~01:08 UTC** without breaching the 24h
guardrail. No compression approved by default — v0.47 was a behavioural-
change release (cache isolation); v0.48 is the second half of Phase C
fallout (multi-tenant visibility on prod) and is load-bearing for demo
realism. The bake is warranted.

---

## What's New in This Deploy

### Flyway V76 — Blue Ridge CoC (demo) tenant seed

`backend/src/main/resources/db/migration/V76__seed_blue_ridge_demo_tenant.sql`

| Artifact | Count | Identity |
|---|---|---|
| Tenant rows | 1 | slug=`dev-coc-west`, UUID=`a0000000-...-000002`, name=`Blue Ridge CoC (demo)` |
| `app_user` rows | 6 | `admin` / `cocadmin` / `coordinator` / `outreach` / `dv-coordinator` / `dv-outreach` at `@blueridge.fabt.org` |
| `shelter` rows | 3 | Example House North (Boone), Blue Ridge Example Shelter (Waynesville), Safe Haven Demo DV West (Undisclosed) |
| `shelter_constraints` rows | 3 | Matches shelter rows |
| `bed_availability` rows | 5 | 3 shelters × population-type splits |

Tenant.config JSONB embeds `noaa_station_id: KAVL` (Asheville Regional
Airport — covers Boone + Waynesville within a few miles). Per-tenant-
weather-station feature (shipped in v0.47.0 code) comes to life for the
first time on prod.

All INSERTs use `ON CONFLICT DO UPDATE SET ...` on tenant / user /
shelter / shelter_constraints so a re-run with an edited seed actually
updates the row (the prior `DO NOTHING` silently dropped edits — caught
during v0.48 local rehearsal). `bed_availability` uses `DO NOTHING`
because it lacks a natural unique key.

### Flyway V77 — Pamlico Sound CoC (demo) tenant seed

`backend/src/main/resources/db/migration/V77__seed_pamlico_sound_demo_tenant.sql`

Parallel to V76. `dev-coc-east` tenant, 6-role user matrix, three
shelters (New Bern, Washington NC, Undisclosed), `noaa_station_id:
KEWN` (New Bern Craven County Regional — coastal).

### `TenantPathGuard` D11 live on 9 controller endpoints

`backend/src/main/java/org/fabt/shared/web/TenantPathGuard.java` — new
helper. `requireMatchingTenant(UUID pathTenantId)` throws
`NoSuchElementException` (rendered as `404` by `GlobalExceptionHandler`)
when the URL-path tenantId differs from `TenantContext.getTenantId()`.

Applied to:

| Controller | Endpoint | Method |
|---|---|---|
| `TenantController` | `/api/v1/tenants/{id}` | PUT (update) |
| `TenantController` | `/api/v1/tenants/{id}/observability` | GET + PUT |
| `TenantController` | `/api/v1/tenants/{id}/dv-address-policy` | PUT |
| `TenantConfigController` | `/api/v1/tenants/{tenantId}/config` | GET + PUT |
| `OAuth2ProviderController` | `/api/v1/tenants/{tenantId}/oauth2-providers` | POST + GET |
| `OAuth2ProviderController` | `/api/v1/tenants/{tenantId}/oauth2-providers/{providerId}` | PUT + DELETE |

**Behavioural change** — previously an admin in Tenant A could send
`PUT /api/v1/tenants/{tenantB-uuid}/config` and the request would reach
the service layer (which rejected via its own `findByIdAndTenantId` guard
or `TenantContext`-sourcing). Now the controller returns `404` before
the service is invoked — symmetric with D3's existence-leak posture.
Per D15, PLATFORM_ADMIN is tenant-scoped; bootstrap-era cross-tenant
admin writes are deliberately gone. Phase F (later) will add a narrow
platform-operator role.

### Tenant-identity UI surfaces

`frontend/src/components/Layout.tsx`:
- Header chip (desktop) — neutral border-only pill left of the user
  menu, `data-testid="app-tenant-name"`, colour via `color.headerText`
  token (WCAG-verified against `color.headerBg` — no rgba opacity)
- Footer — appends ` — {tenantName}` to the version line, wrapped in
  `data-testid="app-tenant-name-footer"`
- Mobile kebab — new row `data-testid="header-overflow-tenant-name"` at
  the top of the dropdown so mobile users have the same wayfinding

Frontend rebuild required for this release (SW cycle applies).

### Integration tests

`TenantPathGuardIntegrationTest` — 11 cases: 10 cross-tenant attack
vectors covering every D11-guarded endpoint plus two nested-resource
attacks (`PUT` + `DELETE` `/tenants/{A}/oauth2-providers/{B-provider-id}`)
that verify the service-layer `findByIdAndTenantId` defence-in-depth,
plus 1 same-tenant control.

`TenantIntegrationTest.test_updateTenant` refactored into two tests
(`test_updateTenant_selfTenant_ok` + `test_updateTenant_otherTenant_rejectedBy_D11_guard`)
reflecting D15 tenant-scoping.

---

## What Does NOT Change

| Surface | Status |
|---|---|
| `docker-compose.yml` base file | **unchanged** |
| `docker-compose.prod.yml` secrets override | unchanged |
| `docker-compose.prod-v0.43-flyway-ooo.yml` | **unchanged** — out-of-order bridge carries forward |
| `docker-compose.prod-v0.44-pgaudit.yml` | unchanged — pgaudit image tag unchanged |
| `.env.prod` | unchanged |
| Postgres image | unchanged (Debian-based pgaudit image from v0.44.1) |
| pgaudit config | unchanged — no new logging rules |
| Prometheus config (`prometheus.yml`) | unchanged — `env=prod` scrape label stays |
| Prometheus alert rules | unchanged — 9 rules (5 Phase B + 4 Phase C) carry forward |
| Alertmanager routing | pending (task #155) — same posture as v0.47 |
| FORCE RLS posture | unchanged — 7 regulated tables still 1.0 |
| RLS policies | unchanged — no policy bytes changed; release-gate SHA pin still valid |
| Frontend npm dependencies | unchanged — only `Layout.tsx` edited |
| FABT_ENCRYPTION_KEY / secrets | unchanged |

This is the shortest "What Does NOT Change" list since v0.42 because
the surface is genuinely small.

---

## VM-specific paths

- Repo on VM: `~/finding-a-bed-tonight/`
- Secrets: `~/fabt-secrets/docker-compose.prod.yml` + `~/fabt-secrets/.env.prod`
- 4-file compose override chain (unchanged from v0.45+):
  1. `docker-compose.yml` (base)
  2. `~/fabt-secrets/docker-compose.prod.yml` (secrets + port bindings)
  3. `docker-compose.prod-v0.43-flyway-ooo.yml` (Flyway out-of-order bridge)
  4. `docker-compose.prod-v0.44-pgaudit.yml` (pgaudit image override)
- Backups: `~/fabt-backups/` (`pg_dump -Fc` custom-archive format)
- SSH key: `~/.ssh/fabt-oracle` (local) → `ubuntu@150.136.221.232`

---

## Pre-deploy sanity

### 1. Confirm CI green on the release-prep commit SHA

On the release-prep commit (after CHANGELOG / pom / runbook commits land
on `main` post-PR-#143-merge), every required check must be green:

- Backend (Java 25 + Maven)
- E2E (Playwright + Karate)
- CodeQL
- Legal Language Scan
- Flyway HWM guard (v0.45 renumber-forward) — V76 + V77 above the HWM of V74, should green
- Release-gate SHA-256 pin verify
- pgaudit image tests (Debian + PGDG)
- Phase B RLS test-discipline
- DV Access Control Canary
- Family C annotation review gate (4.5)

If any are red, **abort**. Do not tag.

### 2. Verify v0.47.0 is live + healthy

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# Version probe
curl -s https://findabed.org/api/v1/version | jq .
# expected: {"version":"0.47.0", ...}

# Health
curl -s http://localhost:9091/actuator/health | jq .
# expected: {"status":"UP", ...}

# FORCE RLS 7 regulated tables still 1.0
docker exec finding-a-bed-tonight-postgres-1 \
    psql -U fabt -d fabt -c "
    SELECT relname, relforcerowsecurity
    FROM pg_class
    WHERE relname IN ('audit_events','hmis_audit_log','app_user',
                      'referral_token','one_time_access_code',
                      'subscription','password_reset_token')
    ORDER BY relname;"
# All 7 rows must show relforcerowsecurity = t

# NULL-tenant audit backfill verification (carry-forward from v0.43.1)
docker exec finding-a-bed-tonight-postgres-1 \
    psql -U fabt -d fabt -c "
    SELECT COUNT(*) FROM audit_events WHERE tenant_id IS NULL;
    SELECT COUNT(*) FROM hmis_audit_log WHERE tenant_id IS NULL;"
# Both must be 0 (backfill-to-SYSTEM_TENANT_ID applied at v0.43.1)

# Flyway HWM on prod
docker exec finding-a-bed-tonight-postgres-1 \
    psql -U fabt -d fabt -c "
    SELECT MAX(CAST(version AS INTEGER)) AS prod_hwm
    FROM flyway_schema_history WHERE success = true;"
# expected: 74 (V74 is the last migration prod has applied)
```

If any check fails: **abort** — file incident, investigate, do not deploy v0.48 on top of an unhealthy v0.47.

### 3. Flyway V76 + V77 dry-run on throwaway container

Jordan's gate — catch idempotency or UPSERT drift **before** touching prod.

```bash
# On laptop or VM, spin a throwaway Postgres with the same pgaudit image
docker run --rm -d --name fabt-pg-rehearse \
    -e POSTGRES_PASSWORD=rehearse \
    -e POSTGRES_DB=fabt \
    -p 5438:5432 \
    fabt-postgres:16.6-pgaudit-debian

# Apply v0.47's full pg_dump so we start from a realistic state (prod-analog)
docker exec -i fabt-pg-rehearse pg_restore -U postgres -d fabt \
    < ~/fabt-backups/fabt-v0.47.0-post-deploy.dump

# Checkout v0.48.0 locally + boot the backend against the throwaway PG
cd ~/finding-a-bed-tonight
git fetch origin && git checkout v0.48.0  # or the release-prep SHA
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5438/fabt
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=rehearse
cd backend && mvn spring-boot:run

# From another terminal: verify V76 + V77 applied + idempotent on second run
docker exec fabt-pg-rehearse psql -U postgres -d fabt -c "
    SELECT version, description, success
    FROM flyway_schema_history
    ORDER BY installed_rank DESC LIMIT 5;"
# expected top two rows: 77 seed pamlico... t  |  76 seed blue ridge... t

# Verify tenant row + bed_availability rows landed
docker exec fabt-pg-rehearse psql -U postgres -d fabt -c "
    SELECT slug, name, config::jsonb #>> '{observability,noaa_station_id}' AS noaa
    FROM tenant WHERE slug LIKE 'dev-coc%' ORDER BY slug;"
# expected 3 rows: dev-coc (null), dev-coc-east (KEWN), dev-coc-west (KAVL)

# Idempotency re-apply test — stop backend, restart, confirm no errors
# Flyway checksum-validates installed migrations; nothing should change

# Tear down
docker stop fabt-pg-rehearse
```

If any step fails: **abort** — triage the migration before tag.

### 4. Compose dry-render — Jordan's mandatory gate

```bash
cd ~/finding-a-bed-tonight
git fetch origin
git checkout v0.48.0

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f docker-compose.prod-v0.43-flyway-ooo.yml \
    -f docker-compose.prod-v0.44-pgaudit.yml \
    config > /tmp/v0.48-config.rendered.yml

# Compare with the currently-running v0.47 render
diff /tmp/v0.47-config.rendered.yml /tmp/v0.48-config.rendered.yml
# EXPECTED: zero byte diff. All four files are unchanged between v0.47 and v0.48.
```

**If diff is non-zero: STOP.** This is the single most common incident
source per the v0.42 / v0.45 / v0.46 / v0.47 warrooms. Any divergence
means an override file drifted — investigate before proceeding.

### 5. pg_dump belt-and-suspenders backup

Even though V76/V77 are data-only INSERTs (not DDL), Jordan requires a
backup before any migration apply. V76/V77 touch `tenant`, `app_user`,
`shelter`, `shelter_constraints`, `bed_availability` — identity data.

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# Create dump (custom-archive format per v0.43.1 precedent — NOT .sql.gz)
DUMP_FILE=~/fabt-backups/fabt-pre-v0.48-$(date +%Y%m%d-%H%M%S).dump
docker exec finding-a-bed-tonight-postgres-1 \
    pg_dump -U fabt -d fabt -Fc > $DUMP_FILE

# SHA-256 pin so we can verify integrity at rollback time
sha256sum $DUMP_FILE | tee $DUMP_FILE.sha256

# Verify dump size is non-zero + within expected range
ls -la $DUMP_FILE
# Expected: ~500KB-5MB depending on audit_events growth since v0.47
```

Keep the dump path + SHA for the rollback section.

### 5.5. Capture the release-prep commit SHA

Before tagging, record the commit SHA that the tag will attach to:

```bash
cd ~/finding-a-bed-tonight
git fetch origin main
git rev-parse origin/main
# Copy this SHA — it fills in the "Release-prep commit SHA" field at the
# top of this document AND the annotated-tag body.
```

### 6. Tag + push

```bash
git tag -a v0.48.0 <release-prep-SHA> -m "$(cat <<'EOF'
v0.48.0 — Phase D core: URL-path-sink tenant guard + two demo tenants live

Behavioural-change release. Controller-layer D11 guard on 9 endpoints
returns 404 on URL-path cross-tenant attempts. Flyway V76 + V77 seed
Blue Ridge + Pamlico Sound demo tenants with per-tenant NOAA station
IDs. Tenant-identity chip lands in Layout header + footer + mobile
kebab. No schema change beyond seed migrations; no Prometheus, compose,
or pgaudit config changes.

See CHANGELOG.md + docs/oracle-update-notes-v0.48.0.md for full detail.
EOF
)"

git push origin v0.48.0
```

---

## Deploy steps

### 1. Checkout tag + preserve last-good backend image

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232
cd ~/finding-a-bed-tonight
git fetch --tags origin
git checkout v0.48.0

# Preserve the currently-running v0.47.0 image for fast rollback
docker tag fabt-backend:latest fabt-backend:v0.47.0-lastgood
docker images fabt-backend
# Expect two tags now: latest + v0.47.0-lastgood
```

### 2. Build backend JAR + image (mvn clean package + docker --no-cache)

**Per `feedback_deploy_old_jars.md` + `feedback_deploy_checklist_v031.md` —
NEVER skip `clean`; ALWAYS use `--no-cache`.**

```bash
cd ~/finding-a-bed-tonight/backend
mvn clean package -DskipTests
# Verify JAR is timestamped NOW + contains v0.48.0 in name
ls -la target/finding-a-bed-tonight-0.48.0.jar

cd ~/finding-a-bed-tonight
docker build --no-cache -t fabt-backend:latest -f deploy/backend.Dockerfile .

# Verify image SHA is new (not reused from v0.47 cached layer)
docker images fabt-backend --format '{{.Tag}} {{.ID}} {{.CreatedAt}}'
```

### 3. Build frontend (SW cycle applies)

```bash
cd ~/finding-a-bed-tonight/frontend
npm ci
npm run build

# Verify new asset hashes (SW will cycle on next visit)
ls -la dist/assets/index-*.js | head -3
```

### 4. Restart backend + frontend only (no Postgres, no Prometheus)

```bash
cd ~/finding-a-bed-tonight

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f docker-compose.prod-v0.43-flyway-ooo.yml \
    -f docker-compose.prod-v0.44-pgaudit.yml \
    up -d --build backend

# Tail backend logs for Flyway V76 + V77 apply success
docker logs -f finding-a-bed-tonight-backend-1 2>&1 | grep -iE "flyway|V76|V77|Started|error"
# Expected messages:
#   Migrating schema "public" to version "76 - seed blue ridge demo tenant"
#   Successfully applied 2 migrations to schema "public", now at version v77
#   Started Application in N seconds
# ^C after "Started Application"

# Restart nginx/frontend container to pick up new built assets
docker compose ... restart frontend
```

Expected total window: **~2-3 minutes**. No Postgres restart — pgaudit
keeps flowing. No Prometheus restart — alerts stay alive.

---

## Post-deploy sanity checks

### 1. Version confirmation

```bash
curl -s https://findabed.org/api/v1/version | jq .
# expected: {"version":"0.48.0", ...}
```

### 2. Flyway history shows V76 + V77 applied

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
    SELECT version, description, success, installed_on
    FROM flyway_schema_history
    WHERE version::int >= 76
    ORDER BY installed_rank DESC;"
# expected:
#  77 | seed pamlico sound demo tenant | t | <now>
#  76 | seed blue ridge demo tenant    | t | <now>
```

### 3. Three tenants visible + per-tenant NOAA stations

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
    SELECT slug, name, config::jsonb #>> '{observability,noaa_station_id}' AS noaa
    FROM tenant WHERE slug LIKE 'dev-coc%' ORDER BY slug;"
# expected 3 rows:
#   dev-coc      | Development CoC          | (null — fallback to KRDU)
#   dev-coc-east | Pamlico Sound CoC (demo) | KEWN
#   dev-coc-west | Blue Ridge CoC (demo)    | KAVL
```

### 4. FORCE RLS still 1.0 across the 7 regulated tables

Same query as pre-deploy step 2. Must stay green — if any flipped to `f`,
rollback immediately.

### 5. TenantPathGuard 404 smoke (new in v0.48)

From the VM, attempt a cross-tenant PUT and verify 404. Requires an
admin token for a known tenant; use the Playwright smoke suite rather
than hand-rolling tokens here.

```bash
# From local laptop
cd finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org \
BASE_URL=https://findabed.org \
    npx playwright test --config=deploy/playwright.config.ts \
    --reporter=line \
    -g "Blue Ridge|Pamlico|Cross-tenant"
# Expected: 3/3 green
#   13. Blue Ridge admin can log in to dev-coc-west
#   14. Pamlico Sound admin can log in to dev-coc-east
#   15. Cross-tenant login rejected — expects 401
```

### 6. Per-tenant temperature endpoint reports correct station

After the monitor's 90s `initialDelay` has elapsed (so the first run
has populated the per-tenant cache):

```bash
# Log in as each tenant and hit /api/v1/monitoring/temperature
# (see FOR-DEVELOPERS.md for token extraction pattern)

# Blue Ridge expects stationId: KAVL
# Pamlico expects stationId: KEWN
# dev-coc expects stationId: KRDU (fallback)
```

If a tenant reports the wrong station, the `ObservabilityConfigService`
60s cache hasn't refreshed OR the 90s `initialDelay` hasn't fired.
Wait 2 minutes and retry.

### 7. Frontend SW cycle reminder

Pilot users (Sarah Dickerson + friends) may see the old UI until their
service worker cycles. Include hard-reload guidance in the post-deploy
comms:

> "We pushed an update with a small UI improvement — if the site looks
> unchanged after 30 seconds, please do a hard-reload (Ctrl+Shift+R on
> Windows/Linux, Cmd+Shift+R on Mac)."

### 8. Playwright full post-deploy smoke against prod

```bash
cd finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org \
BASE_URL=https://findabed.org \
    npx playwright test --config=deploy/playwright.config.ts \
    --reporter=line --retries=1 \
    | tee /tmp/v0.48-post-deploy-smoke.log
```

Expected: all specs green. Occasional flaky retries on cold-JVM login
timeouts are acceptable (documented pattern since v0.43.1); 3+
consecutive retries on the same test = investigate.

---

## Rollback

Two paths — pick based on observed failure mode.

### Path A: forward-roll to v0.48.1 hotfix (preferred)

For any **non-catastrophic** regression (wrong UI copy, a single
endpoint returning wrong shape, etc.): leave v0.48 data in place, fix
the code, release v0.48.1 within 24h.

Advantages:
- No pg_dump restore (V76/V77 rows stay in place — they're additive)
- No risk of Flyway validation failure on older code reading newer `flyway_schema_history`
- Less recovery time for end-users

### Path B: full revert to v0.47.0 (only if catastrophic)

For database corruption, cross-tenant data leak, or Phase B FORCE RLS
downgrade: full revert required.

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232
cd ~/finding-a-bed-tonight

# Restore the pg_dump from pre-deploy step 5
docker exec -i finding-a-bed-tonight-postgres-1 \
    pg_restore -U fabt -d fabt --clean --if-exists \
    < ~/fabt-backups/fabt-pre-v0.48-<TIMESTAMP>.dump

# Verify restored Flyway HWM is 74 (pre-V76/V77)
docker exec finding-a-bed-tonight-postgres-1 \
    psql -U fabt -d fabt -c "
    SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = true;"
# expected: 74

# Retag last-good image as latest
docker tag fabt-backend:v0.47.0-lastgood fabt-backend:latest

# Restart backend
docker compose ... up -d --force-recreate backend

# Verify version back to 0.47.0
curl -s https://findabed.org/api/v1/version | jq .
```

### Rollback criteria matrix

| Symptom | Action |
|---|---|
| TenantPathGuard returning 404 on same-tenant PUT (D11 mis-fires) | Forward-roll (Path A) — fix test condition, ship v0.48.1 |
| Blue Ridge or Pamlico login returns 401 | Investigate seed migration — probably not fatal; forward-roll |
| Cross-tenant data visible in one tenant's search results | **Path B immediately** — DV-safety regression |
| FORCE RLS gauge drops to 0 on any of 7 tables | **Path B immediately** — Phase B posture regression |
| /api/v1/monitoring/temperature returns the wrong station for >5 min | Forward-roll — cache config issue, not structural |
| Frontend hangs on login page | Hard-reload reminder first, then forward-roll |
| Postgres process crashloops | **Path B + file incident** — backup restore required |

---

## After deploy succeeds

### 1. Update memory

Edit `~/.claude/projects/C--Development-findABed/memory/project_live_deployment_status.md`
to record v0.48.0 as live + note the three-tenant state.

### 2. Confirm bake window before next release

v0.48.0 → v0.49.0 must observe the 24h guardrail unless a hotfix is
required. No v0.49 tag before **2026-04-22 ~01:08 UTC + deploy time**.

### 3. Public comms posture

**DO NOT announce "multi-tenant demo live"** in any public channel
(GitHub Discussions, release notes, LinkedIn, etc.) until Phase M proper
(task 14.9-14.17) lands the visual polish + walkthrough docs. Per
Casey (branding lens): three tenants in prod without accent colors or
tenant-specific `<title>` feels incomplete for a public announcement.

Internal / operator comms (Slack #fabt-demo) are fine — flag that
`admin@blueridge.fabt.org` and `admin@pamlico.fabt.org` with password
`admin123` now work on findabed.org and that the tenant header chip +
footer show which tenant you're in.

### 4. 1-hour active watch

Watch the Prometheus UI (since Alertmanager routing still pending per
#155) for:
- Any `FabtPhaseC*` alert firing (cross-tenant cache read, malformed
  entry, hit-rate collapse, detached audit fail)
- Any `FabtPhaseB*` alert firing (tenant-context gauge, audit persist
  failure, RLS panic)
- Backend p99 latency staying under 500ms (no cache-miss storm from
  the new tenants eating cache capacity)

No action needed if everything stays green — just eyeball and log into
the deploy incident thread.

### 5. Known posture to disclose in public-facing demo docs

- **Shared demo bcrypt**: `admin123` is the password for every
  PLATFORM_ADMIN / COC_ADMIN / OUTREACH_WORKER seeded by V76 + V77.
  This is a known dev/demo posture (same as `dev-coc` which has shipped
  since v0.21.0); no real PII, no compliance implication. Flag in the
  FOR-COORDINATORS audience doc that demo passwords are not secrets.
- **Alertmanager routing pending** (task #155, carry-forward from
  v0.47) — Prometheus rules fire in the UI but don't page Slack.
- **Per-shelter weather station** (future): v0.48 uses one NOAA station
  per tenant. Large CoCs spanning multiple microclimates may need a
  per-shelter station (Option C in the per-tenant-weather-station
  design). Tracked as task 14.w-longterm.

---

## Quick reference — one-screen deploy

```bash
# On VM
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232
cd ~/finding-a-bed-tonight

# Pre-deploy
git fetch --tags origin && git checkout v0.48.0
docker tag fabt-backend:latest fabt-backend:v0.47.0-lastgood
pg_dump -Fc backup → ~/fabt-backups/fabt-pre-v0.48-*.dump

# Build
cd backend && mvn clean package -DskipTests
cd .. && docker build --no-cache -t fabt-backend:latest -f deploy/backend.Dockerfile .
cd frontend && npm ci && npm run build

# Deploy
cd ~/finding-a-bed-tonight
docker compose -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f docker-compose.prod-v0.43-flyway-ooo.yml \
    -f docker-compose.prod-v0.44-pgaudit.yml \
    up -d --build backend
docker compose ... restart frontend

# Verify
curl https://findabed.org/api/v1/version  # expect 0.48.0
# Flyway V76/V77 applied, 3 tenants visible, FORCE RLS 7/7 at 1.0
# Playwright post-deploy-smoke 3/3 for Blue Ridge/Pamlico/Cross-tenant
```

---

**This runbook inherits structural lessons from v0.39-v0.47 deploys.** Key
patterns: 4-file compose chain dry-rendered pre-deploy (Jordan's #1 gate
from v0.45+); `mvn clean` + `docker --no-cache` mandatory (per
`feedback_deploy_old_jars.md`); preserve last-good image tag for fast
rollback; pg_dump belt-and-suspenders backup even on data-only
migrations; Playwright smoke as post-deploy authority; 24h bake window
guardrail.
