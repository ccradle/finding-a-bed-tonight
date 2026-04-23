# Oracle Deploy Notes — v0.50.0

**Status:** READY — PR #149 merged 2026-04-23; CI green.

**Release summary:** Ops hardening bundle.
- Phase D nginx tenant-header stripping (D11 defense-in-depth)
- Alertmanager template: `smtp_require_tls` parameterized (new env var required on VM)
- Deploy rehearsal harness (`make rehearse-deploy`) — first use this release
- Docs: canonical runbook template + memory index

**From:** v0.49.0 live at `findabed.org`
**To:** v0.50.0

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: frontend image rebuilt (nginx.conf changed); explicit docker build --no-cache required before compose up
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: alertmanager.yml re-rendered = new inode; --force-recreate alertmanager required (reload alone insufficient)
  - file: feedback_deploy_old_jars.md
    # why-cited: backend NOT rebuilt this release; confirm no stale JAR confusion if mvn ran recently
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: alertmanager.yml re-render step; placeholder-count check only — never cat the rendered file
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke must use BASE_URL=https://findabed.org override
  - file: project_live_deployment_status.md
    # why-cited: current prod state (containers, compose chain, Flyway HWM V78, 3 tenants)
  - file: feedback_release_after_scans.md
    # why-cited: tag only after CI scans green; gh release create after tag
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: nginx.conf location block count verified against source before writing this runbook
  - file: feedback_alertmanager_template_funcs.md
    # why-cited: alertmanager template re-rendered this deploy; confirm no Sprig helpers snuck back in
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.50.0 — nginx tenant-header stripping + alertmanager TLS var + deploy rehearsal harness

**From:** v0.49.0 live at `findabed.org`
(confirm: `curl -s https://findabed.org/api/v1/version | jq .version`)

**To:** v0.50.0

### What ships

| Change | Files | Deploy action |
|---|---|---|
| Phase D nginx header stripping (D11) | `infra/docker/nginx.conf` | Frontend rebuild + force-recreate |
| Alertmanager template TLS parameterization | `deploy/alertmanager.yml.tmpl` | Add env var to .env.prod, re-render, force-recreate alertmanager |
| Alertmanager template Sprig fix | `deploy/alertmanager-templates.tmpl` | Included in alertmanager force-recreate above |
| Deploy rehearsal harness | `scripts/deploy-rehearsal.sh`, `Makefile`, etc. | Operator tooling only — no container action |
| Docs + runbook improvements | `docs/` | No container action |

### What does NOT change in this deploy

- **Backend Java code** — no change; no `mvn clean package`, no backend docker build, no backend recreate
- **Postgres / pgaudit** — no restart, no migration
- **Flyway schema** — no new migrations; HWM stays at V78
- **FORCE RLS posture** — unchanged
- **Prometheus** — no change; no recreate
- **Host nginx** (`/etc/nginx/sites-available/fabt`) — no change; the nginx.conf in scope is inside the frontend Docker image

---

## 3. Service-Recreate Matrix

| Service | Changed? | Recreate required? | Reason |
|---|---|---|---|
| `frontend` | ✅ `infra/docker/nginx.conf` changed (baked into Docker image via COPY) | ✅ | Rebuild image; `--force-recreate` to activate new nginx config |
| `alertmanager` | ✅ `alertmanager.yml` re-rendered (new inode from envsubst) | ✅ | Inode bind-mount pitfall — reload is insufficient; `--force-recreate` required |
| `backend` | ❌ no code change | ❌ | No action; backend stays running throughout |
| `prometheus` | ❌ no change | ❌ | No action |
| `postgres` | ❌ no migration | ❌ | No action |
| Host `nginx` | ❌ no change | ❌ | No action (`nginx -s reload` not needed) |

> **Note on frontend-only recreate:** the v0.49 lesson ("backend recreate requires frontend recreate") is directional — if backend changes, frontend must follow. Here only frontend changes, so backend can stay running. Recreating frontend alone is safe; it re-attaches to the same running backend container over the compose network.

