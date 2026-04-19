# Oracle Deploy Notes — v0.46.0 (Phase C groundwork: TenantScopedCacheService idle wrapper, not yet wired)

**From:** v0.45.0 (currently live at `findabed.org`)
**To:** v0.46.0 (TenantScopedCacheService + DetachedAuditPersister + `evictAllByPrefix` — all idle in the Spring context; no call site invokes the wrapper yet)

> **Jordan's note (SRE):** preparatory-only release. New bean in the
> context, zero production callers route through it, no schema, no
> pgaudit, no frontend, no compose edit. Cheapest deploy of the sprint.
> The warroom-approved framing stands (`CHANGELOG.md` `[v0.46.0]` first
> paragraph) — don't re-litigate it here.
>
> **Compose-file drift is still the #1 deploy-hour incident even when
> nothing about compose is supposed to change.** Run the dry-render in
> pre-deploy step 4. If it diverges from the v0.45.0 render by a single
> byte, investigate BEFORE opening the deploy window.

**Bake window math:** v0.45.0 shipped 2026-04-19 ~12:44 UTC → v0.46.0
can tag after **2026-04-20 ~12:44 UTC** without breaching the 24h
guardrail. No compression needed — Phase C's behavioural-change work
(task 4.4 + 4.2 + 4.b) still needs ~1 week of implementation regardless.

---

## What's New in This Deploy

- **No new Flyway migrations.** HWM stays at **V74**.
  `deploy/prod-state.json` unchanged from v0.45.0. Zero schema risk.
- **No pgaudit config change.** `deploy/pgaudit.conf` unchanged. The
  `fabt-pgaudit:v0.45.0` image stays on the VM; Postgres container is
  NOT rebuilt, NOT restarted. pgaudit `app=%a` log-line-prefix from
  v0.45 stays active. Include_dir fix (task #163) is persistent in
  the pgdata volume — nothing to re-apply.
- **No secrets rotation.** `~/fabt-secrets/.env.prod` unchanged.
- **No frontend bundle change.** `fabt-frontend` container NOT redeployed.
- **Backend JAR only.** `target/finding-a-bed-tonight-0.46.0.jar`
  replaces v0.45.0. Image `fabt-backend:latest` rebuilds. Backend
  container restarts — expect ~5 s Hikari reconnect window during the
  swap (same behaviour as every prior backend-only release).
- **New beans on boot:**
  - `TenantScopedCacheService` (`@Service` in `org.fabt.shared.cache`)
    — reflects over `CacheNames` at `@PostConstruct`, seeds a
    `Set<String>` with 11 cache names, emits one INFO log, registers
    the `fabt.cache.registered_cache_names` Micrometer gauge. If the
    reflection fails or returns empty, bean throws
    `IllegalStateException` and JVM boot halts — fail-loud, fail-fast.
  - `TenantScopedValue<T>` record envelope (idle; not written by any
    caller yet).
  - `DetachedAuditPersister` (`@Service` in `org.fabt.shared.audit`)
    — `REQUIRES_NEW` audit path for security-evidence rows. Idle;
    only invoked by `TenantScopedCacheService` on cross-tenant-read
    detection, which no call site can trigger in v0.46.
- **`CacheService.evictAllByPrefix(cacheName, prefix)`** — new interface
  method. Not called by any production path in v0.46.
- **3 new `AuditEventTypes` constants** — `TENANT_CACHE_INVALIDATED`,
  `CROSS_TENANT_CACHE_READ`, `MALFORMED_CACHE_ENTRY`. Exist in code;
  no path writes them until task 4.b.

## What Does NOT Change

- `flyway_schema_history` is unchanged after this deploy (HWM stays 74).
- No new API endpoints. Existing `/api/v1/*` surface identical.
- No new Micrometer counters with tenant-dimensional cardinality. The
  wrapper's per-tag counter cache is empty until a caller invokes
  `get`/`put` — which no caller does in v0.46. Cardinality surprise
  risk is zero.
- No new `audit_events` rows appear during v0.46 runtime. The three new
  action strings exist in code but no call path writes them.
- FORCE RLS `1.0` posture on the 7 regulated tables is unchanged.
- pgaudit `log_line_prefix` with `app=%a` stays active (v0.45).
- `application_name = 'fabt:tenant:<uuid>'` binding in
  `RlsDataSourceConfig` stays active (v0.45).
- Compose override chain composition is unchanged — still the same 4
  `-f` files from v0.45.0; no new override created, no existing one
  edited. The `~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml`
  bridge stays on disk (retires post-v0.47 when migrations above V74
  land and apply).

---

## VM-specific paths

**Unchanged from v0.45.0.** See the table in
`docs/oracle-update-notes-v0.45.0.md` VM-specific paths section. No
new paths, no new files, no new secrets for this release. The 4
compose override files are in the same locations; pgdata volume is
the same; `~/fabt-backups/` is the same.

---

## Pre-deploy sanity

> **Jordan's note (SRE):** every command in this section reads state;
> none mutate prod. Do it end-to-end before opening the deploy window.
> Nothing on this list is skippable just because this is a preparatory
> release — the idle-bean posture doesn't protect against compose
> drift, image-tag confusion, or a stale-JAR deploy.

### 1. Confirm merge commit `b468da3` CI is green

```bash
# From local laptop
gh pr checks 135 --required
gh run list --branch main --limit 3
```

Required: all green on `b468da3`, including:
- `flyway-hwm-guard` (carried from v0.45)
- `release-gate-pin-verify` (carried from v0.45 — `docs/security/pg-policies-snapshot.md` unchanged in v0.46; pin is a no-op)
- `pgaudit-image-tests` (carried from v0.44.1)

**Known continue-on-error noise:** `audience-pages-a11y.spec.ts`
surfaces `ERR_FILE_NOT_FOUND` due to task #169 (docs-repo-path-gap in
CI). Job conclusion still SUCCESS; not a v0.46 blocker. Cross-reference
`project_ci_e2e_29_failures_rca.md`.

