# Oracle Deploy Notes — v0.52.0

**Tag:** v0.52.0
**Branch:** `main` post-merge of PRs #153 (G-0), #154 (G-1), #155 (G-2), #156 (G-3)
**Theme:** Phase G slices 0–3 — tamper-evident audit chain + OCI Object Storage external anchor.

---

## 1. Consulted Memories

- `feedback_runbook_template_v1.md` — every oracle-update-notes follows the v1 template shape
- `feedback_runbook_compose_chain.md` — prod docker compose up needs ALL override files in order
- `feedback_no_guessing_deployment.md` — read deploy docs first, never guess on the Oracle VM
- `feedback_never_print_rendered_secrets.md` — never cat / head / grep secret-containing config files
- `feedback_release_after_scans.md` — don't tag releases until CI scans are green
- `feedback_smoke_config_on_prod.md` — post-deploy smoke uses correct Playwright invocation
- `feedback_oci_versioning_vs_retention.md` — bucket cannot have both versioning and retention rule
- `feedback_oci_idcs_user_activation_email.md` — Identity Domains activation email is irrelevant for service principals
- `feedback_audit_events_revoke_update.md` — V70 REVOKE on audit_events; verifier IT uses INSERT not UPDATE
- `feedback_spring_batch_for_scheduled_work.md` — Spring Batch pattern for scheduled work
- `project_phase_g_implementation_plan.md` — Phase G slice plan, G-0 through G-3 merged status

## 2. Scope & Non-Scope

### What ships

- Backend JAR `0.51.0 → 0.52.0`
- Flyway HWM `V84 → V85` (one new migration: `audit_events ADD prev_hash, row_hash` + 32-byte CHECK constraints)
- New runtime dependency: OCI Java SDK 3.85.0 (~ 3 MB across `objectstorage` + `httpclient-jersey3`)
- New observability metrics:
  - `fabt.audit.chain_verify.runs.count{result}`
  - `fabt.audit.chain_verify.drift.count{tenant_id}`
  - `fabt.audit.chain_verify.error.count{tenant_id}`
  - `fabt.audit.chain_verify.duration.seconds`
  - `fabt.audit.chain_verify.rows_verified.count`
  - `fabt.audit.chain_missing_head.count`
  - `fabt.audit.chain_advance_failed.count`
  - `fabt.audit.anchor.upload.count{result, tenant_id}` (when OCI enabled)
  - `fabt.audit.anchor.tenants_anchored.count` (when OCI enabled)
  - `fabt.audit.anchor.duration.seconds` (when OCI enabled)
- New Prometheus rule file: `deploy/prometheus/phase-g-chain-verify.rules.yml` (5 alerts)
- New Spring Batch jobs registered with `BatchJobScheduler`:
  - `auditChainVerifier` — daily 04:00 UTC, `dvAccess=false`
  - `auditChainAnchor` — weekly Mondays 05:00 UTC, `dvAccess=false`, **only when OCI enabled**
- New operator runbook: `docs/security/phase-g-anchor-operator-setup.md` (8-section playbook)

### What does NOT change in this deploy

- Frontend (no rebuild)
- Nginx config (no rebuild)
- Postgres image / pgaudit image (no rebuild)
- Alertmanager config (rule file added, alertmanager itself unchanged)
- Prometheus config (rule file added; reload required, image unchanged)
- Grafana dashboards (panels exist; new metrics auto-appear in queries)
- Existing Flyway migrations (V85 is purely additive ALTER, no historical-data rewrite)

### What v0.52.0 enables but does NOT use in prod yet

- The OCI external anchor activates ONLY if `FABT_OCI_AUDIT_ANCHOR_ENABLED=true` is set in `~/fabt-secrets/.env.prod`. If you deploy v0.52.0 without setting it, the chain hashing + verifier still run (G-1 + G-2), but no anchors flow to OCI.
- Recommended: enable OCI on this deploy. The 14-day OCI retention-rule lock window starts the day the rule was created and locks **2026-05-09 02:16 UTC**. After that the bucket is committed for 7 years; we want real anchors flowing in for at least a few cycles before lock.

## 3. Service-Recreate Matrix

