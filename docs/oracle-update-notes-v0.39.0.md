# Oracle Deploy Notes — v0.39.0 (notification-deep-linking Phases 1–4, Issue #106)

**From:** v0.38.0 (shelter-activate-deactivate, currently live)
**To:** v0.39.0 (notification-deep-linking Phases 1–4 combined release)

> v0.39.0 is the largest user-visible delta since v0.21 (shelter-edit). Phases 1,
> 2, and 3 of Issue #106 were merged to main between v0.38.0 and v0.39.0 without
> intermediate deploys, so **all four phases ship together**. Read the full v0.39
> section in `docs/runbook.md` before starting this deploy.

## What's New in This Deploy

- **1 new Flyway migration** (V55). Auto-applied on backend restart.
  - `V55__referral_token_pending_created_at_idx.sql` — partial index
    `ON referral_token (created_at ASC) WHERE status = 'PENDING'` plus an
    `ANALYZE referral_token`. Uses `CREATE INDEX IF NOT EXISTS` so it is
    idempotent. Non-concurrent CREATE (Flyway wraps in a transaction);
    sub-second at current scale. Shape validated via `pg_stat_statements`
    100-run A/B/C — 14× speedup for `findOldestPendingByShelterIds`.
- **New backend API surface:**
  - `GET /api/v1/dv-referrals/{id}` — single-referral fetch (was only used
    internally pre-v0.39; now public for deep-link resolve). Tenant-scoped
    via `findByIdAndTenantId(UUID, UUID)` — returns 404 for cross-tenant
    lookup, not 403 (no existence leak).
  - `GET /api/v1/dv-referrals/pending/count` — response shape extended with
    `firstPending: { referralId, shelterId } | null` routing hint. Back-
    compat preserved — pre-v0.39 callers that destructure only `{ count }`
    continue to work.
  - `POST /api/v1/metrics/notification-deeplink-click` — fire-and-forget
    client-side metric emitter. Frontend swallows network errors via
    `.catch(() => {})` — if this endpoint is missing (post-rollback), no
    user-visible impact.
  - `GET /api/v1/reservations` — new query params `status=CSV` and
    `sinceDays=N`. Back-compat preserved — callers sending only
    `status=HELD` still work.
- **New frontend routes:**
  - `/outreach/my-holds` — outreach worker's past-holds view (HELD +
    terminal).
  - `/coordinator?referralId=X` — deep-link target for DV referral
    notifications.
  - `/admin#dvEscalations?referralId=X` — admin escalation queue deep-link.
- **New `ReferralTokenPurgeService` metrics** — `fabt.notification.deeplink.click.count`,
  `fabt.notification.time_to_action.seconds` (histogram with percentile
  publishing), `fabt.notification.stale_referral.count`. Micrometer lazy-
  registers on first emission — see the post-deploy smoke note below.
- **3 new Grafana panels** on the DV Referrals dashboard — deep-link click
  rate, time-to-accept p50/p95 histogram, stale referral rate.
- **Cross-tenant hardening** — all seven `ReferralTokenService` call sites
  (`acceptToken`, `rejectToken`, `claimToken`, `releaseToken`, `reassignToken`,
  `getById`, diagnostic re-reads) routed through a shared
  `findByIdOrThrow(UUID)` that pulls `tenantId` from `TenantContext`.
- **`docs/runbook.md`** — new "v0.39 Deploy" section (lines 894+) with
  operator-awareness items, smoke sequence, and rollback criteria.
- **`docs/oracle-update-notes-v0.39.0.md`** — this file.

## What Does NOT Change

- **No new env vars.**
- **No Docker compose file changes.**
- **No Cloudflare / nginx / firewall changes.**
- **No new seed users.** The deploy reuses `dv-coordinator@dev.fabt.org`,
  `dv-outreach@dev.fabt.org`, and `cocadmin@dev.fabt.org`.
- **No static content updates** beyond the documentation above.
- **RLS configuration is unchanged** — only `shelter` (via `dv_shelter_access`)
  and `referral_token` (via `dv_referral_token_access`) have RLS; tenant
  isolation remains application-layer. The cross-tenant fix does NOT add RLS;
  it adds a tenant predicate to the JDBC query.

## Unlike v0.34 — Frontend Rebuild IS Required

v0.34.0 deploy skipped frontend entirely (backend-only change). **v0.39
changes ~50+ frontend files** — deep-linking hooks, bell three-state visuals,
new My Past Holds page, banner routing, notification type mappings, Carbon
token contrast fixes. The frontend MUST be rebuilt and redeployed.

