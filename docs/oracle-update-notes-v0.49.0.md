# Oracle Deploy Notes — v0.49.0 (Alertmanager routing: Prometheus → email + ntfy push)

> **Template version:** runbook-template-v1 (back-converted 2026-04-22 as worked example)
> This is the canonical worked example for the deploy runbook template. See `docs/runbook-template.md`.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit docker build + --force-recreate required; three no-op traps
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: prometheus.yml git-checkout on VM → --force-recreate, not /-/reload
  - file: feedback_deploy_old_jars.md
    # why-cited: mvn clean required before docker build to avoid stale JAR
  - file: project_live_deployment_status.md
    # why-cited: confirmed prod was on v0.48.0 (not v0.48.1), compose chain, Flyway HWM
  - file: feedback_release_after_scans.md
    # why-cited: GitHub release created only after CI green
  - file: feedback_never_print_rendered_secrets.md
    not-applicable: created mid-deploy (SMTP password leaked during v0.49 — this memory did not exist at author time; now back-cited as the lesson)
  - file: feedback_verify_doc_facts_against_source.md
    not-applicable: created mid-deploy (fabricated rule names + tenant slugs in triage runbook — this memory did not exist at author time; now back-cited as the lesson)
  - file: feedback_smoke_spec_default_target.md
    not-applicable: smoke gate was skipped entirely in this runbook (v0.49 issue; now a mandatory gate in the template)
