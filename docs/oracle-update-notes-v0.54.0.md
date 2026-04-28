# Oracle Deploy Notes — v0.54.0 (F11 platform-operator UI)

**Status:** draft pending CI green + tag.
**Template version:** v1 (per `docs/runbook-template.md`).
**OpenSpec change:** `platform-operator-ui` (in the docs repo).

---

## What's new in v0.54.0 (one-paragraph summary)

F11 ships the platform-operator SPA at `/platform/*` (login, MFA enroll,
MFA verify, dashboard) plus two narrow backend additions
(`GET /api/v1/auth/platform/me` + `POST /api/v1/auth/platform/logout`)
and the V90 SECURITY DEFINER function family. Operators previously
authenticated via curl against the v0.53 `/login` + `/login/mfa-verify`
endpoints; v0.54 lets them complete the same flow in a browser. The
SPA is gated by a build-time flag (`VITE_PLATFORM_UI_ENABLED`) so the
deploy is **two-stage**: Stage A redeploys with flag-off (zero
behavior change relative to v0.53 for tenant operators) and Stage B
re-redeploys with flag-on (activates `/platform/*` routes in the
running bundle). Stage B is reversible by repeating Stage A — no DB
or backend rollback required.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: v0.53 runbook had 6 wrong references mirrored from
    # dev-start.sh local naming (5x fabt-postgres, 1x JAR path).
    # ALL container names + JAR paths in this runbook ground-truthed
    # against project_live_deployment_status.md (v0.53 deploy audit).
  - file: feedback_runbook_compose_chain.md
    # why-cited: prod uses a 5-FILE compose chain (NOT 4). Missing
    # any one file breaks deploy (postgres crash loop on v0.50 was
    # the lesson).
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit `docker build --no-cache` + `--force-recreate`.
    # F11 ships TWO frontend rebuilds (one per stage); both follow
    # the canonical pattern.
  - file: feedback_deploy_old_jars.md
    # why-cited: `mvn clean` mandatory before backend `docker build`.
  - file: feedback_runbook_template_v1.md
    # why-cited: this runbook follows the v1 template structure.
  - file: feedback_platform_login_via_ssh_tunnel.md
    # why-cited: `/platform/*` access in prod via SSH tunnel; demoguard
    # bypass details MUST NOT appear in this committed runbook (op-sec).
  - file: feedback_release_after_scans.md
    # why-cited: tag + GitHub release published only after CI scans
    # green (CodeQL + npm audit + dependency-check).
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke uses FABT_BASE_URL +
    # deploy/playwright.config.ts + `post-deploy-smoke` positional.
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: never cat/grep .env.prod or alertmanager.yml.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: f11-platform-operator-ui.rules.yml is a NEW file in
    # the rules-volume-mounted directory — Prometheus inode rules
    # apply if the directory is git-checkout-replaced.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: ALL alert names + counter names + version-format
    # claims grep-verified against PlatformAuthService.java,
    # f11-platform-operator-ui.rules.yml, and VersionController.java.
  - file: project_live_deployment_status.md
    # why-cited: ground-truth source. v0.53.0 live; Flyway HWM 89;
    # 5-file compose chain; container names; JAR path; no jq on VM.
  - file: feedback_stale_sw_on_deploy.md
    # why-cited: post-Stage-B SPA testing must be in incognito or
    # site-data-cleared — old service worker will serve cached JS.
  - file: feedback_periodic_resume_save.md
    # why-cited: post-deploy memory updates.
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.54.0 — F11 platform-operator SPA + GET /me + POST /logout + V90 migration + new MFA-verify outcome counter + 2 new Prometheus alert rules.

**From:** `v0.53.0` live at `findabed.org`. Confirm current via:

```bash
curl -s https://findabed.org/api/v1/version
# Expected pre-deploy: {"version":"0.53"}
```

> **Version-format note:** `VersionController.java:27` deliberately
> strips the patch version — `0.53.0` is reported as `"0.53"` and
> `0.54.0` will report as `"0.54"`. Do NOT expect a literal
> `"0.54.0"` in the response body.

**To:** `v0.54.0` — backend JAR `0.53.0 → 0.54.0`, Flyway HWM `V89 → V90`, frontend bundle gains 6 platform-* chunks (only when `VITE_PLATFORM_UI_ENABLED=true` at build time).

**What does NOT change in this deploy:**

