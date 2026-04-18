# Oracle Deploy Notes — v0.42.0 (multi-tenant-production-readiness Phases 0 + A + A5, Issue #126)

**From:** v0.40.0 (cross-tenant-isolation-audit, currently live)
**To:** v0.42.0 (Phase 0 + Phase A + Phase A5 bundled — three merges ship together)
**Deploy window:** Sunday 2026-04-19, 09:00–16:00 local (per warroom cadence guardrails)

> v0.41.x is **skipped deliberately**. CHANGELOG release-gate forces Phase 0
> + A + A5 to ship together: V74's migration-self-preflight refuses to run
> without V60 + V61 applied. No valid Phase-0-only v0.41.x exists.
>
> **v0.42 → v0.41 rollback requires pg_dump restore.** V74 re-encrypts every
> TOTP / webhook / OAuth2 / HMIS ciphertext under per-tenant DEKs. v0.40
> container images do NOT understand v1 envelopes — running v0.40 against a
> post-V74 database breaks TOTP login, webhook signing, OAuth2 SSO, and
> HMIS outbound calls silently at request time.

## What's New in This Deploy

### Migrations applied (Flyway, in numeric order)

- **V59 (Java)** — `db.migration.V59__reencrypt_plaintext_credentials`. Idempotent re-encryption of existing plaintext OAuth2 client secrets in `tenant_oauth2_provider.client_secret_encrypted` and HMIS API keys in `tenant.config -> hmis_vendors[].api_key_encrypted`. `looksLikeCiphertext` try-decrypt guard skips already-encrypted rows; safe to re-run. Emits one `audit_events` row (`SYSTEM_MIGRATION_V59_REENCRYPT`). **Skips silently with WARN log when `FABT_ENCRYPTION_KEY` is unset** — but post-Phase-0, the app itself refuses to start without the key.
- **V60 (SQL)** — `tenant` table additions: `state TenantState NOT NULL DEFAULT 'ACTIVE'`, `jwt_key_generation INT NOT NULL DEFAULT 1`, `data_residency_region VARCHAR(50) NOT NULL DEFAULT 'us-any'`, `oncall_email VARCHAR(255)`. Single combined `ALTER TABLE` (one ACCESS EXCLUSIVE lock).
- **V61 (SQL)** — creates `tenant_key_material(tenant_id, generation, created_at, rotated_at, active)`, `kid_to_tenant_key(kid, tenant_id, generation, created_at)`, `jwt_revocations(kid, expires_at, revoked_at)` tables + UNIQUE `(tenant_id, generation)` index on `kid_to_tenant_key` for lazy-registration race safety.
- **V74 (Java)** — `db.migration.V74__reencrypt_secrets_under_per_tenant_deks`. **ONE-WAY migration.** Sweeps four columns: `app_user.totp_secret_encrypted`, `subscription.callback_secret_hash`, `tenant_oauth2_provider.client_secret_encrypted`, `tenant.config -> hmis_vendors[].api_key_encrypted`. Preflights V60+V61 presence, bounds lock + statement timeouts, row-locks via `FOR UPDATE`, round-trip-verifies each re-encrypted row before commit. Emits `SYSTEM_MIGRATION_V74_REENCRYPT` audit row with `duration_ms`, master-KEK fingerprint, per-column reencrypted/skipped counts.

### Application changes

- **`SecretEncryptionService` prod-profile fail-fast on missing Phase A4 deps.** Constructor throws `IllegalStateException` at startup in the prod profile if `KeyDerivationService`, `KidRegistryService`, or `RevokedKidCache` are null. Mirrors Phase 0 C2's `MasterKekProvider` pattern — breaks loudly instead of silently downgrading to legacy v0 JWT signing.
- **`JwtService` dual-validate cutover window (D28).** Pre-Phase-A JWTs (no `kid` header, signed under `FABT_JWT_SECRET`) continue to validate via the legacy path for ~7 days post-deploy (refresh-token max lifetime). `fabt.security.legacy_jwt_validate.count` counter monitors usage; a spike during the window indicates either forgotten clients OR forgery attempts with old-format tokens.
- **Per-tenant JWT signing keys via HKDF-SHA256.** `KeyDerivationService.forTenant(tenantId).derive(KeyPurpose.JWT_SIGN)`. Verified against RFC 5869 Appendix A test vectors in `KeyDerivationServiceKatTest`.
- **v1 ciphertext envelope format.** `[FABT magic + version + kid + iv + ct+tag]` Base64-encoded. Decrypt-on-read detects v0 by magic-bytes-absence; v0 fallback path stays alive forever as defense-in-depth (per D42).
- **Admin endpoint `POST /api/v1/admin/tenants/{tenantId}/rotate-jwt-key`** — `PLATFORM_ADMIN`-only, returns 202 + rotation summary.
- **`SecretEncryptionService.encrypt/decrypt` legacy methods** marked `@Deprecated(since = "v0.42", forRemoval = true)` targeting Phase L.

