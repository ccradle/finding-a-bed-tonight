# Oracle Deploy Notes — v0.41.0 (multi-tenant-production-readiness Phase 0, Issue #126)

**From:** v0.40.0 (cross-tenant-isolation-audit, currently live)
**To:** v0.41.0 (multi-tenant-production-readiness Phase 0 — latent credential encryption fix)

> v0.41.0 is the **first PR of the 236-task multi-tenant-production-readiness change**.
> Encrypts at rest the OAuth2 client secrets and HMIS API keys that were stored
> as plaintext (with TODO comments) since the original multi-tenant infrastructure
> landed. **Production cutover hardens the contract on `FABT_ENCRYPTION_KEY` —
> the next deploy WILL refuse to start without it.** Read the
> `## Pre-Deploy Checklist` section in full before the cutover window.

## What's New in This Deploy

- **1 new Flyway migration** (V59, Java migration). Auto-applied on backend restart.
  - `db.migration.V59__reencrypt_plaintext_credentials` — re-encrypts existing
    plaintext OAuth2 client secrets in `tenant_oauth2_provider.client_secret_encrypted`
    and HMIS API keys in `tenant.config -> hmis_vendors[].api_key_encrypted`.
    Idempotent — `looksLikeCiphertext` try-decrypt guard skips already-encrypted
    rows; safe to re-run after partial failure. Writes one `audit_events` row
    (`SYSTEM_MIGRATION_V59_REENCRYPT`) inside the migration transaction.
    **Skips silently when `FABT_ENCRYPTION_KEY` is unset** — but post-Phase-0,
    the app itself will refuse to start in that case (see Pre-Deploy Checklist).
- **`SecretEncryptionService` constructor — prod profile fail-fast on missing key.**
  Production deployments that omit `FABT_ENCRYPTION_KEY` (or supply a blank value)
  now throw `IllegalStateException` at startup. Pre-v0.41.0 behavior: only
  WARN-logged and silently degraded. **This is the breaking change for prod ops.**
- **OAuth2 + HMIS credential encryption wired into the read/write paths.**
  Adapters (`ClientTrackAdapter`, `ClarityAdapter`) and OAuth2 login still
  receive plaintext after decryption. No API contract change.
- **Plaintext-tolerant decrypt fallbacks** preserve OAuth2 login + HMIS push
  through the brief window between deploy and V59 completing.
- **No new API endpoints, no new frontend routes.** No user-visible UI change.
- **2 new architecture ADRs** in `docs/`:
  - `docs/architecture/tenancy-model.md` — pool-by-default + silo-on-trigger model
  - `docs/security/timing-attack-acceptance.md` — UUID-not-secret posture
- **`docs/runbook.md`** — new "Required Environment Variables" section. Read before deploy.

## What Does NOT Change

- **No DB schema additions** other than the audit_events row written by V59.
  All existing application tables unchanged.
- **No JWT signing key changes.** Per-tenant JWT keys are Phase A (next PR), not
  Phase 0. Existing access tokens + refresh tokens remain valid through the cutover.
- **No login flow changes.** Users do not need to re-authenticate.
- **No frontend bundle changes.** Frontend is not redeployed.

---

## Pre-Deploy Checklist

### REQUIRED — set `FABT_ENCRYPTION_KEY` on the Oracle VM before starting deploy

Pre-v0.41.0, `SecretEncryptionService` only WARN-logged when this var was missing.
Post-v0.41.0, the prod profile **throws `IllegalStateException` at startup** if it
is unset, blank, or set to the committed dev key.

If the Oracle VM is currently running v0.40.0 without this var (likely — the audit
trail shows no prior runbook step requiring it), the v0.41.0 backend will refuse
to start until it is set.

**Generate a 32-byte base64 key and set it on the VM.** Run on the VM, NOT locally:

