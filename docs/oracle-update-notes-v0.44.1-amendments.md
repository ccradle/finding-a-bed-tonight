# Amendments to `oracle-update-notes-v0.44.0.md` for the v0.44.1 deploy

> **Read alongside** `docs/oracle-update-notes-v0.44.0.md` (frozen inside the
> v0.44.1 tag). The base procedures are unchanged; this file captures deltas
> learned from the v0.42.0 → v0.42.1 hotfix (2026-04-18) and the v0.43.1
> deploy (2026-04-18), plus VM-specific paths verified at planning time.

**Target tag:** `v0.44.1` (SHA `8a6df8d`).
**Deploying from previous state:** `v0.43.1` (shipped 2026-04-18 20:08 UTC).
**Bake window math:** v0.43.1 V67–V72 applied 2026-04-18 20:08 UTC; 24-hour
warroom guardrail satisfied on or after **2026-04-19 20:08 UTC**. A Monday
2026-04-20 deploy window is well above the guardrail.

## Substitutions to apply while reading the base notes

Throughout `oracle-update-notes-v0.44.0.md`, substitute:

| Base text | Replace with |
|---|---|
| `v0.44.0` (in `git checkout`, `jar tf`, `docker build`, JAR filename, image tag) | `v0.44.1` |
| `finding-a-bed-tonight-0.44.0.jar` | `finding-a-bed-tonight-0.44.1.jar` |
| `fabt-pgaudit:v0.44.0` (docker image tag) | `fabt-pgaudit:v0.44.1` |
| `From: v0.43.0 (Phase B RLS hardening, deployed Monday 2026-04-20)` | `From: v0.43.1 (Phase B RLS, deployed Saturday 2026-04-18)` |
| `CHANGELOG [v0.44.0]` | `CHANGELOG [v0.44.1]` (legal-scan reword only; scope identical to 0.44.0) |

The content difference between `v0.44.1` and `v0.44.0` is a CHANGELOG
wording fix to address a legal-language-scan flag. No migration, code, or
operational changes.

## CRITICAL — Flyway out-of-order override required

V73 (pgaudit config) is numerically below V74 (already applied in v0.42.1).
Flyway default `outOfOrder=false` rejects this; same failure mode we hit
on v0.43.1 with V67–V72. **The v0.44 base notes do not mention this
override.** Without it, backend boots into restart-loop exactly as
v0.43.1's first attempt did.

**What's already in place:** During the v0.43.1 deploy we created a bridge
compose override:

```bash
ls ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
# Contains: SPRING_FLYWAY_OUT_OF_ORDER=true environment override
```

**For v0.44.1 deploy: include this file in every compose invocation.**
Base notes' Deploy Step 11 becomes:

```bash
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    --env-file ~/fabt-secrets/.env.prod --profile observability \
    up -d --force-recreate backend
```