```

---

## 2. Scope & Non-Scope

**From:** v0.48.0 live at `findabed.org` (NOTE: v0.48.1 was tagged
2026-04-20 but **never shipped to prod** as a separate deploy — its
content rolls forward into v0.49.0). Current prod state: 3 tenants, 9
Prometheus rules firing into the UI but no notifications, 14
coordinator_assignment rows hot-patched via direct psql INSERT on
2026-04-20 ~15:40 UTC.
**To:** v0.49.0 (Alertmanager container live + V78 seed migration
recorded in flyway_schema_history — Alertmanager is the user-visible
change; V78 is a data-level no-op because prod already has the rows).

> **Three canonical prod-deploy patterns (codified post-v0.48 live-deploy —
> apply to every future release, not just v0.49):**
>  1. `docker compose up -d --build` is a **no-op** on this project — prod
>     compose files declare `image:` tags only, no `build:` blocks. Use
>     explicit `docker build --no-cache -f infra/docker/Dockerfile.<svc>`
>     BEFORE the compose up.
>  2. `-f docker-compose.yml` must be the FIRST `-f` in the chain (compose
>     does NOT auto-add the default base file when any `-f` is passed).
>     Without it: `"service 'prometheus' has neither an image nor a build
>     context specified"`.
>  3. `--force-recreate` is mandatory to swap running containers onto a new
>     image. `up -d` without it sees "matching config + running" and
>     declines to recreate — leaves old image running.
>
> See `feedback_prod_docker_build_pattern.md` for full context.
>
> **Jordan's note (SRE):** Closes task #155 — the largest operational gap
> post-v0.48. 9 rules have been loading since v0.47 (5 Phase B + 4 Phase C)
> and none of them could wake anyone up. With 3 tenants in prod, that gap
> grew a 3× blast radius. This release adds the delivery pipeline.
>
> **Flyway note:** v0.49 rolls in v0.48.1's V78 seed migration
> (`coordinator_assignment` for Blue Ridge + Pamlico — see v0.48.1 runbook
> for context). Prod is already hot-patched so V78 applies as a data-level
> no-op (`ON CONFLICT DO NOTHING`) but records in `flyway_schema_history`
> to unblock future migrations past V78. No other schema change.
>
> **Two concurrent additions to the compose chain:**
>  1. Base `docker-compose.yml` gets a new `alertmanager` service under
>     profile `alerting` (isolated from `observability` so dev
>     workflows don't crashloop on an unrendered config template).
>  2. `prometheus.yml` gets an `alerting:` block pointing Prometheus at
>     the alertmanager container over the compose network.
>
> **New operator-side artifacts** (in `~/fabt-secrets/`, never committed):
>  - `alertmanager.yml` — rendered from repo's `alertmanager.yml.tmpl`
>    via envsubst on the VM (operator one-liner in deploy step 3).
>  - Updated `docker-compose.prod.yml` — opt into the `alerting` profile
>    + replace the template bind-mount with the rendered-file bind-mount.
>
> **No backend Java / frontend / DB change.** No Flyway migration. No
> Postgres restart. FORCE RLS posture unchanged.

**Bake window:** v0.48.1 tagged 2026-04-20 ~17:00 UTC (deploy held for
2026-04-21). This runbook applies to either (a) deploying v0.49 directly
skipping v0.48.1, or (b) deploying v0.48.1 first then v0.49 on top. Option
(b) is safer — the V78 coordinator_assignment migration should land and
bake before adding the alerting layer on top.

---

## What's New in This Deploy

### Alertmanager container at `:9093` (localhost-only binding)

- `prom/alertmanager:v0.27.0` running under compose profile `alerting`
- Bind-mounts the rendered `~/fabt-secrets/alertmanager.yml` + the
  in-repo `deploy/alertmanager-templates.tmpl` for email formatting
- Single-instance (no HA cluster), matches the single-VM deploy posture
- Port 9093 bound to `127.0.0.1` only — operator access via SSH tunnel

### Two receivers — email + ntfy — wired in parallel

| Receiver | Delivery | Used for |
|---|---|---|
| `email_default` | Gmail SMTP, ~30 s | WARN + CRITICAL (historical paper trail in inbox) |
| `ntfy_urgent` | ntfy.sh push, <5 s | CRITICAL only (push-to-phone for wake-you-up urgency) |

Routing policy:
- `severity=critical` → both receivers fan out (`continue: true` on first match)
- `severity=warning` → email only (avoid ntfy push fatigue)
- Inhibit rule: CRITICAL suppresses WARNING with same `alertname`+`tenant_id`
- Group by: `alertname`, `tenant_id` (dedupe multi-alert bursts)
- Timings: `group_wait: 15s`, `group_interval: 5m`, `repeat_interval: 4h`

### 9 existing rules now actually page

All 5 Phase B + 4 Phase C rules that were loaded but dormant since v0.47
now deliver through the pipeline. No new rules added in this release.

---

## What Does NOT Change

- Backend Java code — no change
- Frontend — no change, no rebuild
- Postgres / pgaudit — no change, no restart
- Prometheus rule files (`phase-b-rls.rules.yml`, `phase-c-cache-isolation.rules.yml`) — unchanged
- Phase B FORCE RLS posture — 7 regulated tables stay `t` (matches V69 + `ForceRlsHealthGauge.REGULATED_TABLES`)
- Flyway schema — no new migrations (HWM stays 77 or 78 if v0.48.1 applies first)

---

## 3. Service-Recreate Matrix

| Service | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `backend` + `frontend` | Any backend image rebuild — recreating backend without frontend leaves docker-network stale; host nginx serves 502s until both recreated. **This row is the v0.49 issue #3 lesson; deploy step 5 was originally missing `frontend` here, fixed in commit `def9ea7`.** | ☑ | ☑ |
| `prometheus` | `prometheus.yml` replaced via `git checkout` / `git pull` — inode changes, `/-/reload` insufficient | ☑ (alerting block added) | ☑ via `curl -XPOST /-/reload` (acceptable here because `prometheus.yml` was edited in-place on VM — no git checkout; inode unchanged) |
| `alertmanager` | New container — first deploy; rendered `~/fabt-secrets/alertmanager.yml` bind-mounted | ☑ (new container) | ☑ |
| `postgres` | No pgaudit.conf change in this release | ☐ | ☐ |
| Host `nginx` | No nginx config change in this release | ☐ | ☐ |

---

## 4. Pre-Deploy Gates

### 1a. Confirm v0.48.1 hot-patch is still intact on prod

**v0.49 rolls in v0.48.1's V78 migration** (coordinator_assignment for Blue
Ridge + Pamlico). v0.48.1 itself never shipped as a separate deploy — prod
was hot-patched 2026-04-20 ~15:40 UTC with 14 direct INSERT rows. V78 will
re-apply those rows via `ON CONFLICT DO NOTHING`, meaning **if the
hot-patch is still present it's a no-op at the data level**, but V78
records in `flyway_schema_history` to unblock any future migration above
V77.

Before proceeding, verify the hot-patch is still there (if it somehow
got reverted, V78 will restore it — not fatal — but good to know):

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc "
  SELECT COUNT(*) FROM coordinator_assignment ca
  JOIN app_user u ON u.id = ca.user_id
  WHERE u.email LIKE '%@blueridge.fabt.org'
     OR u.email LIKE '%@pamlico.fabt.org';"