---

## 4. Pre-Deploy Gates

- [ ] **Rehearsal green** — run `make rehearse-deploy` on operator laptop; confirm PASS before tagging. This is the first release the rehearsal harness is available — use it. Log filename: `logs/rehearsal-smoke-YYYYMMDD-HHMMSS.log` (include in PR description or commit `deploy/rehearsal-attest-v0.50.0.txt`)
- [ ] **CI green** — `gh run list --branch main --limit 5` — all runs green (`feedback_release_after_scans.md`)
- [ ] **PR #149 merged** — confirm deploy-rehearsal-harness PR is merged before tagging
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` must return NO output
- [ ] **New env var present** — confirm `FABT_ALERT_SMTP_REQUIRE_TLS` is in `~/fabt-secrets/.env.prod` (value: `true`). This is a new required variable added in v0.50. **If missing, the re-rendered alertmanager.yml will contain the literal string `${FABT_ALERT_SMTP_REQUIRE_TLS}` and alertmanager will refuse to start.**
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.50.0-$(date -u +%Y%m%d-%H%M%S).dump`
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting; do not assume reachability mid-deploy
- [ ] **Compose dry-render** — `docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml --env-file ~/fabt-secrets/.env.prod config > /tmp/v0.50.0-config.rendered.yml` — inspect; confirm `X-FABT-Tenant-Id` blank-header lines appear in the nginx location blocks

---

## 5. Deploy Steps

### 1. Preserve last-good image tags

```bash
docker tag fabt-backend:latest fabt-backend:v0.49.0-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.49.0-lastgood
docker images | grep -E "fabt-(backend|frontend)"
# Confirm: latest + v0.49.0-lastgood both present; IMAGE IDs match (same image, two tags)
```

### 2. Pull latest main

```bash
cd ~/finding-a-bed-tonight
git fetch origin
git checkout main
git pull origin main
# Confirm: git log --oneline -3 shows Phase D + rehearsal-harness commits at top
```

### 3. Add `FABT_ALERT_SMTP_REQUIRE_TLS` to `.env.prod`

> **This step is REQUIRED before re-rendering alertmanager.yml. If skipped, alertmanager will fail to start.**

```bash
# Verify the variable is not already present:
grep -c "FABT_ALERT_SMTP_REQUIRE_TLS" ~/fabt-secrets/.env.prod || echo "NOT FOUND — add it"

# If not found, append (structural check only — do not print file contents):
echo "FABT_ALERT_SMTP_REQUIRE_TLS=true" >> ~/fabt-secrets/.env.prod

# Confirm the line landed (grep for key name only — value is not secret but habit matters):
grep -c "FABT_ALERT_SMTP_REQUIRE_TLS" ~/fabt-secrets/.env.prod
# expect: 1
```

### 4. Re-render alertmanager config

```bash
# Source prod env (set -a exports vars for envsubst)
set -a
source ~/fabt-secrets/.env.prod
set +a

# Render — whitelist includes the new FABT_ALERT_SMTP_REQUIRE_TLS var
envsubst '${FABT_ALERT_SMTP_HOST}${FABT_ALERT_SMTP_PORT}${FABT_ALERT_SMTP_USER}${FABT_ALERT_SMTP_PASSWORD}${FABT_ALERT_SMTP_REQUIRE_TLS}${FABT_ALERT_EMAIL_FROM}${FABT_ALERT_EMAIL_TO}${FABT_ALERT_NTFY_URL}${FABT_ALERT_NTFY_TOPIC}' \
    < deploy/alertmanager.yml.tmpl \
    > ~/fabt-secrets/alertmanager.yml

# Placeholder-count check — must be 0 (feedback_never_print_rendered_secrets.md)
grep -c '\${FABT_' ~/fabt-secrets/alertmanager.yml
# expect: 0   (any non-zero means a var was missing from .env.prod)

# Perm check — alertmanager UID 65534 needs read access
chmod 644 ~/fabt-secrets/alertmanager.yml
stat -c '%a %u' ~/fabt-secrets/alertmanager.yml
# expect: 644 <your-uid>

# amtool config validation (no --alertmanager.url needed for check-config)
docker run --rm \
    -v ~/fabt-secrets/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro \
    -v $PWD/deploy/alertmanager-templates.tmpl:/etc/alertmanager/templates/fabt.tmpl:ro \
    prom/alertmanager:v0.27.0 \
    /bin/amtool check-config /etc/alertmanager/alertmanager.yml
# expect: "Checking '/etc/alertmanager/alertmanager.yml'  SUCCESS"
```

