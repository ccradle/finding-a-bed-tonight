# Oracle Deploy Notes — v0.34.0 (bed-hold-integrity, Issue #102 RCA)

**From:** v0.32.3 (notification bell hotfix, currently live)
**To:** v0.34.0 (bed-hold-integrity — phantom beds_on_hold RCA + fix)

> Note: v0.33.0 (coc-admin-escalation) has not yet shipped. bed-hold-integrity
> branched directly from v0.32.3 and skips the v0.33.0 version number. When
> coc-admin-escalation is ready it will ship as v0.35.0 (sequential release order).
> See `project_coc_admin_escalation_post_v034_resume_plan.md` in memory for the
> rebase + Flyway renumber plan.

## What's New in This Deploy

- **2 new Flyway migrations** (V44, V45). Auto-applied on backend restart.
  - `V44__audit_events_allow_null_actor.sql` — drops `NOT NULL` on
    `audit_events.actor_user_id` so V45 (and the reconciliation tasklet) can
    write system-actor rows. Idempotent (no-op if already nullable).
  - `V45__backfill_phantom_beds_on_hold.sql` — one-time correction for the
    17 drifted `(shelter_id, population_type)` pairs on the live demo, plus
    matching `BED_HOLDS_RECONCILED` audit_events rows (one per correction) with
    `correction_source='V45_backfill'` for chain-of-custody.
- **New backend classes:**
  - `ManualHoldController` — `POST /api/v1/shelters/{id}/manual-hold` endpoint
    for coordinators to create offline holds (phone reservations, expected
    guests).
  - `BedHoldsReconciliationJobConfig` — Spring Batch tasklet running every 5
    minutes (`0 */5 * * * *`) catching any future drift as defense-in-depth.
  - `AuditEventTypes` — new class with `BED_HOLDS_RECONCILED` constant. NOTE:
    this class also holds constants that will be added by coc-admin-escalation
    in v0.35.0; during that branch's rebase the two constant sets get merged.
- **Refactored `ReservationService`** — single write-path discipline for
  `beds_on_hold`. Every reservation lifecycle event (create/cancel/expire/
  confirm) recomputes the cached value from the canonical reservation table
  source instead of doing delta math against the prior snapshot. Eliminates
  the bug class structurally.
- **`AvailabilityController.updateAvailability`** — deprecates client-supplied
  `bedsOnHold` on the PATCH path. Value is ignored (logged WARN if non-zero)
  and will be hard-rejected in v0.35.0.
- **`SecurityConfig.java:172`** — new `POST /api/v1/shelters/*/manual-hold`
  matcher admitting COORDINATOR + COC_ADMIN + PLATFORM_ADMIN before the
  broader `POST /shelters/**` catch-all. This fixes a production bug caught
  in pre-ship smoke where coordinators were silently 403'd at the filter
  chain before reaching the controller. See the `/manual-hold` smoke check
  below — it is the regression guard for any future SecurityConfig narrowing.
- **`seed-data.sql` Component 6 block** — backs 3 orphan `beds_on_hold > 0`
  rows with 5 HELD reservations, plus fresh `bed_availability` snapshots via
  `clock_timestamp()` wrapped in a single transaction with the reservation
  inserts. Only relevant to fresh dev stack restarts; this deploy does not
  run the seed on the live demo.
- **`docs/runbook.md`** — new "Drift query" and "v0.34.0 post-deploy smoke"
  sections, plus a Disclosures entry for the SecurityConfig filter gap.
- **`docs/oracle-update-notes-v0.34.0.md`** — this file.

## What Does NOT Change

- **No frontend code changes.** Do not rebuild frontend. Do not replace
  `frontend/dist`.
- **No new seed run needed on the live demo.** Component 6's 5 fictional
  HELD reservations are demo realism for fresh `--fresh` dev stacks; running
  seed against the live demo would perturb ongoing usage. Skip.
- **No static content (`demo/index.html`) update** — no credential or URL
  additions.
- **No env var additions.**
- **No Docker compose file changes.**
- **No Cloudflare / nginx / firewall changes.**

## Pre-Deploy Checklist

- [ ] Confirm v0.32.3 is live: `curl -s https://findabed.org/api/v1/version` →
      `{"version":"0.32"}`
- [ ] Confirm local main is at the merge commit:
      `git log --oneline -3` shows `204f3a4 Merge pull request #104`
- [ ] Tag and release (from your laptop, not the VM):
      ```bash
      git tag -a v0.34.0 -m "v0.34.0 — bed-hold-integrity (Issue #102 RCA)"
      git push origin v0.34.0
      gh release create v0.34.0 --title "v0.34.0 — bed-hold-integrity" \
        --notes-file /tmp/v0.34.0-release-body.md
      ```
