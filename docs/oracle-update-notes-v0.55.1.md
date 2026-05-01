# Oracle Deploy Notes — v0.55.1 (CI hygiene + i18n review + a11y polish)

**Status:** draft pending CI green + tag.
**Template version:** v1 (per `docs/runbook-template.md`).
**OpenSpec change:** `v0-55-1-followup` (in the docs repo).

---

## What's new in v0.55.1 (one-paragraph summary)

v0.55.1 is an **ops + frontend-only** patch release — no backend rebuild, no Flyway migrations, no compose-file change. Ships the warroom-accepted carryover from `project_v055_1_backlog.md`: T1 (`screen-reader.spec.ts:65` scope-fix to a new `role="region"` results landmark on the outreach page + new `search.resultsRegion` i18n key), T2 (`wcag-vpat-verification.spec.ts:187` split into 3 per-page contrast tests), O1 (new `HmisPushContractTest` asserting hold-attribution PII absence in HMIS push outbox; backend test only), H2 (Javadoc tightening on the O1 reflection check), D2 (AI-synthetic Spanish review of the 5 v0.55.0 reentry es.json keys — 3 of 5 keys revised; **NOT a real native-Spanish-speaker review** per `feedback_truthfulness_above_all` — see Truthfulness disclosure section below), D3 (`demo/capture.sh` enumeration + nginx-default), S1 (3 dark-mode screenshot re-captures: `dark-search.png` / `dark-admin.png` / `dark-coordinator.png`), and S2 (`demo/dvindex.html` walkthrough cards converted from `<div class="card">` to `<ol class="walkthrough-steps" role="list"> ... <li class="card">` per W3C/MDN guidance for numbered procedural steps; warroom B1). Validation green: 1444/1444 backend tests, 470/470 Playwright (excluding 6 documented pre-existing flakes in `project_v055_1_backlog.md`), frontend `npm run build` clean.

**Truthfulness disclosure:** the Spanish translation review on the 5 v0.55.0 reentry keys was performed by AI (Claude playing the Maria persona, with web-search-grounded linguistic research and per-key citations), NOT by a native speaker. The original v0.55.0 commitment was a real-native-reviewer pass; this softening was operator-accepted on 2026-05-01 (warroom Q1). A real-native-reviewer pass remains a future option. See `openspec/changes/v0-55-1-followup/audit/synthetic-maria-pass.md` (in docs repo on `feature/v0-55-1-followup` post-merge).

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: every container name, port, and image tag in this runbook
    # ground-truthed via `docker ps` / `docker images` against the live VM
    # state captured 2026-04-30 post-v0.55.0 deploy (per
    # project_live_deployment_status.md). v0.55.1 is ops-only — no
    # container-name or image-tag change.
  - file: feedback_runbook_compose_chain.md
    # why-cited: prod uses a 5-FILE compose chain (UNCHANGED from v0.55.0).
    # v0.55.1 introduces no new compose override; the same 5 files apply.
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit `docker build --no-cache` BEFORE
    # `compose up --force-recreate frontend`. Frontend rebuild only;
    # backend image stays at v0.55.0.
  - file: feedback_deploy_old_jars.md
    not-applicable: no backend rebuild in v0.55.1; the v0.55.0 JAR continues to run.
  - file: feedback_runbook_template_v1.md
    # why-cited: this runbook follows the v1 template structure. Ops-only
    # release class — `/api/v1/version` correctly continues to report
    # `"0.55"` post-deploy because the backend JAR is unchanged.
  - file: feedback_release_after_scans.md
    # why-cited: tag + GitHub release published only after CI scans green.
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke uses FABT_BASE_URL +
    # `--config=deploy/playwright.config.ts` + `post-deploy-smoke`
    # positional. The smoke spec defaults to localhost without that
    # override.
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: never cat/grep `~/fabt-secrets/.env.prod` or any rendered
    # secret during deploy. Use structural checks only.
  - file: feedback_no_ssh_tunnels.md
    # why-cited: this runbook shares SSH commands the operator runs.
    # v0.55.1 surfaces no new platform-operator paths.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: no compose-file edit, no prometheus/alertmanager config
    # change. No force-recreate beyond `frontend` is needed this release.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: every command path, container name, and image tag below
    # ground-truthed against `project_live_deployment_status.md` +
    # `oracle-update-notes-v0.55.0.md`.
  - file: feedback_stale_sw_on_deploy.md
    # why-cited: post-deploy frontend testing must be incognito or
    # site-data-cleared — old service worker will serve cached JS even
    # after the new bundle ships.
  - file: feedback_periodic_resume_save.md
    # why-cited: post-deploy memory updates per §8.
  - file: feedback_truthfulness_above_all.md
    # why-cited: AI-synthetic Spanish review disclosure is verbose at every
    # surface (this runbook's What's-New section, the audit doc header, the
    # archive marker text, the v0.55.0 archive marker update, and the
    # CHANGELOG `### Truthfulness disclosure` section).
  - file: feedback_persona_transparency.md
    # why-cited: marker text in archives + commit messages + this runbook
    # never says "Maria-reviewed" — always "AI-synthetic-linguistic-review".
  - file: project_live_deployment_status.md
    # why-cited: ground-truth source. v0.55.0 currently live; Flyway HWM
    # V95; 5-file compose chain; `fabt-pgaudit:v0.45.0` postgres image
    # unchanged. v0.55.1 inherits all of this without modification.
  - file: project_v055_1_backlog.md
    # why-cited: defines the v0.55.1 slice scope (T1+T2+O1+D2+H2+D3+S1+S2)
    # and the explicitly-deferred items (D1+D4+D5+O2+seed-reentry-shelters
    # +pre-existing flake long-tail).
  - file: reference_es_json_ai_synthetic_reviewed.md
    # why-cited: AI-synthetic Spanish review pointer + disclosure
    # conventions used throughout this runbook.
  - file: project_seed_reentry_shelters_gap.md
    # why-cited: `--fresh` wipes V95+V96 reentry shelters; manual replay
    # required after dev-start.sh --fresh. v0.55.1 does NOT fix this
    # (deferred per warroom Q5); the workaround stays operative.
  - file: feedback_check_ports_before_assuming.md
    # why-cited: post-deploy frontend smoke must use port 8081 (nginx) in
    # local rehearsal and the public URL via Cloudflare for prod smoke.
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.55.1 — CI hygiene + i18n review + a11y polish patch on top of v0.55.0.

