# Oracle Deploy Notes — v0.56.0 (dv-policy-tenant-flag + platform-observability-split)

**Status:** draft pending CI green + tag.
**Template version:** v1 (per `docs/runbook-template.md`).
**OpenSpec changes:** `dv-policy-tenant-flag` + `platform-observability-split` (both in the docs repo).

---

## What's new in v0.56.0 (one-paragraph summary)

v0.56 ships two openspec changes together: `dv-policy-tenant-flag`
introduces a tenant-scoped acknowledgement (`tenant.config.dv_policy_enabled`)
that gates per-shelter `dv_shelter=true` writes via a `ShelterService`
invariant, with a dedicated `PATCH /api/v1/admin/tenants/{id}/dv-policy`
COC_ADMIN endpoint, an admin Settings panel with extra-confirm modal, and
a forward-only V97 backfill that flips the flag on tenants that already
operate DV shelters. `platform-observability-split` splits the broken-
since-G-4.4 `/admin#observability` tab into three pieces: a new V98
`platform_config` singleton table for the 6 tenant-agnostic fields
(prometheus, tracing, three monitor cadences) flipped via `PUT
/api/v1/platform/observability` (PLATFORM_OPERATOR + `@PlatformAdminOnly`)
on the Platform Operator Dashboard, the surviving tenant-specific
temperature threshold moved to `/admin#surge` with a new
`PUT /api/v1/tenants/{id}/surge-threshold` COC_ADMIN endpoint, and the
`OperationalMonitorService` rewired from literal `@Scheduled` rates to a
`SchedulingConfigurer` + `TaskScheduler` + dynamic `ScheduledFuture`
pattern so platform-operator interval changes take effect on the next
reschedule cycle without a restart. The deploy is **single-stage** with
a backend rebuild + frontend rebuild and two new Flyway migrations
(V97, V98). Backend full mvn test 1538/1538, Vitest 228/228, Playwright
32/32. The originally-planned `info-email-contact` + GH #67 items did
NOT make this bundle and are deferred.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: every container name, JAR path, port, and image tag in
    # this runbook ground-truthed against project_live_deployment_status
    # (current state v0.55.1, Flyway V96, fabt-pgaudit:v0.45.0,
    # 5-file compose chain). v0.56 introduces no new compose override.
  - file: feedback_runbook_compose_chain.md
    # why-cited: prod uses a 5-FILE compose chain (UNCHANGED from v0.55).
    # Missing any one file breaks deploy (postgres crash loop on v0.50).
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit `docker build --no-cache` BEFORE
    # `compose up --force-recreate` for both backend + frontend.
  - file: feedback_deploy_old_jars.md
    # why-cited: `mvn clean` mandatory before backend `docker build` —
    # old JARs in `backend/target/` cause Docker to embed stale bytecode.
  - file: feedback_deploy_checklist_v031.md
    # why-cited: clean build, single-JAR verify, no-cache image, actuator
    # on 9091, post-deploy class verification.
  - file: feedback_runbook_template_v1.md
    # why-cited: this runbook follows the v1 template structure.
  - file: feedback_release_after_scans.md
    # why-cited: tag + GitHub release published only after CI scans green
    # (CodeQL + npm audit + dependency-check + E2E + Performance).
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke uses FABT_BASE_URL +
    # `--config=deploy/playwright.config.ts` + `post-deploy-smoke`
    # positional. The smoke spec defaults to localhost without that
    # override.
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: never cat/grep `~/fabt-secrets/.env.prod` or any
    # rendered alertmanager.yml during deploy. Use structural checks
    # only.
  - file: feedback_no_ssh_tunnels.md
    # why-cited: this runbook shares SSH commands the operator runs.
    # The platform-operator login (now needed to drive the new
    # observability cards on the Platform Operator Dashboard) requires
    # an SSH tunnel; details surfaced in chat at deploy time per
    # `feedback_platform_login_via_ssh_tunnel.md`, NOT committed here.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: no compose-file edit in v0.56 and no new files in
    # mounted config directories, so prometheus does NOT need
    # force-recreate this release.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: every command path, container name, and image tag below
    # ground-truthed against the V97 + V98 migration files, the
    # PlatformObservabilityController + DvPolicyController source, and
    # the live VM state from project_live_deployment_status.md.
  - file: feedback_stale_sw_on_deploy.md
    # why-cited: post-deploy frontend testing must be incognito or
    # site-data-cleared — old service worker will serve cached JS.
  - file: feedback_periodic_resume_save.md
    # why-cited: post-deploy memory updates per §8.
  - file: feedback_platform_login_via_ssh_tunnel.md
    # why-cited: v0.56 SURFACES new platform-operator paths — the
    # observability inline-edit cards on the Platform Operator
    # Dashboard. The SSH tunnel command for hitting the platform UI in
    # prod is shared in chat at deploy time, NEVER committed here
    # (op-sec).
  - file: project_live_deployment_status.md
    # why-cited: ground-truth source. v0.55.1 currently live; Flyway HWM
    # V96; 5-file compose chain; `fabt-pgaudit:v0.45.0` postgres image;
    # JAR at `/app/app.jar`; container_name conventions (fabt-backend,
    # fabt-frontend, finding-a-bed-tonight-postgres-1).
  - file: project_dv_policy_tenant_flag_decisions.md
    # why-cited: 2026-05-01 warroom decisions for dv-policy-tenant-flag
    # (COC_ADMIN + extra-confirm; "no DV shelters without flag"
    # invariant; disable-path forbidden while DV shelters exist).
  - file: feedback_truthfulness_above_all.md
    # why-cited: AI-synthetic Spanish review disclosure for the 4 new
    # platform-obs Spanish keys (admin.observability.threshold*,
    # confirm*) — same convention as the v0.55.1 D2 disclosure.
  - file: feedback_persona_transparency.md
    # why-cited: marker text in commits + this runbook never says
    # "Maria-reviewed" — always "AI-synthetic-linguistic-review".
  - file: reference_es_json_ai_synthetic_reviewed.md
    # why-cited: AI-synthetic Spanish review pointer + disclosure
    # conventions used here for the v0.56 platform-obs key additions.
  - file: feedback_legal_scan_in_comments.md
    # why-cited: legal-language scan applies to JSDoc + JavaDoc, not
    # just user-facing copy. Already cleared by the round-7 `2d337b2`
    # commit on the platform-obs feature branch (4 overclaim words + 1
    # impossibility phrase rephrased to "follows the W3C ARIA APG
    # Switch Pattern" / "could not").
  - file: feedback_check_ports_before_assuming.md
    # why-cited: post-deploy frontend smoke uses port 8081 (nginx) in
    # local rehearsal and the public URL via Cloudflare for prod smoke.
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.56.0 — `dv-policy-tenant-flag` + `platform-observability-split` openspec changes bundled.

