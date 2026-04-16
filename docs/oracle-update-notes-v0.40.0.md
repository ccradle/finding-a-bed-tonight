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

Also check OAuth2 providers and HMIS endpoints (less likely to have private URLs but worth a one-line audit):

```sql
SELECT id, tenant_id, issuer_uri FROM tenant_oauth2_provider WHERE issuer_uri ~ '(localhost|127\.|169\.254\.|10\.|172\.1[6-9]\.|172\.2[0-9]\.|172\.3[01]\.|192\.168\.)';
```

## Pre-Deploy Checklist

- [ ] Confirm v0.39.0 is live: `curl -s https://findabed.org/api/v1/version` → `{"version":"0.39..."}`
- [ ] Confirm local main is at the v0.40 merge commit
- [ ] Confirm pom is at `0.40.0` (no `-SNAPSHOT`): `grep -E "<version>0\.40\.0</version>" backend/pom.xml`
- [ ] Run pre-deploy webhook URL audit query above. Zero rows → proceed. Any rows → contact tenant first.
- [ ] **Backup `audit_events` table** before deploying (V57 backfill is idempotent but a backup is cheap insurance for a 10M-row table). At current dev scale, backup completes in <1s.
- [ ] CI is fully green on the `cross-tenant-isolation-audit` branch — including the new `TenantGuardArchitectureTest`, `TenantPredicateCoverageTest`, and `TenantIdPoolBleedTest`.
- [ ] Marcus Webb (or designee) has signed off on the OWASP ZAP cross-tenant sweep.

## Deploy Steps

Standard deploy per `docs/oracle-update-notes-v0.39.0.md` Section "Deploy Steps". This release adds no new infrastructure.

1. `mvn -pl backend clean package -DskipTests` — produces `backend/target/finding-a-bed-tonight-0.40.0.jar`.
2. `scp backend/target/finding-a-bed-tonight-0.40.0.jar opc@findabed.org:/opt/fabt/`.
3. SSH to VM, `cd /opt/fabt`, `docker compose down && docker compose up -d`.
4. Watch logs for V57 + V58 application: `docker compose logs -f fabt-backend | grep -E "Migrating|Successfully applied"`.
5. Confirm `/api/v1/version` returns `0.40.0`.

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

Both should be 100% green. Then:

```bash
# Verify cross-tenant counter is registered (after first 404)
curl -s https://findabed.org/actuator/prometheus | grep fabt_security_cross_tenant_404s
# Expected: at least one matching metric line.

# Verify Grafana sees per-tenant breakdown (in browser):
# https://grafana.findabed.org/d/fabt-cross-tenant-security
```

## Rollback Criteria

- V57 backfill takes longer than expected and locks `audit_events` past acceptable downtime → rollback to v0.39 JAR. V57 leaves `tenant_id` as nullable so rollback is safe (old code ignores the column).
- Any same-tenant API call returns 404 where it previously returned 200 → likely a refactor regression in one of the 5 fixed endpoints. Rollback + investigate.
- `fabt.security.cross_tenant_404s` rate spikes > 10× baseline within first 30 minutes (with no demo traffic explanation) → not necessarily rollback-worthy (could be a benign tenant configuration error) but warrants immediate investigation per the runbook playbook.
- Webhook delivery success rate drops > 50% → likely a tenant's callbackUrl resolves to a private IP and is now being blocked by SSRF validator. Cross-reference `fabt.webhook.delivery.failures{reason="ssrf_blocked"}` counter. Contact the tenant; do NOT roll back unless multiple tenants are affected.

## Tracking

- `openspec/changes/cross-tenant-isolation-audit/` has the full phase breakdown and design decisions D1–D16.
- `openspec/changes/multi-tenant-production-readiness/` is the companion change scoped for the architectural items deferred from this audit (per-tenant JWT signing keys, per-tenant encryption DEKs, tenant-RLS on regulated tables D14, etc.).
- Issue #117 will be closed with a comment linking the merge commit, this deploy, and the Grafana panel URL.
