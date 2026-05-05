# Oracle Deploy Notes — v0.57.0 (info-email-contact)

**Status:** DRAFT — pending PR #176 merge to main, tag, and CI scans green. Ground-truthed against `feature/info-email-contact` at `ef7d7ee` (code repo) + `feature/info-email-contact` at `63ee774` (docs repo).
**Template version:** v1 (per `docs/runbook-template.md`).
**OpenSpec change:** `info-email-contact` (in the docs repo).
**Pairs with:** docs-repo PR #12 (static content: 14 HTML pages + `/contact.js` + CI guard).

---

## What's new in v0.57.0 (one-paragraph summary)

v0.57 ships the `info-email-contact` openspec change end-to-end: a public read endpoint `GET /api/v1/public/contact-info` returning `{ platform: { email }, tenant: { slug, email } }` for authed callers and `{ platform: { email }, tenant: null }` for unauthed callers, with a Bucket4j 60 req/min/IP rate limit (key fallback to remote address if `X-Real-IP` is absent), an ETag-driven cache contract that splits `Cache-Control` by auth state (`public, max-age=3600` unauthed; `private, max-age=3600` + `Vary: Authorization` authed) so Cloudflare's edge cannot serve one tenant's body to another caller, a per-tenant `PATCH /api/v1/admin/tenants/{tenantId}/contact-email` (COC_ADMIN, DV-policy gated — `400 tenant.contactEmail.dvPolicyForbidden` when the tenant has `dv_policy_enabled=true`), a React `ContactInfoProvider` + `useContactInfo()` hook + login-page footer + `ContactSettings` admin panel, and a docs-repo bundle that adds a shared `/contact.js` lang-aware fetcher (EN/ES dict picked from `<html lang>`), `<a class="contact-email" hidden>` placeholder + `<noscript>` GitHub-Issues fallback on all 14 in-scope HTML pages, audience-page CTA upgrades on 5 pages, and a CI guard at `scripts/ci/check-contact-placeholder.sh` enforcing the markup contract + a no-`@findabed.org`-in-source rule. The deploy is **single-stage** with backend rebuild + frontend rebuild + static-content scp + Cloudflare Purge. **No Flyway migrations.** Backend full mvn test 1538/1538 pre-deploy (TBD post-rebase). Vitest 228/228 + 16 new (TBD post-rebase). Playwright 32/32 + 5 new (TBD post-rebase). The platform email value (`FABT_PLATFORM_CONTACT_EMAIL`) is operator-injected via `~/fabt-secrets/.env.prod` — the literal address never enters git.

---

## 1. Consulted Memories

```yaml
consulted:
  - file: feedback_runbook_groundtruth_vm.md
    # why-cited: every container name, JAR path, port, and image tag
    # in this runbook ground-truthed against project_live_deployment_status
    # (current state v0.56.0, Flyway HWM V98, fabt-pgaudit:v0.45.0,
    # 5-file compose chain). v0.57 introduces no new compose override.
  - file: feedback_runbook_compose_chain.md
    # why-cited: prod uses a 5-FILE compose chain (UNCHANGED from v0.56).
    # Missing any one file breaks deploy.
  - file: feedback_prod_docker_build_pattern.md
    # why-cited: explicit `docker build --no-cache` BEFORE
    # `compose up --force-recreate` for both backend + frontend.
  - file: feedback_runbook_template_v1.md
    # why-cited: this runbook follows the v1 template structure.
  - file: feedback_release_after_scans.md
    # why-cited: tag + GitHub release published only after CI scans green.
  - file: feedback_smoke_spec_default_target.md
    # why-cited: post-deploy Playwright smoke uses FABT_BASE_URL +
    # `--config=deploy/playwright.config.ts` + `post-deploy-smoke`
    # positional. The smoke spec defaults to localhost without that
    # override.
  - file: feedback_never_print_rendered_secrets.md
    # why-cited: NEVER cat/grep `~/fabt-secrets/.env.prod`. The new
    # `FABT_PLATFORM_CONTACT_EMAIL` line lives there alongside JWT
    # secrets and DB passwords; presence-check via `grep -q` only.
  - file: feedback_no_ip_in_repo.md
    # why-cited: same anti-leak posture extended to the platform email
    # value. The literal address `info@findabed.org` lives in operator
    # memory + .env.prod, NEVER in git-tracked files.
  - file: project_platform_contact_email_prod_value.md
    # why-cited: holds the production value for FABT_PLATFORM_CONTACT_EMAIL
    # and the .env.prod placement convention. Operator pulls value from
    # this memory at deploy time; this runbook references the env var by
    # NAME ONLY.
  - file: feedback_bind_mount_inode_pitfall.md
    # why-cited: no compose-file edit + no new files in mounted config
    # directories in v0.57; prometheus/alertmanager do NOT need
    # force-recreate this release.
  - file: feedback_verify_doc_facts_against_source.md
    # why-cited: every command path, container name, env-var name, and
    # endpoint URL ground-truthed against ContactInfoController.java,
    # ContactEmailController.java, application.yml line 152, and the
    # docs-repo §9 CI guard at scripts/ci/check-contact-placeholder.sh.
  - file: feedback_stale_sw_on_deploy.md
    # why-cited: post-deploy frontend testing must be incognito or
    # site-data-cleared. NEW for v0.57: also clear cached `/contact.js`
    # on the docs-site origin since browsers cache it aggressively.
  - file: feedback_periodic_resume_save.md
    # why-cited: post-deploy memory updates per §8.
  - file: project_live_deployment_status.md
    # why-cited: ground-truth source. v0.56.0 currently live; Flyway HWM
    # V98; 5-file compose chain; `fabt-pgaudit:v0.45.0` postgres image.
  - file: reference_cloudflare_email_obfuscation_dependency.md
    # why-cited: findabed.org Cloudflare zone has Email Address
    # Obfuscation `On` as secondary defense for info-email-contact's
    # JS-injection primary defense. Operator MUST NOT silently disable
    # this Cloudflare setting; it is part of the deployed defense-in-depth.
  - file: feedback_legal_scan_in_comments.md
    # why-cited: legal-language scan applies to JSDoc + JavaDoc, not
    # just user-facing copy. Already cleared by `ef7d7ee` commit
    # (rephrased two over-claim words in technical comments).
  - file: feedback_check_ports_before_assuming.md
    # why-cited: post-deploy frontend smoke uses port 8081 (nginx) in
    # local rehearsal and the public URL via Cloudflare for prod smoke.
  - file: project_dv_policy_tenant_flag_decisions.md
    # why-cited: ContactSettings input + Save button are disabled when
    # the tenant's DV-policy flag is on. Same UX gating as the v0.56
    # DvPolicySettings panel; the panel is not blocked, only the
    # contact-email field. Verified at /admin in dev rehearsal.
  - file: feedback_truthfulness_above_all.md
    # why-cited: AI-synthetic Spanish review disclosure for the 14 new
    # ES keys (10 admin/footer + 4 dict strings). Same convention as
    # v0.55.1 D2 + v0.56 disclosures.
  - file: reference_es_json_ai_synthetic_reviewed.md
    # why-cited: AI-synthetic Spanish review pointer + disclosure
    # conventions used here for the v0.57 key additions (ledger updated
    # in §6+§7 commit).
```