**From:** `v0.55.0` live at `findabed.org`. Confirm current via:

```bash
curl -s https://findabed.org/api/v1/version
# Expected pre-deploy: {"version":"0.55"}
```

> **Version-format note:** `VersionController.java` strips the patch
> version. `0.55.0` reports as `"0.55"` and `0.55.1` will ALSO report
> `"0.55"` because **the backend JAR is unchanged in v0.55.1** (no
> backend rebuild — see Section 3). This is correct ops-only-release
> behavior per `feedback_runbook_template_v1.md`. Do NOT treat the
> unchanged version response as a deploy failure.

**To:** `v0.55.1` — same backend JAR (unchanged from v0.55.0), Flyway HWM unchanged at **V96** (`seed_third_reentry_shelter_east`, the last v0.55.0 migration — confirmed via `psql` against the live VM 2026-05-01), frontend bundle gains the T1 `role="region"` landmark + 1 new i18n key (`search.resultsRegion`) + 3 revised Spanish keys (`hold.help.clientDob`, `hold.help.notes`, `shelter.eligibility.notes.help`). Static-content site gains the S2 `<ol>/<li>` semantic-markup fix on `demo/dvindex.html` + 3 re-captured dark-mode screenshots.

**Migrations in this deploy:** NONE. Flyway HWM stays at V96 (the last applied migration was V96 in v0.55.0, NOT V95 as some stale v0.55.0 doc references suggest — see `44ba7c8 docs(changelog): v0.55.0 release date + V90->V96 ground-truth correction`).

**No new endpoints.**

**What does NOT change in this deploy:**

- Backend Java code — **no production-code change**. T2 + O1 + H2 are test-only changes that ride CI; they do NOT alter the running JAR. The v0.55.0 JAR continues to run in production.
- `pom.xml` version — stays at `0.55.0` (matches the unchanged production JAR). The git tag `v0.55.1` is the deploy reference, NOT a JAR-version bump. Conscious choice per ops-only-release pattern; documented here so reviewers see it.
- Flyway schema — NO new migrations. HWM stays at **V96** (`seed_third_reentry_shelter_east`).
- FORCE RLS posture — unchanged.
- Tenant JWT issuer + claims — unchanged.
- Postgres / pgaudit (`fabt-pgaudit:v0.45.0`) — unchanged.
- Prometheus rule files + alertmanager templates — unchanged.
- Container names — unchanged from v0.55.0.
- Compose file chain — same 5 files; no new override.
- Platform-operator surface (`/platform/*`) — no UI or API changes.

