# Oracle Deploy Notes — v0.43.0 (multi-tenant-production-readiness Phase B, Issue #126)

**From:** v0.42.0 (Phase 0 + A + A5, deployed Sunday 2026-04-19)
**To:** v0.43.0 (Phase B — V67-V72 + FORCE RLS + observability + audit-path)
**Deploy window:** Monday 2026-04-20, 09:00–16:00 local (per warroom cadence guardrails + 24h bake after v0.42)

> Phase B is **reversible via panic script**, not a reverse Flyway.
> `scripts/phase-b-rls-panic.sh` drops the policies + clears FORCE RLS
> in a single atomic transaction. Rollback cost: re-opens the pre-V68/V69
> attack surface. Only acceptable under active Sev-1 incident command.
>
> v0.42 must have baked cleanly for ≥ 24h before tagging this release.
> Verify: run `scripts/phase-0-a-a5-smoke.sh` one more time against the
> live demo; expect all PASS.

## What's New in This Deploy

### Migrations applied (Flyway, in numeric order)

- **V67** — `fabt_current_tenant_id()` SQL function (`LANGUAGE sql`, `STABLE`, `LEAKPROOF`, `PARALLEL SAFE`). CASE-guarded regex on `current_setting('app.tenant_id', true)` — malformed GUC value returns NULL rather than raising. D59 prepared-statement-plan-caching test proves PostgreSQL doesn't inline the LEAKPROOF STABLE function at PREPARE time.
- **V68** — tenant-RLS policies on 7 regulated tables:
  - **3 fully-scoped** (`audit_events`, `hmis_audit_log`, `hmis_outbox`): single canonical `FOR ALL` policy with identical `USING + WITH CHECK = tenant_id = fabt_current_tenant_id()`.
  - **4 pre-auth** (`password_reset_token`, `one_time_access_code`, `tenant_key_material`, `kid_to_tenant_key`): D45 PERMISSIVE-SELECT + RESTRICTIVE-WRITE split — queried BEFORE TenantContext binding (JWT validate, password reset, kid lookup).
- **V69** — `ALTER TABLE ... FORCE ROW LEVEL SECURITY` on all 7 regulated tables. Owner sessions now enforce the same policies as `fabt_app`. Closes the "pg_dump with owner creds ignores RLS" surface.
- **V70** — `REVOKE UPDATE, DELETE ON audit_events, hmis_audit_log FROM fabt_app`. Audit tables become append-only for `fabt_app` sessions.
- **V71** — supporting `(tenant_id, …)` indexes on `password_reset_token` + `one_time_access_code` for the pre-auth RESTRICTIVE-WRITE split FK-lookup-then-policy-check sequence.
- **V72** — `REVOKE TRUNCATE, REFERENCES ON audit_events, hmis_audit_log FROM fabt_app`. Forensic-table integrity defense-in-depth (checkpoint-2 warroom).

### Application changes

- **`AuditEventService` three-level tenant lookup** — (1) `TenantContext.getTenantId()`, (2) `tryReadSessionTenantId()` via `current_setting('app.tenant_id', true)`, (3) `SYSTEM_TENANT_ID` sentinel fallback + Micrometer counter + WARN log (throttled by Logback `DuplicateMessageFilter`).
- **`AuditEventPersister`** extracted as separate Spring bean so `@Transactional(REQUIRED)` proxy engages. Fixes the Phase B Bug A+D failure mode (self-invocation bypasses proxy).
- **`PasswordResetTokenPersister`** (new) — same persister pattern for `password_reset_token` regulated-table writes. Binds `app.tenant_id` via `set_config(is_local=true)` before UPDATE/INSERT/DELETE. Fix for CI failure round 1.
- **`AccessCodeService.validateCode`** — pre-auth UPDATE now binds `app.tenant_id` before marking the code used. Fix for W-SYS-1 audit (code-replay window under FORCE RLS).
- **`HmisPushService.createOutboxEntriesForTenant`** — batch path now binds `app.tenant_id` at method entry inside the existing `@Transactional`. Fix for W-SYS-1 audit.
- **`BedHoldsReconciliationJobConfig.writeAuditRowDirect`** — per-row tenant attribution (not platform-wide-null) per D55. Fix for CI failure round 1.
- **`ForceRlsHealthGauge`** — 60s `pg_class.relforcerowsecurity` poller exposing `fabt.rls.force_rls_enabled{table}` gauge.
- **`RlsDataSourceConfig`** — `fabt.rls.tenant_context.empty.count` counter on null-tenant connection borrow.
- **B11 ArchUnit rule** (`TenantContextTransactionalRuleTest`) — `@Transactional` methods must NOT call `TenantContext.runWithContext` (enforcing the ordering invariant from `feedback_transactional_rls_scoped_value_ordering.md`). Two documented allowlist exceptions with paired runtime IT (`B11AllowlistIsolationTest`).
- **Defense-in-depth tenant_id predicates** — every raw-SQL UPDATE/DELETE/INSERT on regulated tables now carries `AND tenant_id = ?` explicit predicate in addition to RLS enforcement (D15 regression fix).