```bash
# Generate the key
openssl rand -base64 32
# Example output: 4z8Q+mP3kRn7vE9c1wW2xY5tH8L0aB6dF/gJ4hK9mN0=
# Save this value — it is irrecoverable. Lose the key, lose access to every
# encrypted secret persisted by the application (OAuth2 client secrets,
# HMIS API keys, TOTP secrets, webhook callback secrets).
```

**Persist the key into the systemd unit's environment file:**

```bash
# /etc/systemd/system/fabt-backend.service.d/encryption-key.conf
[Service]
Environment="FABT_ENCRYPTION_KEY=<the-key-you-generated>"
```

```bash
sudo systemctl daemon-reload
# Do NOT restart the service yet — that's the deploy step.
```

**Verify the var is staged for the next start:**

```bash
sudo systemctl show fabt-backend --property=Environment | grep FABT_ENCRYPTION_KEY
# Must print FABT_ENCRYPTION_KEY=<value>; empty output = not set, deploy will fail.
```

> **DO NOT use the value `s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA=` in prod.**
> That string is the dev-start.sh key committed to the public repo. The prod-profile
> guard rejects it explicitly. Generate your own.

### Verify the existing v0.40.0 deploy is healthy

```bash
curl -fsS https://findabed.org/actuator/health | jq .status
# Expected: "UP"
```

### Confirm git tag pulled

```bash
cd /opt/fabt/finding-a-bed-tonight
git fetch --tags
git checkout v0.41.0
git status   # must show "HEAD detached at v0.41.0", clean tree
```

### Confirm latest JAR present (no stale artifact, per `feedback_deploy_old_jars.md`)

```bash
mvn -B -DskipTests clean package
ls -la backend/target/finding-a-bed-tonight-0.41.0.jar
# Confirm timestamp is post-build, file size > 100MB
```

### Backup tenant_oauth2_provider + tenant.config before V59 runs

V59 is idempotent and safe but the pg_dump backup gives a rollback path if
ciphertext somehow corrupts a row.

```bash
pg_dump -U fabt -d fabt -t tenant_oauth2_provider --data-only \
    > /opt/fabt/backups/v0.41-rollback/tenant_oauth2_provider-pre-V59.sql
pg_dump -U fabt -d fabt -t tenant --data-only \
    > /opt/fabt/backups/v0.41-rollback/tenant-pre-V59.sql
```

---

## Deploy Steps

```bash
# 1. Stop existing backend (15s graceful drain, see runbook §Startup & Shutdown)
sudo systemctl stop fabt-backend

# 2. Restart — Flyway runs V59 during startup
sudo systemctl start fabt-backend

# 3. Tail logs and watch for V59 + the prod-profile guard
sudo journalctl -u fabt-backend -f
```

Expect to see in the startup log, in order:

1. `SecretEncryptionService` initializes silently (no warn). If you see the
   warn `"FABT_ENCRYPTION_KEY not set in a non-prod profile — falling back to
   the committed dev key"` you are NOT in the prod profile — fix
   `SPRING_PROFILES_ACTIVE` and restart.
2. Flyway: `Successfully applied 1 migration to schema "public"`
3. Migration log: `V59: re-encrypted N OAuth2 client secret(s) and M HMIS API key(s)`
   (N and M depend on existing tenant config; both can legitimately be 0)
4. Spring Boot: `Started Application in <X> seconds`

Total expected downtime: ~30–60 seconds.

---

## Post-Deploy Smoke

### Verify the audit_events row landed

```sql
psql -U fabt -d fabt -c "
SELECT id, action, details, timestamp
FROM audit_events
WHERE action = 'SYSTEM_MIGRATION_V59_REENCRYPT'
ORDER BY timestamp DESC LIMIT 1;
"
```

Expect one row with `details` JSONB like `{"migration":"V59","oauth2_reencrypted":N,"hmis_reencrypted":M}`.

### Verify ciphertext shape on a sample OAuth2 row

```sql
psql -U fabt -d fabt -c "
SELECT id, length(client_secret_encrypted) AS secret_len,
       LEFT(client_secret_encrypted, 24) AS secret_prefix
FROM tenant_oauth2_provider
LIMIT 5;
"
```

