# Deploy Runbook Template

Every `docs/oracle-update-notes-vX.Y.Z.md` file MUST follow this template.
Copy it, fill in the version-specific content, and check off each mandatory
element before tagging a release.

**Template version:** v1 (2026-04-22)
**Related changes:** `opsx-runbook-draft-skill` (auto-populates this template),
`ci-runbook-consulted-check` (lints the `consulted:` block in CI)

---

## Mandatory section order

Every runbook must have these 8 sections in this order:

1. Consulted Memories
2. Scope & Non-Scope
3. Service-Recreate Matrix
4. Pre-Deploy Gates
5. Deploy Steps
6. Post-Deploy Gates
7. Rollback Matrix
8. Post-Deploy Housekeeping

Additional sections (e.g. detailed sub-steps, "What's New", release-specific
notes) may appear between any two mandatory sections or nested within them.
Do not reorder the 8 mandatory sections.

---

## 1. Consulted Memories

Each runbook author MUST review deploy-relevant memory files before authoring
the runbook and list them here. See `docs/runbook-memory-index.md` for the
full scannable list.

```yaml
consulted:
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit docker build + --force-recreate required; three no-op traps documented
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: any prometheus.yml git-checkout on VM requires --force-recreate, not just /-/reload
  - file: feedback_deploy_old_jars.md
    # why-cited: mvn clean is mandatory before docker build to avoid stale JAR in image
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: never cat/grep .env.prod or alertmanager.yml — structural checks only
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke must use BASE_URL override against findabed.org
  - file: project_live_deployment_status.md
    # why-cited: current prod state (version, containers, compose chain, Flyway HWM, lessons)
  - file: feedback_release_after_scans.md
    not-applicable: omit if this is a docs-only commit with no release tag
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: before naming rule names / slugs / table lists, grep the source file
```

> **How to fill in this block:** scan `docs/runbook-memory-index.md` for
> entries marked `[deploy]` or `[security]`. Add any that are relevant. Use
> `not-applicable: <reason>` for entries you explicitly reviewed and dismissed.
> Do NOT omit the block — an empty `consulted:` with a reason is better than
> a missing block.

---

## 2. Scope & Non-Scope

**Deploying:** vX.Y.Z — one-line description of the user-visible change

**From:** `vA.B.C` live at `findabed.org` (confirm current version via
`curl -s https://findabed.org/api/v1/version`)

**To:** `vX.Y.Z` (describe what ships)

**What does NOT change in this deploy** (explicit list reduces cognitive load
during a stressful deploy):

- Backend Java code — no change / changed
- Frontend — no change / rebuilt (if rebuilt, docker build step required)
- Postgres / pgaudit — no restart / restart required
- Flyway schema — no new migrations / migrations VNN–VMM
- FORCE RLS posture — unchanged

---

## 3. Service-Recreate Matrix

Check each row before writing deploy steps. "Changed?" refers to whether the
service's config or image changed in this release. "Recreate required?" is
the action consequence.