- Tenant-side routes (`/login`, `/coordinator/*`, `/outreach/*`, `/admin/*`) — unchanged.
- Tenant JWT issuer + claims — unchanged.
- Postgres / pgaudit — no restart, no config change.
- FORCE RLS posture — unchanged.
- Existing Prometheus rule files (`phase-g-platform-admin.rules.yml`, etc.) — unchanged. The new `f11-platform-operator-ui.rules.yml` is a NEW file mounted alongside.
- Alertmanager routing — unchanged. The 2 new F11 alerts use existing severity-based routes (`severity=warning` / `severity=critical`).
- Container names — unchanged from v0.53 (`fabt-backend`, `fabt-frontend`, `finding-a-bed-tonight-postgres-1`, `finding-a-bed-tonight-prometheus-1`, etc.).

---

## 3. Service-Recreate Matrix

| Service (prod container_name) | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `fabt-backend` + `fabt-frontend` | Backend image rebuild (V90 migration + new endpoints + counter) | ☑ | ☑ |
| `fabt-frontend` (Stage B re-rebuild) | Second frontend image rebuild with `VITE_PLATFORM_UI_ENABLED=true` | ☑ | ☑ |
| `finding-a-bed-tonight-prometheus-1` | New `f11-platform-operator-ui.rules.yml` lands in the rules-volume directory `./deploy/prometheus`. Bind-mount inode rules apply if `git checkout` swaps the directory contents. | ☑ | ☑ |
| `finding-a-bed-tonight-alertmanager-1` | No new template / no rendered-secret change | ☐ | ☐ |
| `finding-a-bed-tonight-postgres-1` | No `pgaudit.conf` change | ☐ | ☐ |
| Host `nginx` | No `/etc/nginx/sites-available/fabt` edit; CSP is unchanged at `infra/docker/nginx.conf:12,107` (container-side, not host-side) | ☐ | ☐ |

> **Container-name rule:** `fabt-backend` and `fabt-frontend` carry custom `container_name:` directives in `~/fabt-secrets/docker-compose.prod.yml` (out-of-repo). Postgres and the observability stack use default `<project>-<service>-<replica>` naming. v0.53 deploy audit caught 6 references mirrored from dev-start naming — see memory `feedback_runbook_groundtruth_vm.md`.

---

## 4. Pre-Deploy Gates

