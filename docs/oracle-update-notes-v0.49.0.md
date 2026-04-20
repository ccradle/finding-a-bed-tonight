# Oracle Deploy Notes — v0.49.0 (Alertmanager routing: Prometheus → email + ntfy push)

**From:** v0.48.1 (currently live at `findabed.org` — 3 tenants, 9 Prometheus rules firing into the UI but no notifications)
**To:** v0.49.0 (Alertmanager container live, dual receivers: email via Gmail SMTP + ntfy.sh push — operator gets paged sub-5-seconds on CRITICAL)

> **Jordan's note (SRE):** Closes task #155 — the largest operational gap
> post-v0.48. 9 rules have been loading since v0.47 (5 Phase B + 4 Phase C)
> and none of them could wake anyone up. With 3 tenants in prod, that gap
> grew a 3× blast radius. This release adds the delivery pipeline.
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
- Phase B FORCE RLS posture — 5 regulated tables stay `t`
- Flyway schema — no new migrations (HWM stays 77 or 78 if v0.48.1 applies first)

---

## Pre-deploy sanity

### 1. Confirm 8 operator env vars present in `~/fabt-secrets/.env.prod`

Per `~/OneDrive/Documents/Ark Public Technology LLC/alertmanager-operator-setup.md`:

```bash
grep -c "^FABT_ALERT_" ~/fabt-secrets/.env.prod
# expect: 8 (SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD,
#           EMAIL_FROM, EMAIL_TO, NTFY_URL, NTFY_TOPIC)
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

diff /tmp/v0.48-config.rendered.yml /tmp/v0.49-config.rendered.yml
# EXPECTED DIFF: new `alertmanager` service block + `alerting:` block in
# prometheus env config. Nothing else should change. If there's drift
# elsewhere, STOP.
```

---

## Deploy steps

### 1. Preserve last-good backend image (no backend code change but standard discipline)

```bash
docker tag fabt-backend:latest fabt-backend:v0.48.1-lastgood 2>/dev/null || \
  docker tag fabt-backend:latest fabt-backend:v0.48.0-lastgood
# (whichever version is currently live)
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

# Protect the rendered file (contains SMTP password)
chmod 600 ~/fabt-secrets/alertmanager.yml

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

### 4. Build backend image `--no-cache` (no backend code change but keeps
    image tag advancing, per `feedback_deploy_old_jars.md` discipline)

```bash
cd ~/finding-a-bed-tonight/backend && mvn -B -DskipTests clean package -q
cd ~/finding-a-bed-tonight
docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.49.0 \
    -t fabt-backend:latest .
```

Frontend is NOT rebuilt (no frontend change in v0.49).

### 5. Start alertmanager + recreate backend to reload prometheus.yml

```bash
source ~/fabt-secrets/.env.prod

docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile observability --profile alerting \
    up -d --force-recreate alertmanager

# Prometheus needs to SIGHUP to reload prometheus.yml and pick up the
# new `alerting:` block. Since v0.47's --web.enable-lifecycle flag is
# active, this is a simple HTTP POST (no restart).
curl -s -XPOST http://localhost:9090/-/reload
# Expect: empty response + 200

# Backend stays on same image; no backend recreate needed (backend has
# no awareness of alertmanager).
```

---

## Post-deploy sanity

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

### 4. Existing FORCE RLS + cache-isolation rules still loaded

```bash
curl -s http://localhost:9090/api/v1/rules | python3 -c \
  "import sys,json; d=json.load(sys.stdin); groups=d['data']['groups']; print(sum(len(g['rules']) for g in groups))"
# expect: 9 (5 Phase B + 4 Phase C — unchanged from v0.48)
```

---

## Rollback

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

---

## After deploy succeeds

1. **Delete the v0.49 test alert** from the Alertmanager UI (the firing
   `FabtV049DeployTest` alert will self-resolve after ~5 min; speed it
   up by clicking Expire in the UI at `http://localhost:9093` via SSH
   tunnel)
2. **Remove the smoke-test email + ntfy notification** from the operator
   inbox / app (it's noise)
3. **Update memory** `project_live_deployment_status.md` to v0.49.0 live
4. **30-min active watch** for any unexpected rule fires (the 9 existing
   rules now PAGE, not just sit silent; anything that was quietly
   misconfigured will surface here)
5. **Document the alerting posture** in the release announcement:
   - Dual receiver: email + ntfy push
   - Demo-tier (no BAA, ntfy public topic with long-random shared-secret
     name). Regulated-tier escalation path documented in
     `docs/security/compliance-posture-matrix.md` — Phase F territory

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