### 5. Build frontend image (nginx.conf changed)

> Backend is NOT rebuilt this release. Frontend only.

```bash
# Confirm no stale backend JAR confusion (backend not being built, just documenting)
ls backend/target/*.jar 2>/dev/null | head -2

# Build frontend (no-cache — feedback_prod_docker_build_pattern.md)
docker build --no-cache \
    -f infra/docker/Dockerfile.frontend \
    -t fabt-frontend:v0.50.0 \
    -t fabt-frontend:latest \
    .
# Confirm image built:
docker images fabt-frontend --format "table {{.Tag}}\t{{.CreatedAt}}" | head -4
```

### 6. Force-recreate frontend + alertmanager

```bash
# Recreate both changed services together.
# Backend intentionally omitted — it is running and unchanged.
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    up -d --force-recreate frontend alertmanager

# Watch logs for 30s — expect no crash loops
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    logs --tail=50 frontend alertmanager
```

### 7. Wait for services to be healthy

```bash
# Backend should already be UP (unchanged) — confirm for peace of mind
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend..."; sleep 3
done
echo "backend UP"

# Alertmanager
until curl -fsS http://localhost:9093/-/healthy 2>/dev/null; do
    echo "waiting for alertmanager..."; sleep 3
done
echo "alertmanager healthy"
```

---

## 6. Post-Deploy Gates

### Version check

```bash
curl -s https://findabed.org/api/v1/version | jq .version
# expect: "0.50.0"
```

### Phase D nginx header stripping — verify active

```bash
# Send a request with a spoofed tenant header — backend must receive it blanked
# (Tests that the nginx inside the frontend container is the new image)
curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $(cat ~/fabt-secrets/.dev-admin-jwt 2>/dev/null || echo REPLACE_WITH_TOKEN)" \
    -H "X-FABT-Tenant-Id: injected-evil-tenant" \
    https://findabed.org/api/v1/shelters | head -c 5
# expect: 200 (not 403 — the header is stripped, not rejected)
# To verify stripping in backend logs: grep for X-FABT-Tenant-Id in structured log output;
# it should be absent or empty. Backend resolves tenant from JWT only.
```

### Alertmanager config accepted

```bash
# Reload check — alertmanager loaded the new config
curl -s http://localhost:9093/-/healthy
# expect: "Alertmanager is Healthy."

# Config generation check (confirms smtp_require_tls rendered as 'true', not literal '${...}')
docker exec finding-a-bed-tonight-alertmanager-1 \
    /bin/amtool --alertmanager.url http://127.0.0.1:9093 config show 2>/dev/null | \
    grep -c "smtp_require_tls"
# expect: 1 (line present)
# DO NOT print the config — it contains smtp_auth_password (feedback_never_print_rendered_secrets.md)
```

### Playwright smoke

```bash
# Must use BASE_URL override — spec defaults to localhost (feedback_smoke_spec_default_target.md)
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test \
    deploy/post-deploy-smoke.spec.ts \
    --project chromium \
    --reporter=list \
    --trace on \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.50.0.log
# expect: all tests pass
```