- [ ] **pom.xml version bumped** — `cd backend && grep -nE "<version>0\." pom.xml | head -1` should report **0.54.0** at line 16. Pre-tag prerequisite. If still 0.53.0, edit `backend/pom.xml` line 16 (`<version>0.53.0</version>` → `<version>0.54.0</version>`), commit on the feature branch, and re-run `mvn -B -DskipTests clean package -q` to confirm the JAR filename is now `*0.54.0*.jar`. The `org.springframework.boot:spring-boot-maven-plugin` writes the version into `META-INF/build-info.properties` at package time, which is what `/api/v1/version` reads at runtime.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` returns NO output.
- [ ] **VITE_PLATFORM_UI_ENABLED knowable** — Stage A builds with `--build-arg VITE_PLATFORM_UI_ENABLED=false`; Stage B builds with `=true`. The build-time env-var becomes a string literal in the JS bundle (Rollup tree-shake eliminates platform chunks when false). The frontend `.env` file is gitignored — both stages explicitly set the flag at the build invocation.
- [ ] **mvn clean** — `cd ~/finding-a-bed-tonight && mvn -B -DskipTests clean package -q`; verify exactly 1 JAR in `backend/target/` with `0.54.0` in the filename. `feedback_deploy_old_jars.md`.
- [ ] **Container UID vs perms** — no new bind-mounted files in this deploy. Spot-check `deploy/prometheus/f11-platform-operator-ui.rules.yml` is `chmod 644` after `git checkout v0.54.0`.
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.54.0-$(date -u +%Y%m%d-%H%M%S).dump`.
- [ ] **CI green** — `gh run list --branch main --limit 3` — all runs green. `feedback_release_after_scans.md`.
- [ ] **Git tag + GitHub release published** — `git tag v0.54.0 && git push origin v0.54.0`, then `gh release create v0.54.0 --generate-notes`. Verify with `gh release view v0.54.0`.
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting (`ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}` per memory). Do not assume reachability mid-deploy.
- [ ] **Compose dry-render (5-file chain)** — see Section 5 ("Deploy Steps") below for the canonical chain. Run `docker compose <FULL_5-FILE_CHAIN> --env-file ~/fabt-secrets/.env.prod config > /tmp/v0.54.0-config.rendered.yml` — diff against the v0.53 render. **Expected delta from compose-config: ZERO** (no compose file changed in v0.54 — the only filesystem change is the new `deploy/prometheus/f11-platform-operator-ui.rules.yml` file, which lives inside an existing bind-mounted directory and does NOT show up in `docker compose config` output). The compose-render diff being empty is the correct gate — anything else means a compose file changed unexpectedly.
- [ ] **Mocked Playwright suite green locally** — `cd e2e/playwright && BASE_URL=http://localhost:8081 npx playwright test platform-ui-banner.spec.ts platform-ui-routing.spec.ts platform-ui-mfa-verify-errors.spec.ts platform-ui-dashboard.spec.ts platform-ui-a11y-csp.spec.ts capture-platform-operator-screenshots.spec.ts platform-mfa-enroll.spec.ts platform-print-codes.spec.ts --reporter=list` — expect 54/54 GREEN against `./dev-start.sh` + nginx@8081. (`platform-training-walkthrough.spec.ts` is excluded — it skips itself in CI per `test.skip(!!process.env.CI)`; run it manually with `--headed` for the rehearsal.)
- [ ] **Operator credentials sanity-check** — confirm at least one `platform_user` row with `mfa_enabled=true` AND `account_locked=false`. The Stage B post-deploy smoke needs an unlocked operator. Use heredoc-with-quoted-delimiter to avoid shell-`$` mangling per v0.53 deploy lesson 5:

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt <<'SQL'
SELECT count(*) FROM platform_user WHERE mfa_enabled=true AND account_locked=false;
SQL
# Expected: >= 1
```

---

## 5. Deploy Steps

> **Canonical 5-file compose chain** (per memory `project_live_deployment_status.md`, unchanged from v0.52). EVERY `docker compose ... up -d` invocation in this runbook MUST include all five `-f` flags. Missing any one = service crash loop (per `feedback_runbook_compose_chain.md`):
>
> ```
> docker compose \
>     -f docker-compose.yml \
>     -f ~/fabt-secrets/docker-compose.prod.yml \
>     -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
>     -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
>     -f ~/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml \
>     --env-file ~/fabt-secrets/.env.prod \
>     --profile observability \
>     ...
> ```
>
> Steps below abbreviate to `<COMPOSE>` for readability — substitute the
> full 5-file chain when running each command.

### 1. Preserve last-good image tags

```bash
docker tag fabt-backend:latest fabt-backend:v0.53-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.53-lastgood
docker images | grep -E "fabt-(backend|frontend)"
# Expected: latest + v0.53-lastgood both present (same IMAGE ID, two tags)
```

### 2. Checkout the release tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.54.0
git log --oneline -1
# Expected: HEAD on the v0.54.0 tagged commit (detached HEAD is correct)
```

> **Do NOT `git pull origin main`.** Deployed commit must equal the tag for audit traceability.

### 3. Verify the new rules file is on disk

```bash
ls -l deploy/prometheus/f11-platform-operator-ui.rules.yml
# Expected: -rw-r--r-- ... 1k+ bytes
# The rules-volume in docker-compose.yml mounts the WHOLE
# deploy/prometheus/ directory:
#   ./deploy/prometheus:/etc/prometheus/rules:ro   (line 104)
# So no compose-file edit is required to pick up the new file —
# we just need a force-recreate of prometheus to rebind the inode.
```

### 4. STAGE A — Backend rebuild + frontend rebuild WITH FLAG=FALSE

```bash
mvn -B -DskipTests clean package -q
ls -1 backend/target/*.jar | head -1
# Expected: exactly one *0.54.0*.jar

docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:v0.54.0 -t fabt-backend:latest .

# Stage A: tree-shake the platform chunks out of the bundle.
docker build --no-cache --build-arg VITE_PLATFORM_UI_ENABLED=false \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.54.0-stageA -t fabt-frontend:latest .
```

### 5. STAGE A — Bring up backend + frontend + force-recreate prometheus

```bash
docker compose <COMPOSE> up -d --force-recreate backend frontend prometheus
# COMPOSE = the full 5-file chain noted at the top of this section.
```

### 6. STAGE A — Wait for backend readiness + verify Flyway

