# Deploy Runbook — v0.57.2

**Release class:** Backend-only patch. Three small fixes; no schema, env, or frontend changes.
**From:** `v0.57.1` LIVE on `findabed.org` since 2026-05-05 11:33 UTC (verified `curl -s https://findabed.org/api/v1/version` → `{"version":"0.57"}` on 2026-05-10).
**To:** `v0.57.2` — rolls up GH #177 (RESERVATION_PII_PURGED SYSTEM_TENANT bind), the SSE emitter cleanup race fix (#179), and rehearsal hardening (#180).

> **Why this is a thin runbook.** v0.57.2 is the simplest deploy shape we have:
> backend code change only, no Flyway migrations, no env-var additions, no
> frontend bundle change. The rehearsal harness has new gates that catch the
> exact regression class that aborted v0.57.0. Operator workload is roughly:
> bump pom, tag, build, recreate backend + frontend, verify two alerts auto-resolve.
>
> **Rehearsal status (2026-05-10):** Validated end-to-end against the bumped
> pom in two `make rehearse-deploy` runs:
> 1. `/tmp/deploy-rehearsal-20260510-140105/` — initial run; `REHEARSAL PASS`
>    under the original `8.6 / 8.7` gate labels (commit `45bb745` renumbered
>    them to `8.1 / 8.2` AFTER this run).
> 2. `/tmp/deploy-rehearsal-20260510-145518/` — re-run after the pom bump to
>    `0.57.2`; `REHEARSAL PASS` with the renumbered labels firing as
>    `Step 8.1: Version-match assertion` and `Step 8.2: Env-passthrough
>    assertion`. All 10 steps + 13/15 Playwright smoke (2 expected non-prod
>    skips) green.

---

## What's new in v0.57.2 (one-paragraph summary)