### Flyway HWM (confirm unchanged at V78)

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# expect: top entry = V78__seed_coordinator_assignments_blue_ridge_pamlico.sql | t
# (no new migration in v0.50)
```

---

## 7. Rollback Matrix

| Symptom | Action |
|---|---|
| Frontend 502 after recreate | Backend docker-network stale — recreate backend too: `docker compose ... up -d --force-recreate backend frontend`. (This release: backend was unchanged, but recreate fixes stale network.) |
| Alertmanager crash loop (`function "X" not defined`) | Sprig helper in template — replace with Go `with`/`else`. (`feedback_alertmanager_template_funcs.md`) |
| Alertmanager crash loop (`${FABT_ALERT_SMTP_REQUIRE_TLS}` literal in config) | `FABT_ALERT_SMTP_REQUIRE_TLS` missing from `~/.fabt-secrets/.env.prod`; add `FABT_ALERT_SMTP_REQUIRE_TLS=true`, re-render, `--force-recreate alertmanager`. |
| nginx serving old header-stripping config | Image not rebuilt with no-cache; run step 5 again, confirm new image digest, `--force-recreate frontend`. |
| Full frontend rollback needed | `docker tag fabt-frontend:v0.49.0-lastgood fabt-frontend:latest` then `docker compose ... up -d --force-recreate frontend`. |
| Full alertmanager rollback | Re-render alertmanager.yml from prior template (git checkout v0.49.0 `deploy/alertmanager.yml.tmpl`), `chmod 644`, `--force-recreate alertmanager`. |

---

## 8. Post-Deploy Housekeeping

- [ ] **Update `project_live_deployment_status.md` memory** — version v0.50.0, Flyway HWM V78 (unchanged), frontend image fabt-frontend:v0.50.0, alertmanager config re-rendered with FABT_ALERT_SMTP_REQUIRE_TLS
- [ ] **Commit rehearsal attestation** — if `make rehearse-deploy` PASS log was not in PR description, commit `deploy/rehearsal-attest-v0.50.0.txt` with the log filename
- [ ] **Update `CHANGELOG.md`** — move `[Unreleased]` items under `[v0.50.0]`
- [ ] **pom.xml version bump** — change `<version>0.49.0</version>` to `0.50.0` in `backend/pom.xml`
- [ ] **Archive spent OpenSpec changes** — `deploy-rehearsal-harness` is shippable; archive after merge (`/opsx:archive`)
- [ ] **Delete test alerts** — expire any `FabtRehearsalTest` alerts if rehearsal was run against the prod alertmanager (should not be; rehearsal uses stub stack)
- [ ] **Prune old images** — `docker image prune -f` after confirming v0.49.0-lastgood tags retained
- [ ] **Per `feedback_periodic_resume_save.md`** — update `project_live_deployment_status.md` and `project_resume_point.md`

---

## What's New in v0.50.0 (release notes summary)

**Phase D nginx tenant-header stripping:** The frontend nginx proxy now explicitly blanks `X-FABT-Tenant-Id`, `X-Scope-OrgID`, and `X-Tenant-Id` on all proxied requests. Backend has always resolved tenant from JWT claims only — this adds defense-in-depth so no future code path can accidentally read a client-supplied tenant header. Covered by `e2e/playwright/tests/nginx-tenant-header-stripping.spec.ts`.

**Deploy rehearsal harness:** `make rehearse-deploy` gives the operator a 10-gate prod-mirror rehearsal on their laptop before any tag. Catches the class of failure that produced the v0.49 post-deploy hotfixes (trailing-space env vars, UID/perm mismatches, alertmanager routing errors). Required within 72h of any release tag per `deploy/release-gate-pins.txt`.

**Alertmanager template hardening:** `smtp_require_tls` is now a parameterized variable (`FABT_ALERT_SMTP_REQUIRE_TLS`) rather than hardcoded. Prod stays `true` (Gmail). Enables the rehearsal harness to use Mailpit (plaintext SMTP) without touching the production template value.

---

*Template: `docs/runbook-template.md` v1 — mandatory for all `oracle-update-notes-vX.Y.Z.md` from v0.50.0 onward*