```bash
# Internal mgmt port (NOT public URL — actuator binds to 9091
# localhost-only per v0.47 lesson 3).
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend..."; sleep 3
done
echo "backend is UP"

docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# Expected top row: V90 | platform_user_get_me_function | t
```

### 7. STAGE A — Smoke gate (proves no v0.53 regression)

> **Prod traffic topology** (helps explain the expected status codes
> below): `findabed.org` → Cloudflare → host nginx (`/etc/nginx/sites-enabled/fabt`)
> → `proxy_pass http://127.0.0.1:8081` → container nginx (`fabt-frontend`)
> → backend (`http://backend:8080`). Public requests therefore traverse
> TWO nginx layers AND a Spring `DemoGuardFilter` (active under
> `@Profile("demo")`). VM-localhost direct (`http://localhost:8080/...`)
> bypasses both nginx layers and DemoGuard's trusted-source-IP
> heuristic, hitting the backend directly — useful for proving an
> endpoint EXISTS independent of the public-traffic guards.

```bash
# Tenant /login still works. The response is the SPA shell HTML
# (the visible "Sign in" copy is rendered by React post-hydration —
# curl can't see it). Verify by status code + the shell marker
# `<div id="root"></div>` present in the static HTML.
curl -sS -o /dev/null -w "GET /login: %{http_code}\n" https://findabed.org/login
# Expected: 200
curl -sS https://findabed.org/login | grep -q '<div id="root">' && echo "tenant /login shell: OK"

# /platform/login: with flag=false the platform routes are tree-shaken
# from the bundle, so the React Router catch-all (`<Route path="*"
# element={<Navigate to="/" replace />} />` in App.tsx:203) intercepts
# at the SPA layer. The HTTP layer always returns 200 with the SPA
# shell HTML; the client-side <Navigate> then redirects the browser
# to "/". So we EXPECT 200 here, NOT 404. Verify the redirect-to-/
# in a browser as the actual proof that platform routes were tree-
# shaken (curl alone can't see the post-load <Navigate>).
curl -sS -o /dev/null -w "%{http_code}\n" https://findabed.org/platform/login
# Expected: 200 (SPA shell — client-side Navigate redirects to /)

# Backend /me on PUBLIC URL — returns 401 post-Stage-A (route exists,
# auth required). v0.53 returned 404 (endpoint did not exist).
curl -sS -o /dev/null -w "GET public /me: %{http_code}\n" \
    https://findabed.org/api/v1/auth/platform/me
# Expected: 401 (post-Stage-A; pre-Stage-A this returned 404)

# Backend /logout on PUBLIC URL — Spring's `DemoGuardFilter`
# (`backend/src/main/java/org/fabt/shared/security/DemoGuardFilter.java`,
# active under `@Profile("demo")`) blocks unauth POSTs that don't
# match its `ALLOWED_MUTATIONS` allowlist. The new `/logout` path is
# NOT on the allowlist, so the filter returns 403 with body
# `{"error":"demo_restricted"}`. This is the same response shape
# v0.53 returned (the filter is unchanged in v0.54). The 403 is the
# Spring filter's response — NOT host nginx, NOT container nginx.
# Public URL traverses TWO nginx hops (host nginx → container nginx
# at 127.0.0.1:8081 → backend) but neither nginx is the source of
# the 403. To prove the endpoint EXISTS in the new build, hit it
# from VM-localhost (bypasses BOTH nginx layers; DemoGuard's
# private-IP exemption also bypasses the filter — see
# DemoGuardFilter.java for the trusted-source-IP heuristic).
curl -sS -o /dev/null -w "POST public /logout: %{http_code}\n" \
    -X POST https://findabed.org/api/v1/auth/platform/logout
# Expected: 403 (demoguard; same as v0.53). NOT 401, NOT 404.

# VM-localhost direct (proves the backend endpoint EXISTS — bypasses
# nginx + demoguard). Run THIS via SSH to the VM:
#   ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP} \
#       'curl -sS -o /dev/null -w "%{http_code}\n" \
#        -X POST http://localhost:8080/api/v1/auth/platform/logout'
# Expected: 401 (post-Stage-A; pre-Stage-A this returned 404 from
# the backend itself — same as /me)