**From:** `v0.55.1` live at `findabed.org` (frontend bundle on top of v0.55.0 backend JAR). Confirm current via:

```bash
curl -s https://findabed.org/api/v1/version
# Expected pre-deploy: {"version":"0.55"}
# (VersionController strips the patch version — v0.55.0 backend JAR
# under v0.55.1 frontend reports "0.55", and v0.56.0 will report "0.56".)
```

**To:** `v0.56.0` — backend JAR `0.55.0 → 0.56.0` (rebuilt; pom.xml bumped), Flyway HWM `V96 → V98`, frontend bundle gains the dv-policy admin panel + the new SurgeTemperatureSettings + the 6 inline-edit Observability action cards on the Platform Operator Dashboard.

**Migrations in this deploy:**

- **V97** — `V97__backfill_dv_policy_enabled.sql`. Backfills `tenant.config.dv_policy_enabled = true` on every tenant that already has at least one shelter with `dv_shelter = true` at migration time. Idempotent. Tenants with zero DV shelters are not modified (helper defaults to `false` on absent key). Performance: uses the existing `shelter(tenant_id)` index; fast at 10K-shelter scale (Sam's review, warroom 2026-05-02).
- **V98** — `V98__platform_config.sql`. Creates the `platform_config` singleton table (one canonical UUID, enforced by CHECK constraint). Seeds the initial row with defaults that match the prior `@Scheduled` literal cadences (5/15/60 minutes for stale/dv-canary/temperature) plus `prometheus_enabled=true`, `tracing_enabled=false`, `tracing_endpoint='http://localhost:4318/v1/traces'`. Behavior is identical immediately after migration; operators flip via the new platform endpoint.

**New endpoints:**

- `PATCH /api/v1/admin/tenants/{tenantId}/dv-policy` — COC_ADMIN, requires `dvAccess=true` JWT claim. Tenant-scoping check fires before any DV-shelter inventory query so cross-tenant probes do not leak inventory state via timing or response data. Disable path (`true → false`) forbidden while active DV shelters exist; rejection emits a `TENANT_CONFIG_UPDATED` audit row with `outcome: "rejected"`. Cross-tenant probe also emits `rejection_code: "tenant.crossTenantAccess"`.
- `GET /api/v1/platform/observability` — PLATFORM_OPERATOR + `@PlatformAdminOnly`. Returns the 6 platform-wide config fields.
- `PUT /api/v1/platform/observability` — same role + `X-Platform-Justification` header. Partial-merge update with `SELECT … FOR UPDATE` row lock. Per-field audit emission (`PLATFORM_OBSERVABILITY_UPDATED` AuditEventType). Triggers `OperationalMonitorService.rescheduleFromConfig()` on any monitor-interval change.
- `PUT /api/v1/tenants/{id}/surge-threshold` — COC_ADMIN, no platform-justification header. Replaces the broken-since-G-4.4 `/observability` PUT for the temperature threshold. Validation `-50 ≤ threshold ≤ 150`; `tenant.surgeThreshold.outOfRange` error code. Emits `TENANT_CONFIG_UPDATED`.

**Removed endpoints:** Both `GET` and `PUT /api/v1/tenants/{id}/observability` are removed entirely from `TenantController`. The 6 platform-wide fields move to the platform endpoint; the 1 tenant-specific field (the temperature threshold) moves to a NEW endpoint pair on `TemperatureThresholdController`:

- `GET /api/v1/tenants/{id}/surge-threshold` — COC_ADMIN, returns `{"temperature_threshold_f": <number>}`. Defaults to `32.0` if the tenant has never set a threshold.
- `PUT /api/v1/tenants/{id}/surge-threshold` — COC_ADMIN, accepts `{"temperature_threshold_f": <number>}` in `[-50, 150]`, returns `200` with the same body shape, emits `TENANT_CONFIG_UPDATED` audit row.

The `SurgeTemperatureSettings` admin panel reads via the new GET, NOT the removed `/observability` GET. Per-tenant backward-read of the OLD `tenant.config.observability` JSONB sub-map (where the threshold is still stored) is preserved by the read-modify-write pattern in the new controller (design D3) — the JSONB keys stay readable so a partial v0.55-back rollback is safe.

**New AuditEventType enum value:** `PLATFORM_OBSERVABILITY_UPDATED` (one row per changed field with `field`, `old_value`, `new_value`, `value_changed`, `outcome=applied` payload).

**Frontend bug fix bundled:** `ApiError` class now captures the backend `context` field (was reading `details` previously, but the backend always sent `context`; the field-name mismatch silently dropped structured error payloads for ALL 400 responses across the codebase, not just the new dv-policy / platform-obs endpoints). This is a code-hygiene side-effect of the dv-policy work; behavior change is "structured error UX appears where it was supposed to."

**Accessibility change bundled:** PlatformActionCard switched from native `disabled` attribute to `aria-disabled="true"` + click guard (W3C ARIA APG keyboard-discoverability). Existing Playwright assertions migrated from `toBeDisabled()` to `toHaveAttribute('aria-disabled', 'true')`.

**What does NOT change in this deploy:**

- Tenant JWT issuer + claims, FORCE RLS posture, Postgres / pgaudit (`fabt-pgaudit:v0.45.0` continues unchanged).
- Compose file chain — same 5 files; no new override.
- Prometheus rule files + alertmanager templates — unchanged.
- Container names — unchanged from v0.55.
- Reentry-mode UI (V91-V96 surface) — no change.
- Public demo HTML / PNG screenshots — no v0.56-specific surface needs new captures (operator dashboard is not on the public demo site; admin temperature panel is a cosmetic move only).

**Out of scope (deferred):**

- `info-email-contact` Slice B+ — not yet implemented (only proposal/design phase).
- GH #67 (in-app issue reporting) — depends on info-email-contact.
- Drop of the obsoleted per-tenant `tenant.config.observability.{prometheus_enabled, tracing_enabled, tracing_endpoint, monitor_*_interval_minutes}` JSONB keys — kept for backward-read per design D3; v0.58+ Flyway migration drops them after one release cycle of observed-zero-reads in prod.
- Cross-authenticator MFA QA matrix completion (still tracked at `docs/operations/platform-operator-mfa-compatibility.md`).

**Operator-comms one-liner** (paste into the post-deploy note to the 3 demo CoC admins so they aren't surprised by missing tabs):

> v0.56 reorganizes the admin tabs. The **Observability** tab is gone — its
> one CoC-tunable setting (the temperature threshold for surge activation
> recommendations) has moved to **Admin → Surge** as a new
> "Surge temperature settings" panel. The other 5 settings on the old tab
> (Prometheus toggle, OTel tracing toggle + endpoint, monitor cadences for
> stale-shelter / DV-canary / temperature) were always platform-operator
> concerns; they now live exclusively on the Platform Operator Dashboard
> (platform login required). No CoC-side action is needed for v0.56 —
> the V97 backfill and V98 seed both apply automatically at backend startup.

---

## 3. Service-Recreate Matrix

| Service (prod container_name) | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `fabt-backend` | New JAR (0.56.0) + new V97-V98 migrations + new endpoints | Yes | Yes |
| `fabt-frontend` | New bundle (DvPolicySettings + SurgeTemperatureSettings + ObservabilityActionCard + ApiError fix) | Yes | Yes |
| `finding-a-bed-tonight-postgres-1` | `fabt-pgaudit:v0.45.0` image unchanged; pgaudit.conf unchanged | No | No |
| `finding-a-bed-tonight-prometheus-1` | No new rules file in `deploy/prometheus/`; no compose-file edit; no inode change | No | No |
| `finding-a-bed-tonight-alertmanager-1` | No new template; no rendered-secret change | No | No |
| `finding-a-bed-tonight-{grafana,jaeger,otel-collector}-1` | No dashboard / collector / image change | No | No |
| Host `nginx` | No `/etc/nginx/sites-available/fabt` edit | No | No |

> **Container-name rule:** `fabt-backend` and `fabt-frontend` carry custom `container_name:` directives in `~/fabt-secrets/docker-compose.prod.yml` (out-of-repo). Postgres and the observability stack use default `<project>-<service>-<replica>` naming. Verified via `docker ps` on the live VM 2026-04-30 (post-v0.55.0 deploy, unchanged in v0.55.1).

---

## 4. Pre-Deploy Gates

- [ ] **pom.xml version bumped** — `cd backend && grep -nE "<version>0\." pom.xml | head -1` should report **0.56.0** at line 16. If still 0.55.0, edit `backend/pom.xml` line 16, commit on the release branch, and re-run `mvn -B -DskipTests clean package -q` to confirm the JAR filename is now `*0.56.0*.jar`. The Spring Boot Maven plugin writes the version into `META-INF/build-info.properties` at package time, which is what `/api/v1/version` reads at runtime.
- [ ] **Backend tests green** — `cd backend && mvn -B test -q` — expect 1538/1538 passing locally (1414 pre-v0.56 + ~124 new tests across `PlatformObservabilityControllerTest`, `TemperatureThresholdControllerTest`, `PlatformConfigServiceIntegrationTest`, `V97MigrationIntegrationTest`, `DvPolicyControllerTest`, `ShelterServiceDvPolicyInvariantTest`, `TenantPathGuardIntegrationTest`).
- [ ] **Frontend tests green** — `cd frontend && npm run test:run` — expect 228/228 Vitest (10 new `ObservabilityActionCard.test.ts` + 8 new `SurgeTemperatureSettings.test.ts` covering parseObservabilityError + parseTemperatureError).
- [ ] **v0.56-relevant Playwright specs green locally** — `cd e2e/playwright && BASE_URL=http://localhost:8081 npx playwright test platform-dashboard-inline-flows.spec.ts platform-ui-dashboard.spec.ts platform-training-walkthrough.spec.ts observability.spec.ts --reporter=list` — expect 32/32 across these 4 specs (16 dashboard/inline-edit + 16 regression). Run through nginx, NOT bare Vite (per `feedback_check_ports_before_assuming.md`). The full nginx-targeted suite is exercised by main-branch CI before tagging; this gate just confirms the v0.56-touched specs locally.
- [ ] **Pre-deploy DV-policy backfill scope query** (run on prod DB before deploy):

  ```bash
  docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT t.slug,
         COUNT(s.id) FILTER (WHERE s.dv_shelter = true) AS dv_shelter_count,
         (t.config -> 'dv_policy_enabled')::text AS current_dv_policy_value
  FROM tenant t
  LEFT JOIN shelter s ON s.tenant_id = t.id
  GROUP BY t.id, t.slug, t.config
  ORDER BY t.slug;"
  ```

  Use the `fabt` owner role (per `feedback_rls_hides_dv_data` — `fabt_app` would have RLS hide DV shelters from the count). Expected: each of the 3 prod tenants (`dev-coc`, `blueridge`, `mountain` per `project_live_demo_seed_inventory`) shows ≥1 DV shelter and `current_dv_policy_value = NULL` (V97 will set it to `true` for each).

- [ ] **Pre-deploy platform_config check** — confirm the singleton table does not pre-exist:

  ```bash
  docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
      "SELECT 1 FROM information_schema.tables WHERE table_name='platform_config';"
  # Expected pre-deploy: empty (V98 has not run yet).
  ```

- [ ] **CI green** — `gh run list --branch main --limit 5` — all runs green (CodeQL, CI, E2E, Performance, DV Access Control Canary). Already verified `2026-05-03T22:20:45Z` run 25292474449 — RUN_DONE: success.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` returns NO output. (v0.49 issue #1.)
- [ ] **Container UID vs perms** — no new bind-mounted files in this deploy; `git checkout v0.56.0` does not introduce new bind-mount paths.
- [ ] **Compose dry-render (5-file chain)** — see Section 5 for the canonical chain. Run:

  ```bash
  COMPOSE_CHAIN=(
      -f docker-compose.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
  )
  docker compose "${COMPOSE_CHAIN[@]}" --env-file ~/fabt-secrets/.env.prod \
      config > /tmp/v0.56.0-config.rendered.yml
  ```

  **Expected delta from compose-config: ZERO.** No compose file changed in v0.56; the only filesystem changes are inside the backend JAR and the frontend bundle. A non-empty diff against the v0.55 render means a compose file changed unexpectedly.

- [ ] **OCI key path mounted on the VM** — backend env has `FABT_OCI_AUDIT_ANCHOR_PRIVATE_KEY_PATH=/etc/fabt/oci/audit-anchor.pem`. Confirm via `docker inspect fabt-backend | grep -i oci_audit_anchor`.
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.56.0-$(date -u +%Y%m%d-%H%M%S).dump`. Restore via `pg_restore`, NOT `psql`.
- [ ] **Git tag + GitHub release published** — `git tag v0.56.0 && git push origin v0.56.0`, then `gh release create v0.56.0 --generate-notes`. Verify with `gh release view v0.56.0`. The deploy MUST checkout the tag, not main HEAD.
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting (`ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}`).
- [x] **Local rehearsal PASS** — `make rehearse-deploy` ran 2026-05-03 18:55:56 UTC against `release/v0.56.0` HEAD `159e7df`. PASS — 15/15 smoke tests + 10/10 rehearsal steps green. Artifacts preserved at `/tmp/deploy-rehearsal-20260503-185556` (rehearsal.log + smoke trace + Playwright HTML report). Re-run is required if tagging slips beyond 72h per `deploy/release-gate-pins.txt`. **First attempt 18:53:09 UTC failed at Step 7 with port 5432 collision because dev-start.sh postgres was up on the host; standard `./dev-start.sh stop` before retry, restart after.**

---

## 5. Deploy Steps

> **Canonical 5-file compose chain** (per memory `project_live_deployment_status.md`, ground-truthed 2026-04-30 via `docker compose ls`). EVERY `docker compose ... up -d` invocation MUST include all five `-f` flags. Missing any one breaks deploy (per `feedback_runbook_compose_chain.md`):
>
> ```bash
> # Define once at the top of the operator session.
> COMPOSE_CHAIN=(
>     -f docker-compose.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
> )
> # Use as `docker compose "${COMPOSE_CHAIN[@]}" ...`
> ```

> **No static-content scp this release.** v0.56 ships no demo HTML or PNG churn — the new dv-policy and platform-obs surfaces are operator-facing only and do not appear on the public demo site. Skip the `/var/www/findabed-docs/` deploy step entirely.

### 1. Preserve last-good image tags

```bash
docker tag fabt-backend:latest fabt-backend:v0.55.0-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.55.1-lastgood
docker images | grep -E "fabt-(backend|frontend)"
# Expected: latest + v0.55.x-lastgood both present (same IMAGE ID, two tags).
# Note: backend lastgood is v0.55.0 (last backend-rebuild release);
# frontend lastgood is v0.55.1 (last frontend-rebuild release).
# These are the rollback-target images for §7.
```

### 2. Checkout the release tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.56.0
git log --oneline -1
# Expected: HEAD on the v0.56.0 tagged commit (detached HEAD is correct).
```

> **Do NOT `git pull origin main`.** Deployed commit must equal the tag for audit traceability.

### 3. Verify Dockerfile + migration paths landed

```bash
ls -1 infra/docker/Dockerfile.backend infra/docker/Dockerfile.frontend
# Expected: both present.

ls -1 backend/src/main/resources/db/migration/V9{7,8}__*.sql
# Expected:
#   V97__backfill_dv_policy_enabled.sql
#   V98__platform_config.sql
```

### 4. Backend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight/backend
mvn -B -DskipTests clean package -q
ls -1 target/*.jar | head -3
# Expected: exactly one *0.56.0*.jar (fat JAR) plus the .original side-car.
# More than one fat JAR means `mvn clean` did not run — abort and re-run.

cd ~/finding-a-bed-tonight
docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.56.0 \
    -t fabt-backend:latest .
```

### 5. Frontend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight/frontend
npm ci
npm run build
cd ~/finding-a-bed-tonight

docker build --no-cache \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.56.0 \
    -t fabt-frontend:latest .
```

### 6. Bring up backend + frontend

```bash
docker compose "${COMPOSE_CHAIN[@]}" \
    --env-file ~/fabt-secrets/.env.prod \
    up -d --force-recreate backend frontend
# Postgres + observability stack stay up — they are NOT recreated.
# `--force-recreate` is required for backend (new JAR) and frontend
# (new bundle); per `feedback_prod_docker_build_pattern.md` simply
# rebuilding without recreate leaves the old container running.
```

### 7. Wait for backend readiness + Flyway forward-progress

```bash
# Internal management port — actuator binds to 9091 localhost-only.
TIMEOUT=120
for i in $(seq 1 $((TIMEOUT/3))); do
    if curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        echo "backend is UP after ${i}x3s"; break
    fi
    echo "waiting for backend ($i)..."; sleep 3
done

# If timeout: dump container logs and ABORT to §7 rollback.
# docker logs fabt-backend --tail 200

docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
# Expected top 2 rows in descending order:
#   V98 | platform config                    | t
#   V97 | backfill dv policy enabled         | t
# (Description text comes from the migration filename underscore-split.)
```

> **If the backend does not become UP within 120s**, stop here. Pull `docker logs fabt-backend --tail 200`, look for Flyway errors first. Go to §7 Rollback Matrix.

---

## 6. Post-Deploy Gates

### Mandatory smoke gate

```bash
cd ~/finding-a-bed-tonight/e2e/playwright && \
  FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.56.0.log
```

> **Do NOT skip this gate.** Smoke is the only test that exercises the full Cloudflare → host nginx → frontend → backend chain. `--retries=1` defends against the rate-limit flake on test 13/14 per `feedback_smoke_config_on_prod.md`.

Expected: 13/15 GREEN, 2 SKIPPED (forgot-password demo branch + 211-import-edit; both dev-only).

### Version check

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.56"}
# NOT "0.56.0" — VersionController strips the patch.
```

### Flyway HWM

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version FROM flyway_schema_history
     WHERE success = true
     ORDER BY installed_rank DESC LIMIT 1;"
# Expected: 98
# Note: ORDER BY installed_rank (not version::int) — versions like
# V8_1 exist in the migration history and would break the int cast.
# installed_rank is the monotonic application order Flyway maintains.
```

### V97 — DV-policy backfill verification

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT t.slug,
       COUNT(s.id) FILTER (WHERE s.dv_shelter = true) AS dv_shelter_count,
       (t.config -> 'dv_policy_enabled')::text AS dv_policy_value
FROM tenant t
LEFT JOIN shelter s ON s.tenant_id = t.id
GROUP BY t.id, t.slug, t.config
ORDER BY t.slug;"
# Expected: every prod tenant with dv_shelter_count >= 1 now shows
# dv_policy_value = 'true'. Tenants with dv_shelter_count = 0 (if any)
# show dv_policy_value = NULL (helper defaults to false on absent).
```

### V98 — platform_config singleton present + seeded

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT id, config, updated_by IS NULL AS is_initial_seed
FROM platform_config;"
# Expected: exactly 1 row.
#   id = 00000000-0000-0000-0000-000000000001
#   config = {
#     prometheus_enabled: true,
#     tracing_enabled: false,
#     tracing_endpoint: 'http://localhost:4318/v1/traces',
#     monitor_stale_interval_minutes: 5,
#     monitor_dv_canary_interval_minutes: 15,
#     monitor_temperature_interval_minutes: 60
#   }
#   is_initial_seed = t (no operator has flipped anything yet)
```

### Surge-threshold endpoint smoke (login required)

Confirms the new `TemperatureThresholdController` GET + PUT pair (which replaced the removed `/observability` endpoint) actually serves. The `SurgeTemperatureSettings` admin panel reads via this GET — if it 404s, the panel renders broken.

```bash
# Login as cocadmin@blueridge.fabt.org / dev-coc-west / admin123 (out-of-band token mint), then:
curl -sS -H "Authorization: Bearer ${COC_ADMIN_JWT}" \
    https://findabed.org/api/v1/tenants/${TENANT_ID}/surge-threshold
# Expected: 200 with {"temperature_threshold_f": 32}
# (32°F is the controller default when the tenant has never set one.
# Demo tenants ship with 32 from V77 seed; real values may differ.)
```

### dv-policy endpoint smoke (login required)

```bash
# Login as cocadmin@blueridge.fabt.org / dev-coc-west / admin123 (out-of-band token mint), then:
curl -sS -o /dev/null -w "GET own /tenants/{id}/config: %{http_code}\n" \
    -H "Authorization: Bearer ${COC_ADMIN_JWT}" \
    https://findabed.org/api/v1/tenants/${TENANT_ID}/config
# Expected: 200 (G-4.4 fix from v0.55 still holds).

curl -sS -X PATCH -H "Authorization: Bearer ${COC_ADMIN_JWT}" \
    -H "Content-Type: application/json" \
    -d '{"dvPolicyEnabled": true}' \
    https://findabed.org/api/v1/admin/tenants/${TENANT_ID}/dv-policy
# Expected: 200 with body {"tenantId":"<uuid>","dvPolicyEnabled":true}
# (Idempotent re-set against V97-backfilled-true tenants — the value
# is unchanged, but the endpoint always returns the current state.)
```

### platform-observability endpoint smoke (SSH tunnel + platform login required)

> **SSH tunnel command + platform-operator credentials are shared in chat at deploy time, NOT committed here** (per `feedback_platform_login_via_ssh_tunnel.md`).

```bash
# After establishing the platform-operator JWT via the SSH tunnel + MFA flow:
curl -sS -H "Authorization: Bearer ${PLATFORM_JWT}" \
    https://findabed.org/api/v1/platform/observability
# Expected: 200 + JSON body matching the V98 seed defaults.

# Verify justification is REQUIRED on writes:
curl -sS -w "\nHTTP %{http_code}\n" \
    -X PUT -H "Authorization: Bearer ${PLATFORM_JWT}" \
    -H "Content-Type: application/json" \
    -d '{"prometheus_enabled": true}' \
    https://findabed.org/api/v1/platform/observability
# Expected: 400 with hand-rolled JSON body
#   {"error":"missing_justification",
#    "message":"X-Platform-Justification header is required for platform-admin endpoints.",
#    "status":400}
# Note: this 400 is written directly by JustificationValidationFilter
# (NOT routed through the StructuredErrorException → context.errorCode
# path the other v0.56 endpoints use). The discriminator field is
# `error` at the top level, NOT `context.errorCode`.
```

### Audit row landed for any platform-obs write performed during gates

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc "
SELECT event_type, count(*)
FROM audit_events
WHERE event_type = 'PLATFORM_OBSERVABILITY_UPDATED'
  AND timestamp > NOW() - INTERVAL '1 hour'
GROUP BY event_type;"
# Expected: 0 if no operator PUT was performed during the gate window;
# >= 1 if the gate above included a write. Schema confirms the new
# enum value is registered.
```

### OperationalMonitorService scheduled tasks loaded

```bash
docker logs fabt-backend --since 5m 2>&1 | grep -E "OperationalMonitor|configureTasks|stale_interval" | head -10
# Expected: at least one log line confirming the SchedulingConfigurer
# bean wired its 3 tasks at startup. The actuator/scheduledtasks
# endpoint is NOT exposed in the default v0.56 management config; use
# log-grep as the verification path.
```

### Stale-SW reminder

If anyone tests the new admin/platform UI from a browser that was logged in pre-deploy, **use incognito or clear site data** (per `feedback_stale_sw_on_deploy.md`).

---

## 7. Rollback Matrix

| Symptom | Action | Time to recover |
|---|---|---|
| Backend won't start (Flyway validate error) | Read `docker logs fabt-backend --tail 200` for Flyway specifics first. If a migration broke partway, full DB rollback is the safe path: `docker exec -i finding-a-bed-tonight-postgres-1 pg_restore -U fabt -d fabt --clean --if-exists < ~/fabt-backups/fabt-pre-v0.56.0-<TIMESTAMP>.dump`, then `docker tag fabt-backend:v0.55.0-lastgood fabt-backend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. | ~15 min |
| Backend JAR fails for non-Flyway reasons (NPE on boot, bean wiring) | Image-only rollback. V97-V98 stay applied — both are forward-compatible (V97 sets a JSONB key the v0.55.x backend ignores; V98 creates a new table the v0.55.x backend never reads). `docker tag fabt-backend:v0.55.0-lastgood fabt-backend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. | ~5 min |
| New endpoints return 5xx in steady state | Image-only rollback per row above. The new endpoints disappear; existing tenant flow is unaffected. | ~5 min |
| Frontend admin/platform surface broken or visually wrong | Frontend image-only rollback: `docker tag fabt-frontend:v0.55.1-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate frontend`. The backend keeps running with the new JAR; new UI surfaces are inaccessible until you rebuild the v0.56 frontend. | ~3 min |
| Host nginx 502 after backend recreate | Frontend docker-network is stale — recreate frontend too. (Standard remediation per template.) | ~3 min |
| Frontend serving stale JS post-deploy | Old service worker cached the bundle. Use incognito or clear site data. | <1 min |
| Operator flipped dv-policy to FALSE on a tenant + needs revert | The flag is a JSONB key — operator can re-flip via the admin UI (or via `psql` UPDATE if the backend is down). The forward-only V97 backfill does not need to be re-run; it only initialized rows with NO existing key. | <1 min |
| Operator broke the OTel exporter via a bad `tracing_endpoint` URL | Same flow — operator (or DBA) reverts via the platform endpoint or `psql UPDATE platform_config SET config = jsonb_set(config, '{tracing_endpoint}', '"http://localhost:4318/v1/traces"'::jsonb)`. `OperationalMonitorService` does not reschedule on tracing-endpoint change (only on monitor-interval change), so revert takes effect on next OTel export. | <1 min |
| Full rollback to v0.55.1 | `docker tag fabt-backend:v0.55.0-lastgood fabt-backend:latest && docker tag fabt-frontend:v0.55.1-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend frontend`. **V97-V98 are NOT rolled back** — they are additive and forward-compatible with v0.55.x backend. | ~6 min |

> **V97-V98 are one-way migrations.** V97 is a JSONB key write that the v0.55.x backend ignores; V98 is a new table the v0.55.x backend never references. Rolling back the JAR rolls back the calls, not the schema. Full DB rollback via `pg_restore` is the path only when a migration itself fails partway — both v0.56 migrations are simple enough that this is unlikely.

---

## 8. Post-Deploy Housekeeping

> **Run after the deploy succeeds.** A rolled-back deploy means
> memory should reflect that explicitly (e.g. `v0.56-rolled-back` in
> the resume-point note) — do NOT pre-record version/Flyway updates
> below.

- [ ] Update `project_live_deployment_status.md` memory: version `v0.55.1 → v0.56.0`, Flyway HWM `V96 → V98`, new controllers (`DvPolicyController`, `PlatformObservabilityController`, `TemperatureThresholdController`), `/api/v1/version` reports `"0.56"`.
- [ ] Update `project_resume_point.md` memory per `feedback_periodic_resume_save.md`: dv-policy + platform-obs shipped; remaining backlog items (info-email-contact, GH #67) unblocked for next-cycle.
- [ ] Tag the just-deployed images for the next release's rollback path: `docker tag fabt-backend:v0.56.0 fabt-backend:v0.56.0-lastgood && docker tag fabt-frontend:v0.56.0 fabt-frontend:v0.56.0-lastgood`.
- [ ] Update `CHANGELOG.md`: confirm `[v0.56.0] — 2026-MM-DD` date is the actual deploy date.
- [ ] Archive both spent OpenSpec changes: `/opsx:archive dv-policy-tenant-flag` + `/opsx:archive platform-observability-split`. Sync delta specs into main specs first.
- [ ] Manual demo walkthrough as cocadmin: admin Settings → DV Shelter Operations panel → toggle (with extra-confirm modal) → save → verify audit row. Confirm the disable-rejection error renders the count + link to filtered Shelters tab when the tenant has active DV shelters.
- [ ] Manual smoke as platform operator (via SSH tunnel): `/platform` dashboard → Observability category → toggle a Prometheus value or change a monitor interval → confirm the 2-step destructive-confirm modal + per-field audit row + monitor reschedule (interval changes only).
- [ ] **Keyboard-discoverability spot check** (W3C ARIA APG): on `/platform` dashboard, Tab onto a flag-gated lifecycle action card (e.g. **Suspend tenant** when `fabt.tenant.lifecycle.enabled=false`). Confirm the button receives focus AND announces its disabled state via the `aria-disabled="true"` attribute (verifiable via DevTools Accessibility panel or any AT). The v0.56 change replaced the native `disabled` attribute, which would have removed the button from tab order entirely. Reference: https://www.w3.org/WAI/ARIA/apg/practices/keyboard-interface/#focusabilityofdisabledcontrols.
- [ ] Verify Prometheus rule count is unchanged from pre-deploy:
  ```bash
  docker exec finding-a-bed-tonight-prometheus-1 wget -qO- http://localhost:9090/api/v1/rules \
      | python3 -m json.tool | grep -cE '"name":'
  ```
  Expected: same count as pre-deploy. v0.56 does not add rules.
- [ ] Confirm alert names are unchanged (alertmanager template chain is unmodified in v0.56).
- [ ] Append the v0.56 platform-obs Spanish keys (`admin.observability.thresholdDescription`, `thresholdError`, `confirmTitle`, `confirmBody`) to `reference_es_json_ai_synthetic_reviewed.md` memory — AI-synthetic review, not a native speaker. Same disclosure conventions as v0.55.1 D2.
- [ ] Delete or expire any test alerts fired during deploy verification.

---

## Related artifacts

- OpenSpec changes:
  - `openspec/changes/dv-policy-tenant-flag/` (in the docs repo — full proposal, design, tasks, runbook-fragments)
  - `openspec/changes/platform-observability-split/` (in the docs repo — full proposal, design, tasks)
- Operator user guide updates: `docs/operations/platform-operator-user-guide.md` Observability configuration section (NEW)
- CHANGELOG: `CHANGELOG.md` `[v0.56.0]` section (both changes)
- Backend migrations:
  - `backend/src/main/resources/db/migration/V97__backfill_dv_policy_enabled.sql`
  - `backend/src/main/resources/db/migration/V98__platform_config.sql`
- New backend controllers:
  - `backend/src/main/java/org/fabt/tenant/api/DvPolicyController.java`
  - `backend/src/main/java/org/fabt/observability/api/PlatformObservabilityController.java`
  - `backend/src/main/java/org/fabt/tenant/api/TemperatureThresholdController.java`
- New frontend components:
  - `frontend/src/pages/admin/components/DvPolicySettings.tsx`
  - `frontend/src/pages/admin/components/SurgeTemperatureSettings.tsx`
  - `frontend/src/pages/platform/components/ObservabilityActionCard.tsx`
- New Playwright spec: `e2e/playwright/tests/platform-dashboard-inline-flows.spec.ts`
- Pull requests: code repo PR #173 (dv-policy) + PR #174 (platform-obs); docs repo PR #10 + PR #11

---

*FABT Deploy Runbook v0.56.0 — follows template v1 (`docs/runbook-template.md`)*
