# Oracle Deploy Notes — v0.55.0 (transitional-reentry-support, slice 4)

**Status:** draft pending CI green + tag.
**Template version:** v1 (per `docs/runbook-template.md`).
**OpenSpec change:** `transitional-reentry-support` (in the docs repo).

---

## What's new in v0.55.0 (one-paragraph summary)

v0.55 ships the user-facing surface of the transitional-reentry-support
spec: a new shelter classification taxonomy (`EMERGENCY`,
`TRANSITIONAL`, `REENTRY_TRANSITIONAL`, `DV`, with `OVERFLOW`
deferred per `project_deferred_openspecs_required.md`), a
county-aware advanced search for outreach workers, structured
eligibility-criteria display + edit, a coordinator-side hold
dialog with optional attribution + a 24h PII purge job, an admin
Reservation Settings panel for tenant-scoped hold-duration overrides,
and a per-tenant `features.reentryMode` flag. The deploy is
**single-stage** — no flag flip; the reentry UI activates as soon as
the new frontend bundle is in place. Bundled with the slice is the
latent G-4.4 SecurityConfig fix that lets a `COC_ADMIN` GET their
own `tenants/{id}/config` (the prod symptom was masked by
`DemoGuardFilter` blocking the hold-duration save anyway, per
`project_no_hotfix_g44_securityconfig_gap.md`). Local rehearsal
PASSED on 2026-04-29 at `/tmp/deploy-rehearsal-20260429-191305`. See
`docs/operations/reentry-mode-user-guide.md` for the operator
walkthrough.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: every container name, JAR path, port, and image tag
    # in this runbook ground-truthed via `ssh ... docker compose ls /
    # docker ps / docker images / curl /api/v1/version` against the
    # live VM 2026-04-29.
  - file: feedback_runbook_compose_chain.md
    # why-cited: prod uses a 5-FILE compose chain (unchanged from
    # v0.54). Missing any one file breaks deploy (postgres crash loop
    # on v0.50 was the lesson).
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit `docker build --no-cache` BEFORE
    # `compose up --force-recreate`. Three no-op traps on v0.48.
  - file: feedback_deploy_old_jars.md
    # why-cited: `mvn clean` mandatory before backend `docker build`
    # — old JARs in `backend/target/` cause Docker to embed stale
    # bytecode.
  - file: feedback_deploy_checklist_v031.md
    # why-cited: clean build, single-JAR verify, no-cache image,
    # actuator on 9091, post-deploy class verification.
  - file: feedback_runbook_template_v1.md
    # why-cited: this runbook follows the v1 template structure.
  - file: feedback_release_after_scans.md
    # why-cited: tag + GitHub release published only after CI scans
    # green (CodeQL + npm audit + dependency-check).
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
    # why-cited: this runbook shares the SSH and docker commands the
    # operator runs. v0.55 surfaces no new platform-operator paths so
    # the v0.54 SSH-tunnel section is intentionally omitted.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: no compose-file edit in v0.55 and no new files in
    # mounted config directories, so prometheus does NOT need
    # force-recreate this release. Verified by `ls deploy/prometheus`.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: every command path, container name, and image tag
    # below ground-truthed against migration files, V95 seed source,
    # and live `docker ps` output.
  - file: feedback_stale_sw_on_deploy.md
    # why-cited: post-deploy frontend testing must be incognito or
    # site-data-cleared — old service worker will serve cached JS.
  - file: feedback_periodic_resume_save.md
    # why-cited: post-deploy memory updates per §8.
  - file: feedback_deploy_rehearsal_lessons.md
    # why-cited: rehearsal PASSED 2026-04-29 19:13:05 at
    # /tmp/deploy-rehearsal-20260429-191305. Known-lingering rehearsal
    # containers are NOT a regression — see the memory for the four
    # original rehearsal-harness bugs.
  - file: project_live_deployment_status.md
    # why-cited: ground-truth source. v0.54.0 live; Flyway HWM 90;
    # 5-file compose chain; `fabt-pgaudit:v0.45.0` postgres image;
    # JAR at `/app/app.jar`; container_name conventions.
  - file: project_no_hotfix_g44_securityconfig_gap.md
    # why-cited: G-4.4 tenants/{id}/config 403 fix is BUNDLED in this
    # release per the 2026-04-29 decision. DemoGuardFilter still masks
    # the user-visible symptom on prod, but the underlying SecurityConfig
    # rule lands here so reviewers see it called out.
  - file: project_dv_redaction_county_passthrough.md
    # why-cited: DvAddressRedactionHelper exposes shelterType +
    # county + requiresVerificationCall for DV shelters. Reversal is
    # a single-file change if a future warroom finds it risky.
  - file: project_deferred_openspecs_required.md
    # why-cited: OVERFLOW shelter type is intentionally OUT of slice 4
    # and tracked there.
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.55.0 — transitional-reentry-support slice 4 (search filters, eligibility display + edit, coordinator hold dialog with PII purge, admin reservation settings panel, V91-V95 schema work + V95 demo seed expansion).

**From:** `v0.54.0` live at `findabed.org`. Confirm current via:

```bash
curl -s https://findabed.org/api/v1/version
# Expected pre-deploy: {"version":"0.54"}
```

> **Version-format note:** `VersionController.java` deliberately strips
> the patch version — `0.54.0` is reported as `"0.54"` and `0.55.0`
> will report as `"0.55"`. Do NOT expect a literal `"0.55.0"` in the
> response body.

**To:** `v0.55.0` — backend JAR `0.54.0 -> 0.55.0`, Flyway HWM `V90 -> V95`, frontend bundle gains the reentry-mode component tree (search filters, eligibility section + display, hold dialog, admin reservation settings).