| Service | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `backend` + `frontend` | Any backend image rebuild — recreating backend without frontend leaves docker-network stale (v0.49 issue #3); host nginx serves 502s | ☐ | ☐ |
| `prometheus` | `prometheus.yml` replaced via `git checkout` / `git pull` — inode changes, `/-/reload` insufficient (`feedback_bind_mount_inode_pitfall.md`) | ☐ | ☐ |
| `alertmanager` | Rendered `~/fabt-secrets/alertmanager.yml` re-rendered (new inode on `envsubst` output) | ☐ | ☐ |
| `postgres` | `pgaudit.conf` edited in place (`sed -i` / `vim` OK; `cp` / `git checkout` requires recreate) | ☐ | ☐ |
| Host `nginx` | `/etc/nginx/sites-available/fabt` edited — `nginx -s reload` sufficient (no bind-mount, in-place edit) | ☐ | ☐ |

> **Rule:** if a row is checked "Changed? ☑" you MUST also check "Recreate
> required? ☑" and include an explicit `--force-recreate <service>` in the
> Deploy Steps.

---

## 4. Pre-Deploy Gates

Check each gate before starting any deploy action. Every gate has a pass/fail
criterion — do not proceed if a gate fails.

- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` must return NO output. A trailing space after `=` breaks `source <(grep ...)` at render time. (v0.49 issue #1)
- [ ] **mvn clean** — run `mvn -B -DskipTests clean package -q`; verify exactly 1 JAR in `backend/target/` before `docker build`. (`feedback_deploy_old_jars.md`)
- [ ] **Container UID vs perms** — any config file bind-mounted into a container must be readable by the container's UID. Alertmanager runs as UID 65534 (`nobody`): needs `chmod 644`. Backend runs as UID 1000. (`project_live_deployment_status.md` v0.49 issue #2)
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-vX.Y.Z-$(date -u +%Y%m%d-%H%M%S).dump`
- [ ] **CI green** — `gh run list --branch main --limit 3` — all runs green. (`feedback_release_after_scans.md`)
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting. Do not assume it will be reachable mid-deploy. (`feedback_no_ssh_tunnels.md`)
- [ ] **Compose dry-render** — `docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml ... config > /tmp/vX.Y.Z-config.rendered.yml` — inspect for expected changes; diff against prior render if available.

---

## 5. Deploy Steps

Number every step. The first step is always "preserve last-good image tags."

### 1. Preserve last-good image tags

```bash
docker tag fabt-backend:latest fabt-backend:vPREV-lastgood
docker tag fabt-frontend:latest fabt-frontend:vPREV-lastgood
docker images | grep -E "fabt-(backend|frontend)"
# Confirm: latest + vPREV-lastgood both present; IMAGE IDs will match (same image, two tags)
```

### 2. [Release-specific steps]

Use `feedback_prod_docker_build_pattern.md` for the canonical build sequence:
1. `mvn clean package -DskipTests -q`
2. `docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:vNEW -t fabt-backend:latest .`
3. `docker build --no-cache -f infra/docker/Dockerfile.frontend -t fabt-frontend:vNEW -t fabt-frontend:latest .` (skip if no frontend change)
4. `docker compose <FULL_CHAIN> --env-file ~/fabt-secrets/.env.prod --profile alerting up -d --force-recreate backend frontend`

> **`<FULL_CHAIN>` — every compose override must be included.** Per
> `project_live_deployment_status.md`, the live stack uses a 4-file chain:
> ```
> -f docker-compose.yml \
> -f ~/fabt-secrets/docker-compose.prod.yml \
> -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
> -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
> ```
> Omitting `prod-v0.44-pgaudit.yml` causes compose to recreate `postgres` with
> stock `postgres:16-alpine` (lacking pgaudit config dir), triggering a crash
> loop and cascading backend failure. (v0.50 post-deploy lesson.)

### N. Wait for backend readiness

```bash
# Use internal management port — NOT the public URL (returns 404; v0.49 issue #4)
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend..."; sleep 3
done
echo "backend is UP"
```

---

## 6. Post-Deploy Gates

Run every gate; record the actual value alongside the expected.

### Mandatory smoke gate

```bash
# - FABT_BASE_URL (NOT BASE_URL) is what post-deploy-smoke.spec.ts reads directly
# - --config selects the deploy-isolated config (feedback_deploy_verify_isolation.md)
# - `post-deploy-smoke` positional filter restricts to the smoke spec only
#   (the deploy/ config glob picks up old version-specific specs like
#   deploy-verify-v0.29.x that would fail-by-design against the current version)
cd e2e/playwright && FABT_BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts --project=chromium \
    --reporter=list --trace on post-deploy-smoke \
    2>&1 | tee ../../logs/post-deploy-smoke-vX.Y.Z.log
```

> **Do NOT skip this gate.** The smoke is the only test that exercises the full
> Cloudflare → host nginx → frontend → backend chain. It is a numbered gate,
> not an "if time permits" footnote.

> **Known prod flake:** The 15-test suite rapidly triggers 16+ requests to
> `/api/v1/version` within ~60s, exceeding the `public_api` nginx zone
> (10r/m, burst=5). Cloudflare caches that endpoint for organic traffic but
> Playwright's fresh-session bursts can outpace the cache warmup and hit the
> origin rate limit directly. Symptom: test 13 or 14 fails with
> `app-tenant-name-footer` not found (the `{appVersion && ...}` guard is
> false when `/api/v1/version` returns 429). If you see only ONE test fail
> in the footer-render pair (13/14), re-run that test alone:
> `npx playwright test ... post-deploy-smoke -g "13\."`. A pass-on-retry
> confirms rate-limit flake, not a deploy regression. (v0.50 post-deploy
> lesson; first observed in rehearsal — see `feedback_deploy_rehearsal_lessons.md`.)

### Version check

```bash
curl -s https://findabed.org/api/v1/version
# expect: {"version":"X.Y.Z", ...}
```

### Flyway HWM

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
# expect: top row = newest migration, success=t
```

### [Release-specific gates]

Add any gates specific to this release (new container health, config acceptance,
end-to-end smoke for new feature, etc.)

---

## 7. Rollback Matrix

| Symptom | Action |
|---|---|
| Backend won't start (Flyway validate error) | Never modify applied migrations. Revert the file to exact pre-deploy byte content, then `docker compose up -d --force-recreate backend`. |
| Frontend serving stale JS after deploy | Old service worker cached. Use incognito or clear site data. (`feedback_stale_sw_on_deploy.md`) |
| Host nginx 502 after backend recreate | Frontend docker-network is stale — recreate frontend too: `docker compose ... up -d --force-recreate frontend`. |
| Prometheus shows stale config after `prometheus.yml` update | `git checkout` changed the inode — `/-/reload` insufficient. Run `docker compose ... up -d --force-recreate prometheus`. (`feedback_bind_mount_inode_pitfall.md`) |
| Alertmanager config error (`function "X" not defined`) | Sprig helper used in template. Replace with Go `with`/`else`. (`feedback_alertmanager_template_funcs.md`) |
| Full rollback needed | `docker tag fabt-backend:vPREV-lastgood fabt-backend:latest` then `docker compose ... up -d --force-recreate backend frontend`. |

---

## 8. Post-Deploy Housekeeping

- [ ] Update `project_live_deployment_status.md` memory — version, Flyway HWM, container list, any new lessons
- [ ] Delete/expire any test alerts fired during deploy verification
- [ ] Update `CHANGELOG.md` `[Unreleased]` section to move items under `[vX.Y.Z]`
- [ ] Archive any spent OpenSpec changes (`/opsx:archive`)
- [ ] Per `feedback_periodic_resume_save.md`: update relevant `project_*_resume_point.md` memory files

---

## Related changes

- `opsx-runbook-draft-skill` — skill that seeds a new `docs/oracle-update-notes-vX.Y.Z.md` from this template, auto-populates the `consulted:` block from `docs/runbook-memory-index.md`
- `ci-runbook-consulted-check` — CI check that lints the `consulted:` block (presence, non-empty entries, valid file names against the memory index)

---

*FABT Deploy Runbook Template v1 — applies to all `docs/oracle-update-notes-vX.Y.Z.md` files from v0.50.0 onward*
