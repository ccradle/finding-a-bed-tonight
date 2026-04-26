# Oracle Deploy Notes — v0.53.0

**Tag:** v0.53.0 (DRAFT, not yet tagged)
**Branch:** `feature/g-4.4-endpoint-migration` post-merge to `main`
**Theme:** Phase G-4 — platform admin authority split + per-action audit log.

---

## 1. Consulted Memories

```yaml
consulted:
  - feedback_runbook_template_v1.md   # why-cited: every oracle-update-notes from v0.50 onward must follow runbook-template.md shape
  - feedback_runbook_compose_chain.md # why-cited: prod compose up needs ALL override files in order; missing v0.44-pgaudit.yml = postgres crash loop (v0.50 lesson)
  - feedback_smoke_config_on_prod.md  # why-cited: post-deploy Playwright smoke needs FABT_BASE_URL + --config=deploy/playwright.config.ts + positional filter; test 13/14 flake = rate-limit not regression
  - feedback_never_print_rendered_secrets.md  # why-cited: never cat / head / grep secret-containing config files (.env.prod, alertmanager.yml). Leaked SMTP password during v0.49 deploy.
  - feedback_release_after_scans.md   # why-cited: never tag GitHub release until CI scans (Trivy, ZAP, deps, SBOM) green
  - feedback_pgaudit_include_dir_existing_volume.md  # why-cited: V44 pgaudit Dockerfile include_dir only on fresh pgdata
  - feedback_prod_docker_build_pattern.md  # why-cited: canonical prod deploy = explicit docker build --no-cache BEFORE compose up --force-recreate; three no-op traps caught during v0.48 live deploy
  - feedback_verify_doc_facts_against_source.md  # why-cited: read source files before writing docs; v0.49 had 7/9 wrong Prom rule names
  - feedback_no_named_stakeholders_in_docs.md  # why-cited: no real external stakeholder names in committed docs
  - feedback_persona_transparency.md  # why-cited: never reference AI personas as real contributors
  - feedback_legal_claims_review.md   # why-cited: scan for overclaim language before any release
  - feedback_deploy_old_jars.md       # why-cited: always mvn clean — old JARs cause Docker to deploy stale code
  - feedback_deploy_checklist_v031.md # why-cited: full deploy checklist including pg_dump backup
  - feedback_flyway_immutable_after_apply.md  # why-cited: never modify an applied Flyway migration file
  - feedback_cleanup_old_artifacts.md # why-cited: remove stale JARs and Docker images post-deploy
  - feedback_no_ip_in_repo.md         # why-cited: VM IP fine in memory, must NOT appear in git-tracked files
  - feedback_no_ssh_tunnels.md        # why-cited: share SSH tunnel commands, don't auto-execute them
  - feedback_truthfulness_above_all.md  # why-cited: never claim pilots, partnerships, or compliance that does not exist
  - feedback_actuator_security.md     # why-cited: actuator binds 9091 localhost-only — public URL returns 404
  - project_live_deployment_status.md # why-cited: prod compose chain currently 5 files; actuator on 9091 localhost-only; v0.52 OCI anchor live
  - project_phase_g_implementation_plan.md  # why-cited: Phase G slice plan, G-4.x landed
  - project_resume_point.md           # why-cited: branch HEAD + test status snapshot at deploy time
```

## 2. Scope & Non-Scope

### What ships

- Backend JAR `0.52.0 → 0.53.0`
- Flyway HWM `V85 → V89` — three new migrations:
  - **V87** — `platform_user` + `platform_user_backup_code` + `platform_key_material` schema; bootstrap row at id `00000000-0000-0000-0000-000000000fab` inserted **LOCKED with no credentials** (operator must activate post-deploy — see §5.10)
  - **V88** — `platform_user_lockout_columns` (`failed_mfa_attempts_at`, `locked_out_at`, `last_totp_code`, `last_totp_used_at`) + SECURITY DEFINER wrappers
  - **V89** — `platform_admin_access_log` append-only audit table