### 2. Verify v0.45.0 is live + healthy

```bash
# From local laptop — /api/v1/version returns major.minor only
curl -fsS https://findabed.org/api/v1/version | jq .
# Expected: {"version":"0.45"}

# From VM (9091 binds 127.0.0.1 only)
ssh -i ~/.ssh/fabt-oracle ubuntu@<VM-IP> <<'EOF'
curl -fsS http://localhost:9091/actuator/health | jq .status
# Expected: "UP"

source ~/fabt-secrets/.env.prod
curl -sf http://localhost:9091/actuator/prometheus \
    | grep '^fabt_rls_force_rls_enabled{' | sort
# Expected: 7 lines, each ending in 1.0.
EOF
```

Anchors the rollback target. **If any FORCE RLS gauge reports 0.0, DO
NOT DEPLOY v0.46.** A pre-existing regression must be resolved under
the v0.45 runbook (panic script + warroom) before new code goes on top.

### 3. Capture `tenant_context_empty` noise-floor baseline

Same as v0.45 pre-deploy step 3. Sample twice, 60s apart, so you have
a post-deploy comparison:

```bash
source ~/fabt-secrets/.env.prod
V1=$(curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
sleep 60
V2=$(curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
echo "rate = $(echo "($V2 - $V1) / 60" | bc -l) /s  (v0.45 baseline ~0.83/s)"
```

**Threshold to watch during + after cutover:** sustained rate > **1.5/s**
for 5 minutes → a code path in the new bean's DI graph perturbed
Hikari pool priming. Investigate. Rate within ±0.5/s of the v0.45
baseline is clean.

### 4. Dry-render the 4-file compose config

> **Jordan's note (SRE):** compose-override drift is the #1 deploy-hour
> incident. The 4-override chain is unchanged for this release, but the
> render is cheap (~2s) and catches an override file that was edited
> between v0.45 and v0.46 for any reason.

