# Oracle Deploy Notes — v0.40.0 (cross-tenant-isolation-audit, Issue #117)

**From:** v0.39.0 (notification-deep-linking, currently live)
**To:** v0.40.0 (cross-tenant-isolation-audit closeout)

> v0.40.0 is a **security-focused release** with no user-visible UI changes.
> 7 cross-tenant data-access vulnerabilities (5 VULN-HIGH + 2 VULN-MED)
> closed, plus 2 LIVE leaks discovered mid-audit (audit_events read +
> webhook SSRF). Build-time guards (ArchUnit + JSqlParser SQL static
> analysis) added so regressions fail CI. Read the full v0.40 section in
> `CHANGELOG.md` and the new `docs/security/rls-coverage.md` before
> starting this deploy.

## What's New in This Deploy

- **2 new Flyway migrations** (V57, V58). Auto-applied on backend restart.
  - `V57__audit_events_tenant_isolation.sql` — adds `tenant_id` column to
    `audit_events`, backfills from existing `target_user_id`/`actor_user_id`
    joins, adds composite index `(tenant_id, target_user_id, timestamp DESC)`.
    Forward-only, idempotent (`IF NOT EXISTS`). Backfill uses an UPDATE-FROM-JOIN;
    sub-second on the current dev DB (~10K rows). **At pilot scale (Charlotte
    projection ~10M rows over a year) this could lock the table for several
    minutes — chunk the backfill or run offline before the cutover.**
  - `V58__correct_referral_token_rls_policy_comment.sql` — `COMMENT ON POLICY`
    correction for `dv_referral_token_access` (D5). Comment-only — no behavioral
    change. Sub-second.
- **No new API endpoints, no new frontend routes.** Behavior change is in 5
  existing admin endpoints (OAuth2, API key, TOTP, subscription, access code)
  which now return **404 (not 200, not 403)** when called with a UUID the
  caller's tenant does not own. See "Behavior change for tenant admins" below.
- **`SafeOutboundUrlValidator`** rejects loopback / RFC1918 / link-local /
  cloud-metadata / multicast IPs as outbound URLs (webhook callbacks, OAuth2
  issuer URIs, HMIS endpoints) at creation time AND re-validates at dial time.
  See "Pre-deploy webhook URL audit" below.
- **`fabt.security.cross_tenant_404s`** Micrometer counter + Grafana dashboard
  `fabt-cross-tenant-security.json` (7 panels, `$tenant` template variable).