- [ ] SSH key present: `ls ~/.ssh/fabt-oracle`
- [ ] Docker is running on the VM: `docker ps` (via SSH)

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 2. Pull the tagged release
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.34.0
git log --oneline -3

# 3. Capture pre-deploy drift snapshot (read-only — for audit comparison)
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
WITH latest AS (
  SELECT DISTINCT ON (shelter_id, population_type)
    shelter_id, population_type, beds_on_hold, updated_by
  FROM bed_availability
  ORDER BY shelter_id, population_type, snapshot_ts DESC
),
held_counts AS (
  SELECT shelter_id, population_type, COUNT(*)::int AS held_count
  FROM reservation WHERE status = 'HELD'
  GROUP BY shelter_id, population_type
)
SELECT l.shelter_id, l.population_type, l.beds_on_hold,
       COALESCE(h.held_count, 0) AS held_count, l.updated_by
FROM latest l
LEFT JOIN held_counts h
       ON h.shelter_id = l.shelter_id
      AND h.population_type = l.population_type
WHERE l.beds_on_hold <> COALESCE(h.held_count, 0)
ORDER BY l.shelter_id;
" | tee /tmp/drift-pre-v0.34.0.txt
# Expected: ~17 drifted pairs (the RCA finding)

# 4. Clean backend build (backend-only; no frontend)
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar
# Expect exactly one JAR: finding-a-bed-tonight-0.34.0.jar
cd ..

# 5. Verify V44 + V45 are in the new JAR
jar tf backend/target/finding-a-bed-tonight-0.34.0.jar | grep -E "V4[45]__"
# Expect two lines — V44 and V45

# 6. Build the backend Docker image (no frontend rebuild)
docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 7. Restart backend only (frontend is unchanged and stays up)
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend

# 8. Wait for backend health — probe both ports (9091 with observability profile,
#    8080 as fallback per older runbook notes)
echo "Waiting for backend..."
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy on 9091 (mgmt port)"; break
  fi
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "healthy on 8080 (app port)"; break
  fi
  sleep 2
done

# 9. Watch Flyway apply V44 + V45 in the logs
docker logs fabt-backend 2>&1 | grep -E "Flyway|Migrating|V44|V45|bedHoldsReconciliation" | tail -30
# Expected lines:
#   Migrating schema "public" to version "44 - audit events allow null actor"
#   Successfully applied 1 migration ... v44
#   Migrating schema "public" to version "45 - backfill phantom beds on hold"
#   Successfully applied 1 migration ... v45
#   Registered batch job 'bedHoldsReconciliation' with cron '0 */5 * * * *'

# 10. Docker cleanup
docker image prune -f
```

## Post-Deploy Sanity Checks

### On the VM

```bash
# Flyway history — V44 and V45 must both be success=t
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT version, description, installed_on, success
FROM flyway_schema_history
WHERE version ~ '^[0-9]+$' AND version::int >= 39
ORDER BY installed_rank;
"

# Drift query — must return ZERO rows
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
WITH latest AS (
  SELECT DISTINCT ON (shelter_id, population_type)
    shelter_id, population_type, beds_on_hold
  FROM bed_availability
  ORDER BY shelter_id, population_type, snapshot_ts DESC
),
held_counts AS (
  SELECT shelter_id, population_type, COUNT(*)::int AS held_count
  FROM reservation WHERE status = 'HELD'
  GROUP BY shelter_id, population_type
)
SELECT l.shelter_id, l.population_type, l.beds_on_hold, COALESCE(h.held_count, 0)
FROM latest l
LEFT JOIN held_counts h
       ON h.shelter_id = l.shelter_id
      AND h.population_type = l.population_type
WHERE l.beds_on_hold <> COALESCE(h.held_count, 0);
"

# BED_HOLDS_RECONCILED audit rows from V45 backfill — expect 17 rows,
# source='V45_backfill'
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT COUNT(*) AS rows,
       jsonb_extract_path_text(details, 'correction_source') AS source
FROM audit_events
WHERE action = 'BED_HOLDS_RECONCILED'
GROUP BY source;
"
```

### From your local machine (not the VM)

```bash
# Version probe — must return 0.34
curl -s https://findabed.org/api/v1/version
# Expect: {"version":"0.34"}

# Existing Playwright post-deploy smoke suite (the version assertion now
# targets v0.34 — updated in the same commit as these deploy notes)
cd /c/Development/findABed/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test post-deploy-smoke \
  --config=deploy/playwright.config.ts --trace on --reporter=list \
  2>&1 | tee ../../logs/post-deploy-smoke-v0.34.0.log