- Frontend rebuild **not required** (v0.53.0 is backend-only — no platform-operator UI in this slice; admin actions still happen through existing tenant-admin pages)
- New SecurityConfig URL rule layer (verified at `SecurityConfig.java:179-220`):
  - `/api/v1/auth/platform/**` — public (login flow)
  - `/api/v1/admin/tenants/**` — `PLATFORM_OPERATOR` only (lifecycle, key rotation)
  - `/api/v1/test/platform/unlock-expired` — `permitAll` (profile-gated controller; dev/test only — bean does not exist in prod, gated by `@Profile("dev | test")`)
  - `/api/v1/tenants/**` widened from `PLATFORM_ADMIN` to `hasAnyRole("PLATFORM_OPERATOR", "PLATFORM_ADMIN")` for the deprecation window
- New ArchUnit rules:
  - `NoPlatformAdminPreauthorizeTest` (no controller `@PreAuthorize` references PLATFORM_ADMIN)
  - `TestControllerProfileGuardTest` (every Test*Controller has `@Profile("dev | test")` gate)
- 2 new Playwright specs (`platform-admin-access-log.spec.ts`, `platform-totp-lockout.spec.ts`) + 1 smoke spec
- 3 new dev-seed `platform_user` rows (bootstrap `0fab`, smoke `0fa1`, lockout-target `0fa2`) — LOCAL DEV ONLY; `seed-data.sql` has a runtime DB-name guard that REFUSES non-dev/test DBs
- **G-4.6 — TenantLifecycleController REST endpoints.** 4 new platform-operator endpoints (`POST /api/v1/tenants/{id}/suspend`, `/unsuspend`, `/offboard`, `DELETE /api/v1/tenants/{id}`) finally expose the `TenantLifecycleService` (shipped in v0.51) over HTTP. Pre-G-4.6, lifecycle actions in prod ran via psql against the DB owner — no audit row, no MFA gate, no operator identity, no justification. All 4 endpoints carry the full platform-operator authority stack (`@PreAuthorize("PLATFORM_OPERATOR")` + `@PlatformAdminOnly` aspect + `X-Platform-Justification` header + MFA-verified JWT). Gated behind `fabt.tenant.lifecycle.enabled` (default false); operators flip the flag in `prod-*.env` post-deploy when ready to retire the psql break-glass path. New `TenantLifecycleExceptionAdvice` (with `@Order(HIGHEST_PRECEDENCE)`) maps FSM rejections → 409 and tenant-state-guard rejections → 404 (read) / 503 (write). New `fabt.platform.audit.tenant_id_fallback{action=...}` Counter — should be **zero in steady state**; any non-zero rate indicates a `@PlatformAdminOnly` controller is misnaming its tenant-id parameter and silently miswriting the AE chain (operators should add a `>0` alert rule).

### What does NOT change in this deploy

- Tenant authentication flow (`POST /api/v1/auth/login`) — unchanged
- Existing PLATFORM_ADMIN-bearing app_user accounts — V87 backfills `COC_ADMIN` so they continue to work for tenant-scoped admin tasks (user mgmt, shelter mgmt, OAuth2 provider config, API keys, TOTP admin)
- HMIS push (`POST /api/v1/hmis/push`) authority — remains COC_ADMIN-tenant-scoped per F16; new confirm-header gate + audit row added
- Prometheus rules / Grafana dashboards — no new alerts in this deploy (F18 deferred to G-4.5)
- Nginx config — no changes
- Compose override chain — same 5 files as v0.52 (no new override file in v0.53)

### Known limitations shipped intentionally

- **F13** — `after_state` always NULL on PAL rows in v0.53 (Decision 11)
- **F14** — Cross-tenant operator-driven HMIS push deferred to post-v0.53
- **F16** — HMIS push authority broadening + audit-chain regression vs G-4.3 baseline (mitigated, customer-comms includes disclosure — see §3)
- **F17** — Playwright fixture caching deferred to G-4.5 (CI runtime cost)
- **F18** — Prometheus alert on per-operator tenant-update rate deferred to G-4.5
- **F19** — Platform-operator observability config persistence IT deferred to G-4.5
- **F20** — Audit gaps on platform-scoped reads + cross-tenant batch metadata reads (deliberate v0.53 decision; see design.md F20)

---

## 3. Customer-comms note (C-S1)

**Required reading for every CoC pilot operator-of-record before deploy.**
**Timing: send ≥24 hours before deploy + confirm read-receipt from each operator-of-record before opening §4 deploy gates.**