### Operational surface (new)

- `docs/security/phase-b-silent-audit-write-failures-runbook.md` — 3 AM operator triage + pgaudit prereqs + panic-script ordering (prepared for v0.44).
- `docs/security/pg-policies-snapshot.md` + `scripts/phase-b-rls-snapshot.sh` — deterministic Markdown snapshot covering `pg_policies`, role grants, FORCE RLS flags, SECURITY DEFINER functions, LEAKPROOF functions.
- `docs/security/compliance-posture-matrix.md` — audit-row = committed-event contract.
- `docs/security/phase-b-audited-write-sites.md` — mechanical audit of 17 regulated-table write sites (0 gaps, all bound via Mechanism A/B/C).
- `scripts/phase-b-audit-path-smoke.sh` — 5-step pre-tag smoke test.
- `scripts/phase-b-rls-panic.sh` — atomic D61 rollback; audit INSERT emitted BEFORE NO FORCE (W-GAUGE-1).
- `scripts/phase-b-rehearsal.sh` — restored-dump rehearsal.
- `.github/workflows/ci.yml`: `phase-b-rls-test-discipline` grep-guard.
- `deploy/prometheus/phase-b-rls.rules.yml` — 5 promtool-validated alerts.

### User-visible changes

- **Forced re-login** is NOT a new event (already happened at v0.42). Phase B is transparent to users — no routes change, no UI change.
- **First-query slight delay** on the 7 regulated tables during the policy-apply window (single-digit ms). Imperceptible in practice.

## What Does NOT Change

- No frontend changes — bundle unchanged.
- No API contract changes.
- No V73 yet — pgaudit + Debian image swap ships separately as v0.44 (Tuesday).
- `postgres:16-alpine` remains (Debian image lands at v0.44).
- No schema changes outside the V67-V72 additions.

---

## Pre-Deploy Checklist

### Operator verification (on the VM, BEFORE deploy starts)

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 1. v0.42 bake-test — all 5 post-deploy smoke checks still PASS.
cd ~/finding-a-bed-tonight
FABT_BASE_URL=http://localhost:8080 \
    FABT_ADMIN_EMAIL=cocadmin@dev.fabt.org \
    FABT_ADMIN_PASSWORD=<from 1Password> \
    scripts/phase-0-a-a5-smoke.sh
# Expect: 5/5 PASS. If any FAIL, DO NOT proceed; investigate v0.42 first.

# 2. Pre-v0.43 NULL-tenant audit backfill (warroom S1 — Casey override).
#    Pre-Phase-B audit_events rows with tenant_id=NULL become invisible
#    under V69 FORCE RLS. Backfill to SYSTEM_TENANT_ID so the row is still
#    visible to tenant-scoped queries + survives pg_policies drift checks.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT COUNT(*) FROM audit_events WHERE tenant_id IS NULL"
# If > 0: archive + backfill BEFORE proceeding:
#   docker compose exec postgres psql -U fabt -d fabt -c \
#     "COPY (SELECT * FROM audit_events WHERE tenant_id IS NULL) \
#      TO '/tmp/pre-phase-b-null-tenant.jsonl' WITH (FORMAT TEXT)"
#   docker compose cp postgres:/tmp/pre-phase-b-null-tenant.jsonl \
#     ~/fabt-backups/
#   docker compose exec postgres psql -U fabt -d fabt -c \
#     "UPDATE audit_events SET tenant_id = '00000000-0000-0000-0000-000000000001' \
#      WHERE tenant_id IS NULL"
# Same query on hmis_audit_log.