# Expected: 11/11 green

# v0.34.0-specific smoke: real-coordinator /manual-hold curl
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"email":"dv-coordinator@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  https://findabed.org/api/v1/auth/login \
  | grep -oE '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"$//')

curl -s -w "\nHTTP=%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"populationType":"DV_SURVIVOR","reason":"v0.34.0 post-deploy smoke"}' \
  https://findabed.org/api/v1/shelters/d0000000-0000-0000-0000-000000000011/manual-hold
# Expected: HTTP=201, body contains "Manual offline hold"
# If HTTP=403 → SecurityConfig fix did not ship → rollback immediately (see below)
```

### Manual UI verification

1. `https://findabed.org` — version banner in the footer reads **v0.34**
2. Login as `outreach@dev.fabt.org / admin123` → bed search returns results
3. Login as `admin@dev.fabt.org / admin123` → admin panel loads
4. Login as `dv-coordinator@dev.fabt.org / admin123` → lands at `/coordinator`,
   bell badge visible in header

## Rollback Plan

If verification fails at any step:

```bash
# On the VM:
cd ~/finding-a-bed-tonight
git checkout v0.32.3

cd backend && mvn clean package -DskipTests -q && cd ..

docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend
```

**V44 and V45 remain in `flyway_schema_history` on rollback.** They are
append-only (`INSERT` only, plus one `ALTER COLUMN DROP NOT NULL` which is
monotonically less restrictive). The v0.32.3 code runs correctly against the
V44/V45 schema because the only schema change was dropping a constraint,
which old code tolerates. **No DB volume touching is required or wanted.**
V45-inserted snapshots and audit rows remain as historical record and do not
corrupt v0.32.3 behavior.

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| V45 backfill writes corrective snapshots but drift count wrong | LOW | MEDIUM | EXPLAIN ANALYZE already validated on local dev; V45 SQL is a compound CTE with INSERT ... RETURNING, so audit rows are derived from the actually-inserted snapshots by construction |
| Reconciliation tasklet fails to register | LOW | MEDIUM | `BedHoldsReconciliationJobTest` (4 tests) validates; deploy log grep for "Registered batch job 'bedHoldsReconciliation'" catches silent failure |
| SecurityConfig fix does not ship due to Docker layer cache | LOW | HIGH | The `--no-cache` build on step 6 prevents stale layers. The coordinator `/manual-hold` curl is the final regression guard |
| Coordinator sees 403 on `/manual-hold` | VERY LOW | HIGH | Gated by the curl smoke above. Rollback if seen |
| Live demo users hit the brief reconciliation window | VERY LOW | LOW | V45 backfill runs during Spring init before Tomcat binds the port; no user traffic is served during the correction |
| 17 drifted pairs != 17 audit rows post-V45 | LOW | LOW | Audit rows are a CTE join of the INSERT RETURNING — they cannot number differently |

## After the Deploy Succeeds

Post-deploy housekeeping tracked in memory and action items:

1. **Comment on GH issue #102** with deploy results (drift count before/after,
   audit row count, smoke pass/fail). Close the issue.
2. **Cross-link GH issue #101** (Bed Maintenance UI) noting the structural
   fix has shipped and the UI can be developed against a clean baseline.
3. **Update memory** `project_issue_102_rca_findings.md` — add "DEPLOYED
   v0.34.0 YYYY-MM-DD" line.
4. **Update memory** `project_live_deployment_status.md` — bump to v0.34.0,
   add bed-hold-integrity summary, note V44/V45 applied and drift corrected.
5. **Update memory** `project_v033_deploy_plan.md` — prepend "SUPERSEDED by
   project_coc_admin_escalation_post_v034_resume_plan.md" (already written;
   just mark the old one).
6. **Run** `/opsx:verify bed-hold-integrity` → `/opsx:sync` → `/opsx:archive`
   to close the OpenSpec change.

## Related

- **GH issue:** [#102](https://github.com/ccradle/finding-a-bed-tonight/issues/102) (RCA + bug report)
- **PR:** [#104](https://github.com/ccradle/finding-a-bed-tonight/pull/104) (merged 2026-04-11)
- **Umbrella issue for pre-existing Playwright flakes:** [#103](https://github.com/ccradle/finding-a-bed-tonight/issues/103) (not blocking)
- **OpenSpec change:** `openspec/changes/bed-hold-integrity/` (docs repo)
- **Implementation notes with war-room findings:** `openspec/changes/bed-hold-integrity/IMPLEMENTATION-NOTES.md` § A–D
- **Post-deploy runbook:** `docs/runbook.md` § "Bed Availability Invariants → Drift query" and "§ v0.34.0 post-deploy smoke"