Send the text below verbatim to each pilot's operator-of-record via the contact email used during pilot onboarding. (Do NOT use any name, role, or affiliation in the committed runbook — pilot mappings live in private operator-comms ledgers.)

> **FABT v0.53 — what's changing for your account**
>
> Existing operator accounts continue to work for everyday admin tasks:
> user management, shelter management, OAuth2 provider setup, API keys,
> coordinator assignments, TOTP admin operations. No re-login required.
> Your existing username + password + TOTP enrolment carries forward.
>
> A small set of platform-wide actions (creating new tenants, suspending /
> offboarding tenants, rotating per-tenant JWT keys, triggering platform
> batch jobs, OAuth2 connection-test calls) now require a separate
> "platform operator" login at `https://findabed.org/auth/platform/login`
> with mandatory time-based one-time password (TOTP) using an authenticator
> app (Google Authenticator, Authy, etc.). The platform operator is a
> distinct identity from your tenant operator account; if you need access
> to these actions, reply to this email to coordinate activation.
>
> One usability change you'll see day-one: the **HMIS export trigger**
> (`Push to HMIS Vendors` button on the HMIS settings page, or
> `POST /api/v1/hmis/push` if you script against the API) now requires a
> confirmation header (`X-Confirm-HMIS-Push: CONFIRM`). The admin UI
> wires this automatically; if you script the call, add the header.
> Every HMIS push now leaves an audit-event row with your user id, the
> vendor list, and the bed-inventory snapshot count — visible in the
> tenant audit-events report.
>
> One scope-of-authority disclosure (F16): in v0.53 the HMIS push
> endpoint accepts the COC_ADMIN role (previously required PLATFORM_ADMIN).
> Operators with COC_ADMIN-only roles who never had PLATFORM_ADMIN are
> now authorized to trigger HMIS pushes for their tenant. The
> confirmation header + audit-event row are the mitigations.

---

## 4. Pre-Deploy Gates

Phases run in chronological order. Each phase blocks the next.

### Phase A — Pre-PR-merge

- [ ] Backend full `mvn test` green on the feature branch (1246+/1246+ at this writing; new G-4.4 enum-type cases land at HEAD)
- [ ] Playwright full suite green via `dev-start.sh --nginx` + `BASE_URL=http://localhost:8081 npx playwright test --project=chromium --trace on 2>&1 | tee logs/g-4.4-playwright-post-f16.log`
- [ ] PR description includes the per-endpoint role-migration table linked at `openspec/changes/platform-admin-split-and-access-log/role-migration-table.md` (M-S1)
- [ ] Reviewer signs off endpoint-by-endpoint on the role-migration table

### Phase B — Pre-tag (post-merge)

- [ ] CI green on `main` post-merge (Trivy, ZAP, deps, SBOM all pass — per `feedback_release_after_scans.md`, do NOT tag until all scans green)
- [ ] CHANGELOG entry promoted from `[Unreleased]` to `[v0.53.0]` with deploy date
- [ ] `backend/pom.xml` version bumped to `0.53.0` (NOT a snapshot)
- [ ] Tag `v0.53.0` cut from `main` HEAD: `git tag -a v0.53.0 -m "G-4.4 platform-admin-split"; git push origin v0.53.0`
- [ ] GitHub release created from the tag with CHANGELOG entry as body

### Phase C — Pre-deploy (≥24h before deploy)

- [ ] Customer-comms email sent to each pilot operator-of-record (§3 verbatim text)
- [ ] Read-receipt confirmed from each operator
- [ ] Memory `project_resume_point.md` updated with branch HEAD + intent to deploy
- [ ] Flyway dry-run executed against a copy of prod DB; V87+V88+V89 verified clean

### Phase D — At-deploy (operator on the VM)

- [ ] `docker images` confirms the prior backend image is preserved before pulling new tag (rollback safety)
- [ ] `pg_dump` backup taken — store off-VM with timestamp
- [ ] Confirm 5-file compose chain on the VM (see §5)
- [ ] OCI WORM lock activation date still in window (active per v0.52 deploy)

---

## 5. Deploy Steps

### 5.0. Static content (docs site) — ship the multi-tenant Try-It-Live + role labels FIRST