| Service | Recreate | Why |
|---|---|---|
| backend | YES | New JAR, new env vars, new private-key bind-mount |
| postgres | NO | V85 is metadata-only; no image / config change |
| nginx | NO | No frontend or routing changes |
| prometheus | YES (or `kill -HUP`) | New rule file mount |
| alertmanager | NO | Routing unchanged |
| grafana | NO | Auto-discovers new metrics |

## 4. Pre-Deploy Gates

### G1. CI green on main

```bash
gh run list --repo ccradle/finding-a-bed-tonight --branch main --limit 5 \
  --json conclusion,name,createdAt
```

Expected: most recent run on main shows `conclusion=success` for `Backend (Java 25 + Maven)`, `Legal Language Scan`, `Flyway HWM guard`, `pgaudit image tests`, `DV Access Control Canary`. E2E may show pass/skip — acceptable for a backend-only deploy.

### G2. Git tag + GitHub release published

The deploy uses the tag, not main HEAD. Create and push the tag, then publish a GitHub release before any VM action.

```bash
# From the operator workstation, in the code repo
git tag v0.52.0
git push origin v0.52.0
gh release create v0.52.0 --generate-notes --title "v0.52.0 — Phase G slices 0–3"
gh release view v0.52.0
```

Expected: `gh release view v0.52.0` returns the release page with auto-generated notes. `git ls-remote --tags origin v0.52.0` returns the tag SHA matching the release-prep merge commit on main.

> **Why this gate exists:** the deployed commit must equal the tag for audit traceability. `git pull origin main` couples deploy to whatever SHA happens to be on main when SSH runs, breaking the link between the tag, the release page, and the running JAR. Tagging also satisfies the `rehearsal-green-within-72h` process gate in `deploy/release-gate-pins.txt`.

### G3. Flyway HWM verification

Confirm production is currently at V84 (= post-v0.51.0):

```bash
ssh <VM_USER>@<VM_IP>
docker exec -it fabt-postgres psql -U fabt -d fabt -c \
  "SELECT MAX(installed_rank) AS hwm FROM flyway_schema_history;"
# Expected: hwm = 84
```

If hwm < 84, this is not a v0.51.0-baseline VM — STOP and resolve before proceeding.

### G4. OCI bucket sanity (the 14-day window check)

```bash
# From operator workstation
oci os retention-rule list --namespace-name idtfwmx114rw --bucket-name fabt-audit-anchor --output table
```

Expected: one rule named `fabt-7yr-worm`, `time-rule-locked` set to `2026-05-09T02:16:17.000Z` (or thereabouts — the exact timestamp from rule creation).

### G5. Private key on the VM

```bash
# On the VM
ls -la ~/fabt-secrets/oci/audit-anchor.pem
# Expected: -rw------- 1 <VM_USER> <VM_USER>  ~1700  <date>  audit-anchor.pem
```

If the file is absent, deploy the key per `docs/security/phase-g-anchor-operator-setup.md` step 3 BEFORE proceeding.

### G6. Env vars staged in `~/fabt-secrets/.env.prod`

The 9 new lines must be present (see `docs/security/phase-g-anchor-operator-setup.md` section 4).

```bash
# On the VM — check WITHOUT cat'ing the file (per feedback_never_print_rendered_secrets)
grep -c "FABT_OCI_AUDIT_ANCHOR" ~/fabt-secrets/.env.prod
# Expected: 9
```

If the count is anything else, fix the env file before deploy.

### G7. New compose override file present

```bash
# On the VM
ls -la ~/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
# Expected: file exists, ~300 bytes
```

If absent, the bind-mount won't happen and the backend will fail-fast at startup with "OCI private key not readable at configured path."

### G8. pg_dump backup

V85 is a metadata-only ALTER but the backup hygiene rule applies:

```bash
mkdir -p ~/fabt-backups
docker exec fabt-postgres pg_dump -U fabt -d fabt -Fc \
  > ~/fabt-backups/fabt-$(date -u +%Y%m%dT%H%M%SZ)-pre-v0.52.dump
ls -lh ~/fabt-backups/ | tail -1
```

## 5. Deploy Steps

### 1. Preserve last-good image tag