### User-visible changes

- **Sessions older than 7 days are forced to re-login** after v0.42.0 tags. This is the Phase A JWT-format cutover window. No frontend change.
- **No UI changes.** No new routes, no new buttons, no visible feature flips.

## What Does NOT Change

- No DB schema changes outside the V59/V60/V61/V74 migrations.
- No V67-V72 yet — Phase B ships separately as v0.43.0 (Monday).
- No image swap yet — `postgres:16-alpine` remains. Debian + pgaudit lands at v0.44.0 (Tuesday).
- No API contract changes — controllers unchanged at the wire level.
- Frontend bundle unchanged.

---

## Pre-Deploy Checklist

### Operator verification (on the VM, BEFORE deploy starts)

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 1. FABT_ENCRYPTION_KEY is set in the deploy env. V74 refuses to run
#    without it; Phase 0 makes the app refuse to start without it.
grep -c '^FABT_ENCRYPTION_KEY=' ~/fabt-secrets/.env.prod
# Expect: 1 (exactly one line). The value must be a 32-byte base64 string.

# Verify its length. The key is base64-encoded 32 raw bytes → 44 characters.
source ~/fabt-secrets/.env.prod
echo -n "$FABT_ENCRYPTION_KEY" | wc -c
# Expect: 44

# 2. PostgreSQL version MUST be >= 16.6 (release-gate #7 per CHANGELOG).
#    Pulls the CVE-2024-4317 patch floor forward from v0.43 for cheap insurance.
docker compose exec postgres psql -U fabt -d fabt -tAc "SELECT version()"
# Expect a line containing: PostgreSQL 16.6 (or higher patch)
# If 16.5 or below: stop deploy, upgrade the Postgres image first, rerun checklist.

# 3. Pre-deploy pg_dump. V74 is one-way; this dump is the ONLY rollback path.
#    Store both locally and to 1Password before proceeding.
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose exec -T postgres pg_dump -U fabt -Fc fabt > ~/fabt-backups/pre-v0.42-${timestamp}.dump
ls -lh ~/fabt-backups/pre-v0.42-${timestamp}.dump
# Expect: non-zero size (typical ~10-50 MB depending on audit_events history).

# Upload to 1Password entry `fabt-prod-backups`. Do NOT leave the dump only
# on local VM disk — Casey's compliance-posture-matrix requires off-host
# custody chain.

# 4. Sanity-check no orphan NULL-tenant audit rows exist (Phase B pre-gate
#    that we pull forward here to catch early; V74 won't break on them,
#    but v0.43 FORCE RLS will).
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT COUNT(*) FROM audit_events WHERE tenant_id IS NULL"
# Expect: 0 or a small number (4 per memory as of v0.40). Document the value.
# If > 0, these rows will become invisible under v0.43 FORCE RLS — backfill
# before v0.43 tags (separate operator step, non-blocking for v0.42).
```

### Operator communication

- Before running the steps below, post to the #fabt-demo Slack channel:
  > "Starting v0.42.0 deploy at {time}. Sessions older than 7 days will be forced to re-login after this; no other user-visible changes expected. Deploy window: 09:00–16:00. Actively watching metrics for 4h post-deploy."
- Update the findabed.org banner to: "Session security upgrade in progress — you may be asked to sign in again."

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 2. Pull the tagged release
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.42.0
git log --oneline -5
# Expect: tag at HEAD of release/v0.42.0 branch.

# 3. CLEAN backend build (per feedback_deploy_old_jars.md — NEVER skip "clean")
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar
# Expect exactly ONE JAR: finding-a-bed-tonight-0.42.0.jar
cd ..

# 4. Verify migrations present in the JAR
jar tf backend/target/finding-a-bed-tonight-0.42.0.jar \
    | grep -E '(V59|V60|V61|V74)' | sort
# Expect four lines:
#   BOOT-INF/classes/db/migration/V60__tenant_table_additions.sql
#   BOOT-INF/classes/db/migration/V61__per_tenant_key_material_schema.sql
#   BOOT-INF/classes/db/migration/V59__reencrypt_plaintext_credentials.class
#   BOOT-INF/classes/db/migration/V74__reencrypt_secrets_under_per_tenant_deks.class

# 5. Frontend is NOT rebuilt for v0.42 (no frontend changes).

# 6. Build backend Docker image WITHOUT cache
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 7. Restart backend (frontend remains on v0.40 image)
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend

# 8. Wait for backend health
echo "Waiting for backend..."
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy on 9091 (mgmt port)"; break
  fi
  sleep 2
done

# 9. Watch Flyway apply V59/V60/V61/V74 in the logs
docker logs fabt-backend 2>&1 | grep -E "Flyway|Migrating|V59|V60|V61|V74" | tail -30
# Expected lines:
#   Migrating schema "public" to version "59 - reencrypt plaintext credentials"
#   V59 COMMITTED: reencrypted=<n>, skipped=<m>
#   Migrating schema "public" to version "60 - tenant table additions"
#   Migrating schema "public" to version "61 - per tenant key material schema"
#   Migrating schema "public" to version "74 - reencrypt secrets under per tenant deks"
#   V74 COMMITTED: ...

# 10. Docker cleanup
docker image prune -f
```