# Run the post-deploy Playwright smoke (existing tenant-side coverage)
cd e2e/playwright && FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.54.0-stageA.log
```

> **Stage A success criterion:** /login + /platform/login both 200, public /me 401 (was 404 in v0.53), public /logout 403 (demoguard, unchanged), VM-localhost /logout 401 (was 404 in v0.53), AND post-deploy-smoke GREEN. If any fails, **stop here** — do not proceed to Stage B. Stage A is the v0.53-equivalence check; failure means the backend/frontend rebuild has unexpected behavior.

### 8. STAGE B — Frontend rebuild WITH FLAG=TRUE

```bash
docker build --no-cache --build-arg VITE_PLATFORM_UI_ENABLED=true \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.54.0 -t fabt-frontend:latest .
```

### 9. STAGE B — Force-recreate frontend (only)

```bash
docker compose <COMPOSE> up -d --force-recreate frontend
```

> **Backend was NOT recreated** in Stage B — backend doesn't change between Stage A and Stage B. Recreating it would be unnecessary and would extend the readiness-wait window.

### 10. STAGE B — Wait for frontend readiness

```bash
# Frontend container is healthy when it serves the root document.
until curl -fsS https://findabed.org/ -o /dev/null; do
    echo "waiting for frontend..."; sleep 3
done
echo "frontend is UP"

# Verify the platform chunks are present in the dist/ output.
# Container_name on prod is `fabt-frontend` (custom; from prod
# compose override) — NOT the default `finding-a-bed-tonight-frontend-1`.
docker exec fabt-frontend ls /usr/share/nginx/html/assets/ | grep -i "Platform" | head -10
# Expected: at least 5 chunks across PlatformLogin, PlatformDashboard,
# PlatformLayout, PlatformMfaEnroll, PlatformMfaVerify (the lazy-load
# boundaries declared in App.tsx). PlatformMetadataContext may or may
# not split as a separate chunk depending on Vite's shared-module
# heuristics — don't enumerate it by name. The CI invariant in
# `frontend/scripts/verify-platform-chunk-tree-shake.sh` is `>= 5`.
```

### 11. STAGE B — SPA smoke (operator-driven via SSH tunnel)

> **The /platform/* surface is reachable only via SSH tunnel in prod.** The exact tunnel command + the demoguard bypass posture are NOT committed to this runbook (op-sec). The tunnel command is shared at deploy time via the operator's trusted out-of-band channel; see `feedback_platform_login_via_ssh_tunnel.md` memory for the rationale.

Once the tunnel is up, complete the smoke checklist:

- [ ] `https://localhost:8081/platform/login` renders the distinct heading "Platform Operator Sign-In" + tenant cross-link to `/login`.
- [ ] Submit a known operator's email + password; verify redirect to `/platform/mfa-verify`.
- [ ] Enter a fresh TOTP code; verify redirect to `/platform/dashboard`.
- [ ] Banner shows `PLATFORM OPERATOR MODE`, masked email, 14:5x countdown, Logout button.
- [ ] Backup-codes badge renders the urgency text label (`Healthy` / `Low` / `Critical`).
- [ ] Lifecycle action cards (5 cards) all render disabled-with-tooltip.
- [ ] System Status cards (System Health / Platform Version) open new tabs cleanly.
- [ ] Logout button returns to `/platform/login` and clears sessionStorage.
- [ ] Browser DevTools console shows zero CSP violations during the full flow.

> **Use incognito or clear site data.** Old service worker will serve cached JS. `feedback_stale_sw_on_deploy.md`.

---

## 6. Post-Deploy Gates

### Mandatory smoke gate

```bash
cd e2e/playwright && FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on --retries=1 post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.54.0-stageB.log
# Same suite as Stage A — re-run after the flag flip to confirm Stage B
# didn't introduce a tenant-side regression. Expected: GREEN.
```

### Version check

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.54"}  ← NOT "0.54.0" — VersionController.java:27
# strips the patch component intentionally.
```

### New counter is scrape-visible

```bash
# Run a few MFA-verify attempts via SSH tunnel (correct + wrong codes)
# to populate the new counter, then:
curl -s http://localhost:9091/actuator/prometheus | grep -E "^fabt_platform_mfa_verify_total"
# Expected: two series (one per outcome label)
#   fabt_platform_mfa_verify_total{outcome="success",...} N
#   fabt_platform_mfa_verify_total{outcome="failure",...} M
```

### New alert rules loaded

```bash
# Prometheus exposes loaded rules at /api/v1/rules. The container_name
# on prod is `finding-a-bed-tonight-prometheus-1` (default project-name
# prefix; no custom container_name in the prod overrides).
docker exec -i finding-a-bed-tonight-prometheus-1 wget -qO- http://localhost:9090/api/v1/rules \
    | grep -E "FabtPlatformMfaFailureSpike|FabtPlatformBackend5xx" \
    | head -4