Static content is served from `/var/www/findabed-docs/` on the
Oracle VM. Nginx serves these via `try_files` — no restart needed
after copying. Static deploys safely BEFORE the backend swap because
the new HTML is content-only (no API contract change); the old
backend serves it just fine until §5.6 swaps the image.

v0.53 ships **8 stale files** that need to land before any demo
visitor sees the new platform-operator login flow:

```bash
# From your local Windows / Git Bash machine. FABT_VM_IP is set
# out-of-band per feedback_no_ip_in_repo (it lives in memory + the
# operator's local env, never in git).

# Root + demo walkthroughs — Try-It-Live multi-tenant matrix (17
# accounts × 3 CoC tenants), monitoring notice, "five or six roles
# per tenant" caption fix, role label sweep across walkthroughs:
scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/index.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/

scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/demo/index.html \
  /c/Development/findABed/demo/analyticsindex.html \
  /c/Development/findABed/demo/hmisindex.html \
  /c/Development/findABed/demo/shelter-onboarding.html \
  /c/Development/findABed/demo/for-coordinators.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/

# Training material:
scp -i ~/.ssh/fabt-oracle \
  /c/Development/findABed/docs/training/admin-onboarding-checklist.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/docs/training/

# Verify on VM:
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP} "
  echo '=== root index ==='
  ls -la /var/www/findabed-docs/index.html
  grep -c 'dev-coc-west\\|dev-coc-east' /var/www/findabed-docs/index.html
  echo '=== demo walkthroughs ==='
  ls -la /var/www/findabed-docs/demo/{index,analyticsindex,hmisindex,shelter-onboarding,for-coordinators}.html
  grep -l 'PLATFORM_ADMIN\\|COC_ADMIN' /var/www/findabed-docs/demo/*.html || echo 'no raw role enums (expected)'
  echo '=== training ==='
  ls -la /var/www/findabed-docs/docs/training/admin-onboarding-checklist.html
"
# Expected:
#   index.html ~24779 bytes (vs Apr-9 baseline ~20081)
#   `grep -c dev-coc-west` returns >= 1 in root index.html
#   demo/index.html ~27840 bytes (vs Apr-12 baseline ~23787)
#   no raw role enums (COC_ADMIN/PLATFORM_ADMIN/OBSERVABILITY) in
#     demo/*.html — those were swapped to human labels in §6.a
```

**No nginx reload required** — static content is read at every request
(nginx caches headers, not bodies). Service worker caching on the
client side may serve stale HTML until next refresh; users testing
immediately after deploy should hard-reload (Ctrl+Shift+R) or open
incognito. See `feedback_stale_sw_on_deploy.md`.

The supplementary `demo/shelter-edit-walkthrough.md` (markdown form)
is intentionally NOT scp'd — the walkthroughs render as `*.html`;
the `.md` is reference-only for the docs repo and never served as
a user page. Same for the dvindex/hmisindex companions that didn't
change in v0.53.

### 5.1. Preserve last-good image tag

```bash
ssh <VM_USER>@<VM_IP>
docker tag fabt-backend:latest fabt-backend:v0.52.0
docker images fabt-backend
```

### 5.2. Checkout the v0.53.0 tag

```bash
cd ~/finding-a-bed-tonight
git fetch origin --tags
git checkout v0.53.0
git log --oneline -1
git describe --tags --exact-match
# Expected: v0.53.0
```

> **Do NOT `git pull origin main`.** The deployed commit must equal the tag for audit traceability.

### 5.3. Verify pom.xml version bump

```bash
grep -A 2 "<artifactId>finding-a-bed-tonight" backend/pom.xml | head -4
# Expected: <version>0.53.0</version>
```

### 5.4. pg_dump backup

```bash
docker exec fabt-postgres pg_dump -U fabt -d fabt --schema-only > ~/fabt-backups/v0.53.0-pre-schema.sql
docker exec fabt-postgres pg_dump -U fabt -d fabt --data-only > ~/fabt-backups/v0.53.0-pre-data.sql
ls -la ~/fabt-backups/v0.53.0-*.sql
```

Store off-VM. Do NOT skip — V87 introduces append-only triggers + REVOKEs; rollback options are limited if anything goes wrong.