# 3. Phase B rehearsal against a restored fresh pg_dump.
#    (Per release-gate #3. Full procedure in phase-b-rehearsal.sh.)
FABT_PG_OWNER_URL=postgresql://fabt:$(pass fabt/pg-owner)@localhost/fabt_rehearsal \
    scripts/phase-b-rehearsal.sh
# Expect: all 6 steps PASS. If not, DO NOT tag v0.43.

# 4. Regenerate pg_policies snapshot from live demo + sign-off.
FABT_PG_OWNER_URL=postgresql://fabt:$(pass fabt/pg-owner)@localhost/fabt \
    scripts/phase-b-rls-snapshot.sh > /tmp/pg-policies-snapshot-live.md
diff /tmp/pg-policies-snapshot-live.md docs/security/pg-policies-snapshot.md
# Expect: diff only in "Last regenerated" timestamp. Any structural
# difference requires CODEOWNERS sign-off before proceeding (release-gate #4).

# 5. Panic-script --dry-run (release-gate #5).
scripts/phase-b-rls-panic.sh --dry-run \
    --reason "pre-v0.43 verification"
# Expect: prints the atomic SQL block starting with SYSTEM_PHASE_B_ROLLBACK
# audit INSERT followed by NO FORCE RLS + DROP POLICY. No execution.

# 6. Prometheus + Alertmanager routing test (release-gate #6).
#    All 5 Phase B alert rules must page successfully. Trigger each one
#    synthetically (see runbook "Phase B alert routing verification" section).
```

### Operator communication

Post to the #fabt-demo Slack channel:
> "Starting v0.43.0 deploy at {time}. Phase B database-layer RLS
>  hardening — transparent to users, no re-login required. Deploy window
>  09:00–16:00. Will post rollback signal here if any alert fires during
>  the first 4h of active-watch."

No user-facing banner required (no user-visible change).

## Deploy Steps

```bash
# 1. SSH to VM
ssh -i ~/.ssh/fabt-oracle ubuntu@<oracle-vm-ip>

# 2. Pull the tagged release
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.43.0
git log --oneline -5