# Expected: both alert names appear, severity warning + critical respectively.
# If neither appears, the rules file mount didn't take — see Rollback Matrix.
#
# (Note: `jq` is NOT installed on the VM per memory — use grep + raw
# wget output, or `python -m json.tool` if structured viewing is needed.)
```

### Flyway HWM

```bash
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# Expected top row: V90 | platform_user_get_me_function | t
```

### Tree-shake correctness verification

```bash
# Stage A (flag=false) bundle should NOT contain platform chunks.
# Stage B (flag=true) bundle SHOULD. Verify by re-checking the running
# container's dist/ output. Container_name = `fabt-frontend` per prod
# compose override.
docker exec fabt-frontend ls /usr/share/nginx/html/assets/ | grep -ci "platform"
# Stage B expected: >= 6
# Stage A expected (if you sample post-Stage-A pre-Stage-B): 0
```

### JAR location inside backend container (for log inspection)

If you need to inspect classes inside the running backend image (e.g.
to confirm the new endpoints landed in the JAR), the JAR is at
`/app/app.jar` per memory `project_live_deployment_status.md`. NOT
`/app/finding-a-bed-tonight.jar` (that's a v0.52 typo fixed in v0.53).

The JAR's `Main-Class` (per `META-INF/MANIFEST.MF`, ground-truthed
via `unzip -p /app/app.jar META-INF/MANIFEST.MF` on the live VM
2026-04-28) is
`org.springframework.boot.loader.launch.JarLauncher` — note
`loader.launch.JarLauncher`, NOT `loader.JarLauncher` (the package
path moved between Spring Boot 3 and 4). The `Start-Class` is
`org.fabt.Application`.

```bash
# Example: list controllers in the JAR
docker exec -i fabt-backend sh -c \
    'unzip -l /app/app.jar | grep PlatformAuthController'