### 5.5. mvn clean + build backend JAR + build backend image

```bash
cd ~/finding-a-bed-tonight/backend
mvn clean package -DskipTests
ls -la target/*.jar | tail -2
# Expected: target/finding-a-bed-tonight-0.53.0.jar (~ 100+ MB fat jar)

cd ~/finding-a-bed-tonight
docker build --no-cache -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker images fabt-backend | head -3
```

(`--no-cache` per `feedback_prod_docker_build_pattern.md` — three no-op traps caught during v0.48 live deploy because Docker honored stale layers.)

### 5.6. Force-recreate backend (triggers Flyway V87 + V88 + V89)

**5-file compose chain** — order matters; later override files win.

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

The v0.52 OCI anchor override stays in the chain — v0.53 does not introduce a new override file.

### 5.7. Wait for backend health (localhost:9091, NOT public URL)

Per `project_live_deployment_status.md` v0.47 lesson — actuator binds **port 9091 localhost-only**. The public URL returns 404.

```bash
until curl -fsS http://localhost:9091/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
  sleep 3
  echo "waiting for backend..."
done
echo "backend UP"
```

If the container fails to come up healthy, check logs:

```bash
docker logs fabt-backend --tail 100 2>&1 | grep -E "ERROR|FATAL|Flyway"
```

### 5.8. Verify Flyway HWM advanced

```bash
docker exec fabt-postgres psql -U fabt -d fabt \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history WHERE version IN ('V85','V87','V88','V89') ORDER BY installed_rank DESC;"
```

Expected: V87, V88, V89 each show `success=t` with `installed_on` matching the deploy window.

### 5.9. Verify backend app version

Per the v0.52 prod pattern: `/api/v1/version` returns the SemVer with the trailing `.0` stripped.

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.53"}
```

If you see `"0.52"` the new image isn't live — re-check §5.1 / §5.5.

### 5.10. Activate the bootstrap platform_user

V87 ships the bootstrap row LOCKED with no email/password. `seed-data.sql` is dev/test only (refuses prod DB names), so the operator must activate manually before any platform-operator login can succeed. This step replaces what dev does via seed-data.

```bash
# Step 1 — generate a bcrypt hash for the chosen platform-operator password
# using the HashPasswordCli tool packaged in the deployed backend JAR.
# (No separate fabt-cli module — the CLI lives at
# org.fabt.tooling.HashPasswordCli inside the existing finding-a-bed-tonight
# Spring Boot fat JAR, invoked via Spring Boot's PropertiesLauncher with a
# main-class override.)
ssh <VM_USER>@<VM_IP>
docker exec -it fabt-backend \
  java -cp /app/finding-a-bed-tonight.jar \
       -Dloader.main=org.fabt.tooling.HashPasswordCli \
       org.springframework.boot.loader.launch.PropertiesLauncher
# Tool prompts for password (no echo on TTY); prints the bcrypt hash on stdout.
# Copy the single-line bcrypt hash for Step 2.
# Note the `.launch.` segment — Spring Boot 4 moved PropertiesLauncher under
# the `loader.launch` package; older Spring Boot 3 docs use the unscoped
# `loader.PropertiesLauncher` which now ClassNotFoundExceptions.

# Step 2 — UPDATE the bootstrap row with the chosen email + bcrypt hash.
# REVOKEs prevent fabt_app from doing this; run as fabt OWNER.
# Note: account_locked stays TRUE until first successful MFA enrollment.
docker exec fabt-postgres psql -U fabt -d fabt -c "
UPDATE platform_user
SET email          = '<chosen-operator-email>',
    password_hash  = '<bcrypt-hash-from-step-1>',
    account_locked = false
WHERE id = '00000000-0000-0000-0000-000000000fab';
"

# Step 3 — operator opens https://findabed.org/auth/platform/login and:
#   (a) submits email + password
#   (b) is forced through MFA setup on first login (mfa_enabled=false initially)
#   (c) scans QR / enters TOTP secret in their authenticator app
#   (d) confirms the first TOTP code to complete enrollment
# After step 3, mfa_enabled=true and platform-operator login flow is live.
```

> **Do NOT print the .env.prod file or any rendered config to confirm credentials**, per `feedback_never_print_rendered_secrets.md` — the SMTP password leak in v0.49 was caused by exactly this. Validate via the web flow only.

---

## 6. Post-Deploy Smoke Verification

### 6.1. Backend health (localhost:9091)

Already done in §5.7. Re-run if doubt: `curl -fsS http://localhost:9091/actuator/health | jq .`

