# Oracle Deploy Notes — v0.48.1 (Seed hotfix: coordinator_assignment for Blue Ridge + Pamlico)

**From:** v0.48.0 (currently live at `findabed.org` with three tenants, already hot-patched 2026-04-20 during the post-deploy walkthrough)
**To:** v0.48.1 (V78 codifies the hot-patch so any future `--fresh` reseed reproduces the correct assignments)

> **Jordan's note (SRE):** Lowest-friction release in the v0.42→v0.48 series.
> Single Flyway migration (V78). 14 INSERT rows into `coordinator_assignment`,
> `ON CONFLICT DO NOTHING` on the `(user_id, shelter_id)` PK. Execution time
> under 5ms. Prod is already hot-patched (2026-04-20 ~15:40 UTC) so the
> Flyway apply on deploy is a **no-op at the data level** — it just records
> V78 in `flyway_schema_history` to unblock future migrations past V78.
>
> **Backend-only rebuild** — no frontend change, no Postgres touch, no
> Prometheus touch, no compose touch. Fastest-possible restart window.
>
> **Hold posture recommended:** v0.48.1 was tagged 2026-04-20 but deploy
> deferred to 2026-04-21 per user direction. Deploy any time within the
> next 24h without losing continuity; the hot-patch in prod DB keeps the
> feature working in the interim.

---

## What's New in This Deploy

### Flyway V78 — coordinator_assignment for Blue Ridge + Pamlico

`backend/src/main/resources/db/migration/V78__seed_coordinator_assignments_blue_ridge_pamlico.sql`

14 rows. Mirrors dev-coc's role-to-shelter mapping (see `seed-data.sql`
line 247-258):

| Role (both tenants) | Assignment |
|---|---|
| `admin@{blueridge,pamlico}.fabt.org` | DV shelter only |
| `cocadmin@{blueridge,pamlico}.fabt.org` | All 3 shelters (2 non-DV + 1 DV) |
| `coordinator@{blueridge,pamlico}.fabt.org` | 2 non-DV shelters |
| `dv-coordinator@{blueridge,pamlico}.fabt.org` | DV shelter (banner recipient) |

Root cause of the miss: v0.48.0's V76 + V77 created the tenant / user /
shelter / shelter_constraints / bed_availability rows correctly but
didn't include `coordinator_assignment`. Without those rows, the
`GET /api/v1/dv-referrals/pending/count` endpoint filters by the
caller's assigned shelters via `ReferralTokenController.countPending` →
`countPendingByShelterIds` and returns `{count:0, firstPending:null}`
for every coordinator in the new tenants. `CoordinatorReferralBanner`
stays hidden and DV-referral wayfinding is a dead end.

Caught live on 2026-04-20 during the v0.48.0 post-deploy 3-tenant
walkthrough — `dv-coordinator@pamlico.fabt.org` created a PENDING
referral against Safe Haven Demo DV East, saw no banner. Prod was
hot-patched with direct `INSERT INTO coordinator_assignment` via psql
(14 rows) to unblock the walkthrough. V78 codifies the same rows so
future `--fresh` reseeds land in the correct state.

### `infra/scripts/seed-data.sql` — parallel update

Dev-stack parity block appended after the Blue Ridge + Pamlico
`bed_availability` section. Same 14 rows, same pattern. Dev
`./dev-start.sh --fresh` reproduces the banner-capable state without
running Flyway migrations separately.

---

## What Does NOT Change

Everything else. This is the smallest possible release surface:

| Surface | Status |
|---|---|
| Backend Java code | **unchanged** |
| Frontend | unchanged — no rebuild needed |
| Postgres / pgaudit config | unchanged |
| Prometheus / alerts | unchanged |
| Compose file chain | unchanged |
| FORCE RLS / RLS policies | unchanged |
| Phase B / Phase C / Phase D posture | unchanged |
| Per-tenant NOAA station resolution | unchanged |

---

## Pre-deploy sanity

This is a point-release with one data-only migration. Gates compress
to the essentials (Jordan's 4-file compose dry-render stays mandatory;
the Flyway V78 dry-run collapses to a trivial check that V78 is above
the HWM of 77 which is already known).

### 1. CI green on release-prep commit SHA

```bash
gh run list --branch main --limit 3 \
  --json databaseId,conclusion,headSha,displayTitle
# Expect: success on the release-prep commit ('release: v0.48.1 prep ...')
```

### 2. v0.48.0 live + healthy

```bash
curl -s https://findabed.org/api/v1/version  # expect 0.48
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
  "SELECT MAX(CAST(version AS INTEGER))
   FROM flyway_schema_history WHERE success = true AND version ~ '^[0-9]+$';"
# expect: 77
```

### 3. Confirm prod is already hot-patched (14 rows for Blue Ridge + Pamlico)

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT COUNT(*) FROM coordinator_assignment ca
  JOIN app_user u ON u.id = ca.user_id
  WHERE u.email LIKE '%@blueridge.fabt.org' OR u.email LIKE '%@pamlico.fabt.org';"