**Do not rename or delete the `-flyway-ooo` file yet.** Decision on
permanent policy (task #151) lands in Phase C. After v0.44.1, V73 is in
`flyway_schema_history` and all future migrations will be V75+ above the
V74 high-water mark; at that point the override becomes unnecessary.

## VM-specific paths (verified 2026-04-18 20:55 UTC)

Base notes leave these abstract. Concrete values:

| Resource | Path |
|---|---|
| pgdata Docker volume | `/var/lib/docker/volumes/finding-a-bed-tonight_postgres_data/_data` |
| pgdata volume size (v0.43.1 state) | ~71 MB — full backup fits in a single pg_dump under 500 KB |
| Current postgres image | `postgres:16-alpine` (what we're swapping away from) |
| init script bind-mount (preserves across image swap) | `~/finding-a-bed-tonight/infra/scripts/init-app-user.sql` → `/docker-entrypoint-initdb.d/01-init-app-user.sql` |
| `prometheus.yml` (base notes assume `deploy/prometheus/prometheus.yml` — wrong) | `~/finding-a-bed-tonight/prometheus.yml` (repo root) |

**Base Deploy Step 6 UID chown becomes:**

```bash
docker compose stop postgres
sudo chown -R 999:999 /var/lib/docker/volumes/finding-a-bed-tonight_postgres_data/_data
```

**Belt-and-suspenders recommendation** — tar the pgdata volume before
chown, in case we need an untouched snapshot to fall back to:

```bash
sudo tar -czf ~/fabt-backups/pgdata-alpine-uid70-$(date -u +%Y%m%dT%H%M%SZ).tar.gz \
    -C /var/lib/docker/volumes/finding-a-bed-tonight_postgres_data _data
ls -lh ~/fabt-backups/pgdata-alpine-uid70-*.tar.gz
```

## Pre-deploy checklist — updates

### Base Step 1 (Phase B alert firing check) — downgrade

Base notes: "Verify Phase B alerts not firing in past 24h."

Reality: `phase-b-rls.rules.yml` is defined in source but **not loaded**
into the running Prometheus on the demo VM (verified 2026-04-18 — 0 rule
groups via `/api/v1/rules`). Alert-firing check is confounded. Substitute
with direct counter inspection:

```bash
source ~/fabt-secrets/.env.prod
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus | grep -E '^fabt_(rls|audit)'
# Expect:
#   fabt_rls_force_rls_enabled{...table="..."} 1.0  (× 7 regulated tables)
#   fabt_rls_tenant_context_empty_count_total{...} <some-non-decreasing-number>
#   fabt_audit_system_insert_count_total   (absent OR 0) — lazy Micrometer
#   fabt_audit_rls_rejected_count_total    (absent OR 0) — lazy Micrometer
```

Phase C task #155 tracks wiring Prometheus rule loading + Alertmanager
routing. Not blocking for v0.44.1.

### Base Step 2 (Fresh pg_dump) — carry-forward

Pre-v0.43 dump is at `~/fabt-backups/pre-v0.43-20260418T193429Z.dump`
(SHA `5599721e…`). Take a **fresh** pre-v0.44 dump — this is THE rollback
path for the image swap, not the old v0.43 dump:

```bash
TS=$(date -u +%Y%m%dT%H%M%SZ)
docker compose exec -T postgres pg_dump -U fabt -Fc fabt \
    > ~/fabt-backups/pre-v0.44-${TS}.dump
ls -lh ~/fabt-backups/pre-v0.44-${TS}.dump
sha256sum ~/fabt-backups/pre-v0.44-${TS}.dump \
    | tee ~/fabt-backups/pre-v0.44-${TS}.dump.sha256
```

Pull to local; SHA-pin; confirm before image swap.

### Base Step 4 (Debian image build rehearsal) — expand to full-path

v0.43.1 taught us partial rehearsal misses real-deploy failure modes
(task #152). For v0.44 this is even more critical because the image swap
+ UID chown + CREATE EXTENSION can each break independently. Recommended
rehearsal:

```bash
# On VM, with pre-v0.44 dump already captured:
CNAME=fabt-v044-rehearsal
# Build Debian+pgaudit image (same Dockerfile used in deploy)
docker build -t fabt-pgaudit:rehearsal -f deploy/pgaudit.Dockerfile deploy/
# Start in isolation on a different port + fresh volume
docker run -d --rm --name "$CNAME" \
    -e POSTGRES_PASSWORD=rehearsal -e POSTGRES_USER=fabt -e POSTGRES_DB=fabt \
    -p 55432:5432 fabt-pgaudit:rehearsal
sleep 5
# Restore the pre-v0.44 dump
docker exec "$CNAME" psql -U fabt -d fabt -c "CREATE ROLE fabt_app NOINHERIT LOGIN PASSWORD 'rehearsal'"
docker cp ~/fabt-backups/pre-v0.44-*.dump "$CNAME:/tmp/dump.pgc"
docker exec "$CNAME" pg_restore -U fabt -d fabt --no-owner --no-privileges /tmp/dump.pgc
# Verify pgaudit preloaded
docker exec "$CNAME" psql -U fabt -d fabt -c "SHOW shared_preload_libraries"
# Test CREATE EXTENSION as superuser
docker exec "$CNAME" psql -U postgres -d fabt -c "CREATE EXTENSION pgaudit"
docker exec "$CNAME" psql -U postgres -d fabt -c "SELECT extname, extversion FROM pg_extension WHERE extname='pgaudit'"
# Apply V73 SQL directly to prove it runs on restored state
docker cp backend/src/main/resources/db/migration/V73__*.sql "$CNAME:/tmp/V73.sql"
docker exec "$CNAME" psql -U fabt -d fabt -f /tmp/V73.sql
docker exec "$CNAME" psql -U fabt -d fabt -c "SHOW pgaudit.log"
# Teardown
docker stop "$CNAME"
```

**Important task #152 note:** even this fuller rehearsal doesn't boot the
Spring Boot backend against the restored DB, so it doesn't exercise
Flyway's orchestration layer. V73 is a single SQL migration so
orchestration risk is minimal, but the lesson stands — if V73 were to
fail at Flyway boot in an unexpected way (e.g., some middleware bean
reacting to pgaudit being present), this rehearsal won't catch it. The
image-swap safety net is the pg_dump itself.

## Deploy steps — updates

### Preserve previous image before `docker build` (v0.43.1 learned)

Before rebuilding `fabt-backend:latest` and the postgres image, preserve
the last-good images so rollback is a retag, not a rebuild:

```bash
docker tag fabt-backend:latest fabt-backend:0.43.1-lastgood 2>/dev/null || true
docker tag postgres:16-alpine  fabt-postgres-alpine:lastgood 2>/dev/null || true
docker images | grep -E 'fabt-backend|fabt-pgaudit|fabt-postgres-alpine|postgres'
```

### Flyway out-of-order must be present in the compose invocation

Already covered above; repeating for emphasis. Every `docker compose up`
for the backend must include the `-f docker-compose.prod-v0.43-flyway-ooo.yml`
override.

### Base Step 14 (pgaudit-alert systemd) — Slack webhook substitution

Base notes require `FABT_PANIC_ALERT_WEBHOOK` from a 1Password entry
`fabt-panic-alert-webhook`. **There is no #fabt-demo Slack channel**
(user decision 2026-04-18; Slack comms deferred indefinitely).

Three options, pick one at deploy time:

1. **Leave webhook empty + rely on systemd-journal** — `pgaudit-alert-tail.sh`
   still logs every match to the container's stderr + systemd journal.
   `journalctl -u fabt-pgaudit-alert -f` tails locally. Zero-destination
   alerting, but all events captured.
2. **Point webhook at a local file writer** — run a tiny sidecar that
   appends to `/var/log/fabt/pgaudit-alerts.log`; operators grep when
   needed. More work for the same zero-destination outcome.
3. **Defer service activation** — skip Base Steps 14–15 entirely; V73
   config still applies; pgaudit still logs to Postgres container
   stdout; alerting pathway activated later when Phase C #155 lands.

**Recommended: option 1.** Minimum moving parts, preserves the tailer
for future wiring, aligns with posture-matrix entry on tenant_context_empty
("control signals are visible via actuator; alerting pathway pending").

### Base Step 16 (Alert pipeline end-to-end test) — downgrade

Synthetic DDL step fires `NO FORCE ROW LEVEL SECURITY` then expects a
Slack alert. With no Slack, verify the tailer caught it via journal:

```bash
# Run the same DDL
docker compose exec postgres psql -U fabt -d fabt -c \
    "CREATE TABLE ztest_pgaudit(); \
     ALTER TABLE ztest_pgaudit ENABLE ROW LEVEL SECURITY; \
     ALTER TABLE ztest_pgaudit NO FORCE ROW LEVEL SECURITY; \
     DROP TABLE ztest_pgaudit;"

# Verify tailer logged the NO FORCE match within 5 seconds
sudo journalctl -u fabt-pgaudit-alert --since "30 seconds ago" --no-pager \
    | grep -iE 'NO FORCE|ALERT'
# Expect at least one line mentioning ztest_pgaudit
```

## Post-deploy sanity checks — updates

### Base Step 5 (`scripts/phase-b-audit-path-smoke.sh`) — substitute with direct queries

Same blocker as v0.43.1: script requires `psql`, `jq`, and 1Password-sourced
`FABT_PG_OWNER_PASSWORD` + `FABT_ACTUATOR_PASSWORD` + `FABT_DRIVE_USER_*`.
None available on VM. Substitute with this direct verification:

```bash
# V73 applied cleanly
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history WHERE version = '73'"
# Expect: 73 | configure pgaudit session parameters | t

# pgaudit extension active
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT extname, extversion FROM pg_extension WHERE extname = 'pgaudit'"
# Expect: pgaudit | 16.x

# pgaudit session parameters wired from V73 ALTER DATABASE
docker compose exec -T postgres psql -U fabt -d fabt -c \
    "SHOW pgaudit.log; SHOW pgaudit.log_relation; SHOW pgaudit.log_catalog; SHOW pgaudit.log_parameter"

# Phase B counters + gauges unchanged
source ~/fabt-secrets/.env.prod
curl -sf -u "$FABT_ACTUATOR_USER:$FABT_ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus | grep -E '^fabt_(rls|audit)'
# Same expectations as pre-deploy
```

### Run the Playwright post-deploy smoke (recommended addition)

v0.43.1 showed the Playwright suite catches user-facing regressions the
DB-level checks miss:

```bash
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts \
    deploy/post-deploy-smoke.spec.ts \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.44.1.log
# Expect: 11/11 passed. Any bucket4j rate-limit Test 6 flake is documented
# in the v0.43.1-amendments file.
```

### Active-watch — tenant_context_empty threshold note

From v0.43.1 warroom (posture matrix): single-user traffic produces ~0.83/s
on `fabt_rls_tenant_context_empty_count_total`. Alert threshold is 1/s for
15 m but NOT loaded in prod. If you observe counter rate approaching or
exceeding 1/s during v0.44.1 bake, it's likely SSE + scheduled-job noise
(characterized in `docs/security/compliance-posture-matrix.md`), not a
Phase B regression. Label enrichment pending (task #154).

## Rollback — updates

Base notes cover pg_dump restore onto Alpine. One clarification:

- **Step 6 (revert backend to v0.43.0)** → revert to **v0.43.1**
  (the correct bake-stable version we're rolling back TO, not v0.43.0
  which failed and was superseded).
- **Step 7 (stop pgaudit-alert service)** — only applies if option 1/2
  above was used in Base Step 14. If deferred (option 3), skip.
- **Step 4 (restore dump)** references `${timestamp}` from Pre-Deploy Step
  2 — use the pre-v0.44 dump captured in the amended Pre-Deploy Step 2
  above, NOT the pre-v0.43 dump.

If deploying from a rolled-back Debian container back to Alpine, the
pgdata volume UID is 999 (from the Debian instance). `sudo chown -R 70:70
<pgdata>` is symmetric but base notes flag it as "unverified correctness."
Safer path: `sudo rm -rf` the pgdata volume + pg_restore from
pre-v0.44 dump onto fresh Alpine container, per Base Rollback Step 2.

## Pre-tag checklist — run BEFORE starting the deploy window

- [ ] CI green on v0.44.1 tag (particularly `pgaudit-image-tests` job).
- [ ] v0.43.1 bake ≥ 24h (2026-04-19 20:08 UTC or later).
- [ ] `tar -czf ~/fabt-backups/pgdata-alpine-uid70-<ts>.tar.gz …` taken.
- [ ] Fresh `pre-v0.44-<ts>.dump` taken + pulled to local + SHA-pinned.
- [ ] `~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml` present on VM.
- [ ] Decision recorded: pgaudit-alert webhook strategy (option 1/2/3).
- [ ] Rehearsal completed per amended Pre-Deploy Step 4.
- [ ] Legal-scan: verify this amendment file doesn't trigger CI legal scan.

## Related

- `docs/oracle-update-notes-v0.44.0.md` — base procedures (frozen in v0.44.1 tag)
- `docs/oracle-update-notes-v0.43.1-amendments.md` — predecessor amendments (out-of-order pattern, script availability)
- `docs/security/compliance-posture-matrix.md` — Phase B audit contract + tenant_context_empty noise floor
- `~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml` — Flyway out-of-order bridge override (on VM, not in repo)
- Tasks #151 (Flyway ooo permanent decision), #152 (rehearsal post-mortem), #154 (label enrichment), #155 (alert-wiring)