**Out of scope (deferred per warroom Q5 + design.md non-goals):**

- Seed-reentry-shelters `--fresh` fix — operator workaround documented in `project_seed_reentry_shelters_gap.md` (manual V95+V96 SQL replay after `--fresh`).
- D1 `§13.D detail-endpoint refinement`, D4 `§11.5a mobile + a11y multi-viewport`, D5 `§13.C.3 >10K-row purge load test`, O2 `§13.A.4 audit-attribution refinement` — all explicitly out of scope per design.md non-goals.
- 7-test pre-existing flake long-tail — triage in a separate v0.55.x stabilization slice if any block CI gating; v0.55.1 surfaces 6 of these in the validation pass and confirms none are regressions.
- Real-native-Spanish-reviewer pass on the 5 reentry keys — softened to AI-synthetic per warroom Q1; future option.

---

## 3. Service-Recreate Matrix

| Service (prod container_name) | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `fabt-backend` | New JAR or new migrations | **No** | **No** |
| `fabt-frontend` | New bundle (T1 results-region landmark + new i18n key + 3 revised es.json keys) | **Yes** | **Yes** |
| `finding-a-bed-tonight-postgres-1` | `fabt-pgaudit:v0.45.0` image unchanged; pgaudit.conf unchanged | No | No |
| `finding-a-bed-tonight-prometheus-1` | No new rules file; no compose-file edit; no inode change | No | No |
| `finding-a-bed-tonight-alertmanager-1` | No new template; no rendered-secret change | No | No |
| `finding-a-bed-tonight-{grafana,jaeger,otel-collector}-1` | No dashboard / collector / image change | No | No |
| Host `nginx` | No `/etc/nginx/sites-available/fabt` edit | No | No |