# expect: 14
```

If count != 14, the hot-patch was reverted somehow — V78 will still
apply safely via `ON CONFLICT DO NOTHING`, but investigate.

### 4. Compose dry-render — Jordan's #1 gate

```bash
cd ~/finding-a-bed-tonight
git fetch --tags origin
git checkout v0.48.1
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    config > /tmp/v0.48.1-config.rendered.yml

diff /tmp/v0.48-config.rendered.yml /tmp/v0.48.1-config.rendered.yml
# Expect: ZERO BYTES DIFF. If non-empty, STOP — compose state drifted.
```

### 5. pg_dump backup (belt-and-suspenders)

```bash
DUMP=~/fabt-backups/fabt-pre-v0.48.1-$(date -u +%Y%m%d-%H%M%S).dump
docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > "$DUMP"
sha256sum "$DUMP" | tee "$DUMP.sha256"
```

---

## Deploy steps

### 1. Preserve last-good backend image

```bash
docker tag fabt-backend:latest fabt-backend:v0.48.0-lastgood
docker images fabt-backend --format '{{.Tag}} {{.ID}}' | head -5
```

### 2. Build backend image with `--no-cache`

Per `feedback_prod_docker_build_pattern.md` — explicit `docker build` with
`-f infra/docker/Dockerfile.backend`, NOT `docker compose up --build`.

```bash
cd ~/finding-a-bed-tonight/backend
mvn -B -DskipTests clean package -q
ls -lh target/finding-a-bed-tonight-0.48.1.jar

cd ~/finding-a-bed-tonight
docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.48.1 \
    -t fabt-backend:latest \
    .

# Verify new image ID differs from v0.48.0-lastgood
docker images fabt-backend --format '{{.Tag}} {{.ID}}' | head -5
```

**Frontend is NOT rebuilt** — no frontend change in v0.48.1.

### 3. Force-recreate backend only

```bash
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    up -d --force-recreate backend

# Look for "Container fabt-backend Recreated" in output. If you see
# "Running" without Recreate — STOP, something is wrong.

# Tail logs for V78 apply
docker logs --since=3m -f fabt-backend 2>&1 | grep -iE \
  "Current version|Migrating|Successfully applied|V78|seed.coordinator|Started Application|ERROR"
# Expected:
#   Current version of schema "public": 77
#   Migrating schema "public" to version "78 - seed coordinator assignments blue ridge pamlico"
#   Successfully applied 1 migration to schema "public", now at version v78
#   Started Application in N seconds
# ^C after "Started Application"
```

Expected window: **~4-6 minutes** (mvn clean + single no-cache docker build + container recreate).

---

## Post-deploy sanity

### 1. Version + Flyway HWM

```bash
curl -s https://findabed.org/api/v1/version  # expect 0.48.1
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# expect top row: 78 | seed coordinator assignments blue ridge pamlico | t | <now>
```

### 2. Assignment count confirmation

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT u.email, s.name FROM coordinator_assignment ca
  JOIN app_user u ON u.id=ca.user_id
  JOIN shelter s ON s.id=ca.shelter_id
  WHERE u.email LIKE '%@blueridge.fabt.org' OR u.email LIKE '%@pamlico.fabt.org'
  ORDER BY u.email, s.name;"
# expect: 14 rows total (7 per tenant)
```

### 3. Banner endpoint for DV coordinator

Only meaningful if there's a PENDING referral in one of the new tenants.
From laptop:

```bash
TOKEN=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"tenantSlug":"dev-coc-east","email":"dv-coordinator@pamlico.fabt.org","password":"admin123"}' \
    | python -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))")
curl -s -H "Authorization: Bearer $TOKEN" https://findabed.org/api/v1/dv-referrals/pending/count
# If any PENDING referrals exist for that tenant: {"count":N>0, "firstPending":{...}}
# If no PENDING referrals: {"count":0, "firstPending":null} — also correct
```

### 4. FORCE RLS unchanged

Same 5 regulated tables, still `t`:

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT relname, relforcerowsecurity FROM pg_class
  WHERE relname IN ('audit_events','hmis_audit_log','one_time_access_code',
                    'password_reset_token','referral_token')
  ORDER BY relname;"
# All 5 must show t.
```

---

## Rollback

V78 is pure additive data (`ON CONFLICT DO NOTHING`). If v0.48.1 produces
a backend startup problem unrelated to V78, rollback is:

```bash
docker tag fabt-backend:v0.48.0-lastgood fabt-backend:latest
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    up -d --force-recreate backend
```

V78 stays in `flyway_schema_history` (harmless under v0.48.0 code, which
ignores unknown-to-classpath higher-versioned rows with Flyway's default
`ignoreIgnoredMigrations`). The rows in `coordinator_assignment` remain —
they're correct under v0.48.0 behavior too.

---

## After deploy succeeds

1. Update memory `project_live_deployment_status.md` to v0.48.1 live.
2. Confirm the v0.48.1 Playwright smoke tests 13/14/15 stay green
   (unchanged from v0.48.0 — sanity check).
3. 30-minute active watch on the Prometheus UI for any alert fires;
   no new alerts expected from v0.48.1.
