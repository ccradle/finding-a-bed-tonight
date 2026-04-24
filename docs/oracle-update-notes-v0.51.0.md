# Oracle Deploy Notes — v0.51.0

**Status:** READY — PR #150 merged 2026-04-24 17:39 UTC; PR #151 (postcss CVE-2026-41305 transitive dep bump) merged 2026-04-24 post-scan; rehearsal PASS attested at `deploy/rehearsal-attest-v0.51.0.txt` (run 20260424-134449).

**Release summary:** Phase F slice F-6 — real crypto-shred.
- Backend-code release (first since v0.49 — v0.50 was ops-tier)
- 6 new Flyway migrations (V79–V84). HWM goes V78 → V84
- Adds `TenantDekService`, `TenantLifecycleService.hardDelete`, per-tenant wrapped DEKs in `tenant_dek`
- Converts the §D11 "crypto-shred" claim from a design statement into a verifiable cryptographic property (anchor test green)
- `hardDelete` capability ships but is NOT exercised in prod this release — no shred scheduled

**From:** v0.50.0 live at `findabed.org`
**To:** v0.51.0

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_compose_chain.md
    # why-cited: postgres will be recreated this release (force-recreate for backend triggers migration-path health dependency); 4-file compose chain is non-negotiable. v0.50 90s postgres outage not to be repeated.
  - file: feedback_deploy_old_jars.md
    # why-cited: backend IS rebuilt this release; mvn clean mandatory before docker build.
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit docker build --no-cache + --force-recreate pattern. Three no-op traps caught during v0.48 live deploy still apply.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: no single-file bind mount changes this release, but --force-recreate pattern is universal.
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: V83 re-encrypts under per-tenant DEKs wrapped by FABT_ENCRYPTION_KEY; never cat the env file at any step.
  - file: feedback_smoke_config_on_prod.md
    # why-cited: post-deploy Playwright smoke must use --config=deploy/playwright.config.ts + positional filter + BASE_URL override (v0.50 lesson #2).
  - file: feedback_flyway_immutable_after_apply.md
    # why-cited: V79-V84 are APPLIED on every dev + Testcontainer run; once this release hits staging, these files are immutable — no further edits.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: every table name, constraint name, migration version, and SQL snippet in this runbook was grepped against source before inclusion.
  - file: feedback_release_after_scans.md
    # why-cited: tag only after CI scans green; gh release create after tag.
  - file: project_live_deployment_status.md
    # why-cited: current prod is v0.50.0, Flyway HWM V78, 3 tenants. v0.51 adds 6 migrations + backend rebuild.
  - file: feedback_deploy_rehearsal_lessons.md
    # why-cited: rehearsal harness caught 4 first-run bugs in v0.50; v0.51 adds new migrations + backend image, expect fresh rehearsal findings.
  - file: project_phase_f_implementation_plan.md
    not-applicable: internal slice-sequencing reference — implementation is complete for this release
  - file: feedback_legal_claims_review.md
    # why-cited: release notes language must use "designed to support" phrasing for NIST/GDPR references; CHANGELOG + crypto-shred runbook legal-scanned.
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.51.0 — Phase F crypto-shred foundation. Real crypto-shred via per-tenant wrapped DEKs.

**From:** v0.50.0 live at `findabed.org`
(confirm current version: `curl -s https://findabed.org/api/v1/version`)

**To:** v0.51.0

### What ships

