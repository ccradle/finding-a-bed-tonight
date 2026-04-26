# Oracle Deploy Notes — v0.53.0

**Tag:** v0.53.0 (DRAFT, not yet tagged)
**Branch:** `main` post-merge of PR for `feature/g-4.4-endpoint-migration` (Phase G-4.1, G-4.2, G-4.3, G-4.4 — and tracking G-4.5, G-4.6 as still-open scope below)
**Theme:** Phase G-4 — platform admin authority split + per-action audit log.

---

## 1. Consulted Memories

- `feedback_runbook_template_v1.md` — every oracle-update-notes follows the v1 template shape
- `feedback_runbook_compose_chain.md` — prod docker compose up needs ALL override files in order
- `feedback_never_print_rendered_secrets.md` — never cat / head / grep secret-containing config files
- `feedback_release_after_scans.md` — don't tag releases until CI scans are green
- `feedback_legal_claims_review.md` — scan all docs for overclaimed compliance before any release
- `feedback_dev_keys_prod_guard.md` — runtime guards on dev-only keys / endpoints
- `feedback_no_guessing_security.md` — never guess on security issues
- `project_phase_g_implementation_plan.md` — Phase G slice plan
- `project_resume_point.md` — current branch state

## 2. Scope & Non-Scope

### What ships

- Backend JAR `0.52.0 → 0.53.0`
- Flyway HWM `V85 → V89` — three new migrations:
  - **V87** — `platform_user` + `platform_user_backup_code` + `platform_key_material` schema; bootstrap row at id `0fab` inserted locked
  - **V88** — `platform_user_lockout_columns` (failed_mfa_attempts_at, locked_out_at, last_totp_code, last_totp_used_at) + SECURITY DEFINER wrappers
  - **V89** — `platform_admin_access_log` append-only audit table
- Frontend rebuild not required (v0.53.0 is backend-only — no platform-operator UI in this slice; admin actions still happen through existing tenant-admin pages + a new fabt-cli for platform actions)
- New SecurityConfig URL rule layer:
  - `/api/v1/auth/platform/**` — public (login flow)
  - `/api/v1/admin/tenants/**` — `PLATFORM_OPERATOR` only (lifecycle, key rotation)
  - `/api/v1/test/platform/unlock-expired` — `permitAll` (profile-gated controller; dev/test only — bean does not exist in prod)
  - `/api/v1/tenants/**` widened from `PLATFORM_ADMIN` to `hasAnyRole("PLATFORM_OPERATOR", "PLATFORM_ADMIN")` to let method @PreAuthorize be the real gate during the deprecation window
- New ArchUnit rules:
  - `NoPlatformAdminPreauthorizeTest` (no controller @PreAuthorize references PLATFORM_ADMIN)
  - `TestControllerProfileGuardTest` (every Test*Controller has @Profile dev | test)
- 2 new Playwright specs (`platform-admin-access-log.spec.ts`, `platform-totp-lockout.spec.ts`) + 1 smoke spec
- 3 new dev-seed `platform_user` rows (bootstrap `0fab`, smoke `0fa1`, lockout-target `0fa2`) — local dev only

### What does NOT change in this deploy

- Tenant authentication flow (`POST /api/v1/auth/login`) — unchanged
- Existing PLATFORM_ADMIN-bearing app_user accounts — V87 backfills `COC_ADMIN` so they continue to work for tenant-scoped admin tasks
- HMIS push (`POST /api/v1/hmis/push`) authority — remains COC_ADMIN-tenant-scoped (a brief @PlatformAdminOnly migration in G-4.4 was reverted because the service contract reads TenantContext); see §F16 below for the audit-trail compensation
- Prometheus rules / Grafana dashboards — no new alerts in this deploy (F18 deferred to G-4.5)
- Nginx config — no changes

---

## 3. Customer-comms note (C-S1)

**Required reading for every CoC pilot operator before deploy.** Send the
text below verbatim to each pilot's operator-of-record (Devon's training
cohort + Sarah Dickerson at Asheville).

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
> with mandatory two-factor authentication. The platform operator is a
> distinct identity from your tenant operator account; if you need access
> to these actions, contact platform-ops@findabed.org for activation.
>
> One usability change you'll see day-one: the **HMIS export trigger**
> (`Push to HMIS Vendors` button on the HMIS settings page, or
> `POST /api/v1/hmis/push` if you script against the API) now requires a
> confirmation header (`X-Confirm-HMIS-Push: CONFIRM`). The admin UI
> wires this automatically; if you script the call, add the header.
> Every HMIS push now leaves an audit-event row with your user id, the
> vendor list, and the bed-inventory snapshot count — visible in the
> tenant audit-events report.