> **Service-recreate scope note:** ONLY `fabt-frontend` is recreated in v0.55.1. The v0.49 issue ("recreating backend without frontend leaves docker-network stale, host nginx serves 502s") does NOT apply here because the inverse — recreating frontend without backend — does not break docker-network connectivity (the frontend image's nginx config points to `backend:8080` in the docker-network, and recreating frontend just rebinds that connection cleanly).

> **Container-name rule:** `fabt-backend` and `fabt-frontend` carry custom `container_name:` directives in `~/fabt-secrets/docker-compose.prod.yml` (out-of-repo). Postgres and the observability stack use default `<project>-<service>-<replica>` naming. Verified via `docker ps` post-v0.55.0 deploy.

---

## 4. Pre-Deploy Gates

- [ ] **Backend tests green** — `cd backend && mvn -B test -q` — expect **1444/1444 passing locally** (post-v0.55.1 baseline; includes the new `HmisPushContractTest` 4 tests + Javadoc-tightened reflection check). Validation pass 2026-05-01 already confirmed BUILD SUCCESS.
- [ ] **Frontend build clean** — `cd frontend && npm run build` — expect ✓ built (no missing-i18n-key compile errors). Verified 2026-05-01.
- [ ] **i18n key parity** — `cd frontend && npx vitest run src/i18n/i18n-coverage.test.ts` — 3/3 pass. Verified 2026-05-01.
- [ ] **Full Playwright suite green vs nginx** — `cd e2e/playwright && BASE_URL=http://localhost:8081 npx playwright test --reporter=list 2>&1 | tee logs/v055-1-playwright-pre-deploy-$(date +%Y%m%d-%H%M%S).log` — expect 470 pass + 2 skip. The 6 known pre-existing flakes (`capture-offline-screenshots`, `dv-outreach-worker:88`, `dv-referral:83`, `observability:42+83`, `persistent-notifications:497`) are documented in `project_v055_1_backlog.md` "Test-suite long-tail" section and are NOT regressions of v0.55.1 work. Verified 2026-05-01.
- [ ] **CI green** — `gh run list --branch main --limit 5` — recent runs all green. PR #171 (code) + PR #9 (docs) merged 2026-05-01 with green CI.
- [ ] **AI-synthetic Spanish review audit doc complete** — confirm `docs/audits/2026-05-01-v0-55-1-spanish-review/synthetic-maria-pass.md` exists in docs repo (committed via `eca89db` on the merged docs PR). Doc opens with verbose AI-synthetic disclosure header per warroom B1.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` returns NO output. (v0.49 issue #1.) Unchanged from v0.55.0; spot-check still required.
- [ ] **No new bind-mount files** — v0.55.1 introduces no new files in `deploy/prometheus/` or `deploy/alertmanager/`. Confirm with `git diff v0.55.0..v0.55.1 -- deploy/` — should show no files.
- [ ] **Compose dry-render (5-file chain unchanged)** — confirm zero compose changes from v0.55.0:

  ```bash
  COMPOSE_CHAIN=(
      -f docker-compose.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
  )
  docker compose "${COMPOSE_CHAIN[@]}" --env-file ~/fabt-secrets/.env.prod \
      config > /tmp/v0.55.1-config.rendered.yml
  diff /tmp/v0.55.1-config.rendered.yml /tmp/v0.55.0-config.rendered.yml
  ```

  **Expected diff: ZERO.** Any non-empty diff means a compose file changed unexpectedly between v0.55.0 and v0.55.1; investigate before proceeding.

- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.55.1-$(date -u +%Y%m%d-%H%M%S).dump`. The `-Fc` custom format is restored via `pg_restore`, NOT `psql`. (Even though no migrations run in v0.55.1, the backup is a deploy-discipline gate — not a release-class one.)
- [ ] **Git tag + GitHub release published** — `git tag v0.55.1 && git push origin v0.55.1`, then `gh release create v0.55.1 --generate-notes`. Verify with `gh release view v0.55.1`. The deploy MUST checkout the tag, not main HEAD.
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting (`ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}`). Do not assume reachability mid-deploy.
- [ ] **Local rehearsal PASS** — run `make rehearse-deploy` (or equivalent) and confirm dry-run end-to-end completes. Required per `feedback_deploy_rehearsal_lessons.md`. Schedule before tagging.

---

## 5. Deploy Steps

> **Canonical 5-file compose chain** (unchanged from v0.55.0). Define once at the top of the operator session:
>
> ```bash
> COMPOSE_CHAIN=(
>     -f docker-compose.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
> )
> # Use as `docker compose "${COMPOSE_CHAIN[@]}" ...`
> ```

### 5.0. Static content (docs site) — ship S2 + S1 FIRST

Static content is served from `/var/www/findabed-docs/` on the Oracle VM. Nginx serves these via `try_files` — no restart needed after copying. Static deploys safely before the frontend swap because the new HTML/PNG is content-only (no API contract change).

v0.55.1 ships **4 static files**: 1 HTML (`demo/dvindex.html` — S2 semantic-markup fix) + 3 PNG (`dark-search.png` / `dark-admin.png` / `dark-coordinator.png` — S1 re-captures).

```bash
# From your local Windows / Git Bash machine. FABT_VM_IP is set
# out-of-band per feedback_no_ip_in_repo (it lives in memory + the
# operator's local env, never in git).
cd /c/Development/findABed

# 1. dvindex.html (S2: cards converted to <ol>/<li>)
scp -i ~/.ssh/fabt-oracle \
  demo/dvindex.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/

# 2. 3 dark-mode re-captures (S1)
scp -i ~/.ssh/fabt-oracle \
  demo/screenshots/dark-search.png \
  demo/screenshots/dark-admin.png \
  demo/screenshots/dark-coordinator.png \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/screenshots/
```

**Note:** `dark-login.png` was NOT regenerated in v0.55.1 (per `project_v055_1_backlog.md` S1 scope — the file is functionally identical to `dark-admin.png` per the `color-system.spec.ts:113` comment). Do NOT scp `dark-login.png`.

### 5.1. Preserve last-good frontend image tag

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}
docker tag fabt-frontend:latest fabt-frontend:v0.55.0-lastgood
docker images | grep fabt-frontend
# Confirm: latest + v0.55.0-lastgood both present; IMAGE IDs match.
# Backend image is NOT retagged — v0.55.1 does not rebuild backend.
```

### 5.2. Checkout v0.55.1 tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.55.1
git log --oneline -1
# Confirm: HEAD is the tagged commit (detached HEAD is expected).
```

> **Do NOT `git pull origin main`.** The deployed commit must equal the tag for audit traceability.