### 6.2. Version literal

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.53"} (note: trailing ".0" is stripped by maven property substitution)
```

### 6.3. Existing tenant operator login still works (no regression)

Run the deploy-verify Playwright suite per `feedback_smoke_config_on_prod.md`:

```bash
cd ~/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org \
  npx playwright test \
  --config=deploy/playwright.config.ts \
  --project=deploy-verify
```

**Acceptable flake:** test 13/14 (rate-limit-triggered fail) is NOT a regression per memory — re-run once if it trips. Real regressions show up consistently.

### 6.4. Platform login flow (only after §5.10 activation completes)

**Skip this gate if §5.10 has not yet been completed by the operator.**

In a browser:
1. Open `https://findabed.org/auth/platform/login`
2. Enter the email + password set in §5.10
3. Confirm: redirected to MFA setup (first time) OR MFA verify (subsequent)
4. Complete MFA flow
5. Confirm: returns access token (browser shows the success page or returns to a target URL)

If §5.10 hasn't run yet, this gate cannot pass — and that is the correct state. Document the activation date in the deploy log.

### 6.5. HMIS push admin UI (no regression)

Have any existing tenant operator (admin@dev or staging-tenant cocadmin):
1. Log in via `/auth/login`
2. Navigate to HMIS Settings page
3. Click `Push to HMIS Vendors` button
4. Confirm: returns success (status: push initiated, outboxEntriesCreated number)
5. Navigate to tenant audit-events report
6. Confirm: new row with action=`HMIS_EXPORT_TRIGGERED`, actor matches the logged-in user, vendor list + count populated

### 6.6. Tenant-scoped admin operations regression sweep

Spot-check (one each):
- User CRUD: list users → create user → deactivate
- Shelter CRUD: edit a shelter
- OAuth2 provider: list providers
- API key: list keys
- TOTP admin: disable a user's TOTP (test user only)

All should succeed for COC_ADMIN-bearing accounts (the previous PLATFORM_ADMIN-bearing accounts now also have COC_ADMIN per V87 backfill).

### 6.7. G-4.6 TenantLifecycleController surface check (read-only — flag stays OFF)

The G-4.6 endpoints ship behind `fabt.tenant.lifecycle.enabled=false` (default). DO NOT flip the flag in this deploy — surface check confirms the controller bean is gated off correctly so traffic continues hitting the existing psql break-glass path until operators rehearse the new flow against staging.

```bash
# Should return 404 (controller bean not registered when flag is false).
# A 404 here = correct — the @ConditionalOnProperty gate is working.
# A 401/403/405 here = the controller bean IS registered. Flag was flipped
# accidentally OR the @ConditionalOnProperty annotation regressed. Investigate.
curl -sk -o /dev/null -w "%{http_code}\n" \
  -X POST https://findabed.org/api/v1/tenants/$(uuidgen)/suspend \
  -H 'X-Platform-Justification: surface-check-must-404'
# Expected: 404
```

Confirms the gate is intact. When operators are ready to retire the psql break-glass path (post-deploy decision in §8), flip `fabt.tenant.lifecycle.enabled=true` in `prod-*.env` + restart backend + re-run §6.7 expecting 401 (justification + JWT required) rather than 404.

---

## 7. Rollback