## Pre-Deploy Checklist

- [ ] Confirm v0.38.0 is live: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.38..."}`
- [ ] Confirm local main is at the v0.39 merge commit: `git log --oneline -5` shows `a59aabe Merge pull request #119` followed by `d9f8bef ci: untrack local-only NycWinterNightSimulation`
- [ ] Confirm pom is at `0.39.0` (no `-SNAPSHOT`): `grep -E "<version>0\.39\.0</version>" backend/pom.xml`
- [ ] Confirm CHANGELOG has a v0.39.0 entry at the top
- [ ] Tag and release (from your laptop, not the VM):
      ```bash
      git tag -a v0.39.0 -m "v0.39.0 — notification-deep-linking Phases 1-4 (Issue #106)"
      git push origin v0.39.0
      gh release create v0.39.0 --title "v0.39.0 — notification-deep-linking" \
        --notes-file /tmp/v0.39.0-release-body.md
      ```
      (`/tmp/v0.39.0-release-body.md` = the CHANGELOG v0.39.0 block, verbatim.)
- [ ] SSH key present: `ls ~/.ssh/fabt-oracle`
- [ ] Docker running on the VM: `docker ps` (via SSH)
- [ ] **Flyway rollback compat verified** — on a local throwaway DB, apply V1–V55
      via v0.39 code, then start v0.38 backend against the same volume. Confirm
      startup does NOT fail on checksum validation. If it does, document the
      `DELETE FROM flyway_schema_history WHERE version='55';` step before
      running the rollback section below.

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 2. Pull the tagged release
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.39.0
git log --oneline -5
# Expect: tag at the top, merge commit a59aabe + hotfix d9f8bef visible.

# 3. CLEAN backend build (per feedback_deploy_old_jars.md — NEVER skip "clean")
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar
# Expect exactly ONE JAR: finding-a-bed-tonight-0.39.0.jar
cd ..

# 4. Verify V55 is in the new JAR
jar tf backend/target/finding-a-bed-tonight-0.39.0.jar | grep V55__
# Expect one line: BOOT-INF/classes/db/migration/V55__referral_token_pending_created_at_idx.sql

# 5. FRONTEND rebuild (required — unlike v0.34)
cd frontend
npm ci --silent
npm run build
cd ..
# Expect successful Vite build + PWA service worker regenerated.

# 6. Build backend Docker image WITHOUT cache (per feedback_deploy_checklist_v031.md)
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 7. Build frontend Docker image WITHOUT cache
docker build --no-cache -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

# 8. Restart backend AND frontend
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend

# 9. Wait for backend health — probe the management port (9091 with observability)
echo "Waiting for backend..."
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy on 9091 (mgmt port)"; break
  fi
  sleep 2
done

# 10. Watch Flyway apply V55 in the logs
docker logs fabt-backend 2>&1 | grep -E "Flyway|Migrating|V55|ANALYZE" | tail -20
# Expected lines:
#   Migrating schema "public" to version "55 - referral token pending created at idx"
#   Successfully applied 1 migration ... v55

# 11. Docker cleanup (per feedback_cleanup_old_artifacts.md)
docker image prune -f
```

## Post-Deploy Sanity Checks

### On the VM

```bash
# Flyway history — V55 must be success=t
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT version, description, installed_on, success
FROM flyway_schema_history
WHERE version::int >= 50
ORDER BY installed_rank;
"
# Expect: V55 visible, success=t.

# Partial index exists and has the right shape
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
\d idx_referral_token_pending_created_at
"
# Expect: btree (created_at) WHERE status='PENDING'

# Planner actually uses the new index (Sam's gate — fail rollback if not)
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
EXPLAIN ANALYZE
  SELECT * FROM referral_token
  WHERE shelter_id = ANY(ARRAY[
    (SELECT id FROM shelter WHERE dv_shelter = TRUE LIMIT 1)
  ])
    AND status = 'PENDING'
  ORDER BY created_at ASC LIMIT 1;
"
# Expect: 'Index Scan using idx_referral_token_pending_created_at'
# If instead: 'Seq Scan on referral_token' → ANALYZE did not run or stats are cold.
# Remediate: docker exec ... psql ... -c "ANALYZE referral_token;"
```

### From your local machine (not the VM)

```bash
# 1. Version probe — must return 0.39
curl -s https://findabed.org/api/v1/version
# Expect: {"version":"0.39..."}

