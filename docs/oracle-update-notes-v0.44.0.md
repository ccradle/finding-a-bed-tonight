# Oracle Deploy Notes — v0.44.0 (multi-tenant-production-readiness V73 pgaudit + Debian image swap, Issue #126)

**From:** v0.43.0 (Phase B RLS hardening, deployed Monday 2026-04-20)
**To:** v0.44.0 (V73 pgaudit config + Debian+PGDG Postgres image)
**Deploy window:** Tuesday 2026-04-21, 09:00–16:00 local (per warroom cadence guardrails + 24h bake after v0.43)

> This release is **infrastructure-heavy.** The Postgres container image
> changes (Alpine → Debian bookworm + PGDG `postgresql-16-pgaudit`).
> The data volume's UID ownership changes from 70 (Alpine postgres) to
> 999 (Debian postgres). **Recommended rollback is `pg_dump` restore
> onto the prior Alpine image**, not in-place volume re-use.
>
> v0.43 must have baked cleanly for ≥ 24h before tagging this release.
> Verify: run `scripts/phase-b-audit-path-smoke.sh` one more time against
> the live demo; expect all PASS. Verify Phase B alerts not firing in
> the past 24h.

## What's New in This Deploy

### Migration applied (Flyway)

- **V73 (SQL)** — `ALTER DATABASE` writes four `pgaudit.*` session parameters. Config-only, no `CREATE EXTENSION`. Uses `DO $$ … format('ALTER DATABASE %I …', current_database())` so portable across database names.

### Infrastructure changes

- **Postgres image swap:** `postgres:16-alpine` → Debian bookworm base + PGDG `postgresql-16-pgaudit`. Built from `deploy/pgaudit.Dockerfile`.
- **pgaudit extension** installed one-time via `infra/scripts/pgaudit-enable.sh` (superuser `CREATE EXTENSION`). Flyway does NOT install it (Flyway runs as `fabt_owner`, not superuser).
- **`fabt-pgaudit-alert` systemd service** running `infra/scripts/pgaudit-alert-tail.sh` — tails Postgres container logs, matches `NO FORCE RLS` DDL, posts to `FABT_PANIC_ALERT_WEBHOOK` with 5-minute per-table cooldown. Heartbeat file updated every 30s for weekly operator cron health check.

### Application changes

- None. V73 is config-only; the image change is infrastructure.

### CI changes

- **Surefire `<excludedGroups>pgaudit</excludedGroups>`** excludes `@Tag("pgaudit")` tests from default `mvn test`.
- **`pgaudit-tests` Maven profile** inverts: runs only pgaudit-tagged tests with `combine.self="override"` on `<excludedGroups>`.
- **New CI job `pgaudit-image-tests`** builds `fabt-pgaudit:ci` image + runs `mvn test -P pgaudit-tests`.
- **CI trigger extended** to `release/**` branches.

## What Does NOT Change

- No frontend changes.
- No backend application code changes.
- No schema changes outside V73's ALTER DATABASE.
- No new FABT_* environment variables.

---

## Pre-Deploy Checklist

### Operator verification (BEFORE deploy starts)

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 1. v0.43 bake-test — no Phase B alerts in last 24h.
# In Grafana / Prometheus:
#   - fabt_audit_system_insert_count_total rate should be near-zero
#   - fabt_audit_rls_rejected_count_total{sqlstate="42501"} rate should be zero
#   - fabt_rls_force_rls_enabled{table} all at 1 (7 tables)
#   - FabtPhaseB* alerts: none firing
# If any of these are red, investigate v0.43 before proceeding.

# 2. Fresh pre-deploy pg_dump. This is THE rollback path for the image
#    swap (in-place volume re-use is NOT recommended).
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose exec -T postgres pg_dump -U fabt -Fc fabt > ~/fabt-backups/pre-v0.44-${timestamp}.dump
ls -lh ~/fabt-backups/pre-v0.44-${timestamp}.dump

# Upload to 1Password entry `fabt-prod-backups`. Verify the dump is
# restorable BEFORE touching the image — see rehearsal section below.

# 3. Dry-run the pgaudit-enable superuser script.
FABT_PG_SUPERUSER_URL="postgresql://postgres:$(pass fabt/pg-superuser)@localhost/fabt" \
    infra/scripts/pgaudit-enable.sh --dry-run
# Expect: "DRY RUN: would execute CREATE EXTENSION pgaudit" — no action.

# 4. Verify the new Debian image builds locally (pre-deploy rehearsal).
docker build -t fabt-pgaudit:prerehearsal -f deploy/pgaudit.Dockerfile deploy/
# Expect: "writing image … naming to … fabt-pgaudit:prerehearsal done"