# Expected: BOOT-INF/classes/.../PlatformAuthController.class line
```

### Operator-flow smoke (manual, via SSH tunnel)

Same checklist as Step 11 above; re-running after the post-deploy gates is belt-and-suspenders.

---

## 7. Rollback Matrix

| Symptom | Action | Time to recover |
|---|---|---|
| Stage B activated `/platform/*` is broken (any reason) | Re-build frontend with `VITE_PLATFORM_UI_ENABLED=false` and `--force-recreate frontend` (no backend, no prometheus, no DB rollback). | ~6 min |
| Backend container fails to start (CrashLoopBackOff) post-Stage-A | Most likely cause is the V90 migration: read `docker logs fabt-backend --tail 100` for Flyway errors. The pg_dump in Section 4 was taken with `-Fc` custom format — restore via `pg_restore`, NOT `psql`. **DB rollback path** (rare; V90 is additive functions): `docker exec -i finding-a-bed-tonight-postgres-1 pg_restore -U fabt -d fabt --clean --if-exists < ~/fabt-backups/fabt-pre-v0.54.0-<TIMESTAMP>.dump` then `docker tag fabt-backend:v0.53-lastgood fabt-backend:latest && docker compose <COMPOSE> up -d --force-recreate backend`. **Image-only rollback path** (JAR fails for non-Flyway reasons; V90 stays applied — forward-compatible with v0.53 code): `docker tag fabt-backend:v0.53-lastgood fabt-backend:latest && docker compose <COMPOSE> up -d --force-recreate backend`. | ~5 min (image-only) / ~15 min (image + pg_restore) |
| Backend `/me` or `/logout` returning 5xx | Roll backend image back: `docker tag fabt-backend:v0.53-lastgood fabt-backend:latest && docker compose <COMPOSE> up -d --force-recreate backend`. The endpoints disappear; tenant flow is unaffected. | ~3 min |
| New Prometheus alerts not loading | Verify the file is in the mounted rules-volume directory: `docker exec -i finding-a-bed-tonight-prometheus-1 ls /etc/prometheus/rules/`. If the file is missing, `git checkout` may have produced a new directory inode without `--force-recreate` (see `feedback_bind_mount_inode_pitfall.md`). Run `docker compose <COMPOSE> up -d --force-recreate prometheus`. | ~2 min |
| Operator hits 401 from `/me` mid-flow during Stage B smoke | Likely cause: post-MFA token expired between login and dashboard load (the token is 15min). Re-login in the SSH tunnel session. NOT a deploy failure. | n/a |
| Operator hits 410 anonymized from `/me` | The operator's `platform_user` row is anonymized. Banner force-logs them out. Confirm with `SELECT id, email, anonymized_at FROM platform_user WHERE email = '...'`. NOT a deploy failure. | n/a |
| Frontend serving stale JS post-Stage-B | Old service worker cached the bundle. Use incognito or clear site data. `feedback_stale_sw_on_deploy.md`. | <1 min |
| Full rollback to v0.53 | `docker tag fabt-backend:v0.53-lastgood fabt-backend:latest && docker tag fabt-frontend:v0.53-lastgood fabt-frontend:latest && docker compose <COMPOSE> up -d --force-recreate backend frontend`. The V90 migration **is NOT rolled back** — V90 only ADDS SECURITY DEFINER functions and is forward-compatible (v0.53 backend ignores the new functions). | ~6 min |

> **V90 is a one-way migration.** It defines 5 SECURITY DEFINER
> functions (verified via `grep -nE "^CREATE OR REPLACE FUNCTION"
> backend/src/main/resources/db/migration/V90__*.sql`):
>
> - **4 NEW functions** introduced by V90: `platform_user_get_me`,
>   `platform_user_anonymize`, `platform_user_restore`,
>   `platform_user_record_failure_with_state`.
> - **1 UPDATED function** (replaced via `CREATE OR REPLACE`):
>   `platform_user_update_credentials` — sets `mfa_enrolled_at` on the
>   first false→true MFA transition (V88 behavior gap).
>
> The new functions coexist safely with v0.53 code which never calls
> them. Rolling back the JAR rolls back the calls, not the schema.

---

## 8. Post-Deploy Housekeeping

> **Run after BOTH Stage A and Stage B succeed.** If Stage B is rolled
> back via the §7 matrix (rebuild frontend with flag=false +
> force-recreate), do NOT pre-record version/Flyway updates here —
> the deploy is partial and memory should reflect that explicitly
> (e.g. `v0.54-stageA-only`).

- [ ] Update `project_live_deployment_status.md` memory: version `v0.53.0 → v0.54.0`, Flyway HWM `V89 → V90`, new container behaviors (frontend now serves /platform/* via flag-on bundle), `/api/v1/version` reports `"0.54"`.
- [ ] Delete/expire any test alerts fired during deploy verification.
- [ ] Update `CHANGELOG.md`: move `[Unreleased]` → `[v0.54.0] — 2026-MM-DD`.
- [ ] Archive the spent OpenSpec change: `/opsx:archive platform-operator-ui` (sync delta specs into main specs first).
- [ ] Update `project_resume_point.md` memory: F11 shipped, ready to switch active OpenSpec to `reentry-spec` (V90 → V91-V94 renumber already resolved per memory `project_reentry_spec_renumber.md`).
- [ ] Run the **OpenSpec `tasks.md §6.10`** cross-authenticator MFA QA (with phones) and commit `docs/operations/platform-operator-mfa-compatibility.md`. (This is `tasks.md §6.10` in the `platform-operator-ui` OpenSpec change — NOT a section of THIS runbook.)
- [ ] Surface follow-ups from `tasks.md §10` to the v0.55 backlog tracker.

---

## Related artifacts

- OpenSpec change: `openspec/changes/platform-operator-ui/` (in the docs repo)
- Companion runbook: `docs/observability/platform-admin-monitoring.md` (extended with F11 §5.2 panels + §5.4 alert rules)
- Operator user guide: `docs/operations/platform-operator-user-guide.md`
- New Prometheus rules: `deploy/prometheus/f11-platform-operator-ui.rules.yml`
- Backend: `backend/src/main/java/org/fabt/auth/platform/PlatformAuthService.java` (new MFA-verify counter), `PlatformAuthController.java` (GET /me + POST /logout + Cache-Control: no-store on /mfa-setup)
- Migration: `backend/src/main/resources/db/migration/V90__platform_user_get_me_function.sql`
- Frontend entry: `frontend/src/App.tsx` lazy-loads `/platform/*` chunks under the `VITE_PLATFORM_UI_ENABLED` build flag
- CI tree-shake check: `frontend/scripts/verify-platform-chunk-tree-shake.sh`