# Expected: 14 (7 per tenant — admin + cocadmin + coordinator + dv-coordinator
# assigned per dev-coc pattern). If this returns <14, V78 will bring it
# back to 14; >14 means someone added extras — triage before deploy.
```

### 1. Confirm 8 operator env vars present in `~/fabt-secrets/.env.prod`

Per `~/OneDrive/Documents/Ark Public Technology LLC/alertmanager-operator-setup.md`:

```bash
grep -c "^FABT_ALERT_" ~/fabt-secrets/.env.prod
# expect: 8 (SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD,
#           EMAIL_FROM, EMAIL_TO, NTFY_URL, NTFY_TOPIC)

# Critical format check — bash sourcing requires KEY=value with NO whitespace
# around the `=`. A trailing space after `=` (e.g. `FABT_ALERT_NTFY_TOPIC= xyz`)
# breaks `source <(grep ^FABT_ALERT_ .env.prod)` at render time. Caught
# during v0.49 deploy.
grep -nE "^FABT_ALERT_[A-Z_]*= " ~/fabt-secrets/.env.prod
# expect: NO output. If any line shows, fix the trailing space before proceeding.
```

If count < 8: return to the operator-setup guide; do not proceed.

### 2. CI green on v0.49.0 tag target

Same as every prior release — check `gh run list --branch main --limit 3`.

### 3. pg_dump backup (belt-and-suspenders)

Though no schema change, standard safety net:

```bash
DUMP=~/fabt-backups/fabt-pre-v0.49-$(date -u +%Y%m%d-%H%M%S).dump
docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > "$DUMP"
sha256sum "$DUMP" | tee "$DUMP.sha256"
```

### 3.5. Capture release-prep commit SHA

The v0.49.0 tag attaches to the release-prep commit (pom bump + CHANGELOG
+ this runbook finalization), NOT to the PR merge commit. Record the SHA
now so it appears in deploy logs + 1Password entry for this release.

```bash
cd ~/finding-a-bed-tonight
git fetch origin main
git rev-parse origin/main
# Copy this SHA — fills in the deploy incident thread + tag annotation.
```

### 4. Compose dry-render — Jordan's #1 gate

```bash
cd ~/finding-a-bed-tonight
git fetch --tags origin && git checkout v0.49.0
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    config > /tmp/v0.49-config.rendered.yml

# If you saved /tmp/v0.48-config.rendered.yml from a previous deploy,
# diff it against the v0.49 render to confirm the only changes are the
# new alertmanager block + the alerting: stanza. If there is no v0.48
# render to compare, just sanity-grep for the expected new content:
test -f /tmp/v0.48-config.rendered.yml && \
    diff /tmp/v0.48-config.rendered.yml /tmp/v0.49-config.rendered.yml || \
    grep -E "^  alertmanager:|alertmanager:9093" /tmp/v0.49-config.rendered.yml
# EXPECTED: either a clean diff with only alertmanager+alerting changes,
# OR (no baseline) the grep finds the alertmanager service block + the
# prometheus alerting target. If neither, STOP.
```

---

## 5. Deploy Steps

### 1. Preserve last-good backend image

Prod is currently live on **v0.48.0** (per project_live_deployment_status.md
+ runbook header). v0.48.1 was tagged but never deployed to prod, so no
v0.48.1 image exists on the VM — tag the current `latest` as v0.48.0-lastgood:

```bash
docker tag fabt-backend:latest fabt-backend:v0.48.0-lastgood
docker images | grep -E "fabt-backend.*(latest|v0\.48\.0-lastgood)"
# Both rows should reference the same IMAGE ID (proves the tag landed on
# what's actually running, not on a stale `latest` from a long-ago build).
```

### 2. Checkout + confirm template + render alertmanager.yml on the VM

The repo ships `deploy/alertmanager.yml.tmpl` with `${VAR}` placeholders.
The operator renders it into `~/fabt-secrets/alertmanager.yml` via envsubst
(standard on Ubuntu, in the `gettext` package — already installed).

```bash
cd ~/finding-a-bed-tonight
git fetch --tags origin
git checkout v0.49.0

# Verify the template is in place + placeholders look sane
head -30 deploy/alertmanager.yml.tmpl
grep -c '\${FABT_ALERT_' deploy/alertmanager.yml.tmpl
# expect: 8+ (one per env var, possibly more if a var appears twice)