### 5.3. Verify no migrations + no Dockerfile change

```bash
# Migrations — confirm NO new V97+ files vs v0.55.0:
ls backend/src/main/resources/db/migration/ | tail -10
# Expected most recent (post-v0.55.0): V94, V95, V96. No V97+ in v0.55.1.

# Dockerfile — confirm unchanged from v0.55.0:
git diff v0.55.0 v0.55.1 -- infra/docker/Dockerfile.frontend
# Expected: no diff (frontend Dockerfile unchanged; the bundle content
# changed, but the build instructions did not).

git diff v0.55.0 v0.55.1 -- infra/docker/Dockerfile.backend
# Expected: no diff (backend Dockerfile unchanged AND not rebuilt).
```

### 5.4. Frontend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight/frontend
npm ci
npm run build
ls -lh dist/assets/index-*.js | tail -3
# Confirm: new chunk hash (different from v0.55.0's index-<hash>.js).
# The hash changes because the bundle includes the T1 results-region
# landmark + 1 new i18n key + 3 revised es.json strings.

cd ~/finding-a-bed-tonight
docker build --no-cache \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.55.1 \
    -t fabt-frontend:latest \
    .
docker images | grep fabt-frontend
# Confirm: v0.55.1 + latest + v0.55.0-lastgood all present.
```

### 5.5. Bring up frontend ONLY (force-recreate)

```bash
docker compose "${COMPOSE_CHAIN[@]}" \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    up -d --force-recreate frontend
docker ps | grep fabt-frontend
# Confirm: container restarted with the v0.55.1 image (check the IMAGE
# column shows fabt-frontend:latest with the new IMAGE ID).
```

> **Do NOT `--force-recreate backend`.** v0.55.1 does not rebuild the backend; the running JAR is the v0.55.0 build and stays running.

### 5.6. Wait for frontend readiness

```bash
# Frontend health: container reports healthy + nginx 80 responds:
docker exec fabt-frontend curl -fsS http://localhost/ -o /dev/null -w "%{http_code}\n"
# Expect: 200

# Backend connectivity from frontend container (smoke):
docker exec fabt-frontend curl -fsS http://backend:8080/api/v1/version
# Expect: {"version":"0.55"} — UNCHANGED, because backend JAR is still
# v0.55.0. This is the correct ops-only-release behavior.
```

### 5.7. Cloudflare Purge

```bash
# Purge Everything via Cloudflare dashboard, OR via API:
# curl -X POST "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/purge_cache" \
#   -H "Authorization: Bearer ${CF_API_TOKEN}" \
#   -H "Content-Type: application/json" \
#   --data '{"purge_everything":true}'
# Wait ~1-2 min for refill.
```

---

## 6. Post-Deploy Gates

### Mandatory smoke gate

```bash
cd e2e/playwright && FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.55.1.log
```

Expected: **15/15 GREEN** (same suite as v0.55.0; no new test contract in v0.55.1).

### Version check (UNCHANGED)

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.55"} — UNCHANGED from pre-deploy.
```

| Release class | Expected value |
|---|---|
| Ops-only release (no backend rebuild — v0.55.1) | `{"version":"0.55"}` UNCHANGED |

This is the documented expected behavior per `feedback_runbook_template_v1.md`. The repo tag is newer than the backend binary version. **Do NOT treat as a deploy failure.**

### Frontend bundle hash check

```bash
# The new index.js chunk should have a different hash than pre-deploy:
curl -s https://findabed.org/ | grep -oE 'index-[A-Za-z0-9_-]+\.js' | head -3
# Compare to the v0.55.0 hash (recorded in post-v0.55.0 deploy notes).
# Different hash = new bundle deployed. Same hash = Cloudflare not purged
# (re-run §5.7), or frontend container did not actually recreate.
```

### Flyway HWM (UNCHANGED)

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# Expected (UNCHANGED from v0.55.0 — verified via SSH 2026-05-01):
#   96|seed third reentry shelter east|t
#   95|seed reentry demo shelters east west|t
#   94|shelter requires verification call|t
# v0.55.1 introduces no new migrations; HWM stays at V96.
```

### Static-content (docs site) verification

```bash
# 1. dvindex.html: confirm S2 fix landed (article→li conversion)
curl -s https://findabed.org/demo/dvindex.html | grep -c '<li class="card">'
# Expected: 7 (the 7 walkthrough cards).
curl -s https://findabed.org/demo/dvindex.html | grep -c '<article class="card">'
# Expected: 0 (the previous markup is gone).
curl -s https://findabed.org/demo/dvindex.html | grep -c 'walkthrough-steps'
# Expected: 3 (the 3 <ol class="walkthrough-steps"> wrappers).