# 2. Count-endpoint shape probe (the firstPending field MUST be present — null or object)
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"tenantSlug":"dev-coc","email":"dv-coordinator@dev.fabt.org","password":"admin123"}' \
  https://findabed.org/api/v1/auth/login | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" https://findabed.org/api/v1/dv-referrals/pending/count | jq .
# Expect: {"count": N, "firstPending": {"referralId":"...","shelterId":"..."} | null}
# Critical: the firstPending key MUST be present (even when null).

# 3. Cross-tenant 404 live probe (Casey's security gate)
curl -s -H "Authorization: Bearer $TOKEN" \
  -o /dev/null -w "%{http_code}\n" \
  https://findabed.org/api/v1/dv-referrals/00000000-0000-0000-0000-000000000000
# Expect: 404 (not 403, not 200). Confirms findByIdAndTenantId is active.

# 4. Metrics registered in Prometheus scrape
curl -s https://findabed.org/actuator/prometheus | grep -E "fabt_notification_(deeplink_click|time_to_action|stale_referral)" | head
# Expect: at least one line per metric name.
# Note: Micrometer lazy-registers counters/timers on first emission. If this
# grep returns empty, fire one bell-click in the manual UI step below, then
# re-run this grep — metrics appear after the first click.

# 5. Existing Playwright post-deploy smoke suite
cd /c/Development/findABed/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test post-deploy-smoke \
  --config=deploy/playwright.config.ts --trace on --reporter=list \
  2>&1 | tee ../../logs/post-deploy-smoke-v0.39.0.log