# Render with the operator's env vars loaded
source ~/fabt-secrets/.env.prod
envsubst '$FABT_ALERT_SMTP_HOST $FABT_ALERT_SMTP_PORT $FABT_ALERT_SMTP_USER $FABT_ALERT_SMTP_PASSWORD $FABT_ALERT_EMAIL_FROM $FABT_ALERT_EMAIL_TO $FABT_ALERT_NTFY_URL $FABT_ALERT_NTFY_TOPIC' \
    < deploy/alertmanager.yml.tmpl \
    > ~/fabt-secrets/alertmanager.yml

# Protect the rendered file (contains SMTP password). Must be 644 (NOT 600) —
# the alertmanager container runs as UID 65534 (nobody), distinct from the
# host file owner (ubuntu, UID 1000); 600 would prevent the container from
# reading the config and cause a config-load failure on startup. Parent dir
# ~/fabt-secrets is 700 so host non-root users still can't read the file.
# Caught during v0.49 deploy.
chmod 644 ~/fabt-secrets/alertmanager.yml

# Sanity check — should have no remaining ${} placeholders
grep -c '\${FABT_' ~/fabt-secrets/alertmanager.yml
# expect: 0
```

**IMPORTANT:** the explicit `envsubst '$VAR1 $VAR2 ...'` argument list
whitelists which env vars to substitute. Without the whitelist, envsubst
would also expand any other `${VAR}` patterns in the file (the templates
use `${...}` for some Alertmanager built-in labels if added later). Keep
the whitelist; it's future-proof.

### 3. Update operator-side compose override (one-time setup)

Edit `~/fabt-secrets/docker-compose.prod.yml` to add the alertmanager
volume override + opt into the `alerting` profile. Add this block under
`services:` (or adjacent to existing `alertmanager:` if you've added a
stub):

```yaml
  alertmanager:
    volumes:
      # Override the dev template bind-mount with the rendered secrets file
      - ~/fabt-secrets/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      - ./deploy/alertmanager-templates.tmpl:/etc/alertmanager/templates/fabt.tmpl:ro
```

Then reference this compose file via `--profile alerting` in the deploy
command (see step 5 below). One-time setup; future deploys just re-render
step 2 + restart step 5.

### 4. Build backend image `--no-cache`

Two reasons we DO need a new backend image even though there's no Java
change: (a) `pom.xml` bumped 0.48.1 → 0.49.0 — `/actuator/info` should
report the new version, (b) Flyway runs at startup and needs to pick up
V78 from the JAR's classpath (V78 was hot-patched directly via psql
2026-04-20; without a backend recreate it never enters
`flyway_schema_history`, blocking any future migration above V77).

```bash
cd ~/finding-a-bed-tonight/backend && mvn -B -DskipTests clean package -q
cd ~/finding-a-bed-tonight
docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.49.0 \
    -t fabt-backend:latest .
```

Frontend is NOT rebuilt (no frontend change in v0.49).

### 5. Start alertmanager + recreate backend + frontend + reload prometheus

Four things happen here, in order (steps 1-3 bundled in the same
`up -d --force-recreate`; step 4 is a separate SIGHUP):

1. **Start alertmanager** (new container) — picks up the rendered config
   bind-mounted from `~/fabt-secrets/alertmanager.yml`.
2. **Recreate backend** (new image with v0.49.0 in pom.xml) — Flyway
   discovers V78 in the JAR's classpath and applies it (data-level no-op
   on prod since the rows are already hot-patched, but records V78 in
   `flyway_schema_history`). `/actuator/info` then reports v0.49.0
   accurately.
3. **Recreate frontend** (same image, no rebuild) — refreshes
   docker-network state so host nginx → frontend upstream pool points at
   the live container. Without this, backend recreate alone leaves
   frontend pinned to stale network state and host nginx serves sustained
   502s until manual recreate. Caught during v0.49 deploy. Matches the
   recreate pattern from v0.47/v0.48.
4. **SIGHUP Prometheus** (no container restart) — reloads `prometheus.yml`
   so the new `alerting:` block takes effect. Possible because the
   `--web.enable-lifecycle` flag has been active since v0.47.

```bash
source ~/fabt-secrets/.env.prod

# 1 + 2 + 3: alertmanager + backend + frontend together
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability --profile alerting \
    up -d --force-recreate alertmanager backend frontend