# 2. Dark-mode PNGs: confirm new file timestamps
curl -sI https://findabed.org/demo/screenshots/dark-search.png | grep -i last-modified
curl -sI https://findabed.org/demo/screenshots/dark-admin.png | grep -i last-modified
curl -sI https://findabed.org/demo/screenshots/dark-coordinator.png | grep -i last-modified
# Expected: Last-Modified within ~2 hours of deploy time.
```

### T1 results-region landmark renders (manual)

Open `https://findabed.org/outreach` in an incognito browser (per `feedback_stale_sw_on_deploy.md`). Inspect the DOM around the shelter result cards:

- A wrapping `<div role="region" aria-label="Search results" data-testid="search-results-region">` should be present.
- Confirm via the browser inspector: the wrapping element has `role="region"`, the aria-label is `"Search results"` (English locale) or `"Resultados de búsqueda"` (Spanish locale).

### Spanish-locale spot-check (manual — validates D2)

Open `https://findabed.org/outreach` in incognito, log in (any role), then:

1. Click the language dropdown (`<select aria-label="Select language">`) and choose `es` (Español).
2. Open the bed-hold dialog by clicking "Hold This Bed" on any non-DV shelter.
3. Confirm the 3 revised Spanish strings render correctly:
   - `hold.help.clientDob` (DOB help text): "El refugio lo usa **únicamente** para confirmar a la persona correcta..." (NOT "**solo**")
   - `hold.help.notes` (notes help text): "...un número de teléfono del **navegador de servicios**..." (NOT bare "**del navegador**")
4. Open the shelter form (admin only — Edit existing shelter):
   - `shelter.eligibility.notes.help` (eligibility help text): "...los **trabajadores de alcance comunitario**..." (NOT "**trabajadores de extensión**")
5. No truncation, no overflow on any of the revised strings.

### AI-synthetic disclosure verification

Confirm the v0.55.0 reentry-release-readiness archive marker was updated:

```bash
grep -A1 "AI-SYNTHETIC-LINGUISTIC-REVIEW" \
  openspec/changes/archive/2026-05-01-reentry-release-readiness/tasks.md
# Expected: line 81 reads "AI-SYNTHETIC-LINGUISTIC-REVIEW (Claude with
# web-citation grounding, NOT a native speaker) — see ..."
# (NOT "NATIVE-REVIEWER-PENDING" any longer.)
```

### Stale-SW reminder

Per `feedback_stale_sw_on_deploy.md`: post-deploy frontend testing must be incognito or site-data-cleared. Old service workers will serve cached JS even after the new bundle ships.

---

## 7. Rollback Matrix

| Symptom | Action |
|---|---|
| Frontend serving stale JS after deploy | Old service worker cached. Use incognito or clear site data. (`feedback_stale_sw_on_deploy.md`) |
| `<article class="card">` still rendered on dvindex.html | Static-content scp didn't land. Re-run §5.0 then re-purge Cloudflare (§5.7). |
| Dark-mode PNGs unchanged (Last-Modified is pre-deploy) | Static-content scp didn't land OR Cloudflare cache not purged. Re-run §5.0 + §5.7. |
| Spanish revisions don't render in `?lang=es` (still showing `solo` / `del navegador` / `trabajadores de extensión`) | Frontend container didn't recreate OR Cloudflare cache not purged. Verify `docker ps` shows fabt-frontend with the new IMAGE ID; if not, re-run §5.5. If yes, re-purge Cloudflare. |
| New bundle hash matches pre-deploy hash | Frontend rebuild didn't produce a new artifact (npm cache hit?), OR force-recreate didn't pull the new image. Confirm `npm run build` output mentioned a different chunk hash; if not, run with `--clean` or delete `frontend/dist/` and rebuild. |
| `/api/v1/version` returns something OTHER than `"0.55"` | Backend was rebuilt unintentionally OR the v0.55.0 image was tagged over. Investigate before proceeding. |
| Full rollback needed | `docker tag fabt-frontend:v0.55.0-lastgood fabt-frontend:latest && docker compose ... up -d --force-recreate frontend`. Re-purge Cloudflare. Static-content rollback: scp the pre-v0.55.1 `dvindex.html` + `dark-*.png` from the v0.55.0 git tag back over. |