# 3. CLEAN backend build
cd backend
mvn clean package -DskipTests -q
ls -lh target/*.jar
# Expect exactly ONE JAR: finding-a-bed-tonight-0.43.0.jar
cd ..

# 4. Verify Phase B migrations present in the JAR
jar tf backend/target/finding-a-bed-tonight-0.43.0.jar \
    | grep -E '(V67|V68|V69|V70|V71|V72)' | sort
# Expect six .sql files.

# 5. Frontend is NOT rebuilt for v0.43 (no frontend changes).

# 6. Build backend Docker image WITHOUT cache
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .

# 7. Restart backend
docker compose -f docker-compose.yml -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod --profile observability \
  up -d --force-recreate backend

# 8. Wait for backend health
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy on 9091 (mgmt port)"; break
  fi
  sleep 2
done

# 9. Watch Flyway apply V67-V72
docker logs fabt-backend 2>&1 | grep -E "Flyway|Migrating|V6[7-9]|V7[0-2]" | tail -20

# 10. Docker cleanup
docker image prune -f
```

## Post-Deploy Sanity Checks

```bash
# 1. Flyway history — V67..V72 all success=t
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT version, description, success FROM flyway_schema_history \
     WHERE version IN ('67', '68', '69', '70', '71', '72') \
     ORDER BY installed_rank"
# Expect: 6 rows, all success=t.

# 2. FORCE RLS enabled on all 7 regulated tables.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT relname, relrowsecurity, relforcerowsecurity \
     FROM pg_class \
     WHERE relname IN ('audit_events', 'hmis_audit_log', 'hmis_outbox', \
       'password_reset_token', 'one_time_access_code', \
       'tenant_key_material', 'kid_to_tenant_key') \
     ORDER BY relname"
# Expect: 7 rows, all (t, t).

# 3. audit tables are append-only for fabt_app.
docker compose exec postgres psql -U fabt -d fabt -tAc \
    "SELECT grantee, table_name, privilege_type \
     FROM information_schema.role_table_grants \
     WHERE table_name IN ('audit_events', 'hmis_audit_log') \
     AND grantee = 'fabt_app' \
     ORDER BY table_name, privilege_type"
# Expect: 2 rows per table — INSERT + SELECT. NO UPDATE, DELETE,
# TRUNCATE, REFERENCES.

# 4. Run the Phase B audit-path smoke script.
scripts/phase-b-audit-path-smoke.sh
# Expect: 5/5 PASS.

# 5. ForceRlsHealthGauge is reporting fresh data.
curl -s -u "$ACTUATOR_USER:$ACTUATOR_PASSWORD" \
    http://localhost:9091/actuator/prometheus \
    | grep fabt_rls_force_rls_enabled
# Expect: 7 gauge values, all 1 (FORCE RLS enabled).
```

### Active-watch metrics for 4 hours

- `fabt_audit_system_insert_count_total` — non-zero = publisher missed TenantContext. Investigate source.
- `fabt_audit_rls_rejected_count_total{sqlstate="42501"}` — RLS rejection. Investigate source.
- `fabt_rls_force_rls_enabled{table}` — must be 1 on all 7 tables. Zero means FORCE RLS was cleared (panic script or rogue migration).
- `fabt_rls_tenant_context_empty_count_total` — spike past 1/s sustained = hot path missing `runWithContext`.
- `fabt_audit_rls_rejected_count_total{sqlstate!="42501"}` — non-42501 audit-persist error (schema/connection issue).
- p95 latency on bed-search, availability-update, DV referral — should be within 2× of v0.42 baseline.

## Rollback Procedure

### Decision gate

Roll back if ANY of:
- Backend fails to start (likely a Flyway migration failure — check `flyway_schema_history.success=f`)
- Any of the 5 Phase B Prometheus alerts fires in the first 4h active-watch
- p95 latency on critical endpoints > 2× v0.42 baseline for > 5 min

### Rollback steps (panic script — atomic, reversible)

```bash
# 1. Run the panic script.
FABT_PG_OWNER_URL=postgresql://fabt:$(pass fabt/pg-owner)@localhost/fabt \
FABT_PANIC_ALERT_WEBHOOK=<slack-webhook-from-1Password> \
    scripts/phase-b-rls-panic.sh \
    --reason "Sev-1 incident #<id>, <short reason>"
# Expected output: "Phase B RLS rollback COMMITTED."
# Audit row SYSTEM_PHASE_B_ROLLBACK is present + webhook alert posted.

# 2. Revert backend container to v0.42.0 image (keeps V59/V60/V61/V74 intact).
git checkout v0.42.0
cd backend
mvn clean package -DskipTests -q
cd ..
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker compose up -d --force-recreate backend

# 3. Wait for health.
for i in {1..60}; do
  if curl -sf http://localhost:9091/actuator/health > /dev/null 2>&1; then
    echo "healthy (on v0.42)"; break
  fi
  sleep 2
done

# 4. Run phase-0-a-a5-smoke.sh to confirm v0.42 state intact.

# 5. Post-mortem — do NOT retag v0.43 until root cause is understood.
#    Document in docs/security/phase-b-silent-audit-write-failures-runbook.md
#    under "Incidents" section.
```

## Known Deviations from Warroom

- **Banner not required** — no user-visible change in v0.43 (unlike v0.42 re-login).
- **24h bake window** replaces 7d warroom recommendation, per user-confirmed compressed cadence.

## Related

- `docs/runbook.md` — operational runbook (Phase B troubleshooting section)
- `docs/security/phase-b-silent-audit-write-failures-runbook.md` — 3 AM triage
- `docs/security/pg-policies-snapshot.md` — policy shape reference
- `docs/security/compliance-posture-matrix.md` — audit-row contract
- `docs/security/phase-b-audited-write-sites.md` — write-site audit
- `CHANGELOG.md` — full [v0.43.0] release notes
- `openspec/changes/multi-tenant-production-readiness/design-b-rls-hardening.md` — Phase B design