---

## 2. Scope & Non-Scope

**Deploying:** v0.57.0 — `info-email-contact` openspec change.

**From:** `v0.56.0` live at `findabed.org`. Confirm current via:

```bash
curl -s https://findabed.org/api/v1/version
# Expected pre-deploy: {"version":"0.56"}
# Post-deploy: {"version":"0.57"}
```

**To:** `v0.57.0` — backend JAR `0.56.0 → 0.57.0` (rebuilt; pom.xml bumped), Flyway HWM **unchanged at V98**, frontend bundle gains `ContactInfoProvider` + login-footer rendering + `ContactSettings` admin panel, docs-site gains `/contact.js` + 14 modified HTML pages.

**Migrations in this deploy:** None. `info-email-contact` reuses the existing `tenant.config` JSONB column for the per-tenant email (`tenant.config.contact.email` sub-key); no schema change required. Flyway HWM `V98 → V98` (no-op forward).

**New env var (operator-managed):**

- `FABT_PLATFORM_CONTACT_EMAIL` — read by Spring at startup via `application.yml` line 152 (`contact-email: ${FABT_PLATFORM_CONTACT_EMAIL:}`). Backed by `PlatformContactProperties` (`@ConfigurationProperties(prefix = "fabt.platform")`) and exposed via the new public endpoint. Empty/unset = "not configured" → public endpoint returns `platform: { email: null }` and the static-site renders the GitHub-Issues fallback (intentional degraded-but-safe state). The literal value comes from operator memory (`project_platform_contact_email_prod_value.md`) and lives in `~/fabt-secrets/.env.prod` ONLY — NEVER in this repo, NEVER in this runbook.

**New endpoints:**