`ReferralTokenPurgeService.purgeExpiredHoldAttribution` now binds
`TenantContext.SYSTEM_TENANT_ID` explicitly so its `RESERVATION_PII_PURGED`
audit row writes via the explicit-bind path, not the SYSTEM_TENANT_ID
fallback that fired the `FabtPhaseBSystemTenantFallback` alert on 2026-05-05
12:04 UTC. SSE emitter cleanup is now value-conditional via
`ConcurrentHashMap.remove(key, value)`, fixing a stale-callback race that
intermittently broke `sseCatchupDeliversUnreadNotifications` in CI (and
could have manifested as a real reconnecting user missing catchup delivery
on race-prone hosts). The deploy-rehearsal harness has two new fail-fast
gates — version-match (catches "pom not bumped" → v0.57.0 abort cause #1)
and env-passthrough (catches "var added to .env but not the compose env
block" → v0.57.0 abort cause #2). These are operator-side process gates,
not user-visible behavior.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit docker build + --force-recreate required; three no-op traps documented
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: no cat/grep on .env.prod or alertmanager.yml — structural checks only
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke must use FABT_BASE_URL override against findabed.org
  - file: project_live_deployment_status.md
    # why-cited: current prod state verified 2026-05-10 — v0.57.1 LIVE, Flyway V98, 4-file compose chain
  - file: feedback_release_after_scans.md
    # why-cited: do not tag/publish until all CI green
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: every command in this runbook ground-truthed against VM 2026-05-10
  - file: feedback_no_dismissing_failures.md
    # why-cited: rationale for treating the SSE flake (#179) as a real fix, not a "flaky test"
  - file: feedback_rehearsal_must_test_new_env_vars.md
    # why-cited: motivates §5 + §6 gates (Step 8.1/8.2 added to rehearsal harness)
  - file: project_reservation_pii_purged_system_tenant_bug.md
    # why-cited: full root-cause + fix scope for #178 / GH #177
  - file: project_sse_catchup_severity_order_flake.md
    # why-cited: corrected ground-truth diagnosis for #179 (memory was previously wrong)
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.57.2 — backend-only patch rolling up three fixes for v0.57.x.

**From:** `v0.57.1` LIVE at `findabed.org`. Confirm just before deploy:

```bash
curl -s https://findabed.org/api/v1/version
# expect: {"version":"0.57"}
```

**To:** `v0.57.2`. Backend JAR `0.57.2.jar`. Same major.minor, so `/api/v1/version`
will continue to return `"0.57"` post-deploy — that's correct, not a deploy failure.

**What does NOT change:**

- **Frontend bundle** — no source change; no `npm run build`. Frontend container IS recreated to refresh docker-network coupling (per template §3 rule), but the served bundle hash is unchanged.
- **Postgres / pgaudit** — no restart; no compose change for these services.
- **Flyway schema** — no new migrations. Verified 2026-05-10 against VM: top 3 ranks = V98 platform config / V97 backfill dv policy enabled / V96 seed third reentry shelter east (all `success=t`). §6.3 re-asserts post-deploy.
- **`~/fabt-secrets/` files** — `.env.prod` and the 4 compose override files (`docker-compose.prod.yml` (75 lines), `docker-compose.prod-v0.43-flyway-ooo.yml` (8 lines), `docker-compose.prod-v0.44-pgaudit.yml` (11 lines), `docker-compose.prod-v0.52-oci-anchor.yml` (22 lines)) are untouched. (Filename prefix on the override files is `docker-compose.` — verified against VM 2026-05-10.)
- **FORCE RLS posture** — unchanged.
- **Static content (`/var/www/findabed-docs/`)** — no scp step; no HTML/PNG changes shipped this release.

**What's new — operator-side process improvements (do NOT require deploy action, but operators should know):**

- `scripts/deploy-rehearsal.sh` Step 8.1 (`assert_version_match`) and Step 8.2 (`assert_env_passthrough`) — fail-fast gates that would have caught the v0.57.0 abort. Already validated against this branch on 2026-05-10 (`make rehearse-deploy` → `REHEARSAL PASS`). If a future release adds an env var, the operator will see Step 8.2 fail unless they update both `.env.rehearsal` AND the rehearsal compose env block — which forces them to also update prod's compose chain by symmetry.
- `deploy/rehearsal-prod-overlay.yml` and `deploy/rehearsal.env.example` now include `FABT_PLATFORM_CONTACT_EMAIL` (was already in prod compose since v0.57.1; the rehearsal harness was rubber-stamping its absence).

---

## 3. Service-Recreate Matrix

| Service | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `backend` | Backend JAR rebuilt with v0.57.2 pom version + 2 source-file changes | ☑ | ☑ — `--force-recreate backend` |
| `frontend` | Backend recreate cycles docker-network ARP; without frontend recreate, host nginx serves 502s for ~30s (v0.49 issue #3) | ☐ (no source change) | ☑ — `--force-recreate frontend` (recreate-only, no rebuild) |
| `prometheus` | `prometheus.yml` not edited this release | ☐ | ☐ |
| `alertmanager` | Alertmanager config not edited this release | ☐ | ☐ |
| `postgres` | `pgaudit.conf` not edited this release | ☐ | ☐ |
| Host `nginx` | `/etc/nginx/sites-available/fabt` not edited this release | ☐ | ☐ |

> **Note:** the frontend container is recreated even though no rebuild
> happens. This is per template §3 rule "Any backend image rebuild —
> recreating backend without frontend leaves docker-network stale." The
> existing `fabt-frontend:latest` image is reused; only the container is
> replaced.

> **Recreate order.** A single `docker compose ... up -d --force-recreate
> backend frontend` invocation serializes container start by `depends_on`.
> Verified 2026-05-10 against VM `~/fabt-secrets/docker-compose.prod.yml`
> line 49-50: frontend declares `depends_on: [- backend]` in **list form**,
> meaning compose starts backend before frontend but does NOT wait for
> backend's healthcheck (list-form is start-order-only; healthcheck-wait
> requires the map form with `condition: service_healthy`). The host nginx
> can therefore see frontend up while backend is still warming for ~10–30 s
> after recreate; nginx returns 502 in this window. Step §5.6 then waits for
> backend `/actuator/health = UP`. Worth knowing if a brief 502-burst alarm
> triggers right at recreate.

---

## 4. Pre-Deploy Gates

> Gates are ordered roughly cheapest-first AND fail-late-first — anything that
> can fail after publishing public artifacts (tag, GitHub release) is gated
> by a successful local build first.

**Pre-flight (cheap; run from any context):**

- [ ] **SSH access confirmed** — `ssh -i ~/.ssh/fabt-oracle ubuntu@<VM> 'echo ok'` returns `ok`. Tunnels not required for the deploy itself; share `feedback_no_ssh_tunnels.md` if anything later in the runbook implies otherwise.
- [ ] **CI green on main** — `gh run list --branch main --limit 3` — all three v0.57.2 PRs (#178, #179, #180) merged with green CI; main HEAD `137678a`. (`feedback_release_after_scans.md`)
- [ ] **`backend/pom.xml` bumped to 0.57.2** — `grep -m1 -E '^    <version>' backend/pom.xml` must report `0.57.2` (NOT `0.57.1`). The v0.57.0 abort cause #1 was a missing pom bump; this gate is a runnable assertion, not a checkbox. Commit the bump on main + push BEFORE tagging. The v0.57.1 hotfix used a one-line commit (`5a820b3`) for this; mirror that pattern.

**Local build verification (catches build/version errors before they hit prod):**

- [ ] **mvn clean** — local `mvn -B -DskipTests clean package -q`; then `find backend/target -maxdepth 1 -name '*-0.57.2.jar' -not -name '*.original'` returns exactly 1 file. The `-not -name '*.original'` filter excludes Spring Boot's repackage backup.
- [x] **Rehearsal re-run with the bumped pom** — `REHEARSAL PASS` 2026-05-10, artifacts at `/tmp/deploy-rehearsal-20260510-145518/`. Step 8.1 (Version-match) and Step 8.2 (Env-passthrough) both fired green under their renumbered labels; all 10 steps + 13/15 Playwright smoke (2 expected non-prod skips). (`feedback_rehearsal_must_test_new_env_vars.md`) — Note: the Step 8.1 version-match gate compares major.minor only, so a 0.57.1→0.57.2 bump alone wouldn't trip it; the strict patch-level check happens at the mvn `*-0.57.2.jar` filename gate above. A follow-up enhancement to add full major.minor.patch comparison to Step 8.1 is queued.

**Public-artifact gates (only after local + rehearsal green — these publish):**

- [ ] **Git tag + GitHub release published** — `git tag v0.57.2 && git push origin v0.57.2`, then `gh release create v0.57.2 --generate-notes`. Verify `gh release view v0.57.2`. > **Caution:** if a build problem is discovered AFTER this gate, you have a published tag with no deploy — see `CHANGELOG [v0.57.1]` for the v0.57.0 precedent (the workaround was to skip 0.57.0 and recut as 0.57.1; awkward but recoverable).

**VM-side gates (run from inside the SSH session):**

- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.57.2-$(date -u +%Y%m%d-%H%M%S).dump`. (Postgres container name on prod confirmed 2026-05-10.)

> **Container-name convention** (avoids confusion in §6 + §7 below):
> - **Backend** = `fabt-backend` — `~/fabt-secrets/docker-compose.prod.yml:15` sets `container_name: fabt-backend` explicitly.
> - **Postgres** = `finding-a-bed-tonight-postgres-1` — no override, so compose uses the project-default `<project>-<service>-<index>` form.
> - **Frontend** = `fabt-frontend` — explicit override on prod compose line 47.
>
> Both forms appear later in this runbook for that reason. Both are correct.
- [ ] **Compose dry-render** — `docker compose <FULL_CHAIN> --env-file ~/fabt-secrets/.env.prod config > /tmp/v0.57.2-config.rendered.yml` then diff against any prior `/tmp/v0.57.1-config.rendered.yml` you saved. There should be NO diff except possibly different `image:` tag values if the prior render captured floating tags. (`feedback_runbook_groundtruth_vm.md`)
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` must return NO output. (v0.49 issue #1)
- [ ] **`v0.57.1-lastgood` tag NOT yet present** — `docker images fabt-backend --format '{{.Tag}}' | grep -E '^v0\.57\.1-lastgood$' || echo 'absent — Step 1 will create'`. Expect: `absent — Step 1 will create`. (Verified absent on VM 2026-05-10.) The v0.57.0 abort took the lastgood snapshot at v0.56.0; v0.57.1 went live without a follow-up retag, so v0.57.2 §5.1 must create the missing tag.

---

## 5. Deploy Steps

### 5.1. Preserve last-good image tags

`v0.57.1` shipped without a `-lastgood` tag (post-abort recovery deploy). Create
it now so we have an explicit rollback target distinct from the v0.56.0 lastgood.

**Pre-flight: verify `:latest` is the v0.57.1 build before snapshotting.** A
stray local `docker compose build` could have clobbered `:latest` to point at
something newer or experimental, which would silently snapshot the wrong image
as lastgood:

```bash
# Backend: :latest IMAGE ID should equal v0.57.1's IMAGE ID
docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' fabt-backend \
    | grep -E ':(latest|v0\.57\.1)$'
# Expect: two lines with IDENTICAL IMAGE IDs (one for :latest, one for :v0.57.1).
# If the IDs differ, STOP — :latest has drifted. Investigate before proceeding.

# Frontend: :latest is currently inherited from v0.57.0 (v0.57.1 was backend-only).
# Confirm IMAGE ID against v0.57.0:
docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' fabt-frontend \
    | grep -E ':(latest|v0\.57\.0)$'
# Expect: two lines with IDENTICAL IMAGE IDs.
```

Once the pre-flight passes, snapshot:

```bash
docker tag fabt-backend:v0.57.1 fabt-backend:v0.57.1-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.57.1-lastgood

# Verify both new tags resolve to existing images
docker images | grep -E "fabt-(backend|frontend):v0\.57\.1"
# Expect: fabt-backend:v0.57.1, fabt-backend:v0.57.1-lastgood, fabt-frontend:v0.57.1-lastgood.
# Backend IDs match between v0.57.1 and v0.57.1-lastgood (same image, two tags).
# Frontend lastgood ID equals whatever :latest pointed at when you ran the pre-flight.
```

### 5.2. Checkout the v0.57.2 tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.57.2
git log --oneline -1
# Expect: HEAD is the v0.57.2-tagged commit (detached HEAD is correct).
```

> **Do NOT `git pull origin main`.** The deployed commit must equal the tag for audit traceability.

### 5.3. Verify no migration + no Dockerfile change

```bash
# No new Flyway migrations between v0.57.1 and v0.57.2:
git diff v0.57.1..v0.57.2 -- backend/src/main/resources/db/migration/ \
  | head -5
# expect: empty output (no migrations changed)

# No Dockerfile change (the build will produce the same image shape):
git diff v0.57.1..v0.57.2 -- infra/docker/Dockerfile.backend
# expect: empty output (or only comment-line drift)

# No frontend source change:
git diff v0.57.1..v0.57.2 -- frontend/src/
# expect: empty output (frontend untouched in v0.57.2)
```

### 5.4. Backend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight
mvn -B -DskipTests clean package -q
ls -la backend/target/*.jar
# expect: exactly one *-0.57.2.jar — pom.xml is at 0.57.2 on this tag.
# This guards against the v0.57.0 abort cause #1 (pom not bumped). The
# rehearsal harness Step 8.1 also asserts this against /api/v1/version
# post-deploy, but verify here at build time too.

docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.57.2 \
    -t fabt-backend:latest \
    .
docker images | grep fabt-backend:v0.57.2
# Expect: image present, recent CREATED time
```

### 5.5. Recreate backend + frontend (full compose chain)

```bash
cd ~/finding-a-bed-tonight
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    up -d --force-recreate backend frontend
```

> **Why all 4 override files.** Per `project_live_deployment_status.md` and
> verified on VM 2026-05-10, the prod stack is a 4-file compose chain.
> Omitting `prod-v0.44-pgaudit.yml` recreates `postgres` with stock
> `postgres:16-alpine` (no pgaudit config dir) → crash loop → cascading
> backend failure. (v0.50 lesson.)
>
> **Why `--profile alerting`.** The alertmanager service is gated behind
> the `alerting` profile in `docker-compose.yml`. Compose v2+ (verified
> 2026-05-10: prod runs `Docker Compose v5.1.3`) only schedules a profiled
> service when its profile is explicitly active during `up`. Omitting the
> flag would make compose treat alertmanager as not-in-the-current-set —
> on a `--force-recreate`, that's a stop signal for any already-running
> alertmanager container.

### 5.6. Wait for backend readiness

```bash
# Use internal management port — NOT the public URL (returns 404; v0.49 issue #4)
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend..."; sleep 3
done
echo "backend is UP"
```

---

## 6. Post-Deploy Gates

### 6.1. Mandatory smoke gate

```bash
cd ~/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.57.2.log
# Expect against findabed.org: 15/15 passed, 0 skipped.
# Tests 4 + 12 use `test.skip(!/findabed\.org/i.test(BASE), ...)` — they SKIP
# only on non-prod targets (rehearsal/local), so on prod they RUN and verify
# the demo_restricted policy is active. The 13/15 result you see in
# rehearsal logs is the local-target shape, not the prod-target shape.
```

### 6.2. Version check

Public endpoint returns major.minor only (unchanged across patch releases):

```bash
curl -s https://findabed.org/api/v1/version
# Expect: {"version":"0.57"} — UNCHANGED from v0.57.1 because major.minor
# is the same. This is correct, NOT a deploy failure.
```

For patch-level confirmation, the management actuator's `/actuator/info`
endpoint exposes Spring Boot `BuildProperties` including the full pom version.
Verified 2026-05-10 against prod: `{"build":{...,"version":"0.57.1",...}}`.
Probe via SSH on the VM — port 9091 is internal-only, no auth required:

```bash
ssh ubuntu@<VM>
curl -sf http://localhost:9091/actuator/info | jq -r '.build.version'
# Expect: 0.57.2
```

If this returns `0.57.1`, the new JAR did not replace `:latest` — re-check
§5.4 build output and image-tag wiring.

### 6.3. Flyway HWM (UNCHANGED)

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# expect: top row = V98 platform config, success=t
# (same as v0.57.1; no migrations applied)
```

### 6.4. SYSTEM_TENANT alert auto-resolves (GH #177 fix)

The `FabtPhaseBSystemTenantFallback` alert was firing on `action=RESERVATION_PII_PURGED`
since 2026-05-05 12:04 UTC. After v0.57.2 lands, the next scheduled
`purgeExpiredHoldAttribution` invocation (every 15 min — `@Scheduled(fixedDelay=900_000)`
at `backend/src/main/java/org/fabt/referral/service/ReferralTokenPurgeService.java:109`)
will write its audit row via the explicit `SYSTEM_TENANT_ID` bind, not the
fallback path. The alert rule (`deploy/prometheus/phase-b-rls.rules.yml:31` —
`expr: rate(fabt_audit_system_insert_count_total[15m]) > 0`, `for: 15m`)
sees the counter flatline and Alertmanager auto-resolves it.

**Expected timeline:** up to **~15 min** for next purge invocation in the
worst case (immediately after a prior purge fired pre-deploy, the next one
waits the full `fixedDelay`) + **~15 min** for the alert's `for:` clause to
clear once the counter flatlines + **~5 min** Alertmanager `resolve_timeout`
(default 5m, runbook hasn't customized it) = **up to ~35 min worst-case**
after backend recreate. Don't panic before that window closes.

**Two probes, ordered fastest-signal-first:**

**Probe A — Alertmanager UI (preferred).** Reach the Alertmanager web UI
(operator-only access path; reverse SSH tunnel per `feedback_platform_login_via_ssh_tunnel.md`
if not already exposed) and confirm `FabtPhaseBSystemTenantFallback` for
`action=RESERVATION_PII_PURGED` transitions to `resolved`. This is the
canonical signal — if the alert resolves, the fix landed.

**Probe B — Backend WARN-log grep (fallback if Alertmanager UI is unreachable).**
The fix changes HOW the row is bound (explicit vs fallback); both paths land the
row under `tenant_id = '00000000-0000-0000-0000-000000000001'`. So pure-row
counts won't show a difference. Detect the bind PATH by reading the
`AuditEventService` WARN log (rate-limited per JVM via `DuplicateMessageFilter`
but emitted on fallback only — see `backend/src/main/java/org/fabt/shared/audit/AuditEventService.java:139`):

```bash
# Wait ≥30 min after deploy (covers ≥1 purge cycle on the 15-min schedule).
# Pre-fix: every purge invocation logs WARN "Audit event published without
#          TenantContext bound: action=RESERVATION_PII_PURGED..."
# Post-fix: zero of those warnings; only the existing INFO/DEBUG purge logs.

# 1. Positive control — confirm the scheduler actually fired during the window
#    (a zero count below is meaningless if the scheduler hasn't run yet):
docker logs --since 30m fabt-backend 2>&1 \
  | grep -cE 'purgeExpiredHoldAttribution: purged='
# Expect: ≥1 (one log line per scheduled invocation)

# 2. Negative assertion — the fallback WARN should be gone:
docker logs --since 30m fabt-backend 2>&1 \
  | grep -cE 'TenantContext bound: action=RESERVATION_PII_PURGED'
# Expect: 0
```

If the alert keeps firing after the ~35 min window, OR Probe B shows positive
control ≥1 AND fallback count > 0, the fix did not deploy correctly. Re-check
the JAR version (§6.2) and re-run §5.5 with explicit `fabt-backend:v0.57.2`
instead of `:latest`. The fallback row continues to write successfully —
this is observability only, not data loss.

### 6.5. SSE catchup race no longer flakes (#179 fix)

There is no in-prod observable for the SSE emitter cleanup race; it manifested
as a CI flake. After deploy, watch the next 3 main-branch CI runs for any
recurrence of `sseCatchupDeliversUnreadNotifications` failure. Expected: zero.
The deterministic regression test (`SseStabilityTest#test_staleCleanupCallback_doesNotEvictNewerEmitter`)
on the v0.57.2 build locks in the contract.

### 6.6. Tree-shake — old image cleanup (deferred)

Hold off on `docker image prune -a` until v0.57.2 has been live for ≥24h.
The `v0.57.1-lastgood` tag (created in §5.1) prevents accidental prune.

---

## 7. Rollback Matrix

| Symptom | Action |
|---|---|
| Backend won't start (Flyway validate / startup error) | `docker tag fabt-backend:v0.57.1-lastgood fabt-backend:latest` then re-run §5.5 with `--force-recreate backend frontend`. No DB rollback needed (no migrations). |
| Frontend serving stale JS after deploy | Old service worker cached. Use incognito or clear site data. (`feedback_stale_sw_on_deploy.md`) Cloudflare cache should not be impacted (frontend bundle hash unchanged). |
| Host nginx 502 after backend recreate | Frontend docker-network is stale even though we recreated it. Re-run `docker compose ... up -d --force-recreate frontend`. |
| `FabtPhaseBSystemTenantFallback` alert KEEPS firing post-deploy | The fix didn't land. Verify backend image: `docker exec fabt-backend cat /app/META-INF/build-info.properties` should show `0.57.2`. If it shows `0.57.1`, §5.4 didn't refresh `:latest`. |
| New SYSTEM-tenant fallback alert on a DIFFERENT action | Different bug — not related to #178. Triage via `docs/security/phase-b-silent-audit-write-failures-runbook.md`. |
| Full rollback needed | Retag + recreate — see code block below this table. The DB state at V98 is forward-compatible with v0.57.1 backend (no migrations applied this release). |

**Full rollback command** (referenced from the matrix row above):

```bash
docker tag fabt-backend:v0.57.1-lastgood fabt-backend:latest
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    up -d --force-recreate backend frontend
```

---

## 8. Post-Deploy Housekeeping

- [ ] Update `project_live_deployment_status.md` memory — version → v0.57.2, container list refreshed, lessons noted (rehearsal-harness gates fired their first prod-relevant validation pre-tag)
- [ ] Move CHANGELOG.md `[v0.57.2]` heading from "(planned)" to "(shipped YYYY-MM-DD)" and tighten any "TBD" lines
- [ ] Verify on Alertmanager UI that `FabtPhaseBSystemTenantFallback` is in resolved state by 30 min post-deploy (per §6.4)
- [ ] Per `feedback_periodic_resume_save.md`: update `project_resume_point.md` — current state = v0.57.2 LIVE, queue cleared of #177 / SSE flake / rehearsal hardening
- [ ] Archive any spent OpenSpec changes (`/opsx:archive`) — none expected; info-email-contact is in 7-day hygiene window which closes 2026-05-12
- [ ] Tree-shake old image tags ≥24h after deploy: `docker image prune -a --filter "until=24h"` (preserves v0.57.1-lastgood by tag)

---

## Related changes

- **PR #178** (commit `e032230` → merge `c50c5b9`): GH #177 fix
- **PR #179** (commit `cc0596d` → merge `710ce25`): SSE emitter cleanup race
- **PR #180** (commits `36c97dd`, `45bb745` → merge `137678a`): rehearsal hardening
- Companion runbook: `docs/security/phase-b-silent-audit-write-failures-runbook.md` — referenced for §6.4 triage logic

---

*Runbook authored 2026-05-10 against ground-truthed VM state. Drafted from
`docs/runbook-template.md` v1; closest precedent in shape is
`oracle-update-notes-v0.53.0.md` (the prior backend-only release —
"Frontend rebuild **not required** ... v0.53.0 is backend-only"). Pre-tag
sanity: `make rehearse-deploy` green on this branch with the new gates
firing under their original `8.6 / 8.7` labels (commit `45bb745` renumbered
to `8.1 / 8.2` after that run; pre-tag re-run validates the renumbered
labels and the version-match gate against the bumped pom).*