```

### Manual UI verification (genesis-gap regression — the core v0.39 acceptance gate)

Run in an **incognito window** (per PWA service-worker cache caveat):

1. Navigate to `https://findabed.org` — version banner in footer reads **v0.39**.
2. Login as `dv-coordinator@dev.fabt.org / admin123` → lands at `/coordinator`.
3. If pending referrals exist (count > 0 in the banner), click the red banner once.
4. Verify the URL gains `?referralId=<UUID>` AND the specific referral row scrolls into view with focus on the row heading (NOT on the Accept button — S-2 safety).
5. Verify the expanded shelter matches `firstPending.shelterId` from step 2 of the local sanity checks above. This is the genesis-gap assertion — the authoritative regression is `e2e/playwright/tests/persistent-notifications.spec.ts:243`, which Corey runs locally against dev — NOT against prod (the spec has an `afterAll` that cleans up DV referral state).
6. **URL-stale regression (D-BP, today's fix):** open a second incognito tab and navigate to `https://findabed.org/coordinator?referralId=00000000-0000-0000-0000-c0ffee000106` (a deliberately-bogus UUID). The stale toast should appear; the banner should still render if count > 0. Click the banner. Verify the URL rewrites to the real `firstPending.referralId` — NOT stays at the stale UUID.
7. Login as `dv-outreach@dev.fabt.org / admin123` → verify a "My Past Holds" entry is present in the top nav. Click it. Page loads without 404.
8. Login as `cocadmin@dev.fabt.org / admin123` → admin panel loads. If a dvEscalations row is present, click it — the detail modal should auto-open (deep-link in URL).
9. Three-state bell spot-check (any logged-in user): open the bell dropdown. Unread rows should have a highlighted background. Click one → it becomes "read-but-not-acted" (normal weight, still visible). If the underlying referral/hold has been acted on via the deep-link flow, it should show with a ✓ icon.

## Rollback Plan

If any post-deploy step fails:

```bash
# On the VM:
cd ~/finding-a-bed-tonight
git checkout v0.38.0

# Rebuild BOTH backend and frontend (frontend must roll back too)
cd backend && mvn clean package -DskipTests -q && cd ..
cd frontend && npm ci --silent && npm run build && cd ..

docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build --no-cache -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend frontend
```

**V55 rollback requires DB cleanup — REQUIRED, not optional.** The v0.38 code
has no repository method that targets the new index, BUT v0.38's default
Spring Boot Flyway config (`validateOnMigrate=true`, no `ignoreMigrationPatterns`
override — verified at `backend/src/main/resources/application.yml:36-43`) will
FAIL startup with `FlywayValidateException: Detected applied migration not
resolved locally: 55`, because V55 is in `flyway_schema_history` but not in
v0.38's `db/migration/` source tree.

**Before rebuilding v0.38 backend on rollback, run:**

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
DELETE FROM flyway_schema_history WHERE version='55';
"
# Drop the now-orphaned index so state matches v0.38 expectations:
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
DROP INDEX IF EXISTS idx_referral_token_pending_created_at;
"
```

Only then proceed with `git checkout v0.38.0 && mvn clean package && ...`.

**Security note on rollback.** Rolling back to v0.38 reintroduces the
cross-tenant DV referral leak (v0.38 uses unscoped `findById(UUID)`). Post a
note on issue #117 that the leak is temporarily present. Demo site is
single-tenant so there is no immediate exposure, but the operational hygiene
matters if rollback is prolonged.

**Service worker note on rollback.** Pilot users who received the v0.39
service worker will keep seeing v0.39 UI against v0.38 backend until their SW
cycles. v0.39 frontend will call endpoints that no longer exist on v0.38
(`POST /metrics/notification-deeplink-click`); the frontend's fire-and-forget
`.catch(() => {})` swallows the 404 — no user-visible impact.

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| V55 non-concurrent CREATE INDEX blocks writes | LOW | LOW | Sub-second at current scale; revisit at >1M rows |
| Frontend SW cache serves old bundle to pilot users | HIGH | LOW | Hard-reload / incognito. Proactive pilot comms recommended (see "After the Deploy Succeeds" step 3). |
| Planner does not use V55 index due to cold stats | LOW | MEDIUM | `ANALYZE referral_token;` ships inside V55. EXPLAIN ANALYZE smoke confirms. |
| Micrometer metrics empty until first click | HIGH | LOW | Manual step 3–6 above fires a click; grep then succeeds. |
| Cross-tenant 404 probe returns 403 or 200 | VERY LOW | HIGH | Probe is in post-deploy smoke. Rollback immediately if observed. |
| Banner click routes to wrong shelter | VERY LOW | HIGH | Manual step 5 is the regression guard. Rollback + reopen Section 16 investigation. |
| URL-stale banner click does NOT rewrite URL | VERY LOW | HIGH | Manual step 6 is the D-BP regression guard. Rollback if observed — this is today's fix. |
| Pilot outreach worker confused by new "My Past Holds" nav | LOW | LOW | Their past holds were always recorded in DB; this view just surfaces them. Script this answer. |
| `CriticalNotificationBanner` 0→1 crash returns | VERY LOW | HIGH | Vitest covers; manual step 9 three-state bell spot-check catches any runtime regression. |
| Flyway V55 row blocks v0.38 rollback startup | MEDIUM | MEDIUM | Pre-deploy compat check. DELETE-then-DROP documented above if encountered. |

## After the Deploy Succeeds

1. **Comment on GH issue #106** with deploy results (smoke pass/fail, metrics
   registered, 1-week Grafana baseline begins now). Close the issue.
2. **Update memory `project_live_deployment_status.md`** — bump to v0.39.0,
   summarize Phase 1–4 scope, note V55 applied + cross-tenant hardening live.
3. **Pilot communication.** Per `feedback_stale_sw_on_deploy.md`, message
   pilot leads: "v0.39 shipped — please hard-reload (Ctrl+Shift+R) to see new
   notification deep-linking + My Past Holds page."
4. **Cross-link GH issue #117** — note the DV referral cross-tenant fix has
   shipped, the broader `findById(UUID)` audit remains open as the multi-
   tenant production gate.
5. **Run** `/opsx:verify notification-deep-linking` → `/opsx:sync` →
   `/opsx:archive` to close the OpenSpec change.
6. **Schedule 1-week Grafana histogram review** (task 15.2) for Priya's
   differentiator measurement.
7. **Trigger post-v0.39 pilot-readiness bundle** (#120 + #67) per
   `project_v039_post_deploy_pilot_readiness_bundle.md` memory — target
   within 48 hours of successful deploy + sanity window.

## Related

- **GH issue:** [#106](https://github.com/ccradle/finding-a-bed-tonight/issues/106) (Phase 1–4 umbrella)
- **PR:** [#119](https://github.com/ccradle/finding-a-bed-tonight/pull/119) (merged 2026-04-14 as `a59aabe`)
- **Hotfix:** `d9f8bef` (untrack local-only NycWinterNightSimulation from CI)
- **Cross-tenant audit (open):** [#117](https://github.com/ccradle/finding-a-bed-tonight/issues/117)
- **Pre-existing Playwright flakes:** [#103](https://github.com/ccradle/finding-a-bed-tonight/issues/103) (11.13 state pollution — not v0.39 regression)
- **OpenSpec change:** `openspec/changes/notification-deep-linking/` (docs repo)
- **Post-deploy runbook:** `docs/runbook.md` § "v0.39 Deploy"
- **Deploy-prep plan (this session):** `project_v039_deploy_prep_plan.md` (memory)