| Failure mode | Action | Recovery time |
|---|---|---|
| Backend fails to start (Flyway error, OCI key missing, etc.) | Re-pin to v0.52: `docker tag fabt-backend:v0.52.0 fabt-backend:latest` + `docker compose ... up -d --force-recreate backend`. V87/V88/V89 stay applied — Flyway is forward-only — but a v0.52 JAR running against V89 schema works (the new tables are unused by v0.52 code). | ~10 min |
| Critical security bug post-deploy | Reset the bootstrap operator account to its locked, no-credential V87 state immediately: `docker exec fabt-postgres psql -U fabt -d fabt -c "SELECT platform_user_reset_to_bootstrap('00000000-0000-0000-0000-000000000fab');"` (V88 SECURITY DEFINER function; wipes email/password/MFA/lockout state — strictly stronger than just locking the account; fabt_app cannot direct-UPDATE due to V87 REVOKE). Then cut a v0.53.1 hotfix branch from `main`, fix, scan-skip-allowed-for-hotfix, tag, push. After hotfix deploys, re-activate the operator via §5.10 with a fresh bcrypt hash. | 30-60 min from bug-found to v0.53.1 deployed |
| Critical data corruption | Restore the pg_dump from §5.4. Both schema-only and data-only dumps are required. Re-deploy v0.52.0 against the restored DB. | 60-120 min |
| Backend healthy but HMIS push failing (F16 audit row not landing) | Check `tail -50 logs/backend.log | grep "HMIS push audit event publish failed"`. The push itself succeeds even if audit row publish fails (intentional — push is irreversible, audit is best-effort with WARN log). Forward-fix in v0.53.1. | 0 min (no rollback; log + monitor) |
| Platform-operator login flow broken | If §5.10 activation completed but login 401s, the bcrypt hash is wrong. Re-run §5.10 step 2 with a fresh hash. The bootstrap row is the only entry point. | 5 min |

> **`flyway undo` is NOT supported** for V87 (REVOKEs) or V89 (append-only triggers). Forward-fix is the only path for schema-level issues.

---

## 8. Post-Deploy Housekeeping

- [ ] Archive OpenSpec change `platform-admin-split-and-access-log` via `/opsx:archive`
- [ ] Update `project_resume_point.md` memory to v0.53 deployed state
- [ ] Update `project_live_deployment_status.md` memory (compose-file count + Flyway HWM + JAR version)
- [ ] Smoke-verify post-deploy via §6 sweep
- [ ] Decide when to flip `fabt.tenant.lifecycle.enabled=true` in `prod-*.env` (G-4.6 endpoints currently gated off; flip retires the psql break-glass path for tenant lifecycle actions — coordinate with operator-runbook update + first end-to-end suspend/unsuspend rehearsal against staging)
- [ ] Add Grafana alert: `rate(fabt_platform_audit_tenant_id_fallback_total[5m]) > 0` — pages on the first regression of any `@PlatformAdminOnly` controller's tenant-id parameter convention
- [ ] Update fabt-cli runbook entries for new platform-operator activation flow

---

## 9. Known limitations + deferred follow-ups

See `openspec/changes/platform-admin-split-and-access-log/design.md` §F1-F34 for full follow-up list. Highlights for v0.53:

- **F12** — TenantLifecycleController REST endpoints — **✅ shipped in G-4.6**
- **F13** — `after_state` always NULL on PAL rows in v0.53 (Decision 11). G-4.6 controllers call `captureAfter` for forward compat; surfaced in MDC application logs only until F13 lands the post-proceed PAL update path.
- **F14** — Cross-tenant operator-driven HMIS push (`POST /api/v1/admin/tenants/{id}/hmis/push`) deferred to G-4.5 / dedicated micro-change
- **F16** — HMIS push authority broadening + audit-chain regression mitigated via confirm-header + per-tenant audit_event row; customer-comms in §3 includes the disclosure
- **F17** — Playwright fixture caching across tests (CI runtime cost); G-4.5
- **F18** — Prometheus alert on per-operator tenant-update rate; G-4.5 monitoring
- **F19** — Platform-operator observability config persistence IT; G-4.5
- **F20** — Audit gaps on platform-scoped reads + cross-tenant batch metadata reads (deliberate v0.53 decision)
- **F31** — `@PlatformAdminOnly` `tenantId`-param ArchUnit pin (runtime WARN + Counter half shipped in G-4.6; ArchUnit guard deferred to v0.54)
- **F32** — ArchUnit guard against reflection-based regression of state-capture allowlist; LOW
- **F33** — controller-level ITs for the 3 remaining `TENANT_*_REJECTED` audit-row paths (only suspend covered by G-4.6 IT today); LOW
- **F34** — push `fabt.tenant.lifecycle.enabled=true` into shared `application-lifecycle-test.yml` profile to share Spring contexts across lifecycle ITs; LOW