```bash
ssh <VM_USER>@<VM_IP>
docker tag fabt-backend:latest fabt-backend:v0.51.0
docker images fabt-backend
```

### 2. Checkout the v0.52.0 tag

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.52.0
git log --oneline -1
# Expected: HEAD now equals the release-prep merge commit (detached HEAD is correct)
git describe --tags --exact-match
# Expected: v0.52.0
```

> **Do NOT `git pull origin main`.** The deployed commit must equal the tag for
> audit traceability. Pulling main couples deploy to whatever SHA is on main
> when SSH runs, which can drift forward if PRs merge mid-deploy.

### 3. Verify pom.xml version bump

```bash
grep -A 2 "<artifactId>finding-a-bed-tonight" backend/pom.xml | head -4
# Expected: <version>0.52.0</version>
```

### 4. mvn clean + build backend JAR + build backend image

```bash
cd ~/finding-a-bed-tonight/backend
mvn clean package -DskipTests
ls -la target/*.jar | tail -2
# Expected: target/finding-a-bed-tonight-0.52.0.jar (~ 100+ MB fat jar)

cd ~/finding-a-bed-tonight
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker images fabt-backend | head -3
```

(`--no-cache` per the v0.48.0 lesson — three no-op traps were caught during a live deploy because Docker honored a stale layer.)

### 5. Force-recreate backend (triggers Flyway V85)

```bash
docker compose \
  -f docker-compose.yml \
  -f ~/fabt-secrets/docker-compose.prod.yml \
  -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
  -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
  -f ~/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml \
  --env-file ~/fabt-secrets/.env.prod \
  up -d --force-recreate backend
```

**Order matters** — later override files win. The v0.52 override goes last.

### 6. Wait for backend health

```bash
for i in {1..30}; do
  status=$(docker inspect --format='{{.State.Health.Status}}' fabt-backend 2>/dev/null || echo "starting")
  echo "$i: $status"
  if [ "$status" = "healthy" ]; then break; fi
  sleep 5
done
```

If the container fails to come up healthy, check logs:

```bash
docker logs fabt-backend --tail 100 2>&1 | grep -E "ERROR|FATAL|OCI audit-anchor"
```

Common failure: `OCI private key not readable at configured path` — confirm the bind-mount fired (G5 + G7 above).

### 7. Reload Prometheus to pick up the new rule file

If your prometheus has `--web.enable-lifecycle`:

```bash
curl -X POST http://localhost:9090/-/reload
```

Otherwise:

```bash
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  restart prometheus
```

Confirm the new rule loaded:

```bash
curl -sS http://localhost:9090/api/v1/rules | jq '.data.groups[].name' | grep -iE "phase_g|chain"
# Expected: fabt_phase_g_chain_verify, fabt_phase_g_anchor_upload
```

## 6. Post-Deploy Gates

### Version check

```bash
curl -sS https://findabed.org/api/v1/version | jq '.version'
# Expected: "0.52.0"
```

### Flyway HWM advanced

```bash
docker exec fabt-postgres psql -U fabt -d fabt -c \
  "SELECT MAX(installed_rank) AS hwm FROM flyway_schema_history;"
# Expected: hwm = 85
```

### V85 columns present + CHECK constraints active

```bash
docker exec fabt-postgres psql -U fabt -d fabt -c "\d audit_events" \
  | grep -E "prev_hash|row_hash"
# Expected: prev_hash | bytea  ; row_hash | bytea
```

### audit_events rows written post-deploy have hashes

Trigger a real audit row by causing any audit-emitting action (e.g., a coordinator viewing an admin page). Then:

```bash
docker exec fabt-postgres psql -U fabt -d fabt -c "
  SELECT action, prev_hash IS NOT NULL AS has_prev, row_hash IS NOT NULL AS has_row
  FROM audit_events
  WHERE timestamp > NOW() - INTERVAL '5 minutes'
  ORDER BY timestamp DESC LIMIT 5;
"
# Expected: has_prev=t and has_row=t for every row (assuming non-SYSTEM_TENANT_ID)
```

### Trigger the verifier on-demand and confirm zero drift

```bash
PLATFORM_ADMIN_JWT="<paste a fresh PLATFORM_ADMIN JWT>"
curl -X POST -H "Authorization: Bearer $PLATFORM_ADMIN_JWT" \
  https://findabed.org/api/v1/batch/jobs/auditChainVerifier/run

# Wait ~5s, then check execution
curl -H "Authorization: Bearer $PLATFORM_ADMIN_JWT" \
  https://findabed.org/api/v1/batch/jobs/auditChainVerifier/executions | jq '.[0]'
# Expected: status=COMPLETED, exitCode=COMPLETED
```

Drift counter must be zero in Prometheus immediately after:

```bash
curl -sS http://localhost:9090/api/v1/query \
  --data-urlencode "query=sum(fabt_audit_chain_verify_drift_count_total)" | jq
# Expected: result=[] OR value=0
```

### Trigger the anchor on-demand and confirm OCI upload

```bash
curl -X POST -H "Authorization: Bearer $COC_ADMIN_JWT" \
  https://findabed.org/api/v1/batch/jobs/auditChainAnchor/run

curl -H "Authorization: Bearer $PLATFORM_ADMIN_JWT" \
  https://findabed.org/api/v1/batch/jobs/auditChainAnchor/executions | jq '.[0]'
# Expected: status=COMPLETED
```

Confirm objects landed in the bucket from your operator workstation:

```bash
oci os object list --namespace-name idtfwmx114rw --bucket-name fabt-audit-anchor \
  --prefix "audit-anchors/" --output table | tail -10
# Expected: one object per active tenant with non-zero chain head
```

### Anchor payload shape verification

Pull one anchor object back and confirm the JSON envelope has the expected fields. Catches serialization regressions that would otherwise only surface during a real forensic retrieval.

```bash
# Pick the most recent object key from the previous list
ANCHOR_KEY=$(oci os object list --namespace-name idtfwmx114rw \
  --bucket-name fabt-audit-anchor --prefix "audit-anchors/" \
  --output json | jq -r '.data[-1].name')

oci os object get --namespace-name idtfwmx114rw --bucket-name fabt-audit-anchor \
  --name "$ANCHOR_KEY" --file /tmp/anchor-verify.json

jq 'keys_unsorted' /tmp/anchor-verify.json
# Expected: ["tenant_id","last_hash_hex","last_row_id","anchored_at", ...]
# At minimum: tenant_id, last_hash_hex, anchored_at must all be present and non-null

jq '.last_hash_hex | length' /tmp/anchor-verify.json
# Expected: 64 (32-byte SHA-256 rendered as hex)
```

### Anchor-vs-DB hash equality verification

Compare the anchored hash against the live `tenant_audit_chain_head.last_hash` for that tenant. A mismatch means the anchor wrote stale data (serialization or timing bug); catching this in the lock-grace window is the only chance — once the rule locks, we cannot rewrite the bucket.

```bash
ANCHOR_TENANT=$(jq -r '.tenant_id' /tmp/anchor-verify.json)
ANCHOR_HASH=$(jq -r '.last_hash_hex' /tmp/anchor-verify.json)

# On the VM
ssh <VM_USER>@<VM_IP> "docker exec fabt-postgres psql -U fabt -d fabt -tAc \
  \"SELECT encode(last_hash, 'hex') FROM tenant_audit_chain_head WHERE tenant_id = '$ANCHOR_TENANT';\""
# Expected: returns a hex string EQUAL to $ANCHOR_HASH

# Note: if `auditChainVerifier` ran between anchor and this check, the DB head
# may have advanced. Either re-trigger the anchor or accept that the anchor
# represents an earlier-but-valid chain state — verify by checking that the
# DB hash is downstream of the anchor hash, not divergent.
```

### WORM property verification (lock-grace-window only)

The retention rule lock activates **2026-05-09 02:16 UTC**. Until then, this is the only chance to confirm the WORM posture actually holds — the IAM policy already blocks `OBJECT_DELETE` and `OBJECT_OVERWRITE` for the service principal, but the *retention rule itself* needs to be tested with a principal that DOES have those permissions (your admin OCI CLI session).

```bash
# From your admin OCI CLI session (NOT the service principal config) — attempt to delete
oci os object delete --namespace-name idtfwmx114rw --bucket-name fabt-audit-anchor \
  --name "$ANCHOR_KEY" --force 2>&1 | head -5
# Expected: a retention-rule denial (HTTP 409 / RetentionRuleViolation), NOT an IAM denial.
# If the delete SUCCEEDS, STOP — the retention rule is misconfigured and must be fixed
# before lock activation. Re-run anchor job to replace any deleted object.

# Attempt to overwrite (uploads a small dummy file under the same key)
echo '{"tampered":true}' > /tmp/tamper.json
oci os object put --namespace-name idtfwmx114rw --bucket-name fabt-audit-anchor \
  --name "$ANCHOR_KEY" --file /tmp/tamper.json --force 2>&1 | head -5
# Expected: a retention-rule denial. SUCCEEDS = misconfiguration; halt and fix.
rm /tmp/tamper.json
```

> This gate is one-shot. After 2026-05-09 02:16 UTC, the rule is immutable for 7 years and we will not have another chance to verify the WORM property under controlled conditions.

### Playwright smoke (v0.50 lesson — correct invocation)

```bash
cd ~/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org \
  npx playwright test --config=deploy/playwright.config.ts \
  smoke-prod
```

Test 13/14 flake = rate-limit, not a regression. Acceptable.

## 7. Rollback Matrix

| Failure mode | Rollback action |
|---|---|
| Backend fails to start (e.g., OCI key not readable) | Re-tag previous image: `docker tag fabt-backend:v0.51.0 fabt-backend:latest` then re-deploy step 5. Disable OCI by removing `FABT_OCI_AUDIT_ANCHOR_ENABLED=true` from `.env.prod`. |
| V85 migration fails (extremely unlikely — metadata-only) | Restore from `~/fabt-backups/fabt-*.dump` via `pg_restore`. If only the V85 ALTER landed and you need a quick reverse without a full restore: `docker exec fabt-postgres psql -U fabt -d fabt -c "ALTER TABLE audit_events DROP COLUMN row_hash, DROP COLUMN prev_hash; DELETE FROM flyway_schema_history WHERE version = '85';"` (only valid if no row has been written with non-null hashes — confirm with `SELECT COUNT(*) FROM audit_events WHERE row_hash IS NOT NULL;` first). |
| Verifier fires false-positive drift | Disable just the auditChainVerifier job: `POST /api/v1/batch/jobs/auditChainVerifier/disable`. Investigate via verifier logs. The chain-hashing writer keeps running; verifier disable is operationally inert. |
| OCI upload failures | Disable just the auditChainAnchor job (same endpoint pattern). Then debug the OCI service principal credentials per `docs/security/phase-g-anchor-operator-setup.md` section 7. |
| Any unrecoverable issue | Roll the JAR + DB back to v0.51.0; re-tag; recreate. Note: V85 columns will be on the table but unused under the v0.51 JAR (the JAR doesn't reference them). Safe to leave. |

## 8. Post-Deploy Housekeeping

- Update `project_live_deployment_status.md` memory with new HWM (V85), backend image hash, and OCI-enabled state
- Verify the OCI bucket has objects from this deploy's anchor run BEFORE the **2026-05-09 02:16 UTC** lock activation
- If anything is wrong with the bucket configuration, the rule can still be deleted before lock — DO NOT WAIT past 2026-05-09
- Add a calendar reminder for 2026-05-08 to verify the bucket state once more before lock

## What's New in v0.52.0 (release notes summary)

See `CHANGELOG.md` `[v0.52.0]` section for the complete list. Highlights:

- Tamper-evident audit chain (G-0 + G-1): every audit row carries `prev_hash` + `row_hash`, computed in the writer's transaction, atomic with the audit INSERT
- Daily verifier (G-2): re-walks every tenant's chain, recomputes hashes, detects drift via Prometheus alerts
- Weekly external anchor (G-3): uploads chain-head tuples to OCI Object Storage with 7-year locked WORM retention; even a full DB compromise can't tamper with anchored hashes
- Sentinel-aware: `SYSTEM_TENANT_ID` orphans skip hashing; pre-V85 historical rows skipped from verification by NULL-hash filter
- Default disabled OCI integration: dev/CI/local builds never touch OCI; production opts in via env vars