# 5. Prep the systemd unit and env file (don't activate yet).
sudo install -m 755 infra/scripts/pgaudit-alert-tail.sh /opt/fabt/
sudo install -m 644 deploy/systemd/fabt-pgaudit-alert.service \
    /etc/systemd/system/
sudo mkdir -p /etc/fabt /var/lib/fabt
sudo install -m 640 -o root -g fabt \
    deploy/systemd/fabt-pgaudit-alert.env.example \
    /etc/fabt/pgaudit-alert.env
sudo $EDITOR /etc/fabt/pgaudit-alert.env
# Fill in FABT_PANIC_ALERT_WEBHOOK from 1Password `fabt-panic-alert-webhook`.
# Leave FABT_POSTGRES_CONTAINER as default 'postgres' unless docker-compose
# project name prefixes it (check: `docker compose ps --format '{{.Names}}'`).
```

### Operator communication

Post to the #fabt-demo Slack channel:
> "Starting v0.44.0 deploy at {time}. Postgres image swap + pgaudit
>  install. ~60s Postgres restart required — application briefly
>  returns 503s during that window. Deploy window 09:00–16:00. Will
>  post rollback signal here if anything fails."

Update findabed.org banner briefly (~5 min warning):
> "Brief database restart in progress — some requests may fail for up
>  to 60 seconds."

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 2. Pull the tagged release
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.44.0
git log --oneline -5

# 3. CLEAN backend build
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar
# Expect exactly ONE JAR: finding-a-bed-tonight-0.44.0.jar
cd ..

# 4. Build the new Debian + pgaudit Postgres image
docker build --no-cache -t fabt-pgaudit:v0.44.0 -f deploy/pgaudit.Dockerfile deploy/
# Tag as the deploy-time reference tag. Don't use :latest to avoid
# accidental pick-up by compose files expecting the Alpine image.

# 5. Build backend Docker image WITHOUT cache
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 6. CRITICAL — Stop the running Postgres container before swapping.
#    The data volume's UID ownership must be flipped from 70 (Alpine)
#    to 999 (Debian) BEFORE the Debian container starts. Otherwise
#    the new container refuses to start with "permission denied" on
#    the data directory.
docker compose stop postgres
sudo chown -R 999:999 <path to pgdata volume on host>
# (Path depends on compose file's volume mapping — typically
#  ~/fabt-secrets/pgdata or a named volume's /var/lib/docker/volumes/...)

# 7. Update docker-compose.prod.yml to reference fabt-pgaudit:v0.44.0
#    for the postgres service image. (Operator edits the file.)

# 8. Start the new Postgres image.
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod \
  up -d postgres

# 9. Wait for postgres healthcheck (verifies pgaudit is preloaded).
for i in {1..60}; do
  if docker compose exec postgres pg_isready -U fabt > /dev/null 2>&1; then
    echo "postgres up"
    break
  fi
  sleep 2
done
docker compose exec postgres psql -U postgres -d fabt -tAc "SHOW shared_preload_libraries"
# Expect: pgaudit (or a list containing it)

# 10. Run the superuser CREATE EXTENSION step.
FABT_PG_SUPERUSER_URL="postgresql://postgres:$(pass fabt/pg-superuser)@localhost/fabt" \
    infra/scripts/pgaudit-enable.sh
# Expect: "pgaudit extension installed: pgaudit | 16.x"
# The script also verifies SHOW pgaudit.log etc. at the end.

# 11. Restart backend with --force-recreate (new JAR + picks up V73).
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend

# 12. Wait for backend health.
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy on 9091 (mgmt port)"; break
  fi
  sleep 2
done

# 13. Watch Flyway apply V73.
docker logs fabt-backend 2>&1 | grep -E "Flyway|V73" | tail -10
# Expect: "Migrating schema public to version 73 - configure pgaudit session parameters"

# 14. Activate the pgaudit-alert systemd service.
sudo systemctl daemon-reload
sudo systemctl enable --now fabt-pgaudit-alert
sudo systemctl status fabt-pgaudit-alert
# Expect: "active (running)" — no errors in the status log.

# 15. Verify heartbeat file updating.
ls -l /var/lib/fabt/pgaudit-alert-tail.heartbeat
# Wait 30s, re-check — mtime should have advanced.

# 16. Docker cleanup.
docker image prune -f
```

## Post-Deploy Sanity Checks