# Wait for backend to come back (Flyway runs during startup). Actuator binds
# to port 9091 localhost-only — the public URL returns 404 (v0.47 Lesson 3).
# Management port permits unauthenticated /actuator/health for probes.
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend..."; sleep 3
done
echo "backend is UP"

# 4: Prometheus reload (picks up new alerting: block)
curl -s -XPOST http://localhost:9090/-/reload
# Expect: empty response + 200

# Frontend was recreated alongside backend above — same image, no rebuild;
# the recreate refreshes docker-network state so host nginx routes correctly.
```

---

## 6. Post-Deploy Gates

### 1. Alertmanager is up + config accepted

```bash
# Container running + healthy
docker ps | grep alertmanager
# expect: "Up <time> (healthy)" (health check via /-/ready)

# Version + config status
curl -s http://localhost:9093/api/v2/status | python3 -m json.tool | head -30
# expect: versionInfo with "v0.27.0", no configError

# Rendered config loaded (no ${} placeholders visible)
docker exec <alertmanager-container-name> cat /etc/alertmanager/alertmanager.yml | grep -c '\${FABT_'
# expect: 0
```

### 2. Prometheus sees alertmanager in its targets

```bash
# Via SSH tunnel to 9090, or curl on VM:
curl -s http://localhost:9090/api/v1/alertmanagers | python3 -m json.tool
# expect: activeAlertmanagers list includes "alertmanager:9093"
```

### 2b. Prometheus reload idempotency

Fire the reload endpoint twice in a row; second call should also return
empty + 200 (no "already in progress" or config errors):

```bash
curl -s -XPOST http://localhost:9090/-/reload && echo "first reload ok"
sleep 2
curl -s -XPOST http://localhost:9090/-/reload && echo "second reload ok"
# Both must print "ok" — if either errors, Prometheus config is in a bad
# state and won't page until a restart.
```

### 3. Test-fire a synthetic CRITICAL alert (confirm end-to-end delivery)

```bash
curl -s -XPOST http://localhost:9093/api/v2/alerts \
    -H "Content-Type: application/json" \
    -d '[{
        "labels": {
            "alertname": "FabtV049DeployTest",
            "severity": "critical",
            "tenant_id": "smoke-test",
            "env": "prod"
        },
        "annotations": {
            "summary": "v0.49 deploy smoke test — please ignore",
            "description": "This alert was fired manually post-deploy to verify the email + ntfy pipeline. Delete from Alertmanager UI or wait for resolve_timeout."
        }
    }]'
```

Expected within ~30 seconds:
1. **Email arrives** at `FABT_ALERT_EMAIL_TO` with subject `[FABT FIRING] CRITICAL: FabtV049DeployTest (tenant smoke-test)`
2. **ntfy push arrives** on the phone app subscribed to the operator's topic
3. Alertmanager UI (SSH tunnel to 9093) shows the alert under Active

If email arrives but no ntfy push: verify `FABT_ALERT_NTFY_URL` + `FABT_ALERT_NTFY_TOPIC` in rendered config; test manual publish per operator-setup guide Part B.5.

If ntfy arrives but no email: check SMTP auth; Gmail app password may need regeneration; review alertmanager container logs:
```bash
docker logs <alertmanager-container> 2>&1 | grep -i "smtp\|email\|error" | tail -20
```

### 3b. V78 (rolled in from v0.48.1) applied + assignment count intact

```bash
# Flyway should have recorded V78 during backend startup after the deploy
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
  SELECT version, description, success, installed_on
  FROM flyway_schema_history
  WHERE version::int >= 76
  ORDER BY installed_rank DESC LIMIT 5;"
# Expected top 3 rows: V78 (seed coordinator_assignments ...), V77, V76 — all success=t.
# V78's installed_on should be <now>. V76+V77 installed_on should match the
# original v0.48.0 deploy timestamp (unchanged).

# Assignment count unchanged from pre-deploy gate 1a (14 rows = no-op re-apply)
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc "
  SELECT COUNT(*) FROM coordinator_assignment ca
  JOIN app_user u ON u.id = ca.user_id
  WHERE u.email LIKE '%@blueridge.fabt.org'
     OR u.email LIKE '%@pamlico.fabt.org';"