| Change | Files | Deploy action |
|---|---|---|
| V79 `tenant_state_to_varchar` | `backend/src/main/resources/db/migration/V79__*.sql` | Auto-applies on backend boot |
| V80 `tenant_audit_chain_head_schema` | `V80__*.sql` | Auto-applies |
| V81 `tenant_archive_retention_columns` | `V81__*.sql` | Auto-applies |
| V82 `tenant_dek_schema` | `V82__*.sql` | Auto-applies; adds SECURITY DEFINER trigger function (see §11) |
| V83 `reencrypt_v1_envelopes_under_tenant_dek` | `V83__*.java` (Java migration) | Auto-applies; needs `FABT_ENCRYPTION_KEY` in backend env (already present since v0.42) |
| V84 `tenant_fk_cascade` | `V84__*.sql` | Auto-applies; ALTER TABLE × 18 (each sub-second at pilot scale) |
| `TenantDekService` + `SecretEncryptionService` refactor | `backend/src/main/java/org/fabt/shared/security/` | Backend rebuild required |
| `TenantLifecycleService.hardDelete` | `backend/src/main/java/org/fabt/tenant/service/` | Backend rebuild |
| ArchUnit Family F rules 7.8h + 7.8j | `backend/src/test/java/org/fabt/architecture/CryptoShredArchitectureTest.java` | Test-only (runs in CI) |
| Crypto-shred TDD anchor flipped green | `backend/src/test/java/org/fabt/shared/security/CryptoShredGapIntegrationTest.java` | Test-only |
| New §11 tests (TenantDekRlsTest, TenantDekShredGuardTest, NTenantCanaryShredTest) | `backend/src/test/java/org/fabt/shared/security/` | Test-only |
| Docs: `docs/security/crypto-shred-runbook.md` | NEW | No container action |
| `pom.xml` version bump `0.50.0 → 0.51.0` | `backend/pom.xml` | Landed with backend rebuild. (Prod `/api/v1/version` currently shows `0.49` because v0.50 didn't rebuild the JAR — the checked-in pom is already at 0.50.0. Post-deploy the reported version jumps 0.49 → 0.51, skipping 0.50.) |

### What does NOT change in this deploy

- **Frontend** — no change; no `docker build` for frontend, no force-recreate frontend
- **nginx** — no change (Phase D header stripping from v0.50 stays)
- **Alertmanager** — no change; config stays identical
- **Prometheus** — no change; no new rules in this release
- **Postgres image** — still `fabt-pgaudit:v0.45.0`; no postgres rebuild
- **FORCE RLS posture** — unchanged; V82 adds RLS policies for the new `tenant_dek` table only
- **SSH / VM / compose chain** — same 4-file chain as v0.50; `~/.fabt-secrets/*` unchanged
- **Tenants** — 3 tenants (`dev-coc`, `dev-coc-west`, `dev-coc-east`) remain ACTIVE. No hardDelete scheduled. V83 will re-encrypt existing v1-HKDF envelopes for these 3 tenants under fresh random DEKs (12 new `tenant_dek` rows at pilot scale: 3 tenants × 4 purposes).

### What v0.51.0 enables but does NOT use in prod yet

- `hardDelete` service method is live on the backend, but there is no admin UI, CLI, or API endpoint that invokes it. First prod shred is a future release and will require warroom sign-off + `docs/security/crypto-shred-runbook.md` procedure.

---

## 3. Service-Recreate Matrix

| Service | Changed? | Recreate required? | Reason |
|---|---|---|---|
| `backend` | ✅ Java code changed (TenantDekService, SecretEncryptionService refactor, hardDelete); JAR version bumped to 0.51.0 | ✅ Rebuild image + `--force-recreate`; Flyway runs V79–V84 on boot |
| `postgres` | ❌ no image change, but schema mutates via Flyway at backend boot | ❌ no service recreate, but schema will change irreversibly (pg_dump REQUIRED before deploy) |
| `frontend` | ❌ no change | ❌ |
| `prometheus` | ❌ | ❌ |
| `alertmanager` | ❌ | ❌ |
| `grafana` / `otel-collector` / `jaeger` | ❌ | ❌ |
| Host `nginx` | ❌ | ❌ |

> **Backend recreate triggers Flyway.** The backend container runs Flyway on startup before Spring opens the webserver port. V79–V84 apply before `/actuator/health` returns UP. At pilot scale the full 6-migration chain runs in <5s (V83 per-row tx × ~65 rows + V84 × 18 ALTERs, both sub-second). Watch logs during the first ~30s for `V84 ... Successfully applied`.

---

## 4. Pre-Deploy Gates

- [ ] **Rehearsal green** — `make rehearse-deploy` on operator laptop; confirm PASS. Includes fresh run of V79–V84 against the stub stack. **Expected new rehearsal findings** (this is v0.51's first pass through the harness): V82 trigger function, V83 Java migration encryption-key handling. Log filename: `logs/rehearsal-smoke-YYYYMMDD-HHMMSS.log`.
- [ ] **CI green** — `gh run list --branch feature/phase-f-lifecycle-fsm --limit 5`; all runs green.
- [ ] **Feature branch merged to main** — confirm `feature/phase-f-lifecycle-fsm` is merged before tagging v0.51.0.
- [ ] **pom.xml version bump merged** — `backend/pom.xml` shows `<version>0.51.0</version>`. Prod currently serves JAR `0.49.0.jar` (last built at v0.49 — v0.50 was ops-tier, pom bumped but not rebuilt). Without the v0.51.0 bump, a rebuild would produce `0.50.0.jar` and post-deploy `/api/v1/version` would show `0.50` — the deploy gate would fail.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` must return NO output (v0.49 lesson).
- [ ] **`FABT_ENCRYPTION_KEY` present in `.env.prod`** — V83 Java migration will read it at Flyway boot time. Variable has been in `.env.prod` since v0.42; confirm presence without printing value (`grep -c '^FABT_ENCRYPTION_KEY=' ~/fabt-secrets/.env.prod` expect: 1). **If missing, V83 will WARN-and-skip (Flyway still marks it APPLIED); you then cannot re-run without `DELETE FROM flyway_schema_history WHERE version = '83'`.**
- [ ] **pg_dump backup — MANDATORY** — V82/V83/V84 are irreversible without `pg_restore`. Take the dump BEFORE starting the backend recreate:
      ```bash
      docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc \
          > ~/fabt-backups/fabt-pre-v0.51.0-$(date -u +%Y%m%d-%H%M%S).dump
      ```
- [ ] **SSH access confirmed** — open SSH to the VM before starting; do not assume reachability mid-deploy.
- [ ] **Compose dry-render** — with the full 4-file chain:
      ```bash
      docker compose \
          -f docker-compose.yml \
          -f ~/fabt-secrets/docker-compose.prod.yml \
          -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
          -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
          --env-file ~/fabt-secrets/.env.prod \
          --profile alerting \
          config > /tmp/v0.51.0-config.rendered.yml
      ```
      Inspect: confirm backend image tag does NOT yet reference v0.51.0 (we build it in step 5 below).
- [ ] **Flyway HWM pre-deploy check** — confirm prod is at V78:
      ```bash
      docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
          "SELECT MAX(version::int) FROM flyway_schema_history WHERE success = true;"
      # expect: 78
      ```

---

## 5. Deploy Steps

### 1. Preserve last-good image tag

```bash
# Only backend changes this release — preserve just the backend lastgood.
docker tag fabt-backend:latest fabt-backend:v0.50.0-lastgood
docker images | grep fabt-backend
# Confirm: latest + v0.50.0-lastgood both present with same IMAGE ID
```

### 2. Pull latest main

```bash
cd ~/finding-a-bed-tonight
git fetch origin
git checkout main
git pull origin main
# Confirm: git log --oneline -5 shows the v0.51.0 tag or feature commits at top
```

### 3. Verify pom.xml version bump

```bash
grep '<version>' backend/pom.xml | head -3
# expect: <version>0.51.0</version>
# If 0.50.0, the v0.51 bump commit is missing — STOP and investigate.
# If 0.49.0, this branch is behind main somehow — STOP and investigate.
```

### 4. Confirm `FABT_ENCRYPTION_KEY` is present (no cat)

```bash
grep -c '^FABT_ENCRYPTION_KEY=' ~/fabt-secrets/.env.prod
# expect: 1
# DO NOT cat or grep the value — feedback_never_print_rendered_secrets.md
```

### 5. mvn clean + build backend JAR + build backend image

```bash
# mvn clean FIRST — feedback_deploy_old_jars.md (stale JAR in image is the v0.47 foot-gun)
cd backend
mvn clean package -DskipTests
ls target/*.jar
# expect: finding-a-bed-tonight-0.51.0.jar present; no 0.50.0.jar or 0.49.0.jar

cd ..

# Build backend image --no-cache — feedback_prod_docker_build_pattern.md
docker build --no-cache \
    -f infra/docker/Dockerfile.backend \
    -t fabt-backend:v0.51.0 \
    -t fabt-backend:latest \
    .
docker images fabt-backend --format "table {{.Tag}}\t{{.CreatedAt}}" | head -4
# Confirm: v0.51.0 tag present, new CreatedAt timestamp
```

### 6. Force-recreate backend (triggers Flyway V79–V84)

Use the FULL 4-file compose chain. The v0.50 90s postgres crash loop was caused by a 2-file shorthand — do not repeat.

```bash
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    up -d --force-recreate backend

# Watch Flyway output for ~30s — expect to see V79 through V84 applied in order
docker compose \
    -f docker-compose.yml \
    -f ~/fabt-secrets/docker-compose.prod.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml \
    -f ~/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml \
    --env-file ~/fabt-secrets/.env.prod \
    --profile alerting \
    logs --tail=100 -f backend | grep -E "Migrating schema|Successfully applied|V83 COMMITTED|V84"
```

> **Expected Flyway log signatures:**
> - `Migrating schema "public" to version "79 - tenant state to varchar"`
> - `Migrating schema "public" to version "80 - tenant audit chain head schema"`
> - `Migrating schema "public" to version "81 - tenant archive retention columns"`
> - `Migrating schema "public" to version "82 - tenant dek schema"`
> - `V83 COMMITTED — re-encrypted N TOTP / N webhook / N OAuth2 / N HMIS rows under tenant_dek in Xms; rotation probe: passed`
> - `Migrating schema "public" to version "84 - tenant fk cascade"`
> - `Successfully applied 6 migrations to schema "public", now at version v84`

### 7. Wait for backend health

```bash
# Backend opens the HTTP port AFTER Flyway completes — so a UP response means V79-V84 all committed
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    echo "waiting for backend Flyway + boot..."; sleep 3
done
echo "backend UP"
```

---

## 6. Post-Deploy Gates

### Version check (backend JAR bumped — this is a code-change release)

```bash
curl -s https://findabed.org/api/v1/version
# expect: "0.51" (or "0.51.0" depending on format; check both)

curl -s http://localhost:9091/actuator/info | grep version
# expect: 0.51.0
```

If these report anything other than `0.51.0` (most likely `0.50.0` if the pom-bump commit was missed, or `0.49.0` if the backend wasn't rebuilt at all), re-check step 3 + step 5.

### Flyway HWM

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 8;"
# expect top entry: 84 | tenant fk cascade | t
# expect entries 84, 83, 82, 81, 80, 79 all success = t
```

### V83 audit row — confirms re-encrypt ran

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
    "SET app.tenant_id = '00000000-0000-0000-0000-000000000001';
     SELECT action, details ->> 'totp_reencrypted' AS totp_n,
                    details ->> 'webhook_reencrypted' AS webhook_n,
                    details ->> 'oauth2_reencrypted' AS oauth2_n,
                    details ->> 'hmis_reencrypted' AS hmis_n,
                    details ->> 'rotation_probe_result' AS probe
       FROM audit_events
      WHERE action = 'SYSTEM_MIGRATION_V83_TENANT_DEK'
      ORDER BY timestamp DESC LIMIT 1;"
# expect one row, probe = 'passed' (or 'skipped_no_rows' if fresh DB)
# pilot scale: totp_n + webhook_n + oauth2_n + hmis_n combined ~10-12 rows
```

### V84 CASCADE drift guard — confirm 22 FKs to `tenant` are all CASCADE

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
    "SELECT conrelid::regclass::text AS owning_table, confdeltype
       FROM pg_catalog.pg_constraint
      WHERE confrelid = 'public.tenant'::regclass AND contype = 'f'
      ORDER BY owning_table;"
# expect: 22 rows, every confdeltype = 'c' (CASCADE)
# audit_events is NOT in this list (intentionally FK-less per V57 / Q-F6-5)
```

### tenant_dek populated for existing tenants

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
    "SELECT tenant_id, purpose, generation, active,
            length(wrapped_dek) AS wrapped_bytes
       FROM tenant_dek
      ORDER BY tenant_id, purpose;"
# expect: N rows where N = tenants that had at least one v1-HKDF envelope pre-V83.
# At pilot scale with 3 tenants × 4 purposes, typical N = 4 to 12 depending on
# how many V74-era ciphertext columns were populated. Every wrapped_bytes = 40.
```

### V82 trigger guard installed

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -tAc \
    "SELECT tgname FROM pg_trigger WHERE tgrelid = 'public.tenant_dek'::regclass;"
# expect: tenant_dek_shred_guard_trigger
```

### Playwright smoke (v0.50 lesson #2 — correct invocation)

```bash
# Use --config=deploy/playwright.config.ts + positional filter + BASE_URL override
# (v0.50 lesson: bare `npx playwright test deploy/post-deploy-smoke.spec.ts` fails "No tests found")
cd e2e/playwright
BASE_URL=https://findabed.org npx playwright test \
    --config=deploy/playwright.config.ts \
    post-deploy-smoke \
    --project chromium \
    --reporter=list \
    --trace on \
    2>&1 | tee ../../logs/post-deploy-smoke-v0.51.0.log
# expect: all tests pass; test 13/14 may rate-limit-flake per v0.50 lesson #4 —
# re-run single spec if so, not a regression
```

---

## 7. Rollback Matrix

> **V82/V83/V84 are irreversible without `pg_restore`.** The `~/fabt-backups/fabt-pre-v0.51.0-*.dump` from pre-deploy gate is the canonical rollback artifact. Rollback requires a postgres outage for the restore duration.

| Symptom | Action |
|---|---|
| Flyway fails on V82 trigger/RLS | Check `SET search_path`; V82 tests green in CI so failure in prod is unexpected — capture logs, tag `v0.51.0-failed`, restore from pg_dump, downgrade backend image to `v0.50.0-lastgood`, investigate offline. |
| V83 logs `FABT_ENCRYPTION_KEY not set — skipping re-encryption` | Pre-deploy gate missed; V83 is marked APPLIED with no work done. Re-attempt: `DELETE FROM flyway_schema_history WHERE version = '83';` then `--force-recreate backend`. Does NOT break anything — legacy-HKDF shim in `SecretEncryptionService.decryptV1LegacyHkdf` keeps existing ciphertexts readable. |
| V84 fails mid-ALTER | Unlikely — ACCESS EXCLUSIVE is brief and table scans are pilot-scale. If it happens: transaction rolls back (all 18 ALTERs live in one `DO $$ ... $$` block); re-attempt by `--force-recreate backend`. |
| Post-deploy: tests reach 500s on decrypt | Backward-compat shim broken. Downgrade: `docker tag fabt-backend:v0.50.0-lastgood fabt-backend:latest`; `--force-recreate backend`. New schema rows in `tenant_dek` become unused but don't break v0.50.0 code (it doesn't know the table exists). |
| Full-release rollback (worst case) | (1) Stop backend: `docker compose ... stop backend`. (2) `pg_restore -U fabt -d fabt --clean --create ~/fabt-backups/fabt-pre-v0.51.0-*.dump` (will replace the live DB — expect ~30s outage). (3) Downgrade backend image: `docker tag fabt-backend:v0.50.0-lastgood fabt-backend:latest`. (4) `--force-recreate backend` with full 4-file chain. |
| Backend boots but `/api/v1/version` shows `0.50` (or `0.49`) | pom.xml not bumped in the built JAR (0.50), or backend image wasn't rebuilt at all (0.49 — still running the v0.49-era JAR). Re-check step 3 + 5; rebuild with explicit `mvn clean`; re-run step 6. |

---

## 8. Post-Deploy Housekeeping

- [ ] **Update `project_live_deployment_status.md` memory** — version v0.51.0, Flyway HWM V84, backend image `fabt-backend:v0.51.0`, list V83 audit row counts, confirm `tenant_dek` row count, note "hardDelete capability live but not exercised."
- [ ] **Commit rehearsal attestation** — `deploy/rehearsal-attest-v0.51.0.txt` with the rehearsal log filename.
- [ ] **Update `CHANGELOG.md`** — move `[Unreleased]` items under `[v0.51.0]`.
- [ ] **Prune old images** — `docker image prune -f` after confirming v0.50.0-lastgood tag retained.
- [ ] **Archive spent OpenSpec change** — `multi-tenant-production-readiness` is still active (Phases G+ remain); do NOT archive yet.
- [ ] **File operator verification query** — confirm `docs/security/crypto-shred-runbook.md` is in place; send Riley a pointer so ops knows the verification query exists before the first prod shred.
- [ ] **Update `project_resume_point.md`** (per `feedback_periodic_resume_save.md`).
- [ ] **Delete pre-deploy `.pid-backend` or test .pid files** if left from dev iteration.

---

## What's New in v0.51.0 (release notes summary)

**Real crypto-shred.** Phase F slice F-6 adds per-tenant random DEKs wrapped via AES-KWP (RFC 5649) under a master-KEK-derived wrapping key, stored in a new `tenant_dek` table that `ON DELETE CASCADE` removes on tenant row deletion. The `TenantLifecycleService.hardDelete` method transitions an ARCHIVED tenant to DELETED and fires a single `DELETE FROM tenant` that unwinds 22 CASCADE FKs including `tenant_dek`. Post-commit, the wrapped DEK for that tenant exists nowhere in the live database or in any application cache. A TDD anchor test (`CryptoShredGapIntegrationTest`) asserts an adversary with the master KEK and a pre-shred ciphertext cannot recover plaintext after the shred completes. Designed to support NIST SP 800-88 Rev 2 §2.5 "Cryptographic Erase."

**`hardDelete` ships but is not yet exercised.** v0.51.0 makes the capability available at the service layer. No admin UI or CLI endpoint invokes it; first prod shred is a future release. Operator procedures live in `docs/security/crypto-shred-runbook.md` — including a mandatory 5-query post-shred verification checklist.

**Six new Flyway migrations (V79–V84).** V79 converts `tenant.state` to a VARCHAR-plus-CHECK enum. V80 adds the audit chain head table. V81 adds `offboard_export_receipt_uri` + `archived_at` columns. V82 creates `tenant_dek` with FORCE RLS + a SECURITY DEFINER trigger guard. V83 is a Java migration that re-encrypts the 4 existing v1-HKDF ciphertext columns under fresh random DEKs and writes a diagnostic audit row. V84 flips 18 child-table FKs to `ON DELETE CASCADE`.

**Backend-only release.** Frontend, nginx, alertmanager, and prometheus images are unchanged. The compose chain is the same 4-file prod chain established in v0.44. First backend rebuild since v0.49.

---

*Template: `docs/runbook-template.md` v1 — mandatory for all `oracle-update-notes-vX.Y.Z.md` from v0.50.0 onward*