```bash
# 1. Flyway V73 applied.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history \
     WHERE version = '73'"
# Expect: 1 row, success=t.

# 2. pgaudit extension present.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT extname, extversion FROM pg_extension WHERE extname = 'pgaudit'"
# Expect: pgaudit | 16.x

# 3. pgaudit session parameters honored.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SHOW pgaudit.log"
# Expect: write, ddl

docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SHOW pgaudit.log_relation"
# Expect: on

# 4. End-to-end alert pipeline test (synthetic DDL).
docker compose exec postgres psql -U fabt -d fabt -c \
    "CREATE TABLE ztest_pgaudit(); \
     ALTER TABLE ztest_pgaudit ENABLE ROW LEVEL SECURITY; \
     ALTER TABLE ztest_pgaudit NO FORCE ROW LEVEL SECURITY; \
     DROP TABLE ztest_pgaudit;"
# Within 5 seconds, #fabt-demo Slack channel should show:
#   "Phase B detection-of-last-resort: NO FORCE ROW LEVEL SECURITY on
#    ztest_pgaudit at … at timestamp"

# 5. Confirm tailer detected + posted.
sudo journalctl -u fabt-pgaudit-alert --since "1 minute ago" --no-pager
# Expect: "[timestamp] ALERT: …"

# 6. Phase B audit-path smoke still green.
scripts/phase-b-audit-path-smoke.sh
# Expect: 5/5 PASS.
```

### Active-watch metrics for 4 hours

All Phase B metrics (same as v0.43 active-watch) PLUS:
- **pgaudit log volume** — `du -sh /var/lib/docker/containers/<postgres-container-id>/*-json.log` should grow at a measurable-but-bounded rate. If > 100MB/hour, consider `pgaudit.log = 'ddl'`-only mitigation per runbook.
- **Postgres container CPU** — compare to v0.43 baseline. pgaudit adds per-write overhead; > 20% increase sustained = investigate.
- **fabt-pgaudit-alert service health** — `systemctl is-active fabt-pgaudit-alert` should return `active`. Heartbeat file mtime should be < 60s old at all times.

## Rollback Procedure

### Decision gate

Roll back if ANY of:
- Postgres container fails to start on Debian image (check `docker logs`)
- Flyway V73 migration fails (`flyway_schema_history.success=f`)
- CREATE EXTENSION pgaudit fails
- pgaudit log volume or CPU overhead is untenable (30m window)
- Alert pipeline end-to-end test (step 4) doesn't fire the Slack webhook

### Rollback steps — pg_dump restore onto Alpine image (recommended)

```bash
# 1. Stop the Debian Postgres container.
docker compose stop postgres

# 2. Remove the Debian-owned data volume (DANGER — irreversible without
#    the pg_dump from pre-deploy checklist).
sudo rm -rf <path to pgdata volume>
mkdir -p <path to pgdata volume>

# 3. Revert docker-compose.prod.yml to postgres:16-alpine image tag.
$EDITOR ~/fabt-secrets/docker-compose.prod.yml

# 4. Start the Alpine container (fresh empty volume).
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod up -d postgres

# 5. Wait for healthy, then restore the pre-deploy dump.
for i in {1..60}; do
  if docker compose exec postgres pg_isready -U fabt > /dev/null 2>&1; then
    break
  fi
  sleep 2
done
timestamp=<value from pre-deploy step 2>
docker compose exec -T postgres pg_restore -U fabt -d fabt \
    --clean --if-exists --no-owner \
    < ~/fabt-backups/pre-v0.44-${timestamp}.dump
# Expect: no ERRORs (WARN about owners normal).

# 6. Revert backend to v0.43.0.
git checkout v0.43.0
cd backend && mvn clean package -DskipTests -q && cd ..
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker compose up -d --force-recreate backend

# 7. Stop the pgaudit-alert service (no pgaudit anymore).
sudo systemctl disable --now fabt-pgaudit-alert

# 8. Verify v0.43 state.
scripts/phase-b-audit-path-smoke.sh
# Expect: 5/5 PASS.

# 9. Update banner, post to #fabt-demo, start post-mortem.
```

### In-place rollback (NOT RECOMMENDED)

If the Debian container started + applied V73 successfully but later shows an issue, in-place rollback TO Alpine requires a `chown -R 70:70` on the volume, which is symmetric to the forward path but has **unverified correctness** for Debian→Alpine direction. The pg_dump restore path is the tested one. Only attempt in-place if pg_dump backup is corrupted/missing.

## Known Deviations from Warroom

- **Loki+promtail path** substituted with systemd-service + cron-grep→webhook (V4 pragmatic-path per warroom discussion). Same detection semantics.
- **pg_dump + UID-swap rehearsal** was performed by building the image locally and verifying `CREATE EXTENSION pgaudit` works — full VM rehearsal deferred to the deploy itself with pg_dump safety net in place.

## Related

- `docs/runbook.md` pgaudit section — operator procedures, emergency mitigations, image-swap rollback
- `docs/security/phase-b-silent-audit-write-failures-runbook.md` — pgaudit prereqs + panic-script audit ordering
- `CHANGELOG.md` — full [v0.44.0] release notes
- `openspec/changes/multi-tenant-production-readiness/design-b-rls-hardening.md` — Phase B design (Q2 pgaudit image source)
