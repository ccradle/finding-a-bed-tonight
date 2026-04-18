# Oracle Deploy Notes — v0.42.1 (V74 plaintext-tolerance hotfix)

**From:** partial v0.42.0 state (V59/V60/V61 applied; V74 failed + rolled back; backend rolled back to 41bd162)
**To:** v0.42.1 (same Phase 0 + A + A5 content as v0.42.0, with V74 plaintext-fallback patch)
**Deploy window:** 2026-04-18 (Saturday) or a fresh calendar day — operator call

> v0.42.1 is a **code-only hotfix** — no new migrations, no new env-vars, no new
> container images. V74 is modified in place. Safe because V74 was never
> successfully applied in any environment (v0.42.0's attempt resulted in
> Flyway atomic rollback, which scrubbed the V74 record from
> `flyway_schema_history`). V59/V60/V61 remain committed from v0.42.0's
> attempt; v0.42.1's Flyway run will see V74 as PENDING and apply it with
> the plaintext-tolerance guard.

## What's different from v0.42.0

- **V74 Java migration** — plaintext-tolerance catch added to TOTP + webhook paths (mirroring OAuth2 + HMIS existing pattern).
- **audit_events.SYSTEM_MIGRATION_V74_REENCRYPT** JSONB now carries `{totp,webhook,oauth2,hmis}_plaintext_fallback` counters.
- **No migrations added or reordered** — V74's file name + version number are unchanged. Flyway will apply V74 as pending (it's currently not in `flyway_schema_history`).

## What's UNCHANGED from v0.42.0

- Phase 0 + A + A5 functional scope (OAuth2 + HMIS + TOTP + webhook secrets re-encrypted to per-tenant DEKs).
- Frontend bundle (not rebuilt).
- Env vars (`FABT_ENCRYPTION_KEY` / `FABT_TOTP_ENCRYPTION_KEY` fallback chain).
- `docs/oracle-update-notes-v0.42.0.md` pre-deploy + deploy + post-deploy + rollback procedures ALL still apply — substitute `v0.42.1` for `v0.42.0` in git checkout + tag references.

## Pre-Deploy Checklist

Everything from the v0.42.0 checklist still applies and has already been done:
- ✅ `FABT_ENCRYPTION_KEY` present (added 2026-04-18)
- ✅ PG 16.13 (>= 16.6 gate)
- ✅ Pre-deploy pg_dump captured on VM + local (SHA256 `7bace0e3...`)
- ✅ NULL-tenant audit backfill applied (4 rows → SYSTEM_TENANT_ID; JSONL archived)

**Additional step for v0.42.1 (because the VM was rolled back to 41bd162 during v0.42.0 triage):**

- [ ] Re-apply the prometheus.yml fix on the VM if not already done (see `docs/runbook.md` or memory `project_multi_tenant_phase_b_resume.md`). The VM working-tree reverted to `host.docker.internal:9091` when we rolled back to 41bd162; needs `backend:9091` for Prometheus to scrape.

## Deploy Steps (replace v0.42.0 with v0.42.1 in all step references)

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>
cd ~/finding-a-bed-tonight

# 1. Pull the v0.42.1 tag
git fetch --tags
git checkout v0.42.1
git log --oneline -1   # Expect: tag at release/v0.42.1 HEAD

# 2. CLEAN backend build
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar    # Expect: finding-a-bed-tonight-0.42.1.jar
cd ..

# 3. Verify migrations present
jar tf backend/target/finding-a-bed-tonight-0.42.1.jar \
    | grep -E '(V59|V60|V61|V74)' | sort

# 4. Rebuild backend image
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 5. Restart
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend

# 6. Wait for health
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy"; break
  fi
  sleep 2
done

# 7. Watch Flyway apply V74 (this time should complete)
docker logs fabt-backend 2>&1 | grep -E "Flyway|V74|plaintext_fallback|COMMITTED" | tail -20
# Expect: "V74 COMMITTED: ..." line; NO "Migration of schema ... failed"
```

## Post-Deploy Sanity Checks

```bash
# 1. Flyway schema_history shows V74 success=t
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history WHERE version = '74'"
# Expect: 74 | reencrypt secrets under per tenant deks | t

# 2. V74 audit row present with plaintext_fallback counters
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT details FROM audit_events \
     WHERE action = 'SYSTEM_MIGRATION_V74_REENCRYPT' \
     ORDER BY timestamp DESC LIMIT 1"
# Expect: JSONB with webhook_plaintext_fallback: 3 (matching the 3 demo seed rows)
#          and totp_plaintext_fallback: 0 (no TOTP users on demo)

# 3. subscription.callback_secret_hash values are now v1 envelopes (FABT magic prefix)
docker compose exec -T postgres psql -U fabt -d fabt -tAc \
    "SELECT substring(callback_secret_hash, 1, 7) AS magic_prefix, COUNT(*) \
     FROM subscription WHERE callback_secret_hash IS NOT NULL GROUP BY 1"
# Expect: "RkFCVAE" (base64 of "FABT\\x01") for all 4 rows

# 4. Run phase-0-a-a5-smoke.sh
FABT_BASE_URL=http://localhost:8080 scripts/phase-0-a-a5-smoke.sh
# Expect: 5/5 PASS
```

## Rollback

Same procedure as v0.42.0's oracle-update-notes-v0.42.0.md Rollback section — restore from the pre-deploy pg_dump. The dump is still valid (it was taken at 15:23 UTC before V59 ran).

Decision gate: roll back if Flyway V74 still fails with the v0.42.1 patch (unexpected — regression test T12 covers the demo failure shape). Check for a new failure mode in the log before rolling back.

## Related

- `CHANGELOG.md` — full [v0.42.1] notes
- `docs/oracle-update-notes-v0.42.0.md` — base procedures
- `backend/src/main/java/db/migration/V74__reencrypt_secrets_under_per_tenant_deks.java` — patched migration
- `backend/src/test/java/db/migration/V74ReencryptIntegrationTest.java#t12_plaintextFallback_uniformAcrossColumns` — regression test