---

## 4. Pre-deploy checklist

- [ ] CI green on `feature/g-4.4-endpoint-migration` (full mvn + Playwright)
- [ ] PR opened with per-endpoint OLD-role → NEW-role + AuditEventType table (M-S1)
- [ ] PR merged to main; tag `v0.53.0` cut from main HEAD
- [ ] CI scans green on tag (Trivy, ZAP, deps, SBOM)
- [ ] CHANGELOG entry promoted from `[Unreleased]` to `[v0.53.0]` with date
- [ ] `pom.xml` version bumped to `0.53.0` (NOT a snapshot)
- [ ] Customer-comms email sent to pilot operators (§3 text)
- [ ] Flyway dry-run executed against a copy of prod DB; V87+V88+V89 verified clean
- [ ] OCI WORM lock activation date confirmed (still applies — Phase G-3 contract from v0.52)

## 5. Deploy sequence

(Stub — fill in concrete commands during the deploy session per
runbook-template.md. Standard deploy:
backup → scp jar → flyway → docker compose up → smoke verify.)

## 6. Smoke verification

- [ ] `GET /actuator/health` returns 200 on the new JAR
- [ ] `GET /api/v1/version` returns 0.53.0
- [ ] Existing tenant operator can log in (no regression on `/api/v1/auth/login`)
- [ ] Platform login flow works against the seeded prod platform_user
- [ ] HMIS push admin UI button still works (confirm header attached automatically)
- [ ] Tenant audit-events list shows the new HMIS_EXPORT_TRIGGERED action

## 7. Rollback

- Backend JAR rollback to v0.52.0 + `flyway undo` is **NOT supported** for
  V87 (platform_user + REVOKEs) — V87 is forward-only.
- If post-deploy verification reveals a critical issue, the only safe
  rollback is forward-fix (v0.53.1 hotfix) or restoring the pre-v0.53
  DB snapshot taken in step 5.
- Drop the operator account credentials immediately if rollback is
  required (V87 bootstrap row activated in prod) — operator must
  re-activate on the rolled-forward release.

---

## 8. Known limitations + deferred follow-ups

- **F13 — `after_state` always NULL on PAL rows in v0.53.** Decision 11
  commits PAL+AE rows BEFORE proceed() runs the controller method, so
  `before_state` is captured but `after_state` is not. The append-only
  trigger from V89 prevents post-proceed UPDATE. Documented limitation;
  Phase H+ options captured in design.md.

- **F14 — Cross-tenant operator-driven HMIS push.** Currently
  `/api/v1/hmis/push` reads TenantContext (COC_ADMIN-only). A
  PLATFORM_OPERATOR cannot trigger an HMIS push for an arbitrary tenant.
  Future change adds `POST /api/v1/admin/tenants/{id}/hmis/push` that
  takes tenantId in the path. ~3-4h slice, slot in G-4.5 or post-v0.53.

- **F16 — HMIS push authority broadening (mitigated, not eliminated).**
  Pre-G-4.3 the endpoint required PLATFORM_ADMIN. V87 backfill grants
  COC_ADMIN to former PLATFORM_ADMIN-bearers, so existing tooling
  continues to work. CoC admins who never had PLATFORM_ADMIN are
  authorized in v0.53. Mitigations: confirm-header gate on the endpoint
  + per-tenant audit_event row with actor identity. Customer-comms note
  (§3) discloses to pilot operators.

- **F17 — Playwright fixture caching.** `setupPlatformOperator()`
  resets the bootstrap row on every IT call (~30s of CI bloat per run).
  Cache fixture across tests; defer to G-4.5.

- **F18 — Prometheus alert on per-operator tenant-update rate.** New
  threat surface post-G-4.4: a compromised PLATFORM_OPERATOR could
  mass-rename / mass-config-change tenants. Alert on per-operator
  mutation rate; lands in F3 / G-4.5 monitoring buildout.

- **F19 — Platform-operator observability config persistence IT.**
  Pin that PUT-then-GET observability round-trips on the @PlatformAdminOnly
  endpoint actually persist (in case a future RLS addition silently drops
  the write). ~30 min slice; defer to G-4.5.

---

## 9. Post-deploy housekeeping

- [ ] Archive OpenSpec change `platform-admin-split-and-access-log` via `/opsx:archive`
- [ ] Update `project_resume_point.md` memory to v0.53 deployed state
- [ ] Update `project_live_deployment_status.md` memory
- [ ] Smoke-verify against findabed.org with the deploy-verify Playwright suite
- [ ] Coordinate G-4.5 / G-4.6 kickoff in next session