Expect `secret_len` ≥ 60 (ciphertext is base64 of IV + ciphertext + 16-byte
GCM tag — minimum ~50 chars even for 1-byte plaintext) and `secret_prefix`
that looks like Base64 (not the raw plaintext).

### Verify OAuth2 login still works

If any tenant has an active OAuth2 provider, exercise the login flow against it
via the login UI. The decrypt-on-read path means a successful OAuth2 redirect
proves the round-trip works end-to-end.

If no tenant has OAuth2 configured (likely on the demo deploy), this check is N/A.

### Verify HMIS push still works (skip if no HMIS vendor configured)

```bash
curl -fsS -H "Authorization: Bearer <admin-jwt>" \
    https://findabed.org/api/v1/hmis/vendors
```

Returns the vendor list with `apiKeyMasked` field. The masked value confirms
the decrypt path resolved without throwing.

### Cross-tenant isolation regression check (smoke from inside the VM)

```bash
bash infra/scripts/post-deploy-smoke.sh https://findabed.org
```

Expect all checks green. The Phase 0 work should not affect cross-tenant
behavior — this is a regression gate against the v0.40.0 contract.

---

## Rollback Criteria

**Rollback if:**

1. Backend fails to start with `IllegalStateException: FABT_ENCRYPTION_KEY is
   required in the prod profile` → the env var is not set; either set it and
   restart (forward-fix preferred) OR roll back to v0.40.0.
2. Flyway V59 fails with a SQL error → check the audit_events FK or any other
   schema-level issue; rollback path: restore from the pre-V59 pg_dump backup
   AND deploy v0.40.0 jar.
3. OAuth2 login starts returning 500 with `RuntimeException: Failed to
   decrypt secret` → the C1 plaintext-tolerant fallback should prevent this,
   but if observed, V59 may have written corrupted ciphertext — restore from
   `tenant_oauth2_provider-pre-V59.sql` backup and roll back the JAR.
4. `audit_events` table starts showing unexpected rows (`SYSTEM_MIGRATION_V59_REENCRYPT`
   appearing more than once per deploy) → V59's idempotency guard is failing;
   investigate before next deploy.

**Rollback procedure:**

```bash
sudo systemctl stop fabt-backend
git checkout v0.40.0
mvn -B -DskipTests clean package
psql -U fabt -d fabt < /opt/fabt/backups/v0.41-rollback/tenant_oauth2_provider-pre-V59.sql
psql -U fabt -d fabt < /opt/fabt/backups/v0.41-rollback/tenant-pre-V59.sql
# Manually mark V59 as removed from flyway_schema_history so the v0.40 backend can boot:
psql -U fabt -d fabt -c "DELETE FROM flyway_schema_history WHERE version = '59';"
sudo systemctl start fabt-backend
```

> Note: V59 has been APPLIED to the schema by the time this rollback runs. The
> backup-and-replace approach restores plaintext rows; the
> `DELETE FROM flyway_schema_history` line lets v0.40.0's Flyway not panic on
> seeing a "future" migration in the history.

---

## After Deploy Succeeds

- **Bake window: 1 week.** Phase A (per-tenant JWT signing keys + DEK derivation)
  blocks on Phase 0 baking cleanly. No regression alerts in `fabt.security.*`
  metrics during the bake window.
- **Update `project_live_deployment_status.md`** in memory with v0.41.0 entry.
- **Tag PR #127 as merged + closed,** issue #126 stays OPEN (Phase 0 of N).
- **Open the GitHub Discussion announcement** for v0.41.0 with the Phase 0
  scope + the operator-action footnote.

## Tracking

- PR: ccradle/finding-a-bed-tonight#127
- Issue: ccradle/finding-a-bed-tonight#126
- OpenSpec change: `openspec/changes/multi-tenant-production-readiness/` (Phase 0 = tasks 1.1-1.10, all ticked)
- Predecessor deploy notes: `docs/oracle-update-notes-v0.40.0.md` (cross-tenant-isolation-audit)