---

## 8. Post-Deploy Housekeeping

- [ ] **Update `project_live_deployment_status.md`** memory — append v0.55.1 deploy date + git ref + Flyway HWM (UNCHANGED at V95) + container list (only fabt-frontend recreated). Note the ops-only-release class.
- [ ] **Update `project_resume_point.md`** memory — current state post-v0.55.1.
- [ ] **Mark v0.55.1 items RESOLVED in `project_v055_1_backlog.md`** — T1, T2, O1, D2, H2, D3, S1, S2 all shipped. Leave deferred items (D1, D4, D5, O2, seed-reentry-shelters, 7-flake long-tail) in the backlog with their reasons.
- [ ] **Update `CHANGELOG.md`** — add v0.55.1 section with the standard subsections (Tests, Localization, Documentation, Accessibility).
- [ ] **NEW `### Truthfulness disclosure` CHANGELOG section** — add a top-level subsection under v0.55.1 (NOT folded into "Localization") with the verbose AI-synthetic disclosure:

  > Spanish translation review on v0.55.0 reentry keys was performed by AI (Claude playing the Maria persona, with web-search-grounded linguistic research and per-key citations), NOT by a native speaker. The original commitment was a real-native-reviewer pass; this softening was operator-accepted on 2026-05-01 (warroom Q1). A real-native-reviewer pass remains a future option. See audit doc at `openspec/changes/archive/2026-05-XX-v0-55-1-followup/audit/synthetic-maria-pass.md` (path resolves once v0.55.1 archives) for the methodology + citations + per-key recommendations.

- [ ] **Archive the v0-55-1-followup OpenSpec change** — `cd /c/Development/findABed && openspec archive v0-55-1-followup --date $(date -u +%Y-%m-%d)`. Will move to `openspec/changes/archive/2026-05-XX-v0-55-1-followup/`. Update the `synthetic-maria-pass.md` cross-reference paths in CHANGELOG + memory + the v0.55.0 archive marker after archive.
- [ ] **Update `reference_es_json_ai_synthetic_reviewed.md` memory** — replace the temporary path `docs/audits/2026-05-01-v0-55-1-spanish-review/synthetic-maria-pass.md` with the post-archive path `openspec/changes/archive/2026-05-XX-v0-55-1-followup/audit/synthetic-maria-pass.md`.
- [ ] **Verify the `reference_es_json_ai_synthetic_reviewed.md` 1-flagged-item still surfaces** — `hold.help.notes` `navegador de servicios` is the only future-real-native-reviewer item. Confirms the audit-trail discipline.
- [ ] **Delete/expire any test alerts fired during deploy verification** — none expected for v0.55.1 (no new alert rules).
- [ ] **Per `feedback_periodic_resume_save.md`**: update relevant `project_*_resume_point.md` memory files.

---

## Related artifacts

- **OpenSpec change:** `openspec/changes/v0-55-1-followup/` (in docs repo) — proposal + design + specs + tasks. Will move to `archive/2026-05-XX-v0-55-1-followup/` post-§8 archive.
- **Spec-warroom audit:** `docs/audits/2026-05-01-v0-55-1-followup-spec-warroom/warroom.md` — pre-implementation review (B1+B2+H1-H4).
- **Implementation-warroom audit:** `docs/audits/2026-05-01-v0-55-1-implementation-warroom/warroom.md` — post-implementation review with web-research grounding (B1+H1-H3 caught + fixed).
- **AI-synthetic Spanish review:** `docs/audits/2026-05-01-v0-55-1-spanish-review/synthetic-maria-pass.md` — per-key 8-dimension analysis with citations.
- **PR #171** (code repo): https://github.com/ccradle/finding-a-bed-tonight/pull/171 (merged 2026-05-01 19:45 UTC).
- **PR #9** (docs repo): https://github.com/ccradle/findABed/pull/9 (merged 2026-05-01 19:42 UTC).
- **Validation logs:** `logs/v055-1-backend-20260501-*.log` (1444/1444 backend pass) + `logs/v055-1-playwright-20260501-*.log` (470/470 + 6 documented flakes).