```bash
cd ~/finding-a-bed-tonight
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    config 2>&1 | awk '/^  backend:/,/^  [a-z]/' | head -40
```

Expected: `image` reads `fabt-backend:latest`; `command`/`environment`
blocks unchanged from v0.45. If ANY line diverges from the v0.45
render, STOP and investigate before the window opens — not during it.

Also verify Postgres service block is unchanged:

```bash
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    config 2>&1 | awk '/^  postgres:/,/^  [a-z]/' | head -40
# Expected: image reads fabt-pgaudit:v0.45.0 (UNCHANGED).
```

### 5. Pre-tag checklist

- [ ] `main@b468da3` CI all-green (step 1)
- [ ] 24h bake satisfied (≥ 2026-04-20 ~12:44 UTC)
- [ ] `backend/pom.xml` = `0.46.0` on the release-prep commit
- [ ] `CHANGELOG.md` `[v0.46.0]` section present + legal-scan clean
- [ ] Compose dry-render (step 4) matches v0.45 output byte-for-byte
- [ ] This runbook file passes legal-language scan
- [ ] Current PG `server_version_num ≥ 160006` (no PG bump in v0.46;
      `PgVersionGate` still enforces boot-time floor)

---

## Deploy steps

> **Jordan's note (SRE):** single backend restart. No staged swap, no
> mandatory pause — there's nothing to pause between. Hikari reconnect
> window ~5s; public site (`/`, `/login`) stays 200 OK throughout
> because nginx serves static content.

### 1. Checkout tag + preserve last-good backend image

**Carried from v0.44.1 pattern.** The retag converts rollback from a
rebuild (~2 min of `mvn clean package`) into a ~10s retag.

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<VM-IP>
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.46.0
git status   # expect: HEAD detached at v0.46.0, clean tree

# Preserve current v0.45.0 backend image under an explicit rollback tag
docker tag fabt-backend:latest fabt-backend:v0.45.0-lastgood 2>/dev/null || true
docker images | grep fabt-backend
# Confirm both `fabt-backend:latest` AND `fabt-backend:v0.45.0-lastgood` exist.
```

**Rollback command (for reference — do NOT run yet):**

```bash
docker tag fabt-backend:v0.45.0-lastgood fabt-backend:latest
# …then restart per Rollback section below.
```

### 2. Build backend JAR + image

```bash
# Clean build — never skip `clean` per feedback_deploy_old_jars.md
cd backend
mvn -B -DskipTests clean package
ls -lh target/finding-a-bed-tonight-0.46.0.jar
# Verify: timestamp is post-build, file size >100 MB, version matches tag
cd ..

# --no-cache per feedback_deploy_checklist_v031.md
docker build --no-cache -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.46.0 -t fabt-backend:latest .
docker images | grep fabt-backend
# Expect: v0.46.0 (new), latest (same digest as v0.46.0), v0.45.0-lastgood (preserved)
```

### 3. Restart backend only

```bash
source ~/fabt-secrets/.env.prod
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod --profile observability \
    up -d --force-recreate backend