- **`app.tenant_id` PostgreSQL session variable** set on every connection
  borrow — defense-in-depth infrastructure for the companion change. No
  current RLS policy reads it; no behavior change. **Per-borrow cost
  measured ~0.01ms** (Sam's bench in design D13).
- **9 per-request Micrometer metrics** now carry `tenant_id` tag. Existing
  Grafana panels that don't filter by `tenant_id` continue to work; per-tenant
  filtering now possible via the new `$tenant` template variable.
- **New build-time guards** — `TenantGuardArchitectureTest` (4 ArchUnit rules)
  + `TenantPredicateCoverageTest` (JSqlParser + JavaParser SQL static analysis).
  Runs in CI; new violations fail the build. **No deployment impact.**
- **`docs/runbook.md`** — new "Cross-Tenant Access Behavior" + "Cross-Tenant
  Isolation Observability" sections. Read before going on call.
- **`docs/oracle-update-notes-v0.40.0.md`** — this file.

## Behavior change for tenant admins

**The 5 admin endpoints below now return 404 for cross-tenant lookups:**

- `PUT/DELETE /api/v1/oauth2-providers/{id}`
- `POST /api/v1/api-keys/{id}/rotate`, `DELETE /api/v1/api-keys/{id}`
- `DELETE /api/v1/auth/totp/{userId}`, `POST /api/v1/auth/totp/{userId}/regenerate-recovery-codes`
- `DELETE /api/v1/subscriptions/{id}`
- `POST /api/v1/users/{userId}/generate-access-code`

**Pre-fix:** A CoC admin in Tenant A could mutate Tenant B resources by passing the foreign UUID. **Post-fix:** Returns 404. **Same-tenant operations are unchanged.**

If a tenant admin reports "I get 404 trying to rotate my API key / disable TOTP / etc." after this deploy, **first triage step is to confirm they are logged in to the correct tenant** before escalating. Common causes: multiple browser tabs across tenants, stale bookmark, copy-paste from another admin's UI. See `docs/runbook.md` "Cross-Tenant Access Behavior" section.

## Demo allowlist threat-model audit (NEW in v0.40, 2026-04-16)

`DemoGuardFilter.ALLOWED_MUTATIONS` was audited as part of the cross-tenant-audit closeout. Four entries were removed because they enabled cross-visitor break or persistent-abuse vectors on the public demo site (`https://findabed.org`):

| Endpoint | Vector | Severity |
|---|---|---|
| `POST /api/v1/auth/enroll-totp` | Stores pending TOTP secret on the shared seed account | MEDIUM |
| `POST /api/v1/auth/confirm-totp-enrollment` | Sets `totp_enabled=true` + bumps `token_version` → invalidates all existing JWTs AND requires every subsequent visitor logging in as the shared seed account to provide a TOTP code only the original enroller has → **account-hijack vector** | CRITICAL |
| `POST /api/v1/subscriptions` | Persistent (365-day) outbound-dial configuration the demo backend will attempt for every matching event → outbound-dial amplification, exfiltration via attacker-chosen public webhook URL, persistent state pollution visible in Admin UI to other visitors | HIGH |
| `DELETE /api/v1/subscriptions/*` | Pair with the create | HIGH |

Public-URL POST/DELETE on these endpoints now returns `403 demo_restricted` with a friendly message. **Operators verify these flows via the SSH tunnel pattern** (`X-FABT-Traffic-Source=tunnel`, Design D2 — internal traffic bypasses demoguard).

**No real-user impact** — the demo personas (outreach worker, coordinator, CoC admin, foundation officer, hospital social worker) don't use these surfaces during demos. IT integrators evaluating webhook integration set up their own dev environment.

## What Does NOT Change

- **No new env vars.**
- **No Docker compose file changes.**
- **No Cloudflare / nginx / firewall changes.**
- **No new seed users.**
- **No frontend rebuild required** — this is a backend-only release.
- **RLS configuration is unchanged** at runtime (V58 corrects a misleading
  comment; no policy logic change). Tenant isolation remains service-layer
  per design D1.
- **No new env var for SSRF** — `fabt.security.ssrf.allow-private-addresses`
  was a test-only escape hatch removed during Phase 2.14 hardening; production
  has no kill switch (intentional).

## Pre-deploy webhook URL audit

The new SSRF validator will **reject** any existing webhook subscription whose `callbackUrl` resolves to a private/loopback/cloud-metadata IP. Run this query on the live DB BEFORE deploying to catch any tenant misconfigurations that would start failing post-deploy:

```sql
SELECT s.id, s.tenant_id, s.callback_url
  FROM subscription s
 WHERE s.status = 'ACTIVE'
   AND (s.callback_url LIKE 'http://localhost%'
        OR s.callback_url LIKE 'http://127.%'
        OR s.callback_url LIKE 'http://169.254.%'
        OR s.callback_url LIKE 'http://10.%'
        OR s.callback_url LIKE 'http://172.16.%'
        OR s.callback_url LIKE 'http://172.17.%'
        OR s.callback_url LIKE 'http://172.18.%'
        OR s.callback_url LIKE 'http://172.19.%'
        OR s.callback_url LIKE 'http://172.2_.%'
        OR s.callback_url LIKE 'http://172.3_.%'
        OR s.callback_url LIKE 'http://192.168.%');
```

If any rows return: contact the tenant admin BEFORE the deploy. Their webhook will start failing dial-time validation (logged as WARN, counter `fabt.webhook.delivery.failures{reason="ssrf_blocked"}` increments). The block is per-subscription — other subscriptions continue to work.

**Per Maria Torres (PM):** if a tenant's URL is rejected, contact them. Suggest they either use a public URL or set up a forwarder. Do NOT bypass the validator.

Also check OAuth2 providers and HMIS endpoints (less likely to have private URLs but worth a one-line audit):

```sql
SELECT id, tenant_id, issuer_uri FROM tenant_oauth2_provider WHERE issuer_uri ~ '(localhost|127\.|169\.254\.|10\.|172\.1[6-9]\.|172\.2[0-9]\.|172\.3[01]\.|192\.168\.)';

-- HMIS endpoints (per Marcus Webb's warroom add — same SSRF coverage):
SELECT id, tenant_id, base_url FROM hmis_endpoint WHERE base_url ~ '(localhost|127\.|169\.254\.|10\.|172\.1[6-9]\.|172\.2[0-9]\.|172\.3[01]\.|192\.168\.)';
```

If `hmis_endpoint` does not exist on prod yet (HMIS push integration not wired by every tenant), this query returns no rows or "relation does not exist" — both are fine; the table will be empty.

## Pre-Deploy Checklist

- [ ] Confirm v0.39.0 is live: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.39..."}`
- [ ] Confirm local main is at the v0.40 merge commit
- [ ] Confirm pom is at `0.40.0` (no `-SNAPSHOT`): `grep -E "<version>0\.40\.0</version>" backend/pom.xml`
- [ ] Run pre-deploy webhook URL audit query above (subscription + OAuth2 + HMIS endpoints). Zero rows → proceed. Any rows → contact tenant first.
- [ ] **Backup `audit_events` table** before deploying (V57 backfill is idempotent but a backup is cheap insurance for a 10M-row table). At current dev scale, backup completes in <1s.
  ```bash
  ssh opc@findabed.org "docker exec fabt-postgres pg_dump -U fabt -t audit_events fabt | gzip > /tmp/audit_events.v0.40.pre-deploy.sql.gz"
  ```
- [ ] **Flyway-checksum dry-run for V57+V58** (per Jordan, B5-equivalent from v0.39 plan). On a throwaway local DB, apply V1..V58 via v0.40 code, then start v0.39 backend against the same volume. Confirm startup does NOT fail on checksum validation. **If it DOES fail**, document the fallback `DELETE FROM flyway_schema_history WHERE version IN ('57','58'); ALTER TABLE audit_events DROP COLUMN tenant_id;` in the rollback section before tagging. This is the only unknown in the rollback plan.
- [ ] CI is fully green on `main` HEAD: `gh run list --branch main --limit 3` — all SUCCESS.
- [ ] Marcus Webb (or designee) has signed off on the OWASP ZAP cross-tenant sweep.
- [ ] **Frontend rebuild is NOT required** for v0.40 (confirmed via `git diff --stat v0.39.0..v0.40.0 -- frontend/` returns zero files). Skip the v0.39 frontend-rebuild + frontend-image-build + frontend-container-recreate steps.

## Deploy Steps

Standard deploy per `docs/oracle-update-notes-v0.39.0.md` Section "Deploy Steps". This release adds no new infrastructure but DOES add one new artifact (Grafana dashboard).

1. `mvn -pl backend clean package -DskipTests` — produces `backend/target/finding-a-bed-tonight-0.40.0.jar`.
2. `scp backend/target/finding-a-bed-tonight-0.40.0.jar opc@findabed.org:/opt/fabt/`.
3. SSH to VM, `cd /opt/fabt`, `docker compose down && docker compose up -d`.
4. Watch logs for V57 + V58 application: `docker compose logs -f fabt-backend | grep -E "Migrating|Successfully applied"`. Expect `Migrating schema "public" to version "57"` AND `... to version "58"` (TWO migrations, vs v0.39's one).
5. Confirm `/api/v1/version` returns `0.40.0`.
6. **Verify V57 backfill** — must return `0` (per Elena's gate):
   ```bash
   ssh opc@findabed.org "docker exec fabt-postgres psql -U fabt -d fabt -c 'SELECT COUNT(*) FROM audit_events WHERE tenant_id IS NULL;'"
   ```
   If `> 0`, V57 backfill missed rows. **Stop deploy + investigate** (likely orphaned audit events with no joinable target/actor). Either fix forward by manually setting `tenant_id` for the orphans (with operator review) or roll back per the rollback section.
7. **Upload Grafana dashboard** (NEW for v0.40, per Jordan's warroom add):
   ```bash
   scp infra/grafana/dashboards/fabt-cross-tenant-security.json opc@findabed.org:/tmp/
   # Either via Grafana API:
   ssh opc@findabed.org "curl -X POST -H 'Authorization: Bearer $GRAFANA_API_KEY' -H 'Content-Type: application/json' -d @/tmp/fabt-cross-tenant-security.json https://grafana.findabed.org/api/dashboards/db"
   # Or via UI: Grafana → Dashboards → Import → upload JSON file.
   ```
   Verify the dashboard renders at `https://grafana.findabed.org/d/fabt-cross-tenant-security` with the `$tenant` template variable populated.

## Post-Deploy Smoke

In addition to the standard v0.39 smoke sequence in `docs/runbook.md`, run:

```bash
# Cross-tenant Karate smoke (≤30s)
cd e2e/karate
mvn test -Dtest=KarateRunnerTest -Dkarate.options="--tags @cross-tenant features/security/cross-tenant-isolation.feature" -DbaseUrl=https://findabed.org

# Cross-tenant Playwright smoke (≤30s)
cd e2e/playwright
FABT_BASE_URL=https://findabed.org npx playwright test cross-tenant-isolation --project chromium
```

> **⚠ Smoke landmines (per Riley Cho's warroom):**
> - **`FABT_BASE_URL` is mandatory.** Without it, the Playwright spec defaults to `http://localhost:8080` (per the cross-tenant-spec fix in commit `ac30efc`). The smoke run silently exercises an empty localhost and "passes." Set the env var explicitly.
> - **Cloudflare WAF may return 403 instead of the expected 404.** Cross-tenant probes (random UUIDs, attacker-shaped payloads) match WAF rules. If you see 403 instead of 404 in the smoke output, allowlist the runner IP in Cloudflare OR re-run the smoke from inside the Oracle VM (where requests bypass the public WAF). **Do not interpret 403 as a security regression** — verify against the WAF rules first.

Both should be 100% green. Then:

```bash
# Verify cross-tenant counter is registered (after first 404)
curl -s https://findabed.org/actuator/prometheus | grep fabt_security_cross_tenant_404s
# Expected: at least one matching metric line.

# Verify Grafana sees per-tenant breakdown (in browser):
# https://grafana.findabed.org/d/fabt-cross-tenant-security
```

### **GATING final check — webhook subscription create + delete (FROM INSIDE THE VM)**

> **⚠ Cannot be run from outside.** v0.40 demoguard threat-model audit (2026-04-16) closed the public allowlist for webhook subscription create + delete. Public-URL POST/DELETE on `/api/v1/subscriptions` now returns `403 demo_restricted`. Operators verify via the SSH tunnel pattern (`X-FABT-Traffic-Source=tunnel`, Design D2) — internal traffic bypasses demoguard.

This proves the CI Karate `subscription-crud` 409 (resolved in PR #125) is NOT a real production race. Run from inside the VM:

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# === ON VM (internal traffic — bypasses demoguard) ===

# Login as admin against localhost backend (NOT findabed.org)
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"tenantSlug":"dev-coc","email":"admin@dev.fabt.org","password":"admin123"}' \
  http://localhost:8080/api/v1/auth/login | jq -r .accessToken)

# Create — must return 201
SUB=$(curl -s -w "\n%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"availability.updated","callbackUrl":"https://example.com/v0.40-postdeploy-smoke","callbackSecret":"smoke-test-secret"}' \
  http://localhost:8080/api/v1/subscriptions)
echo "$SUB"
# Expected: trailing 201. Body: {"id":"<uuid>","status":"ACTIVE",...}

SUB_ID=$(echo "$SUB" | head -n 1 | jq -r .id)

# Delete — must return 204
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/subscriptions/$SUB_ID
# Expected: 204
```

**Outcomes:**
- Create returns **201** → v0.40 webhook flow healthy on prod ✓
- Create returns **409 "Resource conflict"** → encryption key not configured in prod env (separate from CI fix). Check `~/fabt-secrets/.env.prod` has `FABT_ENCRYPTION_KEY=<key>`. Roll back per Rollback Criteria until env is set.
- Create returns **403 demo_restricted** → you're not actually inside the VM bypass path; check that container nginx is setting `X-FABT-Traffic-Source=tunnel` for `localhost:8080` traffic. If the request went through Cloudflare somehow, demoguard correctly blocks it (this is the new v0.40 behavior).

### Cross-tenant smoke — also from inside (per Riley's WAF caveat + demoguard audit)

The cross-tenant Karate + Playwright probes hit 5 admin surfaces with foreign UUIDs. ALL of them are admin-only mutations correctly blocked by demoguard from public URLs (oauth2-providers, api-keys, auth/totp, users/*/generate-access-code) — including subscriptions DELETE now (post-audit). To exercise the full 8-probe matrix against the v0.40 backend, run from inside:

```bash
# ALSO ON VM
cd ~/finding-a-bed-tonight/e2e/karate
mvn test -Dtest=KarateRunnerTest \
  -Dkarate.options="features/security/cross-tenant-isolation.feature" \
  -DbaseUrl=http://localhost:8081
# Expected: 14/14 green (8 negative + 5 positive control + 1 ignored metric).
```

Public-URL Karate against `https://findabed.org` will return mostly `403 demo_restricted` for the cross-tenant probes — that's demoguard working as designed, NOT a v0.40 regression. The from-inside run is the authoritative verification.

## Rollback Criteria

- V57 backfill takes longer than expected and locks `audit_events` past acceptable downtime → rollback to v0.39 JAR. V57 leaves `tenant_id` as nullable so rollback is safe (old code ignores the column).
- Any same-tenant API call returns 404 where it previously returned 200 → likely a refactor regression in one of the 5 fixed endpoints. Rollback + investigate.
- `fabt.security.cross_tenant_404s` rate spikes > 10× baseline within first 30 minutes (with no demo traffic explanation) → not necessarily rollback-worthy (could be a benign tenant configuration error) but warrants immediate investigation per the runbook playbook.
- Webhook delivery success rate drops > 50% → likely a tenant's callbackUrl resolves to a private IP and is now being blocked by SSRF validator. Cross-reference `fabt.webhook.delivery.failures{reason="ssrf_blocked"}` counter. Contact the tenant; do NOT roll back unless multiple tenants are affected.

## After Deploy Succeeds

Once both the standard smoke sequence AND the gating webhook subscription create/delete check pass:

1. **Publish the GitHub release** (per Marcus + Casey — public-disclosure moment is now, not pre-deploy):
   ```bash
   gh release edit v0.40.0 --draft=false
   ```
   Or via web UI: https://github.com/ccradle/finding-a-bed-tonight/releases → v0.40.0 → "Edit release" → uncheck "Set as a pre-release / draft" → Publish.
2. Pilot Slack/email (per Maria's warroom wording):
   > **v0.40 is a security-focused backend release. No UI changes.** Hard-reload (Ctrl+Shift+R) recommended in case the service worker cached anything (per the v0.39 cycle pattern). Webhook subscribers: if your callback URL resolves to a private IP, contact us — the new SSRF guard will reject it. Most public webhook URLs are unaffected.
3. Update memory `project_live_deployment_status.md` → v0.40.0.
4. Comment on issue #117: deployed, security audit complete, multi-tenant readiness companion change tracked.
5. `/opsx:archive cross-tenant-isolation-audit` (24h post-deploy, after sanity window).

## Tracking

- `openspec/changes/cross-tenant-isolation-audit/` has the full phase breakdown and design decisions D1–D16.
- `openspec/changes/multi-tenant-production-readiness/` is the companion change scoped for the architectural items deferred from this audit (per-tenant JWT signing keys, per-tenant encryption DEKs, tenant-RLS on regulated tables D14, etc.).
- Issue #117 will be closed with a comment linking the merge commit, this deploy, and the Grafana panel URL.
- Issue #103 (flaky-tests umbrella) tracks the v0.40 CI-only Karate `subscription-crud` flake; PR #125 is the in-flight diagnostic. NOT a deploy blocker — the gating webhook smoke step above proves whether prod is affected.
