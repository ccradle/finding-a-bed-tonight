# Oracle Deploy Notes — v0.47.0 (Phase C completes: cache isolation active across all application call sites)

**From:** v0.46.0 (currently live at `findabed.org` — `TenantScopedCacheService` bean idle, not yet wired)
**To:** v0.47.0 (9 call sites now route through the wrapper; 9 alert rules loaded into Prometheus — 5 Phase B + 4 Phase C — for the first time since those rules existed in the repo; `external_labels: env=prod` propagates to scrape)
**Release-prep commit SHA:** filled in at tag time (see step 5.5 of pre-deploy — `git rev-parse HEAD` on `main` immediately before `git tag -a`). The tag attaches to the release-prep commit (pom bump + CHANGELOG + this runbook), NOT to PR #140's merge commit `31e17f5`.

> **Jordan's note (SRE):** **behavioural-change release** — different risk
> profile from v0.46. The wrapper bean existed and was idle in v0.46;
> v0.47 flips 9 call sites from raw `CacheService` to
> `TenantScopedCacheService`, so every bed-search / analytics / shelter
> read + write now carries the prefix + envelope contract. No schema, no
> pgaudit, no frontend, no compose override chain change. But the hot
> path IS now the wrapper — the 30-second restart window is where you
> watch.
>
> **Base compose file DID change in this release.** This is a DEPARTURE
> from v0.46's "compose unchanged" posture. `docker-compose.yml`
> Prometheus service block gains three things: a `command:` list with
> `--web.enable-lifecycle`, a second volume mount for the rules
> directory (`./deploy/prometheus:/etc/prometheus/rules:ro`), and the
> companion `rule_files:` glob in `prometheus.yml`. The dev-override
> `docker-compose.dev-observability.yml` gets the same rules mount so
> dev and prod Prometheus behaviour stays symmetric. **Without these
> three edits the four Phase C alert rules and the five Phase B alert
> rules cannot load — this is the true blocker the v0.47 pre-release
> deep warroom surfaced (Jordan #1).** See "What's New" item 4.
>
> **Post-deploy, Prometheus must reload AND be recreated.** Because
> `docker-compose.yml` changed the Prometheus service (`command:`
> block is new), Docker Compose must RECREATE the container — a bare
> `restart` re-executes the old definition with the old flags.
> Deploy step 4 recreates Prometheus before reloading. Without the
> recreate the `--web.enable-lifecycle` flag is absent, the reload
> endpoint returns 405, and the alerts stay dark.
>
> **The 4-file PROD compose override chain composition stays.** No
> new `~/fabt-secrets/` override file. The Flyway out-of-order bridge
> remains (task #151 retires it post-v0.47 once the next migration
> lands — V75+). But because the BASE compose file is now at a
> different commit, the v0.47 dry-render in pre-deploy step 4 will
> diverge from the v0.46 render by the Prometheus service block.
> That's expected; verify the diff is scoped to exactly the
> prometheus service and nothing else.
>
> **Alertmanager is NOT wired to these rules as of v0.47** (task #155
> pending). Rules will fire in Prometheus + surface in the Prometheus
> UI but will not page Slack / email. Known issue; documented in the
> active-watch section. On-call must eyeball the Prometheus alert
> dashboard manually until #155 closes.

**Bake window math:** v0.46.0 shipped 2026-04-19 ~18:32 UTC → v0.47.0
can tag after **2026-04-20 ~18:32 UTC** without breaching the 24h
guardrail. No compression approved — this is the defence-in-depth
activation, bake is load-bearing.

---

## What's New in This Deploy

### 9 cache call sites now route through `TenantScopedCacheService`

Task 4.b (PR #139 commit `f74762d`) drained `PENDING_MIGRATION_SITES` to
`Set.of()` by migrating every raw-`CacheService` caller in
application-layer packages:

| Caller | Cache name | Key shape |
|---|---|---|
| `AnalyticsService.getUtilization` | `ANALYTICS_UTILIZATION` | `<tenantId>|<from>:<to>:<granularity>` |
| `AnalyticsService.getDemand` | `ANALYTICS_DEMAND` | `<tenantId>|<from>:<to>` |
| `AnalyticsService.getCapacity` | `ANALYTICS_CAPACITY` | `<tenantId>|<from>:<to>` |
| `AnalyticsService.getDvSummary` | `ANALYTICS_DV_SUMMARY` | `<tenantId>|latest` |
| `AnalyticsService.getGeographic` | `ANALYTICS_GEOGRAPHIC` | `<tenantId>|latest` |
| `AnalyticsService.getHmisHealth` | `ANALYTICS_HMIS_HEALTH` | `<tenantId>|latest` |
| `BedSearchService.doSearch` | `SHELTER_AVAILABILITY` | `<tenantId>|latest` |
| `AvailabilityService.createSnapshot` | `SHELTER_AVAILABILITY` + `SHELTER_PROFILE` + `SHELTER_LIST` (3 evicts) | tenant-scoped |
| `ShelterService.evictTenantShelterCaches` | `SHELTER_AVAILABILITY` + `SHELTER_LIST` (2 evicts) | tenant-scoped |

Every read now verifies the stored envelope's tenant UUID matches
`TenantContext.getTenantId()`. Mismatch throws
`IllegalStateException("CROSS_TENANT_CACHE_READ")` + increments
`fabt_cache_get_total{result="cross_tenant_reject"}` + persists an
`audit_events` row via `DetachedAuditPersister` REQUIRES_NEW (survives
caller rollback). Every write stamps the envelope with the writer's
tenant UUID so a wrong-`TenantContext`-on-write cannot silently poison
another tenant's slot.

**`PENDING_MIGRATION_SITES` is now `Set.of()`.** That empty state is
the release gate per spec requirement `pending-migration-sites-drained`
— any new entry requires design-c warroom sign-off.

### 4 new Prometheus alert rules (`deploy/prometheus/phase-c-cache-isolation.rules.yml`)

| Alert | Severity | Window | PromQL shape |
|---|---|---|---|
| `FabtPhaseCCrossTenantCacheRead` | CRITICAL | `for: 0m` | `rate(fabt_cache_get_total{result="cross_tenant_reject",env="prod"}[5m]) > 0` |
| `FabtPhaseCMalformedCacheEntry` | WARN | `for: 15m` | `rate(fabt_cache_get_total{result="malformed_entry",env="prod"}[15m]) > 0` |
| `FabtPhaseCCacheHitRateCollapse` | WARN | `for: 30m` | per-cache hit-rate < 50% of 7-day avg |
| `FabtPhaseCDetachedAuditPersistFailure` | WARN | `for: 15m` | `rate(fabt_audit_detached_failed_count_total{env="prod"}[15m]) > 0` |

All 4 carry `env="prod"` in their label selector so CI / dev
(which use `prometheus.dev.yml` without the `external_labels` block)
cannot trigger production pages.

Triage: `docs/security/phase-c-cache-isolation-runbook.md`
(symptoms + causes + bash/SQL triage commands + rollback criteria
per alert).

### `external_labels: env=prod` in `prometheus.yml`

Added to the `global:` block of `prometheus.yml`. Every scraped
time-series gets `env="prod"` as an instance-level label.
`prometheus.dev.yml` (dev override volume-mount via
`docker-compose.dev-observability.yml`) deliberately omits the block
so dev scrapes stay unlabelled. Step 4 of the deploy sequence
SIGHUPs Prometheus so the label takes effect.

### Compose + Prometheus config rewired (NEW — deep warroom blocker #1)

Three artifacts edited in the same commit so the pre-release warroom's
Prometheus-alerts-can't-load finding resolves before tag:

- **`docker-compose.yml`** Prometheus service block gains:
  - `command: [--web.enable-lifecycle, --config.file=/etc/prometheus/prometheus.yml, --storage.tsdb.path=/prometheus]`
  - Additional volume mount `./deploy/prometheus:/etc/prometheus/rules:ro`
- **`prometheus.yml`** (prod) + **`prometheus.dev.yml`** (dev) gain
  matching `rule_files: ['/etc/prometheus/rules/*.rules.yml']`
- **`docker-compose.dev-observability.yml`** re-states the rules
  volume (compose merge semantics: `volumes:` list replaces the
  base service's list)

Net effect: Prometheus now loads every `*.rules.yml` file under
`deploy/prometheus/`, which means:

- `phase-b-rls.rules.yml` (5 rules — shipped v0.43.1, never loaded on prod until now)
- `phase-c-cache-isolation.rules.yml` (4 rules — shipped v0.47 this release)

Total: 9 alert rules live on prod Prometheus.

### Observability additions (wrapper already shipped in v0.46; now exercised)

- `fabt_cache_get_total{cache,tenant,result}` — `result` ∈ `{hit, miss, cross_tenant_reject, malformed_entry}`
- `fabt_cache_put_total{cache,tenant}`
- `fabt_cache_registered_cache_names` gauge — `11.0` steady-state
- `fabt_audit_detached_failed_count{action}`

At 100 tenants × 11 caches × 4 results × 2 ops = ~8800 time-series
maximum. Demo-scale (1 active tenant `dev-coc`) ≈ 60 series. Well
within Prometheus's practical ceiling; re-evaluate at >500 tenants.

### Three new test classes (already on main from PR #139/#140)

- `Task4bCacheHitRateTest` — 7 parametrized rows (key-stability per migrated method)
- `Tenant4bMigrationCrossTenantAttackTest` — 8 parametrized rows (poison + rejection per cache name)
- `CacheIsolationDiscoveryTest` — 14 tests (3 guards + 11 parametrized per-cache isolation rows)

Full backend suite: 949/949 green.

## What Does NOT Change

- **`flyway_schema_history`** unchanged after this deploy (HWM stays **V74**). Zero schema risk.
- **No pgaudit config change.** `deploy/pgaudit.conf` unchanged. `fabt-pgaudit:v0.45.0` image stays on the VM; Postgres container is NOT rebuilt, NOT restarted.
- **No secrets rotation.** `~/fabt-secrets/.env.prod` unchanged.
- **No frontend bundle change.** `fabt-frontend` container NOT redeployed.
- **No new API endpoints.** The cache layer is internal.
- **Compose override chain composition is unchanged** — still the same 4 `-f` files from v0.45 / v0.46. The `~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml` bridge stays (retires when migrations above V74 land). BUT the BASE `docker-compose.yml` file itself changed (Prometheus service block rewired — see "What's New" item 4). The override chain COMPOSITION (filenames + ordering) is what's unchanged; the base file's content is not.
- **FORCE RLS `1.0` posture** on the 7 regulated tables is unchanged. Phase B invariant.
- **`application_name = 'fabt:tenant:<uuid>'` binding** in `RlsDataSourceConfig` stays active.

---

## VM-specific paths

Unchanged from v0.46.0. See the table in
`docs/oracle-update-notes-v0.45.0.md` VM-specific paths section. No
new paths, no new files, no new secrets for this release. The 4
compose override files are in the same locations; pgdata volume is
the same; `~/fabt-backups/` is the same.

---

## Pre-deploy sanity

> **Jordan's note:** every command here reads state; none mutate prod.
> Do it end-to-end before opening the deploy window. Nothing is
> skippable. v0.47 is the FIRST release where callers route through
> the wrapper, so the pre-deploy checklist gets one new gate (step 6)
> checking the cache-wrapper PR bake.

### 1. Confirm merge commit `31e17f5` CI is green

```bash
# From local laptop
gh pr checks 140 --required
gh run list --branch main --limit 3
```

Required: all green on `31e17f5`, including:
- `Backend (Java 25 + Maven)` — **949/949** with the 14 new `CacheIsolationDiscoveryTest` rows
- `E2E (Playwright + Karate)` — green on `31e17f5`
- `Legal Language Scan` — passed the post-fix commit `76db543`
- `CodeQL` — green
- `Family C annotation review gate` — empty allowlist honoured
- `Flyway HWM guard` — still V74
- `Release-gate SHA-256 pin verify` — unchanged

Known continue-on-error noise: `audience-pages-a11y.spec.ts` (task #169 docs-repo-path-gap). Not a v0.47 blocker.

### 2. Verify v0.46.0 is live + healthy

```bash
# From local laptop
curl -fsS https://findabed.org/api/v1/version | jq .
# Expected: {"version":"0.46"}

# From VM (9091 binds 127.0.0.1 only; substitute $VM_HOST for the host in ~/.ssh/config)
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
source ~/fabt-secrets/.env.prod

# Backend health
curl -fsS -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/health | jq .status
# Expected: "UP"

# FORCE RLS posture (Phase B invariant)
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep '^fabt_rls_force_rls_enabled{' | sort
# Expected: 7 lines, each ending in 1.0

# v0.46's fabt_cache_registered_cache_names gauge (wrapper was already seeded in v0.46)
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep '^fabt_cache_registered_cache_names'
# Expected: fabt_cache_registered_cache_names 11.0
EOF
```

Anchors the rollback target. **If any FORCE RLS gauge reports `0.0`, DO NOT DEPLOY v0.47.** A pre-existing Phase B regression must be resolved first under `scripts/phase-b-rls-panic.sh` procedure + warroom sign-off.

### 3. Capture cache + context-empty noise-floor baselines

Two samples, 60s apart, so post-deploy has a comparison:

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
source ~/fabt-secrets/.env.prod

echo "-- tenant_context_empty rate (v0.46 baseline) --"
V1=$(curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
sleep 60
V2=$(curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
echo "context_empty = $(echo "($V2 - $V1) / 60" | bc -l) /s  (v0.46 baseline ~0.83/s; threshold 1.5/s)"

echo ""
echo "-- fabt_cache_get_total / put_total — expected ZERO in v0.46 (wrapper idle) --"
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep -E '^fabt_cache_(get|put)_total' | head -5
# Expected: empty output (no caller routed through wrapper in v0.46)
EOF
```

Post-deploy sanity step 5 will contrast: cache counters go from 0 to non-zero as traffic exercises the migrated call sites.

### 4. Dry-render the 4-file compose config

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability \
    config 2>&1 | awk '/^  backend:/,/^  [a-z][a-z-]+:/' | head -40
EOF
```

**If this diverges from the v0.46.0 render by a single byte, investigate BEFORE opening the deploy window.** v0.47 does not change any compose override; the render must be identical.

### 5. Pre-tag checklist

- [ ] `backend/pom.xml` version is `0.47.0` (no `-SNAPSHOT`)
- [ ] `CHANGELOG.md` `[v0.47.0]` section present + legal-scan clean
- [ ] Tag `v0.47.0` created from `31e17f5` on main (local laptop, `git tag -a v0.47.0 -m "..."` + push)
- [ ] GitHub Release `v0.47.0` drafted + published
- [ ] Bake window satisfied (>= 24h since v0.46.0 at 2026-04-19 18:32 UTC → tag permitted after 2026-04-20 18:32 UTC)

### 6. NEW — verify the alert rules YAML files are on disk at the repo-mount path

After the `git checkout v0.47.0`, the rules files must be present at
`~/finding-a-bed-tonight/deploy/prometheus/`. The `docker-compose.yml`
Prometheus service now bind-mounts that directory to
`/etc/prometheus/rules/` inside the container, so a repo-level checkout
is sufficient — no `docker cp` or manual copy needed.

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
ls -la deploy/prometheus/
# Expected: 3 files — phase-b-rls.rules.yml (shipped v0.43.1),
#   phase-c-cache-isolation.rules.yml (shipped v0.47),
#   (any other *.rules.yml files added by future releases)
EOF
```

If either file is missing, the `git checkout v0.47.0` didn't land
cleanly — run `git status` and investigate. DO NOT proceed to deploy.

### 5.5 NEW — capture release-prep commit SHA (fills in the Tag step)

```bash
# From local laptop, AFTER the v0.47 release-prep PR merges to main
git fetch origin main && git log --oneline origin/main -3
# Copy the top SHA (the release-prep commit — pom+CHANGELOG+this runbook)
# Tag will attach to THIS commit, not to 31e17f5 (PR #140 merge).

git tag -a v0.47.0 -m "Phase C completes: cache isolation active across all application call sites" <release-prep-SHA>
git push origin v0.47.0
gh release create v0.47.0 --title "v0.47.0 — Phase C completes" \
    --notes-file docs/oracle-update-notes-v0.47.0.md
```

---

## Deploy steps

### 1. Checkout tag + preserve last-good backend image

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.47.0
git log --oneline -5
# Expected: commit f74762d (task 4.b drain) + 31e17f5 (task 4.6 + fold-ins) visible

# Preserve rollback image (idempotent; no-op if tag already exists)
docker tag fabt-backend:latest fabt-backend:v0.46.0-lastgood 2>/dev/null || true
docker images | grep fabt-backend
# Expected: fabt-backend:latest and fabt-backend:v0.46.0-lastgood both pointing at the same image ID
EOF
```

### 2. Build backend JAR + image (from local laptop, push image to Oracle VM registry if external; else build on VM)

If building on the VM (standard prod-deploy pattern per v0.45/v0.46):

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight/backend
mvn -B -DskipTests clean package -q
ls -lh target/finding-a-bed-tonight-0.47.0.jar
# Expected: exactly one JAR with 0.47.0 in the name

cd ..
docker build --no-cache -t fabt-backend:v0.47.0 -t fabt-backend:latest .
docker images | grep fabt-backend | head -3
# Expected: fabt-backend:v0.47.0 + fabt-backend:latest now point at a new image ID; lastgood unchanged
EOF
```

`--no-cache` per `feedback_deploy_old_jars.md`. `clean package` per `feedback_deploy_checklist_v031.md`.

### 3. Restart backend only

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability \
    up -d --force-recreate backend

# Tail startup log
docker compose logs -f --tail=100 backend 2>&1 | head -80
EOF
```

Expected startup sequence (in order):
1. Hikari pool init (~2s)
2. Flyway "Successfully validated 65 migrations" (HWM stays 74)
3. **`TenantScopedCacheService eager-seeded with 11 cache names: [...]`** (INFO log — unchanged from v0.46)
4. `EscalationPolicyService caches initialised: policyById + currentPolicyByTenant + policyByTenantAndId`
5. `PgVersionGate OK`
6. `Started Application`

**Total backend restart: ~30 seconds.** Public uptime: nginx static content (`/`, `/login`, `/assets`) serves 200 throughout; `/api/v1/*` returns 502/500 during the Hikari reconnect window.

### 4. NEW — recreate Prometheus + reload config

**Recreate first, then reload.** Because `docker-compose.yml` changed
the Prometheus service definition (new `command:` block + new rules
volume), Docker Compose must drop-and-recreate the container — a bare
`restart` keeps the old flags. After recreate, SIGHUP reloads the
config so `external_labels: env=prod` + the new `rule_files:` directive
take effect + rules parse from the bind-mounted directory.

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
source ~/fabt-secrets/.env.prod

# Recreate Prometheus with the new --web.enable-lifecycle flag + rules mount
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability \
    up -d --force-recreate prometheus

# Verify the container came up with the new command line
docker inspect prometheus --format '{{.Config.Cmd}}'
# Expected: [--web.enable-lifecycle --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus]

# Verify rules directory is mounted + readable inside the container
docker exec prometheus ls /etc/prometheus/rules/
# Expected: phase-b-rls.rules.yml, phase-c-cache-isolation.rules.yml

# Wait for first scrape (default interval 15s) so env=prod propagates
sleep 20

# Confirm reload works (needs --web.enable-lifecycle from recreate above)
curl -fsS -X POST http://localhost:9090/-/reload && echo "reload OK"
# Expected: "reload OK" (HTTP 200 from Prometheus, empty body)

# Verify the env label appears on scraped targets
curl -s http://localhost:9090/api/v1/targets | \
    jq '.data.activeTargets[] | select(.labels.job=="fabt-backend") | .labels.env'
# Expected: "prod" (instance-level external_labels block is attached during scrape)

# Verify rules loaded — expect both groups
curl -s http://localhost:9090/api/v1/rules | \
    jq -r '.data.groups[] | "\(.name) : \(.rules | length) rules"'
# Expected (exact match, 2 lines):
#   fabt_phase_b_rls : 5 rules
#   fabt_phase_c_cache_isolation : 4 rules
EOF
```

**If reload returns 405**, the container was NOT recreated cleanly —
`command:` block didn't apply. Run the `up -d --force-recreate
prometheus` again. **If rules count is wrong**, check
`docker logs prometheus --tail 50` for parse errors in
`*.rules.yml` files; fix locally, commit, git pull, redo step 4.

Without this step the 9 alert rules stay inactive even after backend
deploys successfully.

---

## Post-deploy sanity checks

> **Jordan's note:** 8 gates. Steps 1, 3, 5, 6, 7 are non-negotiable for
> v0.47. Step 7 is new: without it the alert rules are shipping but
> muted.

### 1. Version confirmation

```bash
curl -fsS https://findabed.org/api/v1/version | jq .
# Expected: {"version":"0.47"}
```

Non-negotiable. If this shows `0.46`, the container swap did not take effect — check `docker compose ps backend` + redeploy.

### 2. Application health

```bash
curl -fsS https://findabed.org/actuator/health | jq .status
# Expected: "UP"
```

### 3. Wrapper eager-seed log + gauge unchanged

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
docker compose logs backend --since 5m 2>&1 | \
    grep "TenantScopedCacheService eager-seeded with 11 cache names"
# Expected: 1 line (this JVM's startup)

source ~/fabt-secrets/.env.prod
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus | grep fabt_cache_registered_cache_names
# Expected: fabt_cache_registered_cache_names 11.0
EOF
```

If the log line is missing OR the gauge is not 11.0, `CacheNames` reflection broke — the wrapper fails-fast on empty so the JVM wouldn't have started. Check boot logs for `IllegalStateException`.

### 4. FORCE RLS still `1.0` across the 7 regulated tables

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
source ~/fabt-secrets/.env.prod
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep '^fabt_rls_force_rls_enabled{' | sort
# Expected: 7 lines, each ending in 1.0
EOF
```

Phase B invariant. v0.47 does not touch RLS; regression here is a pre-existing bug, not v0.47's fault — but rollback anyway until resolved.

### 5. MOVED — wrapper counters become non-zero under traffic (run AFTER step 8 Playwright smoke)

**Ordering note:** step 5 is the verification step, step 8 is the
traffic generator. Run step 8 first so Playwright exercises the 9
migrated cache call sites; then run step 5 to confirm the counters
moved. A sleep-without-trigger like the original draft is wishful
thinking — `/actuator/prometheus` will still report flat-zero because
a wrapper counter only emits on `.get()` / `.put()`, and no caller
invokes those without inbound traffic.

Re-execute step 5 after step 8 completes:

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
source ~/fabt-secrets/.env.prod

curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep -E '^fabt_cache_(get|put)_total' | head -10
# Expected: ≥1 line with {cache=..., tenant=..., result=...}
# Expected result values: "hit" + "miss" both appear; "cross_tenant_reject"
# and "malformed_entry" should NOT appear under normal traffic.

# No result=cross_tenant_reject rows anywhere
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep 'fabt_cache_get_total.*result="cross_tenant_reject"' | wc -l
# Expected: 0
EOF
```

If counters stay flat-zero after the Playwright smoke + this grep:
the migration landed, but no caller is actually hitting the wrapper.
Investigate `BedSearchService.doSearch` bean injection (the wrapper
may have been wired as the wrong Spring bean name) — this is a silent
regression the `CacheIsolationDiscoveryTest` + `Task4bCacheHitRateTest`
suite caught at build time, but a Spring-context mis-wire on prod
could still slip past.

### 6. NEW — 4 Phase C alert rules loaded into Prometheus

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
curl -s http://localhost:9090/api/v1/rules | \
    jq '.data.groups[] | select(.name == "fabt_phase_c_cache_isolation") | .rules | length'
# Expected: 4

curl -s http://localhost:9090/api/v1/rules | \
    jq '.data.groups[] | select(.name == "fabt_phase_c_cache_isolation") | .rules[] | .name'
# Expected: 4 lines:
#   "FabtPhaseCCrossTenantCacheRead"
#   "FabtPhaseCMalformedCacheEntry"
#   "FabtPhaseCCacheHitRateCollapse"
#   "FabtPhaseCDetachedAuditPersistFailure"
EOF
```

If zero rules are loaded, the pre-deploy step 6 mount was wrong OR the Prometheus reload didn't pick the new file up. Check `docker logs prometheus` for `error parsing rule file`.

### 7. NEW — `env="prod"` label propagates to scraped metrics

`external_labels` are applied to scraped time-series at scrape time,
not at rule-eval time. First scrape after the deploy-step-4 reload
happens within the 15s scrape interval; query too early returns the
pre-reload metric without the label. Sleep generously:

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
# Wait for at least 1 full scrape interval + buffer (20s)
sleep 20

# Target-level: every scraped target should report env=prod in its label set
curl -s http://localhost:9090/api/v1/targets | \
    jq -r '.data.activeTargets[] | .labels.env' | sort -u
# Expected: only "prod" in the output

# Metric-level: query a specific metric and confirm env=prod is on the result.
# Note: Prometheus attaches external_labels to the query RESULT, not to the
# scraped metric line — so /api/v1/query shows it; /actuator/prometheus
# does NOT (the label is applied AFTER the scrape, inside the Prometheus
# storage layer).
curl -s 'http://localhost:9090/api/v1/query?query=fabt_cache_registered_cache_names' | \
    jq '.data.result[0].metric.env'
# Expected: "prod"
EOF
```

If `env` is `null` or empty:
1. Re-run deploy step 4 (`--force-recreate prometheus` + reload).
2. Check `docker logs prometheus --tail 50` for config-load errors.
3. Verify `prometheus.yml` on the VM actually has the `external_labels` block (`ssh ... 'grep -A 2 external_labels ~/finding-a-bed-tonight/prometheus.yml'`).

### 8b. NEW — DV analytics cache smoke (Marcus + Elena fold-in)

v0.47 routes `ANALYTICS_DV_SUMMARY` through the wrapper for the first
time. DV-access is a load-bearing privacy boundary per VAWA posture;
Playwright `post-deploy-smoke.spec.ts` does NOT exercise the
`/api/v1/analytics/dv-summary` endpoint. Manually verify:

```bash
# From local laptop, using seed DV-coordinator creds
DV_TOKEN=$(curl -sfS -X POST https://findabed.org/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"dv-coordinator@dev.fabt.org","password":"admin123"}' \
    | jq -r .token)

# First request: should populate the cache (HIT rate stays 0 for this cache)
curl -sfS -H "Authorization: Bearer $DV_TOKEN" \
    https://findabed.org/api/v1/analytics/dv-summary | jq .suppressed
# Expected: true OR false depending on seed DV shelter count (both valid;
# "suppressed":true is the D18 dual-threshold path)

# Second request: should HIT the cache (same tenant, same logical key "latest")
curl -sfS -H "Authorization: Bearer $DV_TOKEN" \
    https://findabed.org/api/v1/analytics/dv-summary | jq .suppressed
# Expected: same value as above

# Verify the wrapper counter emitted a hit for ANALYTICS_DV_SUMMARY
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
source ~/fabt-secrets/.env.prod
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep 'fabt_cache_get_total{cache="analytics-dv-summary"' \
    | grep -E 'result="(hit|miss)"'
# Expected: one line with result="hit" (>= 1.0) and one with result="miss" (>= 1.0)
EOF
```

If this step produces `result="cross_tenant_reject"` at ANY count,
HOLD deploy — cross-tenant DV leak detected. Rollback immediately,
capture `audit_events` rows with action `CROSS_TENANT_CACHE_READ`,
open security bridge.

### 8. Playwright post-deploy smoke

```bash
# From local laptop (or CI if wired)
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test deploy/post-deploy-smoke.spec.ts --trace on
# Expected: 12/12 PASSED in ~40s
```

This exercises bed-search + availability-read + shelter-list flows, so it implicitly warms the 9 migrated cache call sites. Combined with step 5 (counter check), it proves the wrapper is in the request path.

### 9. Audit-events table has zero CROSS_TENANT rows from v0.47 window

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
# Connect as fabt owner to bypass RLS for diagnostic
docker exec -it fabt-postgres psql -U fabt -d fabt -tAc "
SELECT action, COUNT(*) FROM audit_events
WHERE action IN ('CROSS_TENANT_CACHE_READ', 'CROSS_TENANT_POLICY_READ', 'MALFORMED_CACHE_ENTRY')
  AND timestamp > now() - interval '10 minutes'
GROUP BY action;
"
# Expected: empty result set (no rows)
EOF
```

A non-zero count within 10 minutes of deploy is a **serious signal** — either an active attack OR a code-path regression. Rollback immediately, triage per `phase-c-cache-isolation-runbook.md`.

---

## 2-hour active watch (upgraded from v0.46's 1h)

**Upgrade rationale:** v0.47 is the first behavioural-change release
since v0.45 (Phase B FORCE RLS activation). v0.46 was a preparatory
bean-addition with idle wrapper, warranted 1h. v0.47 flips 9 call
sites into the live request path — the cache-isolation defence is
ON for the first time. 2h gives two 15-min scrape-alert-evaluation
cycles to fire against real user traffic if a regression exists.

**Known-issue — Alertmanager NOT wired (task #155 pending).** The
9 rules loaded via step 4 fire in Prometheus's own UI (visit
`http://localhost:9090/alerts` through a tunnel) but do NOT page
Slack or email. On-call must eyeball Prometheus UI + Grafana
dashboards manually during the 2h window until task #155 closes.
Flag in a runbook annotation below the watch-metric table.

**Known-issue — `FabtPhaseCCacheHitRateCollapse` dormant on day 1.**
The WARN rule compares current-hour hit-rate to a 7-day moving
average. On first deploy day the 7-day window is empty; the
`avg_over_time(...[7d])` expression returns `NaN`, the comparison
evaluates as false, rule never fires. Active after T+7d of
continuous scrape. Not a regression to monitor during v0.47 bake.

Operator stays on the VM for two active hours after deploy; walks
away only if all 9 gates below hold continuously.

| Metric | v0.46 baseline | v0.47 threshold | Rollback trigger |
|---|---|---|---|
| `fabt_cache_registered_cache_names` | 11.0 | unchanged at 11.0 | any drift |
| `fabt_cache_get_total{result="hit"}` | 0 (idle) | non-zero after ~5 min traffic | flat-zero at 60 min = wrapper never exercised |
| `fabt_cache_get_total{result="cross_tenant_reject"}` | 0 | 0 | any increment — attack or regression |
| `fabt_cache_get_total{result="malformed_entry"}` | 0 | 0 | sustained non-zero over 15m |
| `fabt_rls_tenant_context_empty_count_total` rate | ~0.83/s | within ±0.5/s | sustained > 1.5/s over 5m |
| `fabt_rls_force_rls_enabled{}` | 1.0 × 7 | unchanged | any 0.0 |
| BedSearch p95 (Grafana `bedSearchTimer` panel) | v0.46 baseline | within ±30% | > 30% regression over 10m |
| `fabt_audit_detached_failed_count_total` | 0 | 0 | any increment |
| Active alerts in Alertmanager | none | none | any firing |

If any rollback trigger fires within the 1-hour window, execute
"Rollback" section below AND open an incident bridge.

---

## Rollback

### Trigger table

| Signal | Rollback scope | Severity |
|---|---|---|
| v0.47 on `/api/v1/version` but `/actuator/health` not `UP` | Backend | CRITICAL — paging |
| `fabt_rls_force_rls_enabled{}` any gauge = 0.0 | **Phase B panic, not v0.47** — run `scripts/phase-b-rls-panic.sh`, do NOT rollback v0.47 first | CRITICAL |
| `FabtPhaseCCrossTenantCacheRead` fires | Backend | CRITICAL — paging |
| `FabtPhaseCMalformedCacheEntry` sustained 15+ min | Backend | WARN — investigate; rollback if active on every write |
| `fabt_cache_registered_cache_names` ≠ 11.0 | Backend | CRITICAL — reflection broke; JVM wouldn't boot cleanly |
| BedSearch p95 > 30% regression over 10m | Backend | WARN — investigate; rollback if sustained 30m+ |
| Playwright smoke ≥ 2 failures | Backend | CRITICAL — user-visible regression |
| `tenant_context_empty` rate > 1.5/s sustained 5m | Backend | WARN — DI graph perturbed; investigate then rollback |

### §Rollback — atomic, one step

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight
source ~/fabt-secrets/.env.prod

# Swap image tag — fabt-backend:latest now points at v0.46 again
docker tag fabt-backend:v0.46.0-lastgood fabt-backend:latest
docker images | grep fabt-backend

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability \
    up -d --force-recreate backend

sleep 15
curl -fsS https://findabed.org/api/v1/version | jq .
# Expected: {"version":"0.46"}
EOF
```

**Prometheus config rollback** (only if needed — the v0.47 base compose
change added `--web.enable-lifecycle` + rules volume + `rule_files:`
glob. Rolling Prometheus itself back is independent from rolling the
backend):

```bash
ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
cd ~/finding-a-bed-tonight

# Revert the three affected files to v0.46 content (drops --web.enable-lifecycle,
# drops rules volume mount, drops rule_files directive + external_labels block)
git checkout v0.46.0 -- docker-compose.yml prometheus.yml prometheus.dev.yml \
    docker-compose.dev-observability.yml

# Recreate Prometheus with v0.46 config (no --web.enable-lifecycle, no rules)
source ~/fabt-secrets/.env.prod
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability \
    up -d --force-recreate prometheus
EOF
```

Effects: 9 alert rules unload (Phase B 5 + Phase C 4). Prometheus
reverts to scrape-without-rule-eval, env=prod label stops being
attached to time-series. Application metrics keep flowing — only
alerting is affected. This rollback is INDEPENDENT of the backend
rollback above; you can roll just the backend and keep Prometheus on
the v0.47 config (alerts will still fire on the rolled-back backend's
metrics because the rules filter on env=prod, which v0.47 Prometheus
still attaches).

**Preferred scenario:** roll backend ONLY (above). Keep Prometheus on
v0.47 config because the nine rules can't do harm against v0.46
metrics — they either match nothing (v0.46 had zero cross-tenant or
malformed-entry counters firing because the wrapper was idle) OR
they fire harmlessly and wait for task #155 Alertmanager wiring.

### State preserved on rollback

- pgdata volume + all tables (no `DROP COLUMN`, no Flyway change)
- `audit_events` rows already written during the v0.47 window (including any `CROSS_TENANT_CACHE_READ`, `TENANT_CACHE_INVALIDATED`, `MALFORMED_CACHE_ENTRY` action strings — these AuditEventTypes constants exist in v0.46 code, just unwritten)
- Phase B RLS policies + FORCE RLS posture
- pgaudit config + image (`fabt-pgaudit:v0.45.0`)
- Secrets + compose chain composition

### State lost on rollback

- In-memory Caffeine caches (wiped on container restart; this is normal)
- Micrometer counters for `fabt_cache_*` (reset to zero when the bean re-initialises)

Nothing corrupts. Nothing requires pgdata restore unless an unrelated Phase B regression triggered alongside.

---

## After deploy succeeds

1. Close PRs #139 + #140 in GitHub UI (already merged; just mark the Phase C epic thread closed).
2. Update memory `project_live_deployment_status.md` — new live version 0.47.0, Phase C complete, note that Prometheus alert rules are LOADED (v0.47 docker-compose change) but NOT PAGED (task #155 pending).
3. Update memory `project_multi_tenant_phase_b_resume.md` — Phase C closed; note remaining follow-ups (#180 EscalationPolicyAuditRollbackIT, #182 Rule C4 ConcurrentHashMap heuristic, #184 NEGATIVE_SENTINEL enum promotion, #163 pgaudit include_dir fix, #155 Alertmanager wiring).
4. Update docs-repo `openspec/changes/multi-tenant-production-readiness/tasks.md` — tick section 4 "Commit Phase C + open PR" and any outstanding closure items. (Phase C archive via `/opsx:archive` can wait until Phase D starts.)
5. **NEW — Run `pg_stat_statements` DB-floor harness** (Sam follow-up). Captures baseline floor latency for the canonical BedSearch recursive CTE. The harness needs a seeded `perf-probe-bedsearch-4b` tenant with representative `bed_availability` rows; skip if the probe tenant isn't seeded (task for Phase D). If seeded:
   ```bash
   ssh -i ~/.ssh/fabt-oracle $VM_HOST <<'EOF'
   cd ~/finding-a-bed-tonight
   docker exec -i fabt-postgres psql -U fabt -d fabt \
       < docs/performance/probe-bedsearch-4b.sql \
       > ~/fabt-backups/pg-stat-v0.47-postdeploy-$(date +%F-%H%M).txt 2>&1
   tail -30 ~/fabt-backups/pg-stat-v0.47-postdeploy-*.txt
   EOF
   ```
   Paste the `mean_exec_time` line into the GitHub Release notes as the published DB-floor baseline. Future PRs that change the bed_availability CTE can diff against this.
6. Post-deploy pgaudit window (~24h): watch the nine alert panels in Prometheus UI (until #155 closes + Grafana panels land) + manually verify no anomalies in Jaeger traces for bed-search endpoint.

## What the next release series (v0.48+) adds on top

- Task 4.6 / reflection-driven cache-bleed test already landed (v0.47).
- **v0.48 target**: Phase D (control-plane hardening — controller URL-path-sink audit per D11).
- **Task #163 pgaudit fix**: switches `-c` flag in compose to avoid `include_dir` persistence behaviour. Can land in v0.47.1 hotfix if operator wants it before v0.48.
- **Task #151 Flyway out-of-order retire**: when migrations ≥V75 land (Phase D+), retire `~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml`.

## Related

- PR #139 — task 4.b drain — https://github.com/ccradle/finding-a-bed-tonight/pull/139 (merged)
- PR #140 — task 4.6 + pre-release fold-ins — https://github.com/ccradle/finding-a-bed-tonight/pull/140 (merged)
- CHANGELOG `[v0.47.0]` entry — `CHANGELOG.md` at the top
- Alert triage runbook — `docs/security/phase-c-cache-isolation-runbook.md`
- OpenSpec change — `openspec/changes/multi-tenant-production-readiness/` (in the docs repo)
- Design-c (Phase C cache isolation) — D-C-1..13 + D-4.b-1..7 in `design-c-cache-isolation.md`