## Post-Deploy Sanity Checks

### On the VM

```bash
# 1. Flyway history — V59/V60/V61/V74 all success=t
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history \
     WHERE version IN ('59', '60', '61', '74') ORDER BY installed_rank"
# Expect: 4 rows, all success=t.

# 2. Phase A per-tenant key material bootstrapped for every tenant.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT tenant_id, generation, active FROM tenant_key_material \
     ORDER BY tenant_id"
# Expect: at least one row per tenant, generation=1, active=t.

# 3. V74 audit event present with expected shape.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT action, tenant_id, details FROM audit_events \
     WHERE action = 'SYSTEM_MIGRATION_V74_REENCRYPT' \
     ORDER BY created_at DESC LIMIT 1"
# Expect: 1 row; details JSONB has duration_ms, reencrypted counts, fingerprint.

# 4. Run the Phase 0+A+A5 smoke script.
cd ~/finding-a-bed-tonight
FABT_BASE_URL=http://localhost:8080 \
    scripts/phase-0-a-a5-smoke.sh
# Expect: "PASS" for OAuth2 round-trip, HMIS vendor read, TOTP envelope,
#         JWT dual-validate, V74 audit present. Any FAIL = investigate.
```

### Active-watch metrics for 4 hours

Watch in Grafana:

- `fabt.security.legacy_jwt_validate.count` — non-zero is EXPECTED during the 7-day cutover window. Sustained high rate (>10/s) past the first hour = investigate.
- `fabt.security.v0_decrypt_fallback.count{purpose,tenant_id}` — sporadic is EXPECTED for pre-v0.42 cached ciphertext. Climbing rate past hour 2 = a v0 row wasn't re-encrypted — investigate.
- `fabt_http_server_requests_seconds_bucket{quantile="0.95"}` on bed-search, availability-update, DV referral endpoints — compare to baseline. > 2× = investigate.
- JVM heap, GC pause, connection pool (HikariCP active connections) — should be stable.

## Rollback Procedure

### Decision gate

Roll back if ANY of:
- Backend fails to start post-deploy (check: `docker logs fabt-backend | grep FATAL`)
- Flyway migration fails mid-way (V74 throws; see `flyway_schema_history.success=f`)
- `fabt.security.v0_decrypt_fallback.count` climbs past hour 2 (likely a missed re-encryption path)
- Any FAIL in the `phase-0-a-a5-smoke.sh` output

### Rollback steps (one-way — restore from pg_dump)

```bash
# 1. Stop the v0.42 backend (DO NOT let it keep trying to re-encrypt).
docker compose stop backend

# 2. Restore the pre-deploy pg_dump.
timestamp=<value from pre-deploy step 3>
docker compose exec -T postgres psql -U fabt -d fabt \
    -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker compose exec -T postgres pg_restore -U fabt -d fabt \
    < ~/fabt-backups/pre-v0.42-${timestamp}.dump
# Expect: no ERRORs (WARN lines about owners are normal).

# 3. Revert backend container to v0.40.0 image tag.
git checkout v0.40.0
cd backend
mvn clean package -DskipTests -q
cd ..
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker compose up -d --force-recreate backend

# 4. Wait for health, run v0.40 smoke if you have one.
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy"; break
  fi
  sleep 2
done

# 5. Update banner: "Session security upgrade rolled back. No action required."
# 6. Post-mortem:
#    - Capture backend logs from the v0.42 window (pre-rollback)
#    - Document the rollback in docs/security/phase-b-silent-audit-write-failures-runbook.md
#    - Do NOT tag v0.43 until root cause is understood
```

## Known Deviations from Warroom

- **Banner pre-positioning waived** (warroom S3 recommended 7 days; compressed to day-of-deploy only). User-accepted trade-off for Sat-Sun-Mon-Tue deploy cadence.
- **Post-deploy smoke for Phase B surface is NOT yet available** — that lands with v0.43's runbook notes.

## Related

- `docs/runbook.md` — operational runbook (pgaudit section is v0.44, not applicable yet)
- `CHANGELOG.md` — full [v0.42.0] release notes
- `docs/security/timing-attack-acceptance.md` — timing-attack D8 posture
- `docs/architecture/tenancy-model.md` — pool-by-default + silo-on-trigger ADR
- `openspec/changes/multi-tenant-production-readiness/design-a5-v74-reencrypt.md` — Phase A5 design