- `GET /api/v1/public/contact-info` — anonymous OR authenticated. Bucket4j rate limit 60 req/min, keyed on `X-Real-IP` with fallback to `getRemoteAddr()` when the header is missing or blank (capacity / refill / cache-key spec in `application.yml`; Caffeine cache backing in `application.conf`). Returns `{ platform: { email }, tenant: { slug, email } }` for authed callers and `{ platform: { email }, tenant: null }` for unauthed callers. Cache-Control split by auth state:
  - Unauthed: `Cache-Control: public, max-age=3600` + `ETag`. Cloudflare can cache.
  - Authed: `Cache-Control: private, max-age=3600` + `Vary: Authorization` + `ETag`. Browsers cache; shared caches must NOT (the cache-control split was caught in §spec-review by the warroom — prior draft would have allowed Cloudflare edge to serve one tenant's authed body to another caller).
- `PATCH /api/v1/admin/tenants/{tenantId}/contact-email` — COC_ADMIN, tenant-scoped (must match JWT tenant claim; cross-tenant attempts return **403** and emit a defense-in-depth audit row). Accepts `{ email: <RFC-5322-shape, ≤254 chars> | null | "" }` — both `null` and empty-string clear the per-tenant override. Persists to `tenant.config.contact.email`. Emits `TENANT_CONFIG_UPDATED` audit row capturing old + new email values for forensic trace; values are NOT masked at audit-write time, so the `audit_events.details` JSONB carries them as plaintext (operator-visible only via direct DB query). **DV-policy gated**: returns `400` with errorCode `tenant.contactEmail.dvPolicyForbidden` if the tenant has `tenant.config.dv_policy_enabled = true` (the admin-UI also disables the input + Save button as a UX cue; the backend-side guard is the enforcement). Note: the controller is mapped under `/api/v1/admin/tenants` (`@RequestMapping` at `ContactEmailController.java:78`) — any debug curl MUST include the `/admin/` segment.

**New AuditEventType uses:** No new enum value. The PATCH endpoint emits the existing `TENANT_CONFIG_UPDATED` (added in v0.55 for hold-duration + DV-policy patches; reused here per the JSONB-key convention).

**New external static asset:** `/contact.js` at the docs-site root (`/var/www/findabed-docs/contact.js`). Fetches `/api/v1/public/contact-info`, hydrates `<a class="contact-email" hidden>` placeholders to `mailto:` links, falls back to GH-Issues link on any failure with a `console.warn`. Lang detection reads `document.documentElement.lang`; EN/ES dict embedded.

**Modified static pages (14 in-scope HTML):**

- `index.html` (root)
- `404.html`
- `demo/index.html` + `demo/dvindex.html` + `demo/hmisindex.html` + `demo/analyticsindex.html` + `demo/reentry-story.html`
- `demo/for-cities.html` + `demo/for-coc-admins.html` + `demo/for-coordinators.html` + `demo/for-funders.html`
- `demo/outreach-one-pager.html` + `demo/pitch-briefs.html` + `demo/shelter-onboarding.html`

Each page gains the canonical `<p class="footer-contact">` placeholder + `<noscript>` GH-fallback + `<script defer src="/contact.js"></script>` near `</body>`. The §9 CI guard enforces this markup on every page (5 checks: `class="contact-email"` + `aria-live="polite"` + `/contact.js` reference + noscript-with-GH-link + no `@findabed.org` literal).

**Frontend bug fixes bundled:** None this release.

**Accessibility changes:** `<a class="contact-email" aria-live="polite" hidden>` placeholder lets screen readers announce the address when JavaScript hydrates it. The `<noscript>` block is the JS-disabled fallback — verified in `e2e/playwright/tests/contact-info-static.spec.ts` against root + for-cities + 404.

**What does NOT change in this deploy:**

- Tenant JWT issuer + claims, FORCE RLS posture, Postgres / pgaudit (`fabt-pgaudit:v0.45.0` continues unchanged).
- Compose file chain — same 5 files; no new override.
- Prometheus rule files + alertmanager templates — unchanged.
- Container names — unchanged from v0.56.
- DV-policy flag, observability split, surge threshold endpoints — unchanged from v0.56 (the ContactSettings UI consumes the existing DV-policy flag, but does not modify it).
- Cloudflare Email Address Obfuscation — REMAINS ON. Per `reference_cloudflare_email_obfuscation_dependency.md` it is the secondary defense layer; do NOT silently disable.

**Out of scope (deferred):**

- `issue-reporting-feedback` (GH #67) — depends on `useContactInfo()` infra shipped here; targets v0.57.1.
- `opsx-runbook-draft-skill` + `ci-runbook-consulted-check` — operational discipline pair, targets v0.57.2.
- Casey legal-language review of the new EN/ES contact-related strings — manual native-speaker review still pending; AI-synthetic disclosure stands per `reference_es_json_ai_synthetic_reviewed.md`.
- §13.7 CI-guard `--selftest` mode — backlog.

**Operator-comms one-liner** (paste into the post-deploy note to the 3 demo CoC admins so they aren't surprised by the new admin panel):

> v0.57 adds a **Contact Email Settings** card at the top of the admin
> page. It is the place where you set the email address shelters and
> coordinators see for your CoC. The platform-team email at the page
> footer is project-team-managed and cannot be edited from CoC admin.
> If your tenant has DV-policy mode on, the contact-email input is
> disabled — that is intentional and matches the way DV-policy gates
> other tenant-level config writes.

---

## 3. Service-Recreate Matrix

| Service (prod container_name) | What triggers recreate | Changed? | Recreate required? |
|---|---|---|---|
| `fabt-backend` | New JAR (0.57.0) + new endpoints + new env var read | Yes | Yes |
| `fabt-frontend` | New bundle (ContactInfoProvider + ContactSettings + login-footer rendering + i18n keys) | Yes | Yes |
| `finding-a-bed-tonight-postgres-1` | `fabt-pgaudit:v0.45.0` image unchanged; no schema migration | No | No |
| `finding-a-bed-tonight-prometheus-1` | No new rules file in `deploy/prometheus/`; no compose-file edit; no inode change | No | No |
| `finding-a-bed-tonight-alertmanager-1` | No new template; no rendered-secret change | No | No |
| `finding-a-bed-tonight-{grafana,jaeger,otel-collector}-1` | No dashboard / collector / image change | No | No |
| Host `nginx` | No `/etc/nginx/sites-available/fabt` edit | No | No |
| Static content at `/var/www/findabed-docs/` | 14 modified HTML pages + 1 new `/contact.js` | Yes | scp + Cloudflare purge |

> **Container-name rule:** `fabt-backend` and `fabt-frontend` carry custom `container_name:` directives in `~/fabt-secrets/docker-compose.prod.yml` (out-of-repo). Postgres and the observability stack use default `<project>-<service>-<replica>` naming. Verified via `docker ps` on the live VM 2026-04-30 (post-v0.55.0 deploy, unchanged through v0.56).

---

## 4. Pre-Deploy Gates

- [ ] **pom.xml version bumped** — `cd backend && grep -nE "<version>0\." pom.xml | head -1` should report **0.57.0**. If still 0.56.0, edit `backend/pom.xml`, commit on the release branch, and re-run `mvn -B -DskipTests clean package -q` to confirm the JAR filename is now `*0.57.0*.jar`.
- [ ] **Backend tests green** — `cd backend && mvn -B test -q` — expect (TBD post-rebase) all green. New tests: `ContactEmailControllerTest` (12 cases), `ContactInfoControllerTest` (14 cases), `ContactInfoControllerEmptyPlatformTest` (3 cases), `PlatformContactPropertiesTest` (3 cases), `TenantContactEmailHelperTest` (11 cases), `PublicEndpointAllowlistTest` (ArchUnit allowlist guard for the new public endpoint).
- [ ] **Frontend tests green** — `cd frontend && npm run test:run` — expect (TBD post-rebase) all green. New: `ContactInfoContext.test.ts` (provider + `deriveContactInfoState` helper) + `ContactSettings.test.ts` (parseContactEmailError).
- [ ] **v0.57-relevant Playwright spec green locally** — `cd e2e/playwright && BASE_URL=http://localhost:8081 npx playwright test contact-info-static.spec.ts --reporter=list` — expect 5/5 (3 §10.5 JS-disabled tests + 2 §10.8 lang-aware-dict tests). Run through nginx, NOT bare Vite.
- [ ] **§9 CI guard green** — `cd <docs-repo-root> && bash scripts/ci/check-contact-placeholder.sh` returns `OK: 14 pages pass all 5 checks`. This is the same command the GitHub Actions workflow runs; pre-deploy local run catches any drift.
- [ ] **`FABT_PLATFORM_CONTACT_EMAIL` line present in operator's secrets** — on the VM:
  ```bash
  grep -q "^FABT_PLATFORM_CONTACT_EMAIL=" ~/fabt-secrets/.env.prod && echo "PRESENT" || echo "MISSING — append before deploy"
  ```
  Per `feedback_never_print_rendered_secrets`, do NOT `cat` the file or print the value — presence-check only. If MISSING, the operator appends one line:
  ```bash
  # Append (operator pulls value from project memory project_platform_contact_email_prod_value.md;
  # never type the value into a shared chat or commit it).
  echo "FABT_PLATFORM_CONTACT_EMAIL=<value-from-project-memory>" >> ~/fabt-secrets/.env.prod
  ```
  Re-run the presence-check above to confirm.
- [ ] **Env-var trailing-space lint** — `grep -nE "^FABT_[A-Z_]*= " ~/fabt-secrets/.env.prod` returns NO output (v0.49 issue #1). The new line MUST NOT have a trailing space; Spring would read the value as a literal trailing-space-suffixed string. **No format validation runs at startup** — `PlatformContactProperties` does not carry `@Email`/`@Validated` (validation happens at the controller boundary on the PATCH endpoint, not at property level). A malformed `FABT_PLATFORM_CONTACT_EMAIL` value will start the backend cleanly and surface in `/api/v1/public/contact-info` as-is, which `/contact.js` will hydrate into a broken `mailto:` link. The only assurance is the §6 post-deploy curl visually matching the expected address.
- [ ] **Container UID vs perms** — no new bind-mounted files in this deploy; `git checkout v0.57.0` does not introduce new bind-mount paths.
- [ ] **Cloudflare Email Address Obfuscation = ON** — Cloudflare dashboard → `findabed.org` → Scrape Shield → Email Address Obfuscation. Operator confirms ON in browser before continuing. This is the JS-running-scraper secondary defense (the JS-injection from `/contact.js` is the primary; Cloudflare obfuscation handles bots that DO run JavaScript). Per `reference_cloudflare_email_obfuscation_dependency.md` and tasks.md §1.2 — flipping to OFF turns the rendered `mailto:` into harvestable plain text. **Never silently disable.**
- [ ] **Cloudflare Bot Fight Mode = ON** — Cloudflare dashboard → `findabed.org` → Security → Bots. The Bucket4j 60 req/min rate limit on `/api/v1/public/contact-info` is keyed on `X-Real-IP` (per `application.yml:286` cache-key SpEL); a botnet across distinct IPs gets effectively no rate limit AT ORIGIN. Cloudflare Bot Fight Mode is the upstream defense that drops botnet traffic at edge. Same posture as Email Address Obfuscation — confirm ON before deploy.
- [ ] **GH-Issues fallback URL reachable** — `curl -sf -o /dev/null -w "%{http_code}\n" https://github.com/ccradle/finding-a-bed-tonight/issues` returns `200`. `/contact.js` falls back to this URL on every error path (no platform email, fetch failure, JS-disabled noscript path); a 404 here silently degrades the entire deploy's degraded-but-safe state.
- [ ] **Compose dry-render (5-file chain)** — same 5-file chain as v0.56. Generate v0.56 baseline + v0.57 render and diff. **Does NOT mutate the working tree** — uses `git show` so this gate works regardless of current HEAD or uncommitted changes:

  ```bash
  COMPOSE_CHAIN=(
      -f docker-compose.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
  )

  # Render at the current checkout (whatever HEAD is — main, v0.57.0, etc).
  docker compose "${COMPOSE_CHAIN[@]}" --env-file ~/fabt-secrets/.env.prod \
      config > /tmp/v0.57.0-config.rendered.yml

  # Render the v0.56 baseline by piping the v0.56.0-tag content into a
  # temp file. No working-tree mutation, no stash needed.
  git show v0.56.0:docker-compose.yml > /tmp/v0.56-docker-compose.yml
  COMPOSE_CHAIN_BASELINE=(
      -f /tmp/v0.56-docker-compose.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
      -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
  )
  docker compose "${COMPOSE_CHAIN_BASELINE[@]}" --env-file ~/fabt-secrets/.env.prod \
      config > /tmp/v0.56.0-config.rendered.yml

  diff /tmp/v0.56.0-config.rendered.yml /tmp/v0.57.0-config.rendered.yml
  # Expected: NO diff output (exit code 0). v0.57 changes nothing in
  # docker-compose.yml; both files render identically.
  ```

  A non-empty diff means a compose file changed unexpectedly — investigate before proceeding.

- [ ] **OCI key path mounted on the VM** — backend env has `FABT_OCI_AUDIT_ANCHOR_PRIVATE_KEY_PATH=/etc/fabt/oci/audit-anchor.pem`. Confirm via `docker inspect fabt-backend | grep -i oci_audit_anchor` (post-deploy, since pre-deploy refers to the soon-to-be-replaced container).
- [ ] **pg_dump backup** — `docker exec finding-a-bed-tonight-postgres-1 pg_dump -U fabt -d fabt -Fc > ~/fabt-backups/fabt-pre-v0.57.0-$(date -u +%Y%m%d-%H%M%S).dump`. Restore via `pg_restore`, NOT `psql`. (No schema migration, so backup is precautionary — but the discipline stays.)
- [ ] **Git tag + GitHub release published** — `v0.57.0` tagged on main HEAD post-merge of PR #176. The deploy MUST checkout the tag, not main HEAD.
- [ ] **SSH access confirmed** — open an SSH session to the VM before starting (`ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}`).
- [ ] **Local rehearsal PASS** — `make rehearse-deploy` ran against `release/v0.57.0` HEAD. PASS — smoke + rehearsal artifacts preserved at `/tmp/deploy-rehearsal-<TIMESTAMP>`. Re-run is required if tagging slips beyond 72h per `deploy/release-gate-pins.txt`.
- [ ] **CI green** — `gh run list --branch main --limit 5` — all runs green (CodeQL, CI, E2E, Performance, DV Access Control Canary, Legal Language Scan).

---

## 5. Deploy Steps

> **Canonical 5-file compose chain** (UNCHANGED from v0.56). EVERY `docker compose ... up -d` invocation MUST include all five `-f` flags. Missing any one breaks deploy (per `feedback_runbook_compose_chain.md`):
>
> ```bash
> # Define once at the top of the operator session.
> COMPOSE_CHAIN=(
>     -f docker-compose.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.43-flyway-ooo.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.44-pgaudit.yml
>     -f /home/ubuntu/fabt-secrets/docker-compose.prod-v0.52-oci-anchor.yml
> )
> # Use as `docker compose "${COMPOSE_CHAIN[@]}" ...`
> ```

### 5.0. Static content (docs site) — ship the v0.57 footer + /contact.js FIRST

Static content is served from `/var/www/findabed-docs/` on the Oracle VM. Nginx serves these via `try_files` — no restart needed after copying. The static delta deploys safely BEFORE the backend swap because:

1. `/contact.js` will fall back to GH-Issues if `/api/v1/public/contact-info` 404s — and v0.56's backend doesn't have that endpoint. The fallback is the JS-disabled noscript path PLUS the script's own catch-all. Both render the GH-Issues link cleanly. Worst-case window between static deploy and backend swap: visitors see the GH-Issues fallback for 5-10 minutes.
2. The 14 modified HTML pages are markup-only — no API contract change, no breaking layout shift.

```bash
# From your local Windows / Git Bash machine. FABT_VM_IP is set
# out-of-band per feedback_no_ip_in_repo.
cd /c/Development/findABed

# 1. Root index.html + 404.html (2 files)
scp -i ~/.ssh/fabt-oracle index.html 404.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/

# 2. NEW /contact.js (1 file) — must land at the SITE ROOT
#    (the <script src="/contact.js"> reference is absolute).
scp -i ~/.ssh/fabt-oracle contact.js \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/

# 3. 12 demo HTML files
scp -i ~/.ssh/fabt-oracle \
  demo/index.html \
  demo/dvindex.html \
  demo/hmisindex.html \
  demo/analyticsindex.html \
  demo/reentry-story.html \
  demo/for-cities.html \
  demo/for-coc-admins.html \
  demo/for-coordinators.html \
  demo/for-funders.html \
  demo/outreach-one-pager.html \
  demo/pitch-briefs.html \
  demo/shelter-onboarding.html \
  ubuntu@${FABT_VM_IP}:/var/www/findabed-docs/demo/

# 4. SSH-side verification — every page contains the placeholder + /contact.js reference
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP} '
  cd /var/www/findabed-docs
  for page in index.html 404.html \
              demo/index.html demo/dvindex.html demo/hmisindex.html \
              demo/analyticsindex.html demo/reentry-story.html \
              demo/for-cities.html demo/for-coc-admins.html \
              demo/for-coordinators.html demo/for-funders.html \
              demo/outreach-one-pager.html demo/pitch-briefs.html \
              demo/shelter-onboarding.html; do
    grep -q "class=\"contact-email\"" "$page" && \
      grep -q "/contact\.js" "$page" || echo "FAIL: $page"
  done
  test -f contact.js && echo "OK: /contact.js present" || echo "FAIL: /contact.js missing"
'
```

The §9 CI guard catches any in-source `@findabed.org` literal at PR time, but the post-scp grep above is the production-side canary. Expected output: only "OK: /contact.js present" — no "FAIL" lines.

### 5.1. Preserve last-good image tags

```bash
ssh -i ~/.ssh/fabt-oracle ubuntu@${FABT_VM_IP}
docker tag fabt-backend:latest fabt-backend:v0.56.0-lastgood
docker tag fabt-frontend:latest fabt-frontend:v0.56.0-lastgood
docker images | grep -E "fabt-(backend|frontend).*v0\.56\.0-lastgood"
# Expected: 2 lines, both tagged today.
```

These tags enable image-only rollback in §7 without rebuilding.

### 5.2. Checkout the release tag on the VM

```bash
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.57.0
git status   # Expect HEAD detached at v0.57.0
git log -1 --oneline   # Expected commit matches the v0.57.0 tag SHA from gh release view v0.57.0
```

### 5.3. Verify env-var landed (re-run gate from §4)

```bash
grep -q "^FABT_PLATFORM_CONTACT_EMAIL=" ~/fabt-secrets/.env.prod && echo OK || echo "MISSING"
```

Expected: `OK`. If MISSING, append per the §4 recipe and rerun. The compose recreate in §5.6 reads the env var; an unset, empty, or malformed value does NOT fail startup (no `@Email` on `PlatformContactProperties` — see §4 trailing-space-lint note). An absent or empty value is silently treated as empty (intended: degraded-but-safe → static-site falls back to GH-Issues). The §5.7 startup log line and the §6 post-deploy curl are the operator's only assurances that the value was set correctly.

### 5.4. Backend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight/backend
mvn -B clean package -DskipTests -q
ls -la target/*.jar | grep -v sources | grep -v javadoc
# Expected: exactly 1 line, fabt-backend-0.57.0.jar (~85-95 MB)

cd ~/finding-a-bed-tonight
docker build --no-cache \
  -f infra/docker/Dockerfile.backend \
  -t fabt-backend:v0.57.0 \
  -t fabt-backend:latest \
  .
docker images | grep fabt-backend | head -3
# Expected: fabt-backend:latest, fabt-backend:v0.57.0, fabt-backend:v0.56.0-lastgood
```

### 5.5. Frontend rebuild (clean + no-cache)

```bash
cd ~/finding-a-bed-tonight/frontend
rm -rf dist node_modules/.vite
npm ci
npm run build
ls dist/
# Expected: index.html + assets/ + the new ContactInfoProvider chunk in assets/

cd ~/finding-a-bed-tonight
docker build --no-cache \
  -f infra/docker/Dockerfile.frontend \
  -t fabt-frontend:v0.57.0 \
  -t fabt-frontend:latest \
  .
```

### 5.6. Bring up backend + frontend

```bash
cd ~/finding-a-bed-tonight
docker compose "${COMPOSE_CHAIN[@]}" --env-file ~/fabt-secrets/.env.prod \
  up -d --force-recreate backend frontend
docker ps --filter "name=fabt" --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"
# Expected: fabt-backend with image fabt-backend:latest (resolves to v0.57.0)
#           fabt-frontend with image fabt-frontend:latest (resolves to v0.57.0)
#           postgres unchanged.
```

### 5.7. Wait for backend readiness

```bash
# 120s budget — matches v0.56's pattern. Cold-pull of postgres + Flyway HWM
# check + bean discovery has historically pushed past 60s on contended Oracle
# Always Free under GC pressure.
TIMEOUT=120
for i in $(seq 1 $((TIMEOUT/3))); do
  status=$(curl -sf -o /dev/null -w "%{http_code}" http://127.0.0.1:9091/actuator/health/readiness)
  if [[ "$status" == "200" ]]; then
    echo "Backend ready after $((i*3))s"
    break
  fi
  sleep 3
done

# --since 5m catches the most-recent boot regardless of log volume.
# (--tail 50 was insufficient under busy startups; see Round 1 review.)
docker logs fabt-backend --since 5m | grep -iE "Started|platform contact email|FABT_PLATFORM_CONTACT_EMAIL"
# Expected lines:
#   "platform contact email configured: present"   (PlatformContactConfig log line)
#   "Started <App> in <X>s"
```

The `platform contact email configured: present` line confirms only that the env var was non-blank at startup — it does NOT validate RFC-5322 shape (see §4 Pre-Deploy Gates note on the lack of startup-time `@Email`). If it logs `absent`, the env var was empty or unset — re-run §5.3 and §5.6. If the line is missing entirely from the log window, fall back to `docker logs fabt-backend 2>&1 | grep "platform contact email"` (no time bound).

### 5.8. Cloudflare cache purge

After the backend is up + the frontend bundle is recreated, perform a single Cloudflare **Purge Everything** from the dashboard. The unauthed `/api/v1/public/contact-info` response is `Cache-Control: public, max-age=3600` — without an explicit purge, the v0.56-era 404 cached at edge will linger for up to an hour. Same applies to `/contact.js` (cached aggressively by browsers + edge).

Refill takes 1-2 minutes. The cold-cache window is acceptable; the §6 smoke gate runs against `https://findabed.org` and re-warms the relevant URLs.

---

## 6. Post-Deploy Gates

### Mandatory smoke gate

```bash
cd ~/finding-a-bed-tonight/e2e/playwright
FABT_BASE_URL=https://findabed.org \
  npx playwright test --config=deploy/playwright.config.ts post-deploy-smoke
# Expected: smoke spec PASS (15/15 or whatever the current count is — ground-truth pre-deploy).
```

Per `feedback_smoke_spec_default_target`, the `--config=deploy/playwright.config.ts` AND the `post-deploy-smoke` positional are BOTH required. Without them the spec defaults to localhost.

### Version check

```bash
curl -s https://findabed.org/api/v1/version
# Expected: {"version":"0.57"}
```

### Flyway HWM (no migration; expect unchanged)

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
  "SELECT installed_rank, version, description, success
   FROM flyway_schema_history
   ORDER BY installed_rank DESC LIMIT 3;"
# Expected: V98 still the last applied row. No new V99+ row.
```

If a V99+ row appears, something migrated unexpectedly — investigate before declaring success.

### Public contact-info endpoint (anonymous)

> All `curl ... | jq` commands in §6 run from the **operator's local machine** against `https://findabed.org`, not on the VM. Per `project_live_deployment_status.md`, `jq` is NOT installed on the VM — substitute `python -m json.tool` (or `python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))"`) if you must run the verification ON the VM.

```bash
curl -sf https://findabed.org/api/v1/public/contact-info | jq
# Expected JSON:
#   {
#     "platform": { "email": "<the value from .env.prod>" },
#     "tenant": null
#   }
# Anonymous request returns null tenant (no JWT to scope by).
# The platform.email value matches what the operator set in .env.prod.
#
# IMPORTANT — empty-string sentinel: if platform.email is "" (empty string,
# NOT the address), the env var was unset or empty at startup. PlatformContactProperties
# normalizes null/missing → "" rather than failing startup. Re-check §5.3
# (env-var presence on .env.prod) and §5.7 (startup log line "platform
# contact email configured: present|absent").

curl -sfI https://findabed.org/api/v1/public/contact-info
# Expected response headers:
#   Cache-Control: public, max-age=3600
#   ETag: "<some-hash>"
#   NO Vary: Authorization header (unauthed branch)
```

### Public contact-info endpoint (authed)

```bash
# FABT_TEST_JWT is a short-lived COC_ADMIN token from the test-jwt-mint script.
# Generate per project_demo_seed_credentials and the helpers/auth/ pattern.
curl -sfI -H "Authorization: Bearer ${FABT_TEST_JWT}" \
  https://findabed.org/api/v1/public/contact-info
# Expected response headers:
#   Cache-Control: private, max-age=3600
#   Vary: Authorization
#   ETag: "<some-hash>"   (different from the unauthed ETag)

curl -sf -H "Authorization: Bearer ${FABT_TEST_JWT}" \
  https://findabed.org/api/v1/public/contact-info | jq
# Expected: { platform: { email: "..." }, tenant: { slug: "<caller-tenant-slug>", email: "..." | null } }
#
# IMPORTANT — DV-policy read-side suppression:
#   tenant.email is null when the caller's tenant has tenant.config.dv_policy_enabled=true
#   (read-side suppression in ContactInfoController.buildResponseBody — same defense-in-depth
#   as the v0.56 DV-policy work). All 3 prod demo tenants (dev-coc, dev-coc-east,
#   dev-coc-west) have dv_policy_enabled=true after the V97 backfill. Therefore EVERY
#   authed curl from a demo-tenant JWT will return tenant.email=null regardless of
#   what (if anything) is set on tenant.config.contact.email. This is INTENDED, not a
#   regression. tenant.email is only non-null on a non-DV-policy tenant — which prod
#   does not currently have.
```

### Static-site /contact.js fetcher

```bash
curl -sf https://findabed.org/contact.js | head -10
# Expected: starts with /* contact.js — info-email-contact §6 ... */ comment block
#           then "(function() { ... }" IIFE wrapper.

curl -sf -I https://findabed.org/contact.js
# Expected: Content-Type: application/javascript (or text/javascript)
```

### 14 in-scope HTML pages — placeholder present

```bash
for url in \
  https://findabed.org/ \
  https://findabed.org/404.html \
  https://findabed.org/demo/ \
  https://findabed.org/demo/dvindex.html \
  https://findabed.org/demo/hmisindex.html \
  https://findabed.org/demo/analyticsindex.html \
  https://findabed.org/demo/reentry-story.html \
  https://findabed.org/demo/for-cities.html \
  https://findabed.org/demo/for-coc-admins.html \
  https://findabed.org/demo/for-coordinators.html \
  https://findabed.org/demo/for-funders.html \
  https://findabed.org/demo/outreach-one-pager.html \
  https://findabed.org/demo/pitch-briefs.html \
  https://findabed.org/demo/shelter-onboarding.html
do
  count=$(curl -sf "$url" | grep -c 'class="contact-email"')
  echo "$url -> $count placeholder(s)"
done
# Expected: every URL >= 1. for-cities.html shows 2 (§8.1 CTA + §7.7 footer).
# A 0 means the static scp missed the page or Cloudflare cached the v0.56 version.
```

### Static-HTML email-leak check (redundant safety net)

```bash
curl -sf -A "GoogleBot/2.1" https://findabed.org/ | grep -E '@findabed\.org' | wc -l
# Expected: 0
# Note: this is a redundant safety net. The §9 CI guard already enforces
# "no @findabed.org literal in source" at PR time, so this curl will be 0
# regardless of User-Agent. The check confirms (a) Cloudflare did not
# inject the address via Email Address Obfuscation rendering, and (b) the
# static scp shipped the right version. JS-running scrapers are NOT
# exercised here; the JS-injection primary defense (Cloudflare Email
# Address Obfuscation, verified pre-deploy in §4) handles them.
```

### Manual click-through (browser, incognito)

1. Open `https://findabed.org/` in an incognito window (per `feedback_stale_sw_on_deploy`).
2. Confirm footer placeholder hydrates to a `mailto:` link with the platform email visible.
3. Click the link — local mail client opens with the address pre-filled.
4. View page source — the `<a class="contact-email">` is no longer `hidden`; the `<noscript>` block is present in source but not rendered.

### Admin UI smoke (browser, COC_ADMIN login required) — READ-ONLY ON PROD

> **Important — column-name discipline (v0.56 lesson re-pinned):** the audit table is `audit_events` (PLURAL) per `V29__create_audit_events.sql`. Columns are `id, timestamp, action, actor_user_id, target_user_id, tenant_id, details, ip_address` — NOT `audit_event` / `event_type` / `occurred_at` / `actor_id` / `target_tenant_id`. Any post-deploy SQL must use the actual schema or the operator will see `column "X" does not exist` and mistake it for an audit-emission regression.

> **Prod runs `SPRING_PROFILES_ACTIVE=lite,demo,observability`.** `DemoGuardFilter` (`@Profile("demo")`) blocks PATCH on `/api/v1/admin/tenants/[^/]+/contact-email` with the rejection message "Contact-email changes are disabled in the demo environment." A CoC Admin save attempt on prod returns an error toast — that is INTENDED, not a deploy regression.

> **End-to-end save-path coverage gap (honest disclosure):** the PATCH save-path is verified by `ContactEmailControllerTest` (web-layer/unit tests) only. **It is NOT exercised end-to-end on the deploy timeline** — `scripts/deploy-rehearsal.sh` does not PATCH the endpoint, and DemoGuardFilter blocks it on prod. To exercise the full save path against prod, an operator can use the SSH-tunnel + platform-operator path (per `feedback_platform_login_via_ssh_tunnel.md` — surfaced in chat at deploy time, NEVER committed here). Treat this as a known-and-accepted gap until v0.57.1 extends the rehearsal script.

**On prod, smoke is read-only**:

1. Log in at `https://findabed.org/login` as a CoC Admin (any of the 3 demo tenants).
2. Navigate to `/admin`.
3. Confirm the **Contact Email Settings** card renders at the top of the page (above the tab bar).
4. Confirm the input shows the tenant's current contact email (or empty placeholder if the tenant has not set one).
5. If the tenant has DV-policy enabled, the input and Save button are disabled with the localized "DV-policy mode locks contact-email edits" copy.
6. **Do not click Save on prod** — DemoGuardFilter will reject. Save-path coverage is `ContactEmailControllerTest` only (see the disclosure above the steps); end-to-end save against prod requires the SSH-tunnel + platform-operator path, surfaced in chat at deploy time per `feedback_platform_login_via_ssh_tunnel.md`.
7. (Optional, ground-truth confirmation that recent v0.57 audit emissions are landing) confirm the audit table is reachable + recent rows exist on the corrected schema:

```bash
docker exec finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
SELECT action, timestamp, actor_user_id, tenant_id, details->>'config_key' AS config_key
FROM audit_events
WHERE action = 'TENANT_CONFIG_UPDATED'
  AND timestamp > now() - interval '7 days'
ORDER BY timestamp DESC
LIMIT 5;"
# Expected: zero or more rows from prior v0.55/v0.56 TENANT_CONFIG_UPDATED
# emissions (hold-duration, dv-policy, surge-threshold). Confirms the table
# + columns are right; the v0.57 contact.email writes will appear here once
# operators exercise the PATCH via SSH-tunnel + platform-operator path
# (NOT documented here per feedback_platform_login_via_ssh_tunnel — surface
# in chat at deploy time only).
```

### Stale-SW reminder

If the manual click-through shows the GH-Issues fallback instead of the email after 30 seconds, clear site data (or use a different incognito window). The frontend service worker may serve a cached v0.56 bundle.

### Declare deploy successful

Confirm ALL of the following are green before proceeding to §8:

- [ ] §6 mandatory smoke gate: PASS
- [ ] `curl /api/v1/version` reports `{"version":"0.57"}`
- [ ] Flyway HWM still V98 (no surprise V99+ row)
- [ ] `curl /api/v1/public/contact-info` (anonymous) returns the expected platform email (not `""`)
- [ ] Cache-Control split verified — `public` unauthed, `private + Vary: Authorization` authed
- [ ] All 14 in-scope HTML pages return ≥ 1 `class="contact-email"` placeholder
- [ ] Bot-UA static-leak check returns 0
- [ ] Manual click-through (incognito): footer hydrates to a `mailto:` link with the expected email
- [ ] Admin UI smoke read-only: ContactSettings card renders + DV-policy disabled state visible

If any gate fails → consult §7 Rollback Matrix for the matching row before proceeding. Do NOT advance to §8 with a failing gate.

Once all gates green → mark §5-§6 done; proceed to §8 housekeeping.

---

## 7. Rollback Matrix

| Symptom | Action | Time |
|---|---|---|
| Backend won't start (Spring context fails) | Read `docker logs fabt-backend --tail 200` first. Note: `FABT_PLATFORM_CONTACT_EMAIL` is NOT validated at startup (no `@Email` on `PlatformContactProperties`) — a malformed value will not fail context refresh. Likely causes are unrelated bean-wiring issues. If you suspect the env var anyway, edit `~/fabt-secrets/.env.prod`, fix the value, `docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. Re-run §5.7. | ~3 min |
| Backend OK but `/api/v1/public/contact-info` returns 500 | Image-only rollback — no schema migration to undo. `docker tag fabt-backend:v0.56.0-lastgood fabt-backend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. The static `/contact.js` falls back to GH-Issues automatically. | ~5 min |
| Frontend admin/login surface broken or visually wrong | Frontend image-only rollback: `docker tag fabt-frontend:v0.56.0-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate frontend`. The backend keeps running with the new JAR; the new admin Contact Email card is inaccessible until you rebuild the v0.57 frontend. | ~3 min |
| Static site shows wrong email (or no email) on the 14 pages | First diagnose: `curl -sf https://findabed.org/api/v1/public/contact-info`. If the backend is wrong, see row above. If the backend is right but pages still show fallback, the issue is `/contact.js` cache — Cloudflare Purge Everything again. | ~2 min |
| Cloudflare cache stuck on v0.56 response | Cloudflare dashboard → Caching → Configuration → Purge Everything. Refill is 1-2 minutes. | ~3 min |
| Full rollback to v0.56.0 | `docker tag fabt-backend:v0.56.0-lastgood fabt-backend:latest && docker tag fabt-frontend:v0.56.0-lastgood fabt-frontend:latest && docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend frontend`. Static-content rollback is OPTIONAL — the v0.57 markup is forward-compatible (the `<script src="/contact.js">` fails silently against a v0.56 backend that has no `/api/v1/public/contact-info`, the noscript fallback still renders). If you want to fully revert the static deploy, `git checkout v0.56.0` in the docs repo and re-scp the 14 pages + delete the now-orphaned `/contact.js`. | ~6-10 min |
| Env var `FABT_PLATFORM_CONTACT_EMAIL` MUST be removed | Edit `~/fabt-secrets/.env.prod` and delete the line, then `docker compose "${COMPOSE_CHAIN[@]}" up -d --force-recreate backend`. The endpoint will return `platform.email = ""` (empty string — `PlatformContactProperties` normalizes null to empty), the static-site falls back to GH-Issues. **No data loss** — the env var has no DB persistence. | ~3 min |
| Cloudflare dashboard unreachable during §5.8 purge | Wait window: edge cache refills automatically in ~1h via TTL. Frontend `/contact.js` falls back to GH-Issues link during the window — degraded but safe. Do not retry deploy until Cloudflare is reachable. Aligns with `reference_cloudflare_email_obfuscation_dependency.md` framing of Cloudflare as part of the runtime stack. | up to ~60 min |

---

## 8. Post-Deploy Housekeeping

- [ ] **Update `project_live_deployment_status.md` memory** — bump version line to `v0.57.0 (2026-MM-DD) — info-email-contact`, Flyway HWM unchanged at V98, note the new env var is set in `.env.prod`. Per §11 of the OpenSpec change tasks.md.
- [ ] **Update `project_resume_point.md` memory** — info-email-contact archived, next priority queue head is `issue-reporting-feedback` per `project_post_info_email_contact_priorities.md`.
- [ ] **Archive the OpenSpec change** — `/opsx:archive info-email-contact` after the 7-day post-deploy hygiene window opens (per tasks.md §13).
- [ ] **CHANGELOG.md entry** — release notes for v0.57.0 covering: PATCH endpoint, public read endpoint with cache-control split (mention the spec-review catch — Marcus + Casey + Sam — that prevented a cross-tenant cache leak), ContactSettings admin panel + DV-policy gating, `/contact.js` + 14-page footer, §9 CI guard, AI-synthetic Spanish disclosure for the new keys.
- [ ] **GitHub release notes** — `gh release create v0.57.0 --generate-notes` then edit to surface the security mention from CHANGELOG.
- [ ] **Update `project_planned_changes_post_analytics.md`** — mark `info-email-contact` as shipped and remove from the queue.
- [ ] **Optional: `FOR-DEVELOPERS.md` Recent Changes entry** — one-paragraph note that the platform email is now public via `/api/v1/public/contact-info` and admin-able via `/admin` ContactSettings (per tasks.md §12.3).
- [ ] **7-day inbound monitoring** — per tasks.md §13.1-§13.3:
  - Track inbound `info@` traffic — spam volume, legitimate inquiry volume, response-time achievable.
  - Watch `fabt_contact_info_requests_total` Micrometer counter for actual edge-cache hit rate vs origin hits.
  - Monitor Bucket4j 429 rate; investigate if >1% of traffic is hitting the limit.

---

## Related artifacts

- OpenSpec change: `openspec/changes/info-email-contact/` (proposal + design + tasks + specs delta).
- Code repo PR: `#176` (feature/info-email-contact).
- Docs repo PR: `#12` (feature/info-email-contact in findABed/).
- Memory: `project_platform_contact_email_prod_value.md` (the value source, NEVER in git).
- Memory: `reference_cloudflare_email_obfuscation_dependency.md` (secondary-defense Cloudflare setting).
- Memory: `feedback_runbook_compose_chain.md` (5-file chain discipline).
- CI guard script: `<docs-repo>/scripts/ci/check-contact-placeholder.sh` (the §9 enforcement).
- Playwright spec: `e2e/playwright/tests/contact-info-static.spec.ts` (§10.5 + §10.8 coverage).