# Expected: 14 (same as pre-deploy). Any drift here indicates V78 ran
# against an unexpected starting state — triage before closing deploy.
```

### 3c. Banner smoke-check — one DV coordinator from each new tenant

Quick curl against the pending-count endpoint to confirm the
CoordinatorReferralBanner pipeline still works post-V78 + post-deploy.
Not load-bearing (V78 is a data-level no-op), just confirms nothing
regressed:

```bash
# From laptop — Blue Ridge
T=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"dev-coc-west","email":"dv-coordinator@blueridge.fabt.org","password":"admin123"}' \
  | python -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))")
curl -s -H "Authorization: Bearer $T" https://findabed.org/api/v1/dv-referrals/pending/count
# Expected: {"count":N, "firstPending":...}  — count may be 0 if no pending
# referrals exist in Blue Ridge; that's fine. The response shape is the signal.
```

Same curl with `dev-coc-east` + `dv-coordinator@pamlico.fabt.org` if desired.

### 4. Existing FORCE RLS + cache-isolation rules still loaded

```bash
curl -s http://localhost:9090/api/v1/rules | python3 -c \
  "import sys,json; d=json.load(sys.stdin); groups=d['data']['groups']; print(sum(len(g['rules']) for g in groups))"
# expect: 9 (5 Phase B + 4 Phase C — unchanged from v0.48)
```

---

## 7. Rollback Matrix

V0.49 adds capability but doesn't change existing behavior. If
Alertmanager causes any issue:

```bash
# Stop just the alertmanager service (Prometheus falls back to UI-only alerts)
docker compose ... --profile alerting stop alertmanager
docker compose ... --profile alerting rm -f alertmanager

# Remove the alerting: block from prometheus.yml (one-line removal + SIGHUP)
# Or leave it — prometheus tolerates an unreachable alertmanager
curl -s -XPOST http://localhost:9090/-/reload
```

Backend is unchanged; no backend rollback needed.

### Rollback triage matrix

| Symptom | Action |
|---|---|
| Alertmanager `Up` but `status` shows `configError` | Check `docker logs fabt-alertmanager` for YAML parse error; re-source `~/fabt-secrets/.env.prod` and re-render step 2 (likely SMTP password has unescaped chars). |
| Alertmanager unhealthy (restart loop) | Confirm rendered file has `0` remaining `${FABT_` placeholders. If >0, envsubst whitelist in step 2 missed a var — re-render with all 8 vars listed. |
| Email not arriving (ntfy works) | Gmail app password may have been revoked (regenerate per operator-setup Part A); check `docker logs fabt-alertmanager` for `535 Authentication failed`. |
| ntfy not arriving (email works) | Verify `FABT_ALERT_NTFY_URL` + `_TOPIC` in rendered config; publish manually per operator-setup Part B.5 — if manual publish succeeds the config is wrong, if it fails check ntfy.sh status. |
| Both receivers silent + Prometheus has active alerts | Prometheus may not have reloaded after the `alerting:` block was added. Run reload twice per post-deploy step 2b. |
| FORCE RLS gauge drops to 0 during deploy | **Path B full rollback** — this is a Phase B posture regression unrelated to Alertmanager. See v0.48.0 runbook. |

---

## 8. Post-Deploy Housekeeping

1. **Delete the v0.49 test alert** from the Alertmanager UI (the firing
   `FabtV049DeployTest` alert will self-resolve after ~5 min; speed it
   up by clicking Expire in the UI at `http://localhost:9093` via SSH
   tunnel)