```

Tail the logs for the expected startup sequence:

```bash
docker compose -f docker-compose.yml logs -f backend --since 2m
```

Expect, in order:

1. Hikari init → `HikariPool-1 - Start completed`
2. Flyway → `Successfully validated 74 migrations` (no new apply; HWM 74)
3. **`TenantScopedCacheService eager-seeded with 11 cache names: […]`** (the v0.46 signal)
4. `PgVersionGate: PostgreSQL 16.6 OK` (from v0.45; still runs every boot)
5. `Started Application` — ready for traffic

Expect a ~5 s window where `/api/v1/*` returns 502/500 while Hikari
reconnects. Public `/`, `/login` stays 200 OK throughout (nginx static).

---

## Post-deploy sanity checks

Seven checks — run in order. Checks 1-3 are v0.46-specific; checks 4-7
carry Phase B invariants from v0.45.

### 1. Version confirmation

```bash
# Public — major.minor only per VersionController
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.46"}

# VM-internal — full version
docker compose -f ~/finding-a-bed-tonight/docker-compose.yml exec -T backend \
    curl -s http://localhost:9091/actuator/info | jq .build.version
# Expected: "0.46.0"
```

Catches stale-tag-rebuild-but-compose-pointed-wrong per v0.39 canon.

### 2. `TenantScopedCacheService` eager-seed INFO log

```bash
docker compose -f ~/finding-a-bed-tonight/docker-compose.yml logs backend \
    --since 5m | grep -F "TenantScopedCacheService eager-seeded"
```

Expected one line naming all 11 cache names from `CacheNames`:

```
INFO o.f.s.cache.TenantScopedCacheService - TenantScopedCacheService eager-seeded with 11 cache names: [tenant-config, analytics-geographic, analytics-demand, analytics-dv-summary, shelter-profile, analytics-capacity, analytics-utilization, shelter-list, geo-query-results, analytics-hmis-health, shelter-availability]
```

**If absent**, the bean did not boot — investigate via the full
5-minute log for an `IllegalStateException` from
`TenantScopedCacheService.seedRegisteredCacheNames`. This is the
fail-loud guard; boot should have halted. Rollback trigger.

### 3. `fabt.cache.registered_cache_names` gauge scrapable

```bash
docker compose -f ~/finding-a-bed-tonight/docker-compose.yml exec -T backend \
    curl -s http://localhost:9091/actuator/prometheus | grep fabt_cache_registered_cache_names
```

Expected one line, value `11.0`:

```
fabt_cache_registered_cache_names 11.0
```

**If missing**, Micrometer didn't wire it — NOT a fail-stop condition
(the bean still guards `invalidateTenant`), but open an operator ticket
because the gauge is the only observable proof the registry is
populated. Do NOT rollback on this alone.

### 4. FORCE RLS still `1.0` across the 7 regulated tables

```bash
source ~/fabt-secrets/.env.prod
curl -sf http://localhost:9091/actuator/prometheus \
    | grep '^fabt_rls_force_rls_enabled{' | sort
```

Phase B invariant. Unchanged from v0.45. **If any table drops to 0.0 →
PANIC per runbook §Phase B Observability, NOT a v0.46 rollback** —
the regression is pre-existing and affects every release.

### 5. `application_name` spot-check

```bash
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT application_name FROM pg_stat_activity WHERE application_name LIKE 'fabt:tenant:%' LIMIT 5"
```

Confirms v0.45's `RlsDataSourceConfig` binding still runs with the new
bean in the DI graph. Expect at least one row formatted
`fabt:tenant:<uuid>` once a request has flowed through the backend.

### 6. `tenant_context_empty` rate unchanged

Re-sample 120s apart; compare to pre-deploy step 3 baseline:

```bash
V1=$(curl -sf http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
sleep 120
V2=$(curl -sf http://localhost:9091/actuator/prometheus \
    | awk '/^fabt_rls_tenant_context_empty_count_total/ {print $2}')
echo "post-deploy rate = $(echo "($V2 - $V1) / 120" | bc -l) /s"
```

Expected: within ±0.5/s of the pre-deploy baseline (~0.83/s single-user
traffic). Sustained >1.5/s for 5m = new bean perturbed Hikari priming =
investigate before bed.

### 7. Playwright post-deploy smoke

```bash
cd ~/finding-a-bed-tonight/e2e/playwright
BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts \
    deploy/post-deploy-smoke.spec.ts \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.46.0.log
```

Expected 12/12 pass in ~40 s. **Zero of the 12 flows route through
`TenantScopedCacheService` in v0.46** — behaviour should be identical
to v0.45. Any failure is NOT a cache-isolation regression; treat as
a standard smoke-failure triage.

---

## 1-hour active watch (downgraded from v0.45's 4h)

Rationale: idle bean, no schema change, no pgaudit change, no Postgres
restart. 4-hour watch is overkill; 1-hour is enough to confirm Hikari
re-borrows settled and the gauge stays stable.

**T+30m check:**
- `curl /actuator/health` → `{"status":"UP"}`
- `grep fabt_cache_registered_cache_names` → still `11.0`

**T+60m check:**
- Same two checks
- Also re-run `fabt_rls_tenant_context_empty_count_total` rate sample
  (2-minute delta); compare to post-deploy step 6.

After 60m, walk away. No v0.46-specific long-tail signal to watch for.

---

## Rollback

### Trigger table

| Observation | Action |
|---|---|
| Version wrong (step 1) | §Rollback — retag + restart |
| `TenantScopedCacheService eager-seeded` INFO log absent (step 2) | §Rollback — `@PostConstruct` failed |
| `fabt.cache.registered_cache_names` gauge absent, everything else green (step 3) | **Do NOT rollback.** Open observability ticket; bean is functional |
| Any FORCE RLS gauge dropped to 0.0 (step 4) | Phase B PANIC script — NOT a v0.46 rollback |
| `application_name` spot-check empty (step 5) | Check pgaudit image + `RlsDataSourceConfig`; v0.45 issue, not v0.46 |
| `tenant_context_empty` sustained > 1.5/s for 5m+ (step 6) | §Rollback — new bean perturbed DI graph |
| ≥ 2 Playwright smoke failures (step 7) | §Rollback — unexpected regression |
| Unexpected Spring context startup failure (any step) | §Rollback immediately |

### §Rollback — atomic, one step

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<VM-IP>
cd ~/finding-a-bed-tonight

# Retag last-good to :latest — no rebuild needed
docker tag fabt-backend:v0.45.0-lastgood fabt-backend:latest

# Restart — same 4-file compose chain
source ~/fabt-secrets/.env.prod
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod --profile observability \
    up -d --force-recreate backend

# Verify rolled back
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.45"}
```

No Flyway rollback. No schema rollback. No pgaudit rollback. No secrets
rollback. The 3 new `AuditEventTypes` constants live only in the v0.46
JAR — rollback removes them from the running process. Zero historical
`audit_events` rows to clean (no caller wrote any of the new action
strings during v0.46 runtime because no call site routes through the
wrapper).

---

## After deploy succeeds

- Announce in ops log; bake ≥ 24h before starting Phase C task 4.4.
- Update `project_live_deployment_status.md` memory to v0.46.0 with
  timestamp, release URL, deploy commit, post-deploy smoke result.
- Leave `fabt-backend:v0.45.0-lastgood` image on disk through the v0.47
  window (typical: retire ~2 releases later).
- Merged commit on main is `b468da3` + the v0.46.0 release-prep commit
  (this runbook + pom + CHANGELOG).

## What the next release (v0.47 series) adds on top

- Task 4.4 — `EscalationPolicyService` split (pre-ArchUnit-rule work)
- Task 4.2 — ArchUnit Family C basic rule
- Task 4.3 — Family C extended scope (`*.api`, `*.security`, `*.auth.*`)
- Task 4.b — **migrate the 7 existing cache call sites** in
  `BedSearchService`, `AvailabilityService`, `AnalyticsService`. This
  is the release where tenant-scoped cache isolation becomes a live
  defence in production.

## Related

- `docs/oracle-update-notes-v0.45.0.md` — precedent runbook; same 4-file
  compose chain, same VM paths, same `pre-v0.45-<ts>.dump` backup
  format
- `docs/oracle-update-notes-v0.44.1-amendments.md` — last-good image
  retag pattern, Slack-less alerting option selection
- `docs/oracle-update-notes-v0.43.1-amendments.md` — Flyway OOO bridge,
  4-file compose chain origin
- `docs/runbook.md` — "Flyway Out-of-Order Posture" + "PostgreSQL
  Minor-Version Bump Checklist"
- `CHANGELOG.md` — `[v0.46.0]` section (preparatory-only framing)
- PR #135 — task 4.1 merge, commit `b468da3`