**Migrations in this deploy:**

- **V91** — `shelter.shelter_type` enum-as-text + `shelter.county` nullable + `tenant.config` `features.reentryMode` flag. Backfills `shelter_type='DV'` for every existing `dv_shelter=true` row and adds the `shelter_dv_implies_dv_type` CHECK constraint. Non-breaking.
- **V92** — GIN partial index on `shelter_constraints.eligibility_criteria` (JSONB) for the search filter contains-key operator. Non-breaking.
- **V93** — `reservation` `_encrypted` columns + `tenant_dek.purpose` extension for `RESERVATION_PII`. Nullable; only populated when the new hold dialog is used. Non-breaking.
- **V94** — `shelter.requires_verification_call` BOOLEAN sentinel (top-level, NOT in eligibility JSONB per design D1). Defaults to false; non-breaking.
- **V95** — demo shelter seed expansion for `dev-coc-east` + `dev-coc-west`. Adds 6 shelters across the new shelter-type values, backfills `county` on existing east+west shelters, sets `active_counties` on tenant config, and adds `coordinator_assignment` rows. Idempotent via per-row `INSERT ... WHERE NOT EXISTS`.

**New endpoints:**

- `GET /api/v1/active-counties` — any authenticated principal, tenant-scoped (returns the calling tenant's `features.activeCounties` list).
- `PATCH /api/v1/admin/tenants/{id}/hold-duration` — `COC_ADMIN`, audit-logged, tenant-scoped to the caller's tenant.
- `GET /api/v1/shelters/{id}/reservations` — `COORDINATOR` if assigned to the shelter, `COC_ADMIN` always.

**Bundled SecurityConfig fix:** `/api/v1/tenants/*/config` rule is now placed before the catch-all so a `COC_ADMIN` can read their own tenant config. The user-visible bug on prod was masked by `DemoGuardFilter` blocking the hold-duration save flow anyway, so this is a no-op for end-users; it is committed here for code-hygiene.

**JsonString wire-format fix:** the frontend now stringifies `eligibility_criteria` on write to match the read-side. Previously a 500 on shelter create with eligibility_criteria from the UI.

**What does NOT change in this deploy:**

- Tenant JWT issuer + claims, FORCE RLS posture, Postgres / pgaudit (`fabt-pgaudit:v0.45.0` continues unchanged).
- Compose file chain — same 5 files; no new override.
- Prometheus rule files + alertmanager templates — unchanged.
- Container names — unchanged from v0.54.
- Platform-operator surface (`/platform/*`) — no UI or API changes in v0.55, so the SSH-tunnel section from the v0.54 runbook is intentionally NOT carried forward.

**Out of scope (deferred):**

- `OVERFLOW` shelter type — defined in V91's enum-as-text but with no UI and no CI-enforced rendering. Tracked in `project_deferred_openspecs_required.md`.
- Casey final i18n re-review eyeball — manual sign-off; tracked as a §8 housekeeping checkbox, not a code gate.
- §7.4 NVDA / VoiceOver manual a11y spot-check — deferred to v0.56 release prep.
- Platform-operator UI changes — none in this slice.

---

## 3. Service-Recreate Matrix

| Service (prod container_name) | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `fabt-backend` | New JAR (0.55.0) + new V91-V95 migrations + new endpoints | Yes | Yes |
| `fabt-frontend` | New bundle (reentry components + JsonString wire-format fix) | Yes | Yes |
| `finding-a-bed-tonight-postgres-1` | `fabt-pgaudit:v0.45.0` image unchanged; pgaudit.conf unchanged | No | No |
| `finding-a-bed-tonight-prometheus-1` | No new rules file in `deploy/prometheus/`; no compose-file edit; no inode change | No | No |
| `finding-a-bed-tonight-alertmanager-1` | No new template; no rendered-secret change | No | No |
| `finding-a-bed-tonight-{grafana,jaeger,otel-collector}-1` | No dashboard / collector / image change | No | No |
| Host `nginx` | No `/etc/nginx/sites-available/fabt` edit | No | No |

> **Container-name rule:** `fabt-backend` and `fabt-frontend` carry custom `container_name:` directives in `~/fabt-secrets/docker-compose.prod.yml` (out-of-repo). Postgres and the observability stack use default `<project>-<service>-<replica>` naming. Verified via `docker ps` on the live VM 2026-04-29.

---

## 4. Pre-Deploy Gates

- [ ] **pom.xml version bumped** — `cd backend && grep -nE "<version>0\." pom.xml | head -1` should report **0.55.0** at line 16. If still 0.54.0, edit `backend/pom.xml` line 16, commit on the release branch, and re-run `mvn -B -DskipTests clean package -q` to confirm the JAR filename is now `*0.55.0*.jar`. The Spring Boot Maven plugin writes the version into `META-INF/build-info.properties` at package time, which is what `/api/v1/version` reads at runtime.
- [ ] **Local rehearsal PASS** — already completed 2026-04-29 19:13:05 at `/tmp/deploy-rehearsal-20260429-191305`. Known-lingering rehearsal containers from the harness are not a regression (see `feedback_deploy_rehearsal_lessons.md`).
- [ ] **Backend tests green** — `cd backend && mvn -B test -q` — expect 1414/1414 passing locally (1394 pre-slice + 5 ShelterReservationsEndpointTest + 5 TenantConfigEndpointTest + 15 V95SeedDemoShelterTest as the slice-4 deltas; recount from the most recent commit if exactness matters).
- [ ] **Mocked Playwright suite green locally** — `cd e2e/playwright && BASE_URL=http://localhost:8081 npx playwright test reentry-search-filters.spec.ts reentry-eligibility-display.spec.ts reentry-hold-dialog.spec.ts reentry-integrated-navigator.spec.ts --reporter=list` — expect 11/11 GREEN against `./dev-start.sh` + nginx@8081. Run through nginx, NOT bare Vite (per `feedback_check_ports_before_assuming.md`).
- [ ] **CI green** — `gh pr checks 167` on the v0.55 release PR. All checks green except possibly E2E if it has not finished yet. `feedback_release_after_scans.md`.
- [ ] **Casey final i18n re-review** — confirm sign-off on `shelter.criminalRecordPolicyDisclaimer`, `shelter.vawaNoteDisclaimer`, and `search.acceptsFeloniesEmptyHint` (English + Spanish renderings) before tagging. Track via the legal-review thread in the OpenSpec change. Without sign-off, hold the tag.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` returns NO output. (v0.49 issue #1.)
- [ ] **Container UID vs perms** — no new bind-mounted files in this deploy. Spot-check `ls -l ~/finding-a-bed-tonight/deploy/prometheus/*.rules.yml` after `git checkout v0.55.0` shows the existing rules files still `-rw-r--r--`.
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
      config > /tmp/v0.55.0-config.rendered.yml
  ```

  **Expected delta from compose-config: ZERO.** No compose file changed in v0.55; the only filesystem changes are inside the backend JAR and the frontend bundle (rebuilt below). A non-empty diff against the v0.54 render means a compose file changed unexpectedly.

- [ ] **OCI key path mounted on the VM** — backend env has `FABT_OCI_AUDIT_ANCHOR_PRIVATE_KEY_PATH=/etc/fabt/oci/audit-anchor.pem`. Confirm via `docker inspect fabt-backend | grep -i oci_audit_anchor`. Required for the OCI audit anchor that landed in v0.52 and is still in the chain.
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.55.0-$(date -u +%Y%m%d-%H%M%S).dump`. The `-Fc` custom format is restored via `pg_restore`, NOT `psql`.
- [ ] **Git tag + GitHub release published** — `git tag v0.55.0 && git push origin v0.55.0`, then `gh release create v0.55.0 --generate-notes`. Verify with `gh release view v0.55.0`. The deploy MUST checkout the tag, not main HEAD — `git pull origin main` couples the deployed commit to whatever happens to be on main when SSH runs, breaking the audit trail.
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting (`ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}`). Do not assume it will be reachable mid-deploy.

---

## 5. Deploy Steps

> **Canonical 5-file compose chain** (per memory `project_live_deployment_status.md`, ground-truthed 2026-04-29 via `docker compose ls`). EVERY `docker compose ... up -d` invocation MUST include all five `-f` flags. Missing any one breaks deploy (per `feedback_runbook_compose_chain.md`):
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

### 5.0. Static content (docs site) — ship the v0.55 demo + audit fixes FIRST

Static content is served from `/var/www/findabed-docs/` on the Oracle VM
(verified during v0.53 deploy). Nginx serves these via `try_files` — no
restart needed after copying. Static deploys safely before the backend
swap because the new HTML/PNG is content-only (no API contract change);
the v0.54 backend serves it just fine until §5.6 swaps the image.

v0.55 ships **30 stale-or-new files** (12 HTML + 18 PNG): the §6 demo
audit fixes (2 BLOCKERs + 4 HIGHs + 7 MEDIUMs across 10 demo HTML files
+ root index.html), the new `demo/reentry-story.html` capability deep-
dive, and 18 screenshots (12 re-captured to align with §10/§11/§16
surfaces + 6 NEW reentry walkthrough captures).

```bash
# From your local Windows / Git Bash machine. FABT_VM_IP is set
# out-of-band per feedback_no_ip_in_repo (it lives in memory + the
# operator's local env, never in git).
cd /c/Development/findABed

# 1. Root index.html (1 file) — §2.1 "ever" claim DV-scoped
scp -i ~/.ssh/fabt-oracle index.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/

# 2. 11 demo HTML files (10 modified + 1 NEW reentry-story.html)
scp -i ~/.ssh/fabt-oracle \
  demo/dvindex.html \
  demo/for-cities.html \
  demo/for-coc-admins.html \
  demo/for-coordinators.html \
  demo/for-funders.html \
  demo/hmisindex.html \
  demo/index.html \
  demo/outreach-one-pager.html \
  demo/pitch-briefs.html \
  demo/reentry-story.html \
  demo/shelter-onboarding.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/

# 3. 12 modified screenshots (post-§10/§11/§16 captures + 20-25 import flow)
scp -i ~/.ssh/fabt-oracle \
  demo/screenshots/02-bed-search.png \
  demo/screenshots/03-search-results.png \
  demo/screenshots/04-shelter-detail-search.png \
  demo/screenshots/05-reservation-hold.png \
  demo/screenshots/11-admin-shelters.png \
  demo/screenshots/13-admin-shelter-detail.png \
  demo/screenshots/20-import-211-preview.png \
  demo/screenshots/21-import-211-success.png \
  demo/screenshots/22-admin-shelters-edit.png \
  demo/screenshots/23-shelter-edit-phone.png \
  demo/screenshots/24-shelter-edit-dv-toggle.png \
  demo/screenshots/25-dv-confirm-dialog.png \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/screenshots/

# 4. 6 NEW reentry screenshots (capture-reentry-screenshots.spec.ts output)
scp -i ~/.ssh/fabt-oracle \
  demo/screenshots/reentry-01-advanced-search-filters.png \
  demo/screenshots/reentry-02-search-results-filtered.png \
  demo/screenshots/reentry-03-shelter-detail-eligibility.png \
  demo/screenshots/reentry-04-hold-dialog-attribution.png \
  demo/screenshots/reentry-05-admin-reservation-settings.png \
  demo/screenshots/reentry-06-no-match-failure-path.png \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/screenshots/

# Verify on VM:
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP} "
  echo '=== root index ==='
  ls -la /var/www/findabed-docs/index.html
  echo '=== 11 demo HTML ==='
  ls -la /var/www/findabed-docs/demo/{dvindex,for-cities,for-coc-admins,for-coordinators,for-funders,hmisindex,index,outreach-one-pager,pitch-briefs,reentry-story,shelter-onboarding}.html
  echo '=== 18 screenshots ==='
  ls -la /var/www/findabed-docs/demo/screenshots/{02,03,04,05,11,13,20,21,22,23,24,25}-*.png /var/www/findabed-docs/demo/screenshots/reentry-{01,02,03,04,05,06}-*.png
"
# Expected:
#   reentry-story.html ~18376 bytes (NEW; previously 404 → SPA fallback)
#   reentry-*.png 6 files present (NEW)
#   for-funders.html size up vs v0.53 (BLOCKER-FND-1 fix added text)
#   for-coc-admins.html size up vs v0.53 (BLOCKER-COC-1 + HIGH-COC-2 added ~250 words)
```

**No nginx reload required** — static content read per-request. Cloudflare
caches HTML + PNG aggressively, so a CDN purge is required after scp:
- Cloudflare → findabed.org → Caching → Configuration → Purge Cached Content
- Choose **Purge Everything** (1-2 min refill from origin; broader hammer
  but simpler than enumerating 12 URLs)
- Verify: `curl -sf -w "%{size_download}\n" -o /dev/null https://findabed.org/demo/reentry-story.html`
  should return ~18376 bytes (NOT 592 — that's the SPA fallback shell)

Per `feedback_stale_sw_on_deploy.md`: SPA SW caches `/login` and `/outreach`
React routes but NOT `/demo/*.html` (nginx serves those as real files
ahead of the SPA fallback chain). Demo pages refresh on next request
post-Cloudflare-purge; SPA users may still need hard-reload after §5.5.

### 1. Preserve last-good image tags

```bash
docker tag fabt-backend:latest fabt-backend:v0.54.0-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.54.0-lastgood
docker images | grep -E "fabt-(backend|frontend)"
# Expected: latest + v0.54.0-lastgood both present (same IMAGE ID, two tags).
# This is the rollback-target image for §7.
```

### 2. Checkout the release tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.55.0
git log --oneline -1
# Expected: HEAD on the v0.55.0 tagged commit (detached HEAD is correct).
```

> **Do NOT `git pull origin main`.** Deployed commit must equal the tag for audit traceability.

### 3. Verify Dockerfile + migration paths landed

```bash
ls -1 infra/docker/Dockerfile.backend infra/docker/Dockerfile.frontend
# Expected: both present.

ls -1 backend/src/main/resources/db/migration/V9{1,2,3,4,5}__*.sql
# Expected: V91 shelter_type_county_and_reentry_flag,
#           V92 eligibility_criteria_jsonb,
#           V93 reservation_pii_encrypted,
#           V94 shelter_requires_verification_call,
#           V95 seed_reentry_demo_shelters_east_west.
```

### 4. Backend rebuild (clean + no-cache)

```bash
mvn -B -DskipTests clean package -q
ls -1 backend/target/*.jar | head -3
# Expected: exactly one *0.55.0*.jar (fat JAR) plus the .original side-car.
# More than one fat JAR means `mvn clean` did not run — abort and re-run.

docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.55.0 \
    -t fabt-backend:latest .
```

### 5. Frontend rebuild (clean + no-cache)

```bash
cd frontend
npm ci
npm run build
cd ..

docker build --no-cache \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.55.0 \
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
# Internal management port — actuator binds to 9091 localhost-only
# (NOT the public URL — it returns 404 by design).
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
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 6;"
# Expected top 5 rows in descending order:
#   V95 | seed reentry demo shelters east west            | t
#   V94 | shelter requires verification call              | t
#   V93 | reservation pii encrypted                       | t
#   V92 | eligibility criteria jsonb                      | t
#   V91 | shelter type county and reentry flag            | t
# (Description text comes from the migration filename underscore-split.)
```

> **If the backend does not become UP within 120s**, stop here. Pull `docker logs fabt-backend --tail 200`, look for Flyway errors first (most likely: a migration mismatch). Go to §7 Rollback Matrix.

---

## 6. Post-Deploy Gates

### Mandatory smoke gate

```bash
cd e2e/playwright && FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.55.0.log
```

> **Do NOT skip this gate.** The smoke is the only test that exercises the full Cloudflare -> host nginx -> frontend -> backend chain. `--config=deploy/playwright.config.ts` selects the deploy-isolated config (per `feedback_deploy_verify_isolation.md`); `post-deploy-smoke` is the positional filter that restricts to the canonical smoke spec; `--retries=1` defends against the rate-limit flake on test 13/14 (per `feedback_smoke_config_on_prod.md`).

Expected: 13/15 GREEN, 2 SKIPPED. The 2 skipped are the forgot-password demo branch and the 211-import-edit, both dev-only and gated off in the deploy config.

### Version check

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.55"}
# NOT "0.55.0" — the version controller strips the patch component.
```

### Flyway HWM

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT max(version) FROM flyway_schema_history WHERE success=true;"
# Expected: 95
```

### V91 columns landed

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT 1 FROM information_schema.columns
       WHERE table_name='shelter' AND column_name='shelter_type';"
# Expected: 1

docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT 1 FROM information_schema.columns
       WHERE table_name='shelter' AND column_name='county';"
# Expected: 1
```

### V94 sentinel column landed

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT 1 FROM information_schema.columns
       WHERE table_name='shelter' AND column_name='requires_verification_call';"
# Expected: 1
```

### V95 demo seed expansion

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
    "SELECT t.slug, s.shelter_type, count(*)
       FROM shelter s JOIN tenant t ON t.id = s.tenant_id
       WHERE t.slug IN ('dev-coc-east','dev-coc-west')
       GROUP BY t.slug, s.shelter_type
       ORDER BY t.slug, s.shelter_type;"
# Expected exact rows:
#   dev-coc-east | DV                   | 1
#   dev-coc-east | EMERGENCY            | 3
#   dev-coc-east | REENTRY_TRANSITIONAL | 1
#   dev-coc-east | TRANSITIONAL         | 1
#   dev-coc-west | DV                   | 1
#   dev-coc-west | EMERGENCY            | 3
#   dev-coc-west | REENTRY_TRANSITIONAL | 1
#   dev-coc-west | TRANSITIONAL         | 1
```

### Active-counties endpoint

```bash
# Login as cocadmin (out-of-band; obtain a JWT first), then:
curl -sS -H "Authorization: Bearer ${COC_ADMIN_JWT}" \
    https://findabed.org/api/v1/active-counties
# Expected: 200 with the calling tenant's active_counties list.
```

### COC_ADMIN can read own tenant config (G-4.4 fix)

```bash
# Login as cocadmin@blueridge.fabt.org / dev-coc-west / admin123
# (out-of-band token mint), then:
curl -sS -o /dev/null -w "GET own /tenants/{id}/config: %{http_code}\n" \
    -H "Authorization: Bearer ${COC_ADMIN_JWT}" \
    https://findabed.org/api/v1/tenants/${TENANT_ID}/config
# Expected: 200. Pre-v0.55 this returned 403 (G-4.4 bug). The
# DemoGuardFilter still blocks the hold-duration SAVE flow, but the
# READ side is now correct.
```

### Outreach-side advanced filters (manual)

- [ ] Login as `outreach@blueridge.fabt.org` / `dev-coc-west` / `admin123`; navigate to search/match.
- [ ] Toggle shelter-type chips (`TRANSITIONAL`, `REENTRY_TRANSITIONAL`) — result set updates.
- [ ] County dropdown is populated from `active_counties`; accepts-felonies toggle respects `eligibility_criteria.acceptsFelonies`.

### Coordinator hold dialog (manual)

- [ ] Login as a coordinator assigned to a v0.55-seeded shelter; confirm per-hold rows + countdown render on the dashboard.
- [ ] Open a hold; submit with optional attribution; verify it persists. Confirm `CriminalRecordPolicyDisclaimer` co-renders where the CI invariant requires it.

### Per-tenant reentry-mode flag

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT slug, config->'features'->>'reentryMode'
       FROM tenant
       WHERE slug IN ('dev-coc-east','dev-coc-west');"
# Expected: both rows show 'true' (V95 seeds the flag on east+west).
```

#### Prod demo tenants — flip features.reentryMode (BLOCKER B3)

V0.55 introduces an API serialization gate (§16.B) and four frontend gates
(§16.C) that hide reentry-specific UI surfaces (advanced filters,
eligibility section, hold-attribution PII fields, coordinator dashboard
PII display) for any tenant whose `config.features.reentryMode` is unset
or false. Without this post-deploy step, the prod demo tenants (`blueridge`
and `mountain` per `project_live_demo_seed_inventory`) will look broken
to a visitor — the new reentry capabilities will not surface for any
non-DV outreach worker login.

Default-off is the production-correct posture for any tenant that has
not affirmatively opted into reentry; this step is the affirmative opt-in
for the demo-tier tenants the public site exercises.

```bash
# Pre-flip verification — confirm which tenants currently have the flag set:
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT slug, config #>> '{features,reentryMode}' AS reentry_mode
       FROM tenant
       ORDER BY slug;"
# Expected pre-flip: dev-coc-east + dev-coc-west show 'true' (V95 seed);
# dev-coc + blueridge + mountain (and any other prod tenants) show NULL
# or 'false'.

# Flip prod demo tenants. Use the JSONB concat pattern (NOT jsonb_set with
# a nested path on a missing parent — that returns the input unchanged
# when 'features' does not yet exist; the concat pattern creates the key
# correctly whether or not 'features' is already present).
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
UPDATE tenant
   SET config = config
       || jsonb_build_object(
              'features',
              coalesce(config -> 'features', '{}'::jsonb)
                || jsonb_build_object('reentryMode', true))
 WHERE slug IN ('blueridge','mountain')
RETURNING slug, config -> 'features' AS features;"
# Expected: 2 rows returned, each with features = {"reentryMode": true}.
# If 0 rows, the slugs differ from this list — query the live tenant
# table (`SELECT slug FROM tenant WHERE slug NOT LIKE 'dev-%';`) and
# update the WHERE clause.
```

Token-TTL caveat: the JWT `reentryMode` claim is captured at token-issue
time. Operators currently logged in will not see the new surface until
their access token refreshes (15-minute TTL bound) or they log out + back
in. Expect a soft window between the SQL flip and visible UI change.

DEK note: this flag flip touches `tenant.config` only. It does NOT
require the `RESERVATION_PII` DEK to be rotated, and it has no effect
on data-at-rest. It is a UI/API serialization toggle exclusively.

### §6.5 PII purge verification

The hold-attribution PII columns added in V93 (`held_for_client_name_encrypted`, `held_for_client_dob_encrypted`, `hold_notes_encrypted` on `reservation`) are erased no later than 25 hours after a reservation reaches a terminal status. Implementation: `ReservationService.purgeExpiredHoldAttribution(Instant)` invoked by `ReferralTokenPurgeService.purgeExpiredHoldAttribution()` on a `@Scheduled(fixedDelay=900_000)` (15 minutes — worst-case PII lifetime is 24h+15m). Verify post-deploy:

**1) Confirm the `@Scheduled` purge bean is registered in the running backend.** First check whether `actuator/scheduledtasks` is exposed in this deploy — `management.endpoints.web.exposure.include` does not include `scheduledtasks` in the default v0.55 management config, so a 404 here is **expected, not a failure**. If 404, skip to the log-parse fallback immediately below. If the endpoint is exposed:

```bash
curl -fsS http://localhost:9091/actuator/scheduledtasks 2>&1 \
  | python3 -m json.tool \
  | grep -A2 -E "purgeExpiredHoldAttribution|purgeTerminalTokens"
# Expected (when exposed): two scheduled tasks visible —
# purgeTerminalTokens (DV referral tokens, 1h fixedRate) and
# purgeExpiredHoldAttribution (hold-attribution PII, 15m fixedDelay).
# Expected (when NOT exposed): HTTP 404 — fall through to log-parse below.
# Only treat as failure if exposed AND a task is missing from the JSON.
```

Log-parse fallback (preferred for v0.55, and required when actuator/scheduledtasks is not exposed):

```bash
docker logs fabt-backend --since 30m 2>&1 | grep "purgeExpiredHoldAttribution"
# Expected: at least one log line within the last 30 minutes
# (15m schedule + ~15m grace). Format:
#   purgeExpiredHoldAttribution: purged=N
# where N is the count of rows whose ciphertext columns were nulled this run.
# A line every ~15 minutes confirms the schedule fires.
```

**2) Confirm a sample row honored the 25-hour SLA.** Pick a reservation that resolved >25 hours ago and verify its ciphertext columns are NULL:

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT id, status,
       held_for_client_name_encrypted IS NULL AS name_purged,
       held_for_client_dob_encrypted IS NULL AS dob_purged,
       hold_notes_encrypted IS NULL AS notes_purged,
       updated_at
FROM reservation
WHERE status IN ('CANCELLED','CONFIRMED','EXPIRED','CANCELLED_SHELTER_DEACTIVATED')
  AND updated_at < NOW() - INTERVAL '25 hours'
  AND (held_for_client_name_encrypted IS NOT NULL
       OR held_for_client_dob_encrypted IS NOT NULL
       OR hold_notes_encrypted IS NOT NULL)
LIMIT 5;"
# Expected: ZERO rows (i.e. NO terminal-status reservations older than 25h
# with un-nulled ciphertext). Any row returned is a 25h SLA violation —
# stop the deploy, capture the row IDs, surface in chat.
```

**First-run waiver:** on a fresh v0.55 deploy, the candidate set this query measures (terminal-status reservations whose hold ended >25h ago AND still have un-nulled ciphertext) is naturally empty — there has not been time for any v0.55 hold-attribution row to age past the 25h SLA. **0 rows in this case confirms the invariant, not the purge execution path.** The first real exercise of the purge fires ~25h after the first navigator records optional hold attribution post-deploy. Schedule a re-run of this probe ~26-30h after the first reentry-mode hold lands, and capture the result in the deploy log; until then, rely on step 1's log-parse output (a `purged=N` line every ~15 min) as the running confirmation that the bean is firing.

**3) Confirm the purge audit-event lifecycle.** v0.55 emits audit events for hold-attribution PII writes, decrypt-on-read (throttled), and purges:

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc "
SELECT event_type, count(*)
FROM audit_events
WHERE event_type IN (
    'RESERVATION_HELD_FOR_CLIENT_RECORDED',
    'RESERVATION_PII_DECRYPTED_ON_READ',
    'RESERVATION_PII_PURGED'
)
  AND timestamp > NOW() - INTERVAL '24 hours'
GROUP BY event_type;"
# Expected: at least one RESERVATION_PII_PURGED row (the scheduled job has
# emitted at least one event in the last 24 hours, even if its purgedCount
# was 0). If demo activity has used the navigator hold dialog, also expect
# RESERVATION_HELD_FOR_CLIENT_RECORDED rows. Throttle on the read-side
# emitter is one row per (coordinator, shelter, hour) tuple.
```

**Honest disclosure (v0.55):** The Prometheus metric proving the purge SLA at scale (`fabt.reservation.pii_purge.success.count` + `fabt.reservation.pii_purge.lag_seconds` histogram + failure alert) is **NOT YET WIRED** in v0.55. Tracked for v0.56, target Q2-2026. Until then, rely on the log-parse fallback (step 1) and the audit-event count (step 3) as the operator-side signal. See `docs/security/compliance-posture-matrix.md` "Hold-attribution PII (v0.55+)" section for the full disclosure.

**Stale ciphertext after DEK rotation:** if a tenant's `tenant_dek` row for the `RESERVATION_PII` purpose is rotated or hard-deleted, prior ciphertext becomes unrecoverable (this IS the at-rest crypto-shred posture by design). The purge job continues to function — it nulls the columns regardless of decryptability. There is no operator action required for crypto-shredded ciphertext beyond confirming the row was purged on its normal SLA.

### Prometheus rules unchanged but loaded

```bash
docker exec finding-a-bed-tonight-prometheus-1 wget -qO- http://localhost:9090/api/v1/rules \
    | python3 -m json.tool | grep -E '"name":' | wc -l
# Expected: same count as pre-deploy. v0.55 does NOT add or remove
# any rules files. (jq is NOT installed on the VM per memory — use
# python -m json.tool.)
```

### JAR location inside backend container

If you need to inspect classes inside the running backend image, the JAR is at `/app/app.jar` (verified via `docker exec fabt-backend ls /app/` on the live VM 2026-04-29). Example:

```bash
docker exec fabt-backend sh -c \
    'unzip -l /app/app.jar | grep -E "ActiveCountiesController|ReservationSettingsController"'
# Expected: the new v0.55 controller classes appear.
```

### Stale-SW reminder

If anyone tests the new UI from a browser that was logged in pre-deploy, **use incognito or clear site data**. Old service worker will serve cached JS (per `feedback_stale_sw_on_deploy.md`).

### Static-content (docs site) verification

After §5.0 scp + Cloudflare "Purge Everything":

```bash
# 1. reentry-story.html now serves real content (NOT 592-byte SPA fallback)
curl -sf -w "Bytes: %{size_download}\n" -o /dev/null https://findabed.org/demo/reentry-story.html
# Expected: ~18376 bytes. 592 bytes means the file isn't on the VM (or the
# scp landed in the wrong dir) and nginx is falling through to the SPA.

# 2. All 6 NEW reentry screenshots reachable
for s in 01-advanced-search-filters 02-search-results-filtered 03-shelter-detail-eligibility 04-hold-dialog-attribution 05-admin-reservation-settings 06-no-match-failure-path; do
  curl -sf -o /dev/null -w "reentry-${s}.png: %{http_code}\n" https://findabed.org/demo/screenshots/reentry-${s}.png
done
# Expected: all 200.

# 3. BLOCKER-FND-1 fix landed (no platform-wide PII overclaim)
curl -sf https://findabed.org/demo/for-funders.html | grep -cE "opt-in privacy posture|Zero client PII on the DV referral path"
# Expected: 2+ matches (line 10 og:description + line 285 Defense bullet).
curl -sf https://findabed.org/demo/for-funders.html | grep -cE 'Open-source, zero-PII"|<strong>Zero client PII\.</strong>'
# Expected: 0 — the unscoped tagline + bare bullet are gone.

# 4. BLOCKER-COC-1 reentry-mode section landed
curl -sf https://findabed.org/demo/for-coc-admins.html | grep -c "Reentry-Mode Tenant Flag"
# Expected: 1+ — the new ~250-word section.

# 5. HIGH-IDX-1 5th tile in More Walkthroughs grid
curl -sf https://findabed.org/demo/index.html | grep -c "Reentry Walkthrough"
# Expected: 1.

# 6. §2.1 root index.html "ever" claim DV-scoped
curl -sf https://findabed.org/index.html | grep -c "DV referrals carry no client name and no address, ever"
# Expected: 1 — the ever-scope replaced the platform-wide "no client name ever" claim.
```

If any of these return unexpected values, the static deploy didn't land
cleanly — re-check §5.0 scp logs and Cloudflare purge confirmation
before proceeding to §15 demo flow walkthroughs.

---

## 7. Rollback Matrix

| Symptom | Action | Time to recover |
|---|---|---|
| Backend won't start (Flyway validate error) | Never modify applied migrations. Read `docker logs fabt-backend --tail 200` for Flyway specifics first. If a migration broke partway, full DB rollback is the safe path: `docker exec -i finding-a-bed-tonight-postgres-1 pg_restore -U fabt -d fabt --clean --if-exists < ~/fabt-backups/fabt-pre-v0.55.0-<TIMESTAMP>.dump`, then `docker tag fabt-backend:v0.54.0-lastgood fabt-backend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. | ~15 min |
| Backend JAR fails for non-Flyway reasons (NPE on boot, bean wiring) | Image-only rollback. V91-V95 stay applied — they are forward-compatible (additive columns + JSONB index + nullable `_encrypted` columns + tenant-config JSON keys are all ignored by v0.54 code). `docker tag fabt-backend:v0.54.0-lastgood fabt-backend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. | ~5 min |
| New endpoints return 5xx in steady state | Image-only rollback per row above. The new endpoints disappear; existing tenant flow is unaffected. | ~5 min |
| Frontend reentry surface broken or visually wrong | Frontend image-only rollback: `docker tag fabt-frontend:v0.54.0-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate frontend`. The backend keeps running with the new JAR; reentry-mode UI surfaces are inaccessible until you rebuild the v0.55 frontend. | ~3 min |
| Host nginx 502 after backend recreate | Frontend docker-network is stale — recreate frontend too. (Standard remediation per template.) | ~3 min |
| Frontend serving stale JS post-deploy | Old service worker cached the bundle. Use incognito or clear site data. (`feedback_stale_sw_on_deploy.md`.) | <1 min |
| Full rollback to v0.54 | `docker tag fabt-backend:v0.54.0-lastgood fabt-backend:latest && docker tag fabt-frontend:v0.54.0-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend frontend`. **V91-V95 are NOT rolled back** — they are additive and forward-compatible with v0.54 backend. | ~6 min |
| V93 `_encrypted` column writes failed (DEK key missing) | Read `docker logs fabt-backend --tail 200` for `RESERVATION_PII` key-purpose errors. The new purpose extends `tenant_dek.purpose`. If DEK provisioning failed, image rollback (above) is fastest. The `_encrypted` columns are nullable, so v0.54 reads of v0.55-written rows ignore them. | ~5 min |

> **V91-V95 are one-way migrations.** They are additive (columns,
> indexes, CHECK, JSON keys, seed rows) and coexist safely with v0.54
> code which never reads or writes them. Rolling back the JAR rolls
> back the calls, not the schema. Full DB rollback via `pg_restore`
> is the path only when a migration itself fails partway — which is
> rare given V91-V94's idempotent guards and V95's `INSERT ... WHERE
> NOT EXISTS` pattern.

> **V91 CHECK constraint caveat.** The `shelter_dv_implies_dv_type`
> constraint enforces `(dv_shelter = false) OR (shelter_type = 'DV')`.
> If V91's backfill stage fails partway, the CHECK is added but rows
> may still violate it on subsequent insert attempts. `pg_restore` is
> the safe path; do NOT manually `ALTER TABLE shelter DROP CONSTRAINT`
> on a half-migrated database.

---

## 8. Post-Deploy Housekeeping

> **Run after the deploy succeeds.** A rolled-back deploy means
> memory should reflect that explicitly (e.g. `v0.55-rolled-back` in
> the resume-point note) — do NOT pre-record version/Flyway updates
> below.

- [ ] Update `project_live_deployment_status.md` memory: version `v0.54.0 -> v0.55.0`, Flyway HWM `V90 -> V95`, new controllers (`ActiveCountiesController`, `ReservationSettingsController`, `ShelterReservationsController`), `/api/v1/version` reports `"0.55"`.
- [ ] Update `project_resume_point.md` memory per `feedback_periodic_resume_save.md`: reentry-spec slice 4 shipped; remaining slice items per `openspec/changes/transitional-reentry-support/tasks.md` §15.6 (Casey legal review eyeball) + §15.7 (NVDA/VoiceOver spot-check, deferred to v0.56).
- [ ] Tag the just-deployed images for the next release's rollback path: `docker tag fabt-backend:v0.55.0 fabt-backend:v0.55.0-lastgood && docker tag fabt-frontend:v0.55.0 fabt-frontend:v0.55.0-lastgood`. The `v0.54.0-lastgood` tag created in §5.1 stays as the rollback target during the post-deploy validation window.
- [ ] Update `CHANGELOG.md`: move `[v0.55.0] — UNRELEASED` to `[v0.55.0] — 2026-MM-DD`.
- [ ] Archive the spent OpenSpec change: `/opsx:archive transitional-reentry-support` (sync delta specs into main specs first).
- [ ] **Casey post-deploy i18n eyeball** — log in as outreach worker en/es and visually confirm the criminal-record-policy disclaimer and VAWA note copy render verbatim per legal sign-off. Track follow-ups in the OpenSpec change's tasks.md §15.6.
- [ ] Manual demo walkthrough as cocadmin: admin shelter edit -> eligibility-criteria form -> save -> reload -> confirm the structure round-trips. This catches the JsonString wire-format regression class.
- [ ] Verify Prometheus rule count is unchanged from pre-deploy:
  ```bash
  docker exec finding-a-bed-tonight-prometheus-1 wget -qO- http://localhost:9090/api/v1/rules \
      | python3 -m json.tool | grep -cE '"name":'
  ```
  Expected: same count as pre-deploy. v0.55 does not add rules.
- [ ] Confirm alert names are unchanged (alertmanager template chain is unmodified in v0.55):
  ```bash
  docker exec finding-a-bed-tonight-prometheus-1 wget -qO- http://localhost:9090/api/v1/rules \
      | python3 -m json.tool | grep -E '"alert":' | sort -u | head -20
  ```
- [ ] Note for future operators: V95 is data-seed; if a future operator wants to add or remove demo shelters, they MUST author a separate Flyway migration. Do NOT edit V95 once it is applied (`feedback_flyway_immutable_after_apply.md`).
- [ ] Delete or expire any test alerts fired during deploy verification.
- [ ] Surface follow-ups from the slice's tasks.md §15.6 and §15.7 to the v0.56 backlog tracker.

---

## Related artifacts

- OpenSpec change: `openspec/changes/transitional-reentry-support/` (in the docs repo — full proposal, design, tasks, and per-slice notes)
- Operator user guide: `docs/operations/reentry-mode-user-guide.md`
- CHANGELOG: `CHANGELOG.md` `[v0.55.0]` section
- Backend migrations:
  - `backend/src/main/resources/db/migration/V91__shelter_type_county_and_reentry_flag.sql`
  - `backend/src/main/resources/db/migration/V92__eligibility_criteria_jsonb.sql`
  - `backend/src/main/resources/db/migration/V93__reservation_pii_encrypted.sql`
  - `backend/src/main/resources/db/migration/V94__shelter_requires_verification_call.sql`
  - `backend/src/main/resources/db/migration/V95__seed_reentry_demo_shelters_east_west.sql`
- Frontend reentry surfaces (entry points): `frontend/src/components/CriminalRecordPolicyDisclaimer.tsx`, `frontend/src/components/HoldDialog.tsx`, `frontend/src/components/EligibilityCriteriaSection.tsx`, `frontend/src/components/EligibilityCriteriaDisplay.tsx`, `frontend/src/components/TagEditor.tsx`
- Playwright reentry specs: `e2e/playwright/tests/reentry-search-filters.spec.ts`, `reentry-eligibility-display.spec.ts`, `reentry-hold-dialog.spec.ts`, `reentry-integrated-navigator.spec.ts`
- Pull request: PR #167 (release branch on `feature/reentry-impl-slice-1`)
- Local rehearsal artifacts: `/tmp/deploy-rehearsal-20260429-191305` (PASS, 2026-04-29 19:13:05)

---

*FABT Deploy Runbook v0.55.0 — follows template v1 (`docs/runbook-template.md`)*