2. **Remove the smoke-test email + ntfy notification** from the operator
   inbox / app (it's noise)
3. **Update memory files:**
   - `project_live_deployment_status.md` → v0.49.0 live, alertmanager
     active, Flyway HWM now 78
   - New: `project_operational_alerting_posture.md` — document the
     dual-receiver active state, ntfy topic UX, email integration,
     resolve-timeout lifecycle, Phase F upgrade path
4. **30-min active watch** for any unexpected rule fires (the 9 existing
   rules now PAGE, not just sit silent; anything that was quietly
   misconfigured will surface here). Keep the phone + inbox visible
   during this window.
5. **Operator comms (internal only):** brief Slack post to `#fabt-demo`:
   ```
   v0.49.0 live at findabed.org — Alertmanager wired.
   CRITICAL alerts now email + push to phone within ~30s (CRITICAL),
   email-only for WARN.
   9 existing rules (Phase B + Phase C) now deliver — no new rules.
   Test alert `FabtV049DeployTest` fired + auto-resolved during deploy.
   No user-visible change. V78 seed migration from v0.48.1 applied
   as a no-op (prod was hot-patched earlier).
   ```
   **No pilot-partner comms** — this is operator-only infrastructure;
   no UI change, no workflow change, no reason to send them email.
6. **Document the alerting posture + v0.48.1 roll-in** in the GitHub
   release notes:
   - **v0.48.1 never shipped separately** — its content (V78 seed fix
     for Blue Ridge + Pamlico coordinator_assignment gap) is bundled
     into v0.49.0. This fact needs to be explicit in the release notes
     so anyone reading later understands why there's a v0.48.1 tag
     with no deploy trail.
   - Dual receiver: email + ntfy push
   - Demo-tier (no BAA, ntfy public topic with long-random shared-secret
     name). Regulated-tier escalation path documented in
     `docs/security/compliance-posture-matrix.md` — Phase F territory

---

### Lessons Surfaced (v0.49 deploy — 10 issues)

The following issues hit during the v0.49 live deploy on 2026-04-20. Each entry
records whether the lesson was already in a memory file (missed) or is new.

| # | Issue | Pre-existing memory? | Outcome |
|---|---|---|---|
| 1 | Env-var trailing-space (`FABT_ALERT_NTFY_TOPIC= xyz`) breaks `source <(grep ...)` | No — new lesson | New pre-deploy gate added to `docs/runbook-template.md` |
| 2 | `chmod 644` needed (not 600) — alertmanager container runs as UID 65534 (`nobody`) | No — new lesson | New pre-deploy gate added to template |
| 3 | `--force-recreate` must include `frontend` — backend-only recreate leaves docker-network stale | Yes — `feedback_prod_docker_build_pattern.md` (MISSED) | Service-recreate matrix row (a) explicitly calls this out |
| 4 | Wait-loop must use `http://localhost:9091/actuator/health`, not public URL | Yes — `project_live_deployment_status.md` Lesson 3 (MISSED) | Now in template § Deploy Steps wait loop with callout |
| 5 | Prometheus `external_labels` invisible to local PromQL — use `scrape_configs.labels` | Yes — `feedback_prometheus_external_labels_gotcha.md` (MISSED) | Not a deploy step, but cited in memory index |
| 6 | Docker bind-mount `prometheus.yml` inode pitfall — `git checkout` = new inode, `/-/reload` fails | Yes — `feedback_bind_mount_inode_pitfall.md` (MISSED) | Service-recreate matrix row (b) + pre-deploy gate |
| 7 | Playwright smoke gate omitted entirely — no end-to-end verification ran | Yes — implied by `feedback_smoke_spec_default_target.md` (MISSED) | Now a mandatory numbered post-deploy gate in template |
| 8 | Alertmanager template `default` (Sprig) not available in v0.27.0 — `function "default" not defined` | No — new lesson | `feedback_alertmanager_template_funcs.md` created; deploy fixed in commit `47d348d` |
| 9 | SMTP password leaked to transcript via `grep ~/fabt-secrets/alertmanager.yml` | No — new lesson | `feedback_never_print_rendered_secrets.md` created; Gmail app password rotated |
| 10 | Fabricated rule names + tenant slugs in `alertmanager-triage-runbook.md` | No — new lesson | `feedback_verify_doc_facts_against_source.md` created; triage runbook rewritten against source |

**Pattern:** issues 3–7 all had pre-existing memory entries that the runbook author did not consult. The `consulted:` frontmatter block above lists what SHOULD have been reviewed; the `not-applicable:` entries flag the two memories that didn't exist yet.

---

## Known posture / follow-ups

- **ntfy public topic** — acceptable for demo-tier per warroom (Marcus);
  alert bodies contain tenant UUIDs + counter names, no PII. Regulated-
  tier upgrade path: authenticated ntfy (self-hosted or Pro) OR PagerDuty
  with BAA. Tracked for Phase F.
- **Single-instance alertmanager** — no HA cluster. If the VM dies,
  alerts die with it. Acceptable for demo; regulated-tier needs HA.
- **Test alert stays in the alertmanager store for `resolve_timeout` (5m)**
  — expected behavior; auto-resolves.
- **Integration test for the receiver pipeline** — not shipped in v0.49;
  requires CI-side ntfy topic + mock SMTP server or ephemeral Gmail. Open
  follow-up: issue #146.
