# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [v0.36.0] — 2026-04-13 — referral-shelter-name-visibility (#92)

### Added
- **Shelter name in My Referrals** — outreach workers see shelter name + creation time in the referral list, replacing the indistinguishable population-type-only display. Shelter name is snapshotted at referral creation (denormalized for offline resilience, 24h purge cycle makes redundancy negligible).
- **Safety check for deactivated shelters** — `GET /mine` performs a batch shelter lookup; PENDING referrals for inactive or non-DV shelters are flagged as `SHELTER_CLOSED` with phone withheld. ACCEPTED/REJECTED referrals keep their terminal status (Keisha Thompson: "showing 'Shelter closed' on a completed referral causes unnecessary panic").
- **Shelter active flag** (V52) — `shelter.active BOOLEAN NOT NULL DEFAULT TRUE`. Bed search query filters inactive shelters at the SQL level (`AND s.active = TRUE`). BedSearchService retains an in-memory filter as defense-in-depth.
- **Expiry tooltip** — hover over the referral headline shows "Expires at {time} ({N} min remaining)" following the DataAge.tsx native `title=` pattern.
- **DV_REFERRAL_REQUESTED audit event** — audit trail captures shelter_id, shelter_name, and urgency at referral creation.
- **Safety check Micrometer counter** — `fabt.dv.referral.safety.check.count` tagged by reason (SHELTER_CLOSED, SHELTER_MISSING, SHELTER_NOT_DV).
- **10 new integration tests** — accepted-keeps-status, shelter-rename-preserves-snapshot, multiple-referrals-distinct-names, null-shelter-name-backward-compat, inactive-shelter-excluded, audit-event-details, audit-resilience, and the original 3 Gemini-authored tests.
- **Active Changes section in README** — all 8 OpenSpec changes listed with status.

### Fixed
- **i18n: hardcoded English pluralization** — `person/persons` replaced with ICU plural form `{count, plural, one {# person} other {# persons}}` in EN and ES.
- **i18n: Spanish "VD" abbreviation** — replaced with "violencia doméstica" in sentence contexts (4 strings). Short UI labels kept abbreviated.
- **Accessibility: ARIA list semantics** — referral list container uses `role="list"` with `role="listitem"` children. Screen readers now announce "My DV Referrals, list, N items."
- **Mobile overflow** — referral headline reduced from 4-part to 3-part format (`{status} — {shelter} — {time}`) with CSS `text-overflow: ellipsis`. Population type moved to secondary line.
- **Playwright silent skip** — referral list assertion changed from conditional `if (count > 0)` to `await expect(toggle).toBeVisible()` per `feedback_never_skip_silently`.
- **Wrong package.json** — `playwright` accidentally added to `frontend/package.json` devDependencies instead of `e2e/playwright/`. Removed.
- **@Transactional on controller** — removed from `listMine()` and `listPending()`. Project convention: transactions on service methods, not controllers.
- **Dead 2-arg overload** — removed unused `ReferralTokenResponse.from(token, phone)`.
- **Metric naming** — `fabt.dv-referral.safety-check.count` → `fabt.dv.referral.safety.check.count` (dots, not hyphens).
- **CORS + nginx documentation** — added FABT_CORS_ALLOWED_ORIGINS and Playwright BASE_URL guidance to FOR-DEVELOPERS.md.

### Changed
- **V51 migration includes backfill** — existing referral tokens receive `shelter_name` from their associated shelter via `UPDATE ... FROM shelter WHERE shelter_name IS NULL`. Elena Vasquez overruled the original "avoid backfilling" note.

### Migrations
- **V51** — `ALTER TABLE referral_token ADD COLUMN shelter_name VARCHAR(255)` + backfill UPDATE
- **V52** — `ALTER TABLE shelter ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE`

---

## [v0.35.1] — 2026-04-12 — coordinator UX hotfix

### Fixed
- **Escalation CTA dead-end for coordinators** — "Review pending escalations" button only renders for COC_ADMIN and PLATFORM_ADMIN roles. Coordinators still see the CRITICAL banner text.
- **Coordinator dashboard status text** — "Total Beds" label replaced with "{N} assigned shelters" ICU plural form.

---

## [v0.35.0] — 2026-04-12 — coc-admin-escalation (#82)

### Added
- **CoC admin DV escalation queue** — pending DV referrals surface in the admin panel with claim/release/reassign/accept/deny lifecycle. Atomic claim with TOCTOU-safe `UPDATE ... RETURNING`. Soft-lock for 10 minutes, `Override-Claim` header for stealing, SPECIFIC_USER reassign breaks the escalation chain.
- **Per-tenant escalation policy** (V46) — append-only, versioned, configurable thresholds per CoC operational rhythm. Frozen-at-creation: each referral records the policy version active at creation time; mid-flight policy edits only affect new referrals (chain-of-custody invariant).
- **Escalation policy admin UI** at `/admin#dvEscalations` — table + detail modal + policy editor on desktop, card list on mobile. 4 SSE event types keep every admin's queue live (`referral.claimed`, `referral.released`, `referral.queue-changed`, `referral.policy-updated`).
- **CriticalNotificationBanner CTA** — "Review N pending escalations →" navigates to the escalation queue tab.
- **4 Micrometer metrics** — `fabt.escalation.batch.duration` (histogram), escalation policy Caffeine cache stats via CaffeineCacheMetrics, `fabt.dv-referral.claim.duration` (histogram by outcome), `fabt.dv-referral.claim.auto-release.count` (counter). 4 Grafana panels on the DV Referrals dashboard.
- **T-55a /manual-hold API Playwright** — 3 API-level scenarios including Prometheus counter-increment regression guard for Issue #102 RCA SecurityConfig fix.
- **ReferralEscalationPerfTest** — 200-referral regression guard (763ms measured, 60s budget).
- **EscalationPolicyServiceCacheMetricsTest** — 3 assertions pinning Caffeine cache stats as FunctionCounter.
- **Tomás Herrera accessibility persona** added to PERSONAS.md — WCAG 2.1 AA engineering advocate, former GSA 18F.

### Fixed
- **Dark mode `--color-error-mid` contrast** — `#f87171` (2.76:1 vs white) → `#b91c1c` (6.7:1). Pre-existing bug newly exercised by the cocadmin notification seed row. NotificationBell unread-count badge was the failing element. color-system.spec.ts now 6/6 green.

### Changed
- **cocadmin `dv_access` flipped to `true`** in seed-data.sql (fresh installs) and V50 migration (existing Oracle demo). Required for the escalation queue — CoC admins operating the DV workflow must see DV-protected data through RLS.
- **DV canary test** updated — creates a dedicated `dvAccess=false` COC_ADMIN user inline instead of depending on cocadmin's seed state. Per isolated test data policy.
- **Pre-release documentation audit** — version numbers updated to v0.35.0 across README, FOR-DEVELOPERS, architecture.md, WCAG-ACR, sustainability-narrative, theory-of-change. "VAWA Compliance Checklist" → "VAWA Self-Assessment Checklist" across 6 files. DBML updated with escalation_policy table and referral_token columns. Stale test counts corrected (586/338/42). Fabricated pilot city references removed from MCP-BRIEFING, proposal, and load test plan. Demo pages updated: "not yet deployed" → accurate demo-deployment framing, v0.12.0 badge removed, "Privacy Guarantee" → "Privacy Design", grant template placeholders replaced.

### Migrations
- **V46** — `CREATE TABLE escalation_policy` (append-only, per-tenant, NULLS NOT DISTINCT unique constraint, platform default seed)
- **V47** — `ALTER TABLE referral_token ADD COLUMN escalation_policy_id, claimed_by_admin_id, claim_expires_at` + partial index for auto-release scheduler
- **V48** — `ALTER COLUMN audit_events.actor_user_id DROP NOT NULL` (no-op — V44 already applied this in v0.34.0; preserved for lineage)
- **V49** — `ALTER TABLE referral_token ADD COLUMN escalation_chain_broken BOOLEAN DEFAULT FALSE`
- **V50** — `UPDATE app_user SET dv_access = true` for dev cocadmin UUID (idempotent, fresh-install safe)

---

## [v0.34.0] — 2026-04-11 — bed-hold-integrity (Issue #102 RCA)

> v0.33.0 (coc-admin-escalation) has not yet shipped at the time of this
> release. bed-hold-integrity branched directly from v0.32.3. Both
> branches' database migrations renumbered their `V4x` files to avoid
> collision when the branches eventually land on main. See the
> [bed-hold-integrity IMPLEMENTATION-NOTES](../openspec/changes/bed-hold-integrity/IMPLEMENTATION-NOTES.md)
> for the migration rename rationale.

### Fixed

- **CRITICAL — phantom `beds_on_hold` drift corrupting bed search**
  (Issue [#102](https://github.com/ccradle/finding-a-bed-tonight/issues/102)).
  `bed_availability.beds_on_hold` is the denormalized count that drives the
  bed search formula `beds_available = beds_total - beds_occupied - beds_on_hold`.
  Three independent write paths could write the cache out of sync with the
  source of truth (`COUNT(*)` of `HELD` reservations): delta-math-against-stale-
  baseline in `ReservationService.adjustAvailability`, lower-bound-only
  validation in the `PATCH /availability` path, and seed file inserting
  `beds_on_hold > 0` rows without matching reservation rows. Once drift was
  introduced, no path corrected it. Live demo had 17 shelter+population pairs
  with phantom holds totalling 24 beds through v0.32.3. **Program-harm risk in
  real-tenant deployments:** outreach workers searching for beds are silently
  routed away from shelters that actually have capacity — a person seeking
  shelter is told "no" when there's a bed available. Sam Okafor's framing:
  the worst kind of bug for FABT, silently invisible, biased toward affecting
  the most vulnerable populations.
- **CRITICAL — coordinator `/manual-hold` endpoint returned 403 at the
  Spring Security filter level.** `SecurityConfig.java:172` had a POST
  `/api/v1/shelters/**` catch-all that only admitted `COC_ADMIN` and
  `PLATFORM_ADMIN`. The new `POST /api/v1/shelters/{id}/manual-hold` endpoint
  matched this wildcard, so every coordinator call was rejected at the filter
  chain before `@PreAuthorize` or the controller's `isAssigned` check could
  run. Discovered during the pre-ship smoke test for bed-hold-integrity; the
  original IMPLEMENTATION-NOTES documented it as a "test infra wrinkle" when
  it was actually a production bug. Had this shipped unfixed, every
  coordinator in every real-tenant deployment who tried to create an offline
  hold (phone reservation, expected guest) would have been silently 403'd.
  See [IMPLEMENTATION-NOTES §A](../openspec/changes/bed-hold-integrity/IMPLEMENTATION-NOTES.md).

### Added — structural fix bundle

- **Single write-path discipline for `beds_on_hold`**
  (`ReservationService.recomputeBedsOnHold`). Replaces delta math in all
  four call sites (`createReservation`, `confirmReservation`,
  `cancelReservation`, `expireReservation`). Every write reads the actual
  `COUNT(HELD)` from the reservation table and writes a fresh
  `bed_availability` snapshot — no more trust in stale baselines.
- **`POST /api/v1/shelters/{id}/manual-hold` endpoint**
  (`ManualHoldController`) — creates a real `reservation` row for the
  legitimate "coordinator marks beds as held for offline reasons" use case
  (phone reservations, expected guests). Goes through
  `recomputeBedsOnHold` so `beds_on_hold` stays consistent. Idempotency
  key derived from `(userId, shelterId, populationType, current_minute)`
  hashed via `UUID.nameUUIDFromBytes` to fit the 36-char column width.
  `DemoGuardFilter` friendly-blocks this path in demo profile (would
  interfere with other visitors' bed search results).
- **`AvailabilityController` PATCH ignores client-supplied `bedsOnHold`**.
  Field is deprecated (Javadoc on `AvailabilityUpdateRequest.bedsOnHold`),
  logged as WARN if non-null and non-zero, will be hard-rejected in
  v0.35.0. Eliminates drift source #2.
- **Spring Batch reconciliation tasklet
  (`BedHoldsReconciliationJobConfig`)** — 5-minute cadence, defense in depth.
  `findDriftedRows()` SELECT-JOINs latest snapshots with HELD counts; every
  drifted pair gets a corrective `recomputeBedsOnHold` call plus a
  `BED_HOLDS_RECONCILED` audit row. Wrapped in per-row
  `TenantContext.runWithContext(tenantId, true, ...)` so DV shelter rows
  are visible under RLS and corrections bind the right tenant. Uses
  `ResourcelessTransactionManager` on the step so per-row failures don't
  mark the outer transaction rollback-only (the
  `feedback_transactional_eventlistener` gotcha applies here too).
- **`AuditEventTypes.BED_HOLDS_RECONCILED` constant** — shared audit event
  action string used by both the reconciliation tasklet AND the V45 backfill
  migration.
- **`V44__audit_events_allow_null_actor.sql`** — drops `NOT NULL` on
  `audit_events.actor_user_id` so system-actor audit writes can use `NULL`.
  Idempotent with coc-admin-escalation's V42.
- **`V45__backfill_phantom_beds_on_hold.sql`** — one-time correction
  migration. Writes corrective `bed_availability` snapshots for every
  drifted `(shelter, population)` pair, plus matching
  `BED_HOLDS_RECONCILED` audit rows (compound CTE with `INSERT ...
  RETURNING` → audit row join). Per Casey Drummond's chain-of-custody ask
  in the war room — the backfill is a system-initiated state change and
  must be auditable.
- **Seed `infra/scripts/seed-data.sql` backs 3 orphan `beds_on_hold > 0`
  rows with 5 HELD reservations**, plus a Component 6 ordering fix block
  that writes fresh `bed_availability` snapshots after the reservation
  inserts. Without the ordering fix, V45 runs during Spring context init
  before the seed reservations exist, writes corrective snapshots of 0,
  then the seed reservations get inserted → reverse drift that
  reconciliation catches 5 minutes later but leaves a 5-minute phantom-LOW
  window on every `--fresh` restart. The ordering fix block uses
  `clock_timestamp()` (strictly later than V45's rows) and is wrapped in
  a single `BEGIN; ... COMMIT;` transaction with the reservation inserts
  per Elena Vasquez (war room).
- **`BedHoldsInvariantTest`** — Riley Cho's gating test class. Asserts
  `beds_on_hold === COUNT(HELD reservations)` after every lifecycle event
  (create, cancel, expire, confirm, manual hold, explicit recompute).
  Load-bearing regression guard — any future refactor that reintroduces
  delta math fails this test loudly.
- **`BedHoldsReconciliationJobTest`** — 4 tests covering the tasklet end
  to end: seeded drift correction, audit row write, no-drift-no-work,
  per-row failure continuation.
- **`OfflineHoldEndpointTest`** — 5 tests covering the new `/manual-hold`
  endpoint. **The `coordinator_creates_offline_hold_succeeds_when_assigned`
  test uses real `coordinatorHeaders()` (not admin bypass)** — this is the
  production-path coverage that was originally missing and that caught the
  SecurityConfig filter bug. The
  `coordinator_not_assigned_to_shelter_403` test now asserts that the
  `fabt.http.access_denied.count` counter incremented by 1, which proves
  the rejection came from the controller's `isAssigned` branch
  (GlobalExceptionHandler increments the counter) and not from the
  SecurityConfig filter chain (does not). Regression guard for Marcus
  Webb's concern that the two 403 paths were indistinguishable.
- **`ManualHoldController.create()` Javadoc** documents the two-layer
  authorization contract (filter chain admits roles, controller body
  enforces shelter assignment) so a future refactor of either layer can
  see the contract it's breaking.

### Changed

- `SecurityConfig.java:172` — added explicit
  `POST /api/v1/shelters/*/manual-hold` matcher admitting `COORDINATOR`,
  `COC_ADMIN`, `PLATFORM_ADMIN` BEFORE the broader `POST /shelters/**`
  rule. Spring matchers are first-match-wins.
- `DemoGuardFilter.getBlockMessage` — friendly-block message for
  `/api/v1/shelters/{id}/manual-hold` ("Manual offline holds are disabled
  in the demo environment — would interfere with other visitors' bed
  search results"). Path is intentionally NOT allowlisted (cross-visitor
  impact, like reassign).
- Three pre-existing tests updated for server-managed `bedsOnHold`:
  `BedAvailabilityHardeningTest.tc_1_7_*` rewritten to place real
  reservations first; `OverflowBedsIntegrationTest.overflow_doesNot_*`
  and `AvailabilityIntegrationTest.test_shelterDetail_*` changed hold
  inputs from non-zero to zero since the cached value is no longer
  client-supplied.

### Migration ordering and sequencing

- V44 and V45 renumbered from the IMPLEMENTATION-NOTES' original V40/V41
  to avoid collision with coc-admin-escalation's V40-V43 on shared
  volumes. V44/V45 were also **swapped** late in the cycle after the war
  room: V44 is now the `NOT NULL` drop (previously V45), V45 is now the
  backfill (previously V44). This ordering is required so V45's audit
  row inserts can use `actor_user_id = NULL`. Flyway runs migrations in
  version order, so V44 → V45 is guaranteed.

### Pre-existing non-blockers tracked separately

4 Playwright failures discovered during the pre-ship verification are
pre-existing on main and do not block this release. Tracked in
[#103](https://github.com/ccradle/finding-a-bed-tonight/issues/103):
`notifications.spec.ts:101` test isolation, `persistent-notifications.spec.ts:54`
seed dependency, two `totp-and-access-code.spec.ts` copy/testid drifts,
plus a Playwright-config Vitest contamination from the v0.32.3 hotfix.

### Affected versions (phantom beds_on_hold)

- **Introduced:** gradually over multiple releases, starting from the
  initial seed-data.sql orphan rows. Delta math in `adjustAvailability`
  has been the primary propagator since the reservation lifecycle was
  added.
- **Detected in:** v0.32.3 live demo (2026-04-11 founder RCA —
  [GH #102](https://github.com/ccradle/finding-a-bed-tonight/issues/102)).
- **Fixed in:** v0.34.0 (this release).

### Deployment notes

- V44 + V45 migrations run automatically during backend startup.
- V45 backfill on the live demo is expected to correct **17 drifted
  pairs** (24 total phantom beds) and write 17 `BED_HOLDS_RECONCILED`
  audit rows with `correction_source = 'V45_backfill'`.
- Post-deploy verification includes a real-coordinator curl against
  `POST /api/v1/shelters/{id}/manual-hold` to confirm the SecurityConfig
  fix is live. See `docs/runbook.md` for the exact command.
- Rollback: redeploy v0.32.3 backend jar. V44 and V45 leave append-only
  state (no UPDATE, no DELETE), so rolling back the code does not
  corrupt existing data — it just re-introduces the original drift until
  the next forward deploy.

### Disclosure

If any coordinator was trained on `/manual-hold` between the merge of
`bed-hold-integrity` and the v0.34.0 deploy, notify them that the
coordinator path was unavailable in that window and verify they can
reach the endpoint now. See
[`docs/runbook.md`](docs/runbook.md) "Disclosures" section for the
trainer email template (same pattern as the v0.32.3 hotfix disclosure).

---

## [v0.32.3] — 2026-04-11 — Notification Bell Accept/Reject Render Fix (Hotfix)

### Fixed
- **CRITICAL — Persistent DV referral notifications rendered as "rejected" regardless of actual status.** `NotificationBell.getNotificationMessageId` read `data.status` directly, but persistent notifications loaded from the database carry their domain-event payload as a JSON **string** under `data.payload`. For every persisted `dv-referral.responded` or `referral.responded` notification, `data.status` was `undefined`, the check fell through to the else branch, and the UI rendered `notifications.referralRejected` — even when the backend had correctly published `status: 'ACCEPTED'` and the database row carried that value in the payload. Live SSE push notifications received during an active session were unaffected because the domain event puts the status directly on `data`.
- **Secondary — Shelter name on `availability.updated` notifications failed to render** for persistent notifications via the same payload-shape bug at `NotificationBell.tsx` line 322. Users saw "A shelter updated availability" with no shelter name attached when the notification was loaded from the database. Live SSE events rendered correctly. Same fix, same module.

### Affected versions
- **Introduced:** commit `8ebb666` (2026-04-08), "Add persistent notifications: DB-backed bell badge, coordinator banner, DV escalation (#77)". Before this commit, the codebase only had SSE push notifications, which always carry status on `data` directly, and the code was correct.
- **Shipped in:** v0.31.0, v0.31.1, v0.31.2, v0.32.1, v0.32.2.
- **Live impact on findabed.org:** every user who logged in and viewed the notification bell saw "rejected" text for their accepted DV referrals, and missing shelter names on availability updates, from the v0.31.0 deploy until this hotfix. This is a demo site with synthetic data — **no real survivors were affected** — but trainers, funders, and onboarding CoC admins who used the demo during this window may have formed incorrect mental models of the workflow. If you trained anyone between 2026-04-08 and today, a brief heads-up email noting the visual bug and the correct behavior is recommended. See [docs/runbook.md](docs/runbook.md) for the disclosure note template.

### Added
- **`frontend/src/components/notificationMessages.ts`** — extracted `parseNotificationPayload`, `getNotificationMessageId`, `getNotificationMessageValues`, `getNavigationPath` from `NotificationBell.tsx` into a standalone module so they can be unit-tested in isolation without mounting the React component. Every future read of notification payload fields must use `parseNotificationPayload` + the `data.x ?? payload.x` fallback pattern documented in that file's header comment.
- **`frontend/src/components/notificationMessages.test.ts`** — 20 Vitest unit tests covering both data shapes (live SSE direct `data.*` + persistent `data.payload` JSON string) across both directions (ACCEPTED + REJECTED) across both event type spellings (`dv-referral.responded` + `referral.responded`), plus `parseNotificationPayload` happy path + malformed + missing + wrong-type edge cases, plus `getNotificationMessageValues` for `shelterName`/`status`/`count` extraction. These tests are the load-bearing regression guard. A future refactor that reintroduces the single-shape read fails all four `persistent …` tests loudly.
- **`frontend/package.json` test script** — `"test": "vitest run"` and `"test:watch": "vitest"` added so the test infrastructure is discoverable to CI and future contributors. Previously there was no npm test script despite Vitest being installed.

### Changed
- `NotificationBell.tsx` — imports the extracted helpers; the inline `getNotificationMessageId`, `getNotificationMessageValues`, and `getNavigationPath` definitions are removed. Behavior is identical for live SSE events; persistent notifications now render correctly.
- The `availability.updated` shelter name render path at the former line 322 now routes through `getNotificationMessageValues(notification).shelterName` so persistent availability notifications display the shelter name consistently with live ones.

### Test Results
- Frontend: **35 passed** (20 new notification tests + 15 pre-existing offlineQueue tests), 0 failures
- `npm run build` — tsc strict + Vite production build pass clean, 0 errors, 0 warnings beyond the existing chunk-size notice
- Manual verification: create a DV referral as dv-outreach, accept as cocadmin via the escalation queue, log back in as dv-outreach, open notification bell — correctly shows "A shelter accepted your referral"

### Not included in this hotfix (deferred to v0.33.0)
- Playwright E2E spec for the full create → accept → verify-rendered-text round-trip. The Vitest unit tests + manual verification are sufficient for a targeted hotfix; the broader E2E build-out is part of Riley Cho's Session 7 testing work.
- Related notification bell empty-state test fix (different bug, task #123) — deferred to v0.33.0.

### Deployment
- No database migrations.
- No backend code changes (version bump only — the backend was already publishing the correct payload).
- Frontend-only change, single JS bundle rebuild.
- Rollback plan: redeploy v0.32.2 backend jar + frontend dist. No schema state change to undo.

---

## [v0.32.2] — 2026-04-10 — Webhook Read Timeout Fix

### Fixed
- **Webhook delivery read timeout** — `WebhookDeliveryService` documented a 30s read timeout in design D3 but only the connect timeout (10s) was actually wired into `JdkClientHttpRequestFactory`. JDK `HttpClient` has no per-client read timeout, so a hanging webhook subscriber would block a virtual thread until the JVM died. Marcus Webb's lens: documented timeouts that don't exist are a security gap. Discovered while writing `WebhookTimeoutTest` — exactly what the test was for.

### Added
- **Configurable webhook timeouts** — both timeouts are now constructor-injected and configurable:
  - `fabt.webhook.connect-timeout-seconds` (env: `FABT_WEBHOOK_CONNECT_TIMEOUT_SECONDS`, default `10`)
  - `fabt.webhook.read-timeout-seconds` (env: `FABT_WEBHOOK_READ_TIMEOUT_SECONDS`, default `30`)
  - Defaults preserve the original D3 contract. Slow legacy partners can be granted extended timeouts via env var without code changes.
- **`WebhookTestEventDeliveryTest`** (T-25) — 4 integration tests covering the `POST /api/v1/subscriptions/{id}/test` endpoint: HMAC signing format, X-Test-Event header, JSON body shape, success/failure recording, 404 on missing subscription. Uses WireMock to verify the full transport path.
- **`WebhookTimeoutTest`** (T-25a) — 2 integration tests verifying the read timeout fires (1s configured timeout vs 3s WireMock delay → completes in ~1.1s, failure recorded, never reports the upstream's would-be 200) and that fast endpoints under the timeout still succeed.
- **WireMock 3.13.2** test dependency (`org.wiremock:wiremock-standalone`, test scope). Spring Framework explicitly recommends mock web servers over `MockRestServiceServer` for RestClient testing because they exercise the real transport layer and can simulate timeouts. Validated against Java 25 / Spring Boot 4. Matches portfolio standard `PLATFORM-STANDARDS.md §10`.

### Changed
- `WebhookDeliveryService` constructor signature: added `@Value("${fabt.webhook.connect-timeout-seconds:10}")` and `@Value("${fabt.webhook.read-timeout-seconds:30}")` parameters. Spring DI handles defaults — existing test contexts continue to work without overrides.

### Test Results
- Backend: **502 passed**, 0 failures, 0 errors (up from 425 at v0.30.0)
- ArchUnit: **22 passed** — modular monolith boundaries intact
- Webhook test classes: 20 (existing) + 4 (T-25) + 2 (T-25a) + 6 (resilience) = 32, all green

### Spec
- `openspec/changes/platform-hardening/`: tasks T-25, T-25a, T-43, T-PD-2, T-9a, T-24g marked done with evidence; T-24b and T-64l marked REJECTED with rationale (the implementation pattern eliminated the cases the tests would have covered); design D3a documents the timeout configurability decision.

---

## [v0.32.1] — 2026-04-09 — Bed Search Performance Optimization

### Changed
- **Bed search query optimized** — rewrote `findLatestByTenantId()` from `SELECT DISTINCT` subquery to recursive CTE skip scan. Uses existing indexes (no schema change). Verified functionally equivalent across multiple tenants. All 44 availability tests pass.

---

## [v0.32.0] — 2026-04-09 — Charlotte Pilot Readiness

### Added
- **Coordinator-shelter assignment UI** (#91) — searchable combobox + removable chips on shelter edit (W3C APG), read-only chips on user edit drawer. `GET /users/{id}/shelters`, `GET /shelters/{id}/coordinators` endpoints.
- **Notification pagination** (#83) — `GET /notifications?page=0&size=20` with `{items, page, size, hasMore}` response. "Load more" button in bell dropdown.
- **Email password reset** (#36) — `POST /forgot-password` + `POST /reset-password`. SHA-256 token hashing (OWASP), 256-bit SecureRandom tokens, 30-min expiry. DV users blocked (NNEDV). Generic email (no platform name). GreenMail test infrastructure.
- **Webhook retry + circuit breaker** (#51) — resilience4j Retry (3 attempts, exponential backoff) + CircuitBreaker (sliding window 20, 50% threshold). Config-driven via application.yml.

### Fixed
- **DV referral expiry** (v0.31.2) — `@Transactional` on `expireTokens()` acquired JDBC connection before dvAccess=true was set, making DV tokens invisible. 3 stuck tokens on demo site (oldest 7 days).
- **Notification dismiss** — X button now calls mark-read API (was client-only, CRITICAL notifications reappeared on refresh).
- **tokenVersion on password change** — `PasswordController.changePassword()` and admin `resetPassword()` now increment tokenVersion to invalidate all existing JWTs immediately.

### Changed
- Notification `GET /notifications` response changed from `List<Notification>` to `{items, page, size, hasMore}` (breaking API change, documented in @Operation).
- Scheduled job logging: `log.debug` for zero-result runs, `log.info` when rows affected (reduces log volume).
- `EmailService` conditionally loaded (`@ConditionalOnProperty("spring.mail.host")`).

---

## [v0.31.2] — 2026-04-09 — DV Referral Expiry Fix

### Fixed
- **DV referral tokens stuck in PENDING** — `@Transactional` on `expireTokens()` eagerly acquired a JDBC connection before `TenantContext.runWithContext()` set dvAccess=true. The RLS-aware DataSource read dvAccess=false, making DV shelter referral tokens invisible to the UPDATE query. Tokens remained PENDING indefinitely (3 stuck on demo site, oldest 7 days).
- **DV terminal tokens never purged** — same root cause in `purgeTerminalTokens()`. EXPIRED DV tokens were never hard-deleted by the hourly purge job.

### Changed
- Removed `@Transactional` from `expireTokens()` and `purgeTerminalTokens()` — single-statement SQL is already atomic. JdbcTemplate now acquires connections lazily inside `runWithContext` where dvAccess=true.
- Added fail-fast assertion: `if (!TenantContext.getDvAccess()) throw IllegalStateException` — prevents silent zero-row failures.
- Added diagnostic logging: every scheduled run now logs dvAccess state and affected row count, even when 0 rows.

### Added
- `DvReferralExpiryRlsTest` — integration test calling `expireTokens()` without outer TenantContext (as `@Scheduled` does). Proves DV tokens are correctly expired.
- Troubleshooting entry in FOR-DEVELOPERS.md: `@Transactional + runWithContext` pattern rule.

---

## [v0.31.1] — 2026-04-09 — Notification RLS Hotfix

### Fixed
- **Escalation dedup failure** — per-user RLS policy blocked the dedup SELECT, causing 144 duplicate escalation notifications in 2 hours on demo site. Root cause: per-user RLS has no "see all" value for system operations.
- **Cleanup job silent failure** — DELETE WHERE clause evaluation went through per-user SELECT RLS policy, matching zero rows.
- **INSERT RETURNING failure** — Spring Data JDBC's `save()` triggers SELECT policy on RETURNING clause.

### Changed
- **V38 migration**: replaced per-user SELECT + UPDATE RLS policies with unrestricted `USING (true)`. Service layer enforces per-user access via `WHERE recipient_id = ?` in all repository queries.
- **markRead/markActed**: controller now extracts userId from JWT and passes to repository (replaces RLS as security boundary).
- **Removed all RESET ROLE hacks**: eliminated raw connections, set_config overrides, and @Transactional workarounds from NotificationPersistenceService and ReferralEscalationJobConfig.

---

## [v0.31.0] — 2026-04-09 — Persistent Notifications (#77)

### Added
- **Persistent notification store** — PostgreSQL `notification` table with JSONB payload, severity tiers (INFO, ACTION_REQUIRED, CRITICAL), 4 granular RLS policies. Notifications survive logout/restart.
- **Bell badge from DB** — REST count on mount (source of truth), SSE real-time increment/decrement, dedup by notification ID. Badge shows accurate unread count immediately on login.
- **Coordinator referral banner** — persistent red banner with pending DV referral count, SSE real-time updates, not dismissable (resolves when referrals are actioned).
- **CRITICAL notification banner** — persistent at top of page until acted on (Design D3), `role="alert"` for screen readers.
- **DV referral escalation** — Spring Batch job (every 5 min): T+1h reminder to coordinator, T+2h CRITICAL to CoC admin, T+3.5h expiry warning to coordinator + worker, T+4h expired to worker. Dedup via functional index on `payload->>'referralId'`.
- **Notification event listeners** — `@TransactionalEventListener(AFTER_COMMIT)` for referral requested/responded, surge activated/deactivated, reservation expired.
- **Notification REST API** — 6 endpoints: list, count, stream (SSE), mark read, mark acted, mark all read. "Mark all read" excludes CRITICAL (Design D3).
- **DV coordinator seed user** — `dv-coordinator@dev.fabt.org` (COORDINATOR + dvAccess=true), assigned to all 3 DV shelters. Receives referral notifications.
- **Coordinator pending count endpoint** — `GET /api/v1/dv-referrals/pending/count` for dashboard banner.
- **33 backend integration tests** — RLS policies, escalation batch job, referral/surge/reservation notifications, cleanup, cross-tenant isolation, zero-PII payloads.
- **8 Playwright E2E tests** — bell badge, CRITICAL banner, mark-as-read, DV isolation, i18n rendering, WCAG compliance.
- **11 i18n keys** (en + es) for surge, reservation expiry, escalation thresholds, coordinator banner, critical banner.

### Changed
- **TenantContext** extended with `userId` for notification RLS (`app.current_user_id` set on every JDBC connection).
- **RlsDataSourceConfig** sets `app.current_user_id` (nil UUID when no user context — fails closed).
- **BatchJobScheduler** supports `dvAccess` flag — wraps job execution in TenantContext before Spring Batch acquires connections.
- **JsonString** `@JsonValue` annotation for correct REST serialization of JSONB payloads.
- **DemoGuard** allowlists notification mutation endpoints (read/acted/read-all).
- **Duplicate referral error** — descriptive "You already have a pending referral for this shelter" instead of generic 409.

### Database
- V35: `notification` table, 4 RLS policies (SELECT/UPDATE by recipient, DELETE/INSERT unrestricted), partial index on unread, tenant index
- V36: Functional index on `notification((payload ->> 'referralId'))` for escalation dedup
- V37: Partial index on `referral_token(status, expires_at) WHERE status = 'PENDING'` for expiry job

---

## [v0.30.1] — 2026-04-08 — Surge Banner Contrast Fix (#79)

### Fixed
- **WCAG 1.4.3 Contrast** — Admin Surge tab "Active since" timestamp used `color.textTertiary` (gray) on dark red gradient background. Changed to `color.textInverse` (white). Same pattern as the v0.29.5 fix for outreach search and coordinator dashboard surge banners — this instance was missed during the admin panel extraction.

---

## [v0.30.0] — 2026-04-08 — Platform Hardening (#51)

### Added
- **API key lifecycle** — rotate with 24h grace period (old + new key both authenticate), revoke (immediate, clears grace), 256-bit key entropy (32 bytes), `lastUsedAt` tracking. Admin UI: confirm dialogs, 3-state status badges (Active/Grace Period/Revoked), grace period countdown.
- **Webhook subscription management** — pause/resume toggle, send test event (inline result), expandable delivery log (last 20 attempts, secrets redacted, 1KB truncated), delete with confirmation. 5-state status badges (Active/Paused/Failing/Deactivated/Cancelled). Auto-disable at 5 consecutive failures, reset on re-enable.
- **Per-IP API key rate limiting** — Bucket4j + Caffeine cache (10K max IPs, 10m TTL). Returns 429 with `Retry-After`, `X-RateLimit-Limit/Remaining/Reset` headers. Nginx edge rate limit at 1r/s with burst=20.
- **Server-side retry** — Spring Framework 7 native `@Retryable` on availability snapshots (2 retries, 100ms backoff, exponential). AOP self-invocation fix: separate `AvailabilityRetryService` class.
- **AES-256-GCM secret encryption** — generalized `SecretEncryptionService` (from TOTP-only `TotpEncryptionService`). Webhook callback secrets encrypted at rest, decrypted for HMAC computation. Dev-key-in-prod rejection.
- **7 Playwright tests** for API key and subscription management UI
- **Seed data** — 3 API keys (all badge states), 3 subscriptions (Active/Failing/Deactivated), 6 delivery log entries
- 63 i18n keys (en + es) for API key lifecycle and webhook management

### Changed
- **Backend DTOs** — `ApiKeyResponse` now includes `lastUsedAt`, `oldKeyExpiresAt`; `SubscriptionResponse` includes `consecutiveFailures`
- **`SubscriptionStatus` union type** — compile-time safety for 5 status values
- **Dev nginx rate limits** — relaxed to 30r/s (vs 1r/s production) in `00-rate-limit-dev.conf` for Playwright test reliability

### Fixed
- **9 pre-existing Playwright failures** — 7 caused by nginx `api_edge` rate limiting throttling rapid test API calls (RCA: 1r/s limit on all `/api/` paths in dev). 2 caused by reservation panel double-toggle bug (hold handler opens panel, test re-toggled it closed) + shelter detail modal missing Escape key support.
- **WCAG 2.1.1 Keyboard** — shelter detail and DV referral modals now support Escape dismiss (WAI-ARIA dialog pattern: `tabIndex={-1}`, `ref`, `useEffect` auto-focus, `role="dialog"`, `aria-modal`).
- **CI: DV canary job** — added `FABT_TOTP_ENCRYPTION_KEY` env var (required since `KarateRunnerTest` runs all features including subscription-crud which encrypts webhook secrets).

### Security
- API key entropy: 128-bit → 256-bit (32 bytes, `SecureRandom`)
- Webhook response body redaction: Bearer tokens, AWS keys, API keys, emails, SSNs, credit card numbers — 6 regex patterns applied before database persistence
- `WebhookDeliveryLog` truncates response body to 1KB in entity constructor
- Demo guard blocks all new mutation endpoints (revoke, rotate, pause, test) on public demo site

### Database
- V33: `old_key_hash`, `old_key_expires_at` columns on `api_key` table (grace period support)
- V34: `webhook_delivery_log` table + `consecutive_failures` column on `subscription`

### Documentation
- `schema.dbml`: webhook_delivery_log, api_key grace period, subscription failures
- `asyncapi.yaml`: webhook.test-delivered, subscription.auto-disabled channels
- `FOR-DEVELOPERS.md`: API reference for 6 new endpoints, rate limit configuration
- `erd.svg`: regenerated from updated DBML
- `WCAG-ACR.md`: updated to v0.30.0 with modal Escape fixes

### Test Results
- Backend: 425 passed, 0 failures (including 22 ArchUnit rules)
- Playwright: 299 passed, 0 failures (through nginx, `--trace on`)
- Karate: 82 API scenarios across 29 features
- ESLint + TypeScript: 0 errors

---

## [v0.29.6] — 2026-04-07 — Admin Panel Extraction (#74)

### Changed
- **Admin panel refactored from 2,136-line monolith to 15 focused files** — `AdminPanel.tsx` is now a 128-line orchestrator with `React.lazy()` + `Suspense` for code splitting. Each of the 10 tabs is a separate Vite chunk, downloaded only when activated.
- **New `TabErrorBoundary`** — a failing tab shows an error message with "Try Again" button instead of crashing the entire admin panel. Tab bar stays functional. Resets on tab switch via `key={activeTab}`.
- **Deploy-verify specs isolated** — 5 deployment verification scripts moved from `e2e/playwright/tests/` to `e2e/playwright/deploy/` with standalone `playwright.config.ts`. These were silently corrupting seed credentials (outreach password changed on non-demo runs), causing cascading 401 failures across the test suite.

### Added
- `frontend/src/pages/admin/types.ts` — shared interfaces (User, ShelterListItem, ApiKeyRow, etc.)
- `frontend/src/pages/admin/styles.ts` — shared style objects (tableStyle, thStyle, tdStyle, etc.)
- `frontend/src/pages/admin/components/` — StatusBadge, RoleBadge, ErrorBox, NoData, Spinner, ReservationSettings, TabErrorBoundary
- `frontend/src/pages/admin/tabs/` — UsersTab, SheltersTab, ApiKeysTab, ImportsTab, SubscriptionsTab, SurgeTab, ObservabilityTab, OAuth2ProvidersTab, HmisExportTab, AnalyticsTab

### Fixed
- **WCAG 2.4.1 skip-to-content test** — rewritten to verify existence, focus visibility, and activation instead of fragile Tab-from-body order that conflicted with route-change focus management.
- **App-version admin test** — waits for element to be attached before `scrollIntoViewIfNeeded()` (version footer renders conditionally after API call).

### Removed
- `frontend/src/pages/AdminPanel.tsx` (2,136 lines) — replaced by `admin/AdminPanel.tsx` (128 lines) + extracted modules
- `frontend/src/pages/AnalyticsTab.tsx` — moved to `admin/tabs/AnalyticsTab.tsx`

### Test Results
- Playwright: 286 passed, 2 pre-existing failures (#64), 11 skipped (through nginx, `--trace on`)
- `npm run build`: zero TS/Vite errors, 10 separate tab chunks
- axe-core: zero new violations

---

## [v0.29.5] — 2026-04-06 — Audit Fix, Clickable Reservations, Contrast Fixes

### Fixed
- **#58 ACCESS_CODE_USED audit event** — `actor_user_id` was null, causing NOT NULL constraint violation. Set to `target_user_id` (self-authentication). IP address now recorded. 4 integration tests.
- **#72 Low-contrast text on dark gradient heroes** — `color.textTertiary` on dark `linear-gradient` backgrounds across outreach search, coordinator dashboard, and admin panel subtitle text. Surge banner "Active since" timestamp had same issue. All replaced with `color.headerText` / `color.textInverse`.
- **#64 My Reservations shelter names not clickable** — shelter names in the reservations panel are now clickable buttons that open the shelter detail modal. Includes `data-testid`, `aria-label` with i18n (en/es), and `stopPropagation` to preserve countdown timer. 6 Playwright tests.
- **seed-reset.sql** — `--fresh` flag now resets all users (was commented out), preventing stale passwords and test user accumulation. Correct FK dependency order using DELETE (not TRUNCATE — safe while backend runs). `seed-data.sql` ON CONFLICT DO UPDATE restores canonical passwords on reload.

### Changed
- **Surge-active axe-core scan** — new accessibility test activates surge via API, verifies banner renders, scans for WCAG violations, deactivates in `finally` block. Closes the gap where axe-core never scanned conditional UI.
- **`logIncomplete()` on all axe-core scans** — gradient contrast items that axe marks as "incomplete" (manual review needed) are now visible in test output instead of silently ignored.

### Test Results
- Playwright: 323 passed, 6 skipped, 4 known non-regressions (through nginx, `--trace on`)
- axe-core: zero violations including surge-active scan (9/9 accessibility tests)
- Backend: compiles clean with 4 new audit integration tests

---

## [v0.29.4] — 2026-04-06 — WCAG 2.1 AA Accessibility Fixes (#52)

### Fixed
- **WCAG 1.3.5 Identify Input Purpose** — added `autocomplete` attributes to all login form inputs (`email`, `current-password`, `organization`). Change password modal and admin password reset already had them. Eliminates the only "Does Not Support" finding in the ACR.
- **WCAG 2.4.7 Focus Visible** — added global `:focus-visible` CSS rules in `global.css` using `var(--color-border-focus)` token. Inputs get `border-color` + `box-shadow` highlight; buttons/tabs get 2px solid outline. Removed `outline: 'none'` from 5 interactive input elements across `OutreachSearch.tsx`, `AdminPanel.tsx`, and `ChangePasswordModal.tsx`. Focus token switches correctly in dark mode (`#1a56db` light, `#78a9ff` dark).

### Changed
- **WCAG-ACR.md** rewritten for v0.29.4: 36 Supports, 3 Partially Supports, **0 Does Not Support**, 11 N/A. AI preparation disclosure added. Previous version (v0.12.1) was 17 versions behind.
- **18 VPAT verification tests** added (`wcag-vpat-verification.spec.ts`) — hard assertions on autocomplete attributes, focus visibility (light + dark mode), keyboard operability, skip links, reflow, text spacing, contrast, and aria-live regions.

### Test Results
- Playwright: 267 passed, 0 failed (full suite through nginx with `--trace on`)
- axe-core: zero WCAG 2.1 AA violations across all pages (light + dark mode)
- Focus visible verified on login inputs, search input, admin tabs (live spot check + automated)

---

## [v0.29.3] — 2026-04-05 — DemoGuard SSH Tunnel Bypass

### Fixed
- **SSH tunnel admin bypass now works** — administrators can open SSH tunnel to :8081, login in browser, and perform all admin operations (create users, activate surge, edit shelters). Previously returned 403 due to X-Forwarded-For header behavior in container nginx.
- **nginx `map` directive** detects traffic source based on XFF presence: tunnel (no XFF) → bypass, public (XFF from Cloudflare) → guard applies. Header set by `proxy_set_header` (unforgeable).
- **DemoGuardFilter** checks `X-FABT-Traffic-Source` header first, retains IP-chain fallback for port 8080 direct access. Diagnostic logging shows traffic source on every block/bypass decision.

### Security
- Port 8081 bound to 127.0.0.1 only (verified `ss -tlnp`)
- iptables default DROP, only 22/80/443 allowed (80/443 Cloudflare IPs only)
- `proxy_set_header` always replaces client-sent values — header forgery impossible
- Forged header test: `X-FABT-Traffic-Source: tunnel` + XFF → nginx overwrites to "public" → blocked

---

## [v0.29.2] — 2026-04-05 — SSE Emitter Lifecycle Fix

### Fixed
- **SSE cascading errors eliminated** — `sendHeartbeat()` called `completeWithError()` inside `forEach()`, triggering `onError` callback that modified the emitter map during iteration. Spring Security then re-challenged the async dispatch against an already-committed response. Root cause of persistent "Reconnecting to live updates..." banner.
- **Remove-before-completeWithError pattern** — emitter removed from registry before calling `completeWithError()` in both `sendHeartbeat()` and `sendEvent()`. Prevents `onError` callback race.
- **Idempotent callbacks** — `onError`, `onCompletion`, `onTimeout` check `containsKey` before cleanup. Prevents double-removal when heartbeat has already cleaned up.
- **DispatcherType.ASYNC permitAll** — prevents Spring Security 401 re-challenge on async dispatch (spring-security#16266).
- **Async timeout 600s** — was 30s default, dangerously close to 20s heartbeat interval. SSE connections no longer timeout prematurely.
- **WARN-level logging** on emitter failures — was DEBUG, invisible in production. Now visible in Grafana/Loki.
- **Cloudflare HTTP/3 disabled** — QUIC protocol kills long-lived SSE streams with `ERR_QUIC_PROTOCOL_ERROR`. HTTP/2 over TLS 1.3 is equally secure and improves WAF consistency.

### Test Results
- Backend: 388 tests (4 new SSE lifecycle tests), 0 failures
- SSE error recovery, broadcast isolation, 30s+ timeout survival, auth regression all verified

---

## [v0.29.1] — 2026-04-04 — Mobile Header + Dark Mode WCAG Contrast

### Added
- **Kebab overflow menu** on mobile (< 768px): notification bell and queue indicator stay visible, username/language/password/security/sign-out collapse into dropdown. (#55)
- **App title shortens to "FABT"** on mobile via i18n key `app.nameShort` (same in en/es). Full title on desktop unchanged.
- **16 Playwright tests** for mobile header: kebab interaction, Escape/Tab/click-outside, dark mode contrast, breakpoint boundaries (768/767), Galaxy S25 Ultra (480px), desktop regression.
- `data-testid` on locale selector and DV shelter indicator for deploy verification scripts.

### Fixed
- **88 dark mode WCAG contrast violations → 0** across all routes. Root cause: `color.primary` (#0f62fe, 3.56:1) used as text color where `color.primaryText` (#78a9ff, 7.58:1) should be used. Fixed in AdminPanel (active tabs, role badges, role selector, DV filters), OutreachSearch (syncing text, population badges), CoordinatorDashboard (bed hold badges).
- **`--color-text-muted`** bumped from #64748b (3.75:1) to #8494a7 (5.76:1) — comment claimed 4.5:1 but axe measured 3.75:1.
- **ConnectionStatusBanner** reconnecting banner: white on amber (2.14:1) → dark text on amber (8.31:1).
- **Dropdown clipped by `overflow-x: hidden`** — replaced with `position: relative; z-index: 100` on header. Lesson learned: always screenshot-verify UI changes, don't trust test pass alone.

### Test Results
- Backend: 384 tests, 0 failures
- Playwright: 268 tests (203 full suite pass, 0 regressions)
- Dark mode axe-core: 0 contrast violations across all 4 routes

---

## [v0.29.0] — 2026-04-04 — DV Referral Expiration UI + README Accuracy + Audience Pages

### Added
- **DV referral countdown timer** on coordinator dashboard — live decrementing display with format switch at 5 minutes (`{N}m remaining` → `{M}m {S}s remaining`). (#31)
- **Expired referral badge** — disabled Accept/Reject buttons with "Expired" badge when countdown reaches zero or SSE expiration event received. Design decision: disable, don't hide (Smashing Magazine UX research). (#31)
- **SSE `dv-referral.expired` event** — backend publishes via `UPDATE...RETURNING` (atomic, no race condition), NotificationService pushes to coordinators, useNotifications.ts hook dispatches window event. (#31)
- **Specific error handling** for expired token API responses — shows i18n expiration message instead of generic error. (#31)
- **i18n** for all expiration text: `referral.expired`, `referral.expiredError`, `referral.remainingMinutes`, `referral.remainingMinutesSeconds` in en.json and es.json. (#31)
- **3 audience HTML pages** — `demo/for-coordinators.html`, `demo/for-coc-admins.html`, `demo/for-funders.html` following `for-cities.html` template (FAQ schema, OG tags, dark mode, WCAG). (#39)
- **Audience-specific card link text** — "Quick Start Guide", "Admin Overview", "Impact Report" instead of generic "Read more". (#39)
- **9 axe-core accessibility tests** for audience pages (a11y scan, skip links, FAQ schema). (#39)
- **2 Karate API contract tests** — 409 response shape for non-pending token actions. (#31)
- **7 Playwright tests** — countdown timer, expired badge via SSE, expired accept/reject errors, format switch, Spanish locale verification. (#31)

### Fixed
- **README test counts** — Flyway 30→33, Karate 73→75, Playwright 217→241, ArchUnit 351→378, Vitest 15→20. Fixed in README.md and all 7 locations in FOR-DEVELOPERS.md. (#40)
- **ArchUnit rule count** — internal consistency fix (line 66 said 21, line 1257 said 22, both now 22). (#40)
- **README version** — was stuck at v0.27.0, now v0.29.0. (#40)
- **GitHub markdown links** — 3 "Who It's For" cards on findabed.org no longer link to github.com. (#39)

### Test Results
- Backend: 384 tests (356 @Test + 22 @ArchTest + 6 parameterized), 0 failures
- Playwright: 241 tests, 0 regressions
- Karate: 75 API scenarios

---

## [v0.28.3] — 2026-04-03 — Availability Validation + Test Cleanup

### Added
- **Population type validation** in `AvailabilityService.createSnapshot()` — rejects mismatched types with 422 and descriptive error message. NULL or empty constraints remain permissive. (#34)
- **3 new backend integration tests**: matching type accepted, mismatched type rejected, no-constraints shelter permissive.

### Fixed
- **shelter-edit.spec.ts** restores original shelter name after editing — prevents seed data corruption from accumulating "(edited)" on DV shelters. (#44)
- **Karate reservation tests** used wrong population types (VETERAN on SINGLE_ADULT shelter, SINGLE_ADULT on FAMILY shelter) — silently accepted before validation, now correctly matched.

---

## [v0.28.2] — 2026-04-03 — WCAG Contrast Fix

### Fixed
- **Offline referral button**: replaced `opacity: 0.5` with explicit disabled colors (`borderLight` bg + `textMuted` fg) for WCAG 4.5:1 contrast. Button already had `aria-disabled={!isOnline}`. (#35)
- **Header display name**: replaced `opacity: 0.9` with `color.headerText` token — guaranteed contrast on dark header.
- **Surge timestamp**: replaced `opacity: 0.85` with `color.textTertiary` token.
- **Landing page stat citations**: replaced `opacity: 0.7` with `color: var(--muted)` — contrast ratio 6.4:1 on white (was 3.59, below WCAG 4.5:1 minimum). This was introduced by the same-day stat cleanup.

### Fixed (cache + SSE)
- **sw.js served with 1-year immutable cache** — prevented browsers from detecting new service worker versions after deployments. Added explicit nginx `location = /sw.js` with `no-cache` headers. (#45)
- **Service worker intercepted SSE connections** — Workbox `NetworkFirst` route matched `/api/v1/notifications/stream`, causing the SW to get stuck holding the streaming connection open (Workbox issue #2692). Excluded SSE from SW routing. This also resolved intermittent SSE flakiness (+5 passing tests in regression suite).
- **manifest.webmanifest** — added `no-cache` header and correct `application/manifest+json` content type.

### Principle
`opacity` on text is fragile for WCAG — the computed color depends on background blending. Explicit color tokens from the design system guarantee contrast ratios in both light and dark modes.

---

## [v0.28.1] — 2026-04-03 — Shelters Tab Fix + Stat Cleanup

### Fixed
- **Admin Shelters tab "Updated" column** — was showing `shelter.updatedAt` (profile edit date, stuck at seed creation) instead of `availabilitySummary.dataAgeSeconds` (actual bed data freshness). Every shelter showed "4 days ago" even with recent availability updates. (#32)
- **Removed redundant Freshness column** — the Updated column's DataAge component already renders both the freshness badge (Fresh/Stale/Aging) and the time. The separate Freshness column displayed the same information without the time context.

### Changed
- Added `dataAgeSeconds` to `availabilitySummary` TypeScript interface
- Landing page: replaced unsourced "55-140 minutes" stat with "1 in 4 shelter beds sit empty" (LA City Controller audit, 2024)
- Landing page: added "hours to days" context (NBC San Diego, 2025) to body copy
- FOR-FUNDERS.md: updated impact section and grant language with government-audited sources
- Playwright test: `shelters-updated-column.spec.ts` verifies Updated column shows recent times

---

## [v0.28.0] — 2026-04-02 — Demo Guard + Error Handling + SSE Cloudflare Fix

### Added
- **DemoGuardFilter**: `@Profile("demo")` servlet filter blocks destructive API operations (user CRUD, shelter edit, password changes, surge, import, batch jobs, API keys, OAuth2 providers) for public traffic. Returns 403 with context-specific `demo_restricted` messages. Fail-secure allowlist — new endpoints blocked by default.
- **Admin bypass**: Requests without `X-Forwarded-For` from localhost/private IPs pass through — SSH tunnel to `:8081` gives full admin access while public visitors are guarded.
- **Safe mutation allowlist**: Login, bed search, holds, referrals, availability updates, webhooks functional in demo mode.
- **Frontend demo_restricted enhancement**: `api.ts` appends "This feature is available in a full deployment" to demo-restricted errors.
- **`--demo` flag for dev-start.sh**: `./dev-start.sh --nginx --demo` activates DemoGuardFilter locally.
- **SSE Cloudflare compatibility**: `X-Accel-Buffering: no` header on SSE endpoint (NotificationController + container nginx).
- **Playwright E2E**: `demo-guard-verify.spec.ts` — admin create user blocked, outreach search passes.
- **25 backend tests**: 20 unit (DemoGuardFilterTest) + 5 integration (DemoGuardIntegrationTest).

### Fixed
- **29 swallowed API error catch blocks** across AdminPanel.tsx (17), CoordinatorDashboard.tsx (6), ShelterEditPage.tsx (1) — now display actual API error messages with intl fallback.
- **5 intentionally silent catch blocks** documented with comments.
- **Container nginx SSE**: `add_header X-Accel-Buffering "no" always` — nginx strips `X-Accel-*` from upstream by default.

### Changed
- Backend version: 0.27.0 → 0.28.0
- Oracle update notes: nip.io URLs replaced with findabed.org
- dev-start.sh: new `--demo` flag

---

## [v0.27.0] — 2026-04-01 — Password Recovery + TOTP 2FA

### Added
- **TOTP two-factor authentication**: "Sign-in verification" via Google Authenticator / Authy. Two-phase login: password → mfaRequired → 6-digit TOTP code → JWTs. TOTP secrets AES-256-GCM encrypted at rest with key from env var (never in DB). 8 single-use backup codes (bcrypt-hashed, displayed once). mfaToken single-use (jti blocklist) with 5-attempt rate limit. Clock drift ±1 step tolerance (RFC 6238).
- **Admin-generated one-time access codes**: 15-minute expiry, single-use, bcrypt-hashed. Primary recovery path for locked-out field workers. DV safeguard: generating code for dvAccess user requires dvAccess admin.
- **PasswordChangeRequiredFilter**: After access-code login, blocks all API calls except password change with 403 `password_change_required`.
- **Auth capabilities endpoint**: `GET /api/v1/auth/capabilities` returns `{emailResetAvailable, totpAvailable, accessCodeAvailable}` — frontend adapts UI based on server config.
- **Frontend**: TotpEnrollmentPage (client-side QR via `qrcode` npm — secret never leaves browser), two-phase login screen in LoginPage, AccessCodeLoginPage, ForgotPasswordPage, admin "Access Code" button + modal, "Security" button in Layout header.
- **10 backend integration tests**: Enrollment, two-phase login, backup codes, access code, DV safeguard, mfaToken single-use, rate limiting, PasswordChangeRequiredFilter, auth capabilities.
- **6 full-flow Playwright E2E tests**: TOTP enrollment (QR → code → backup codes), two-phase login (password → TOTP → logged in), access code (admin generates → worker enters → mustChangePassword).
- **7 Playwright page-render tests**: Enrollment page, login UI, forgot password, access code form, capabilities, security button.
- **Gatling simulation**: TotpVerificationSimulation — 100 concurrent TOTP verifications.
- **Documentation**: FOR-COORDINATORS sign-in verification guide, FOR-CITIES CJIS AAL2 note, government adoption guide MFA details, Oracle update notes with one-time encryption key step.

### Changed
- Backend version: 0.26.0 → 0.27.0
- Flyway migrations: 30 → 32 (V31: TOTP columns, V32: one_time_access_code table)
- Language: "Sign-in verification" not "2FA" in all user-facing copy (D15: Simone/Devon)
- Test counts: 351 backend (+10), 217 Playwright (+10), 73 Karate, 15 Vitest, 8 Gatling (+1)
- Rate limiting: added forgot-password (3/60min) and verify-totp (20/15min) bucket4j rules
- SecurityConfig: 7 new auth endpoint matchers (TOTP enrollment/admin authenticated, admin TOTP role-gated)
- Filter chain: PasswordChangeRequiredFilter after SseTokenFilter, before ApiKeyAuthenticationFilter
- AsyncAPI: 4 new auth event channels (totp-enabled, totp-disabled, access-code-generated, access-code-used)

### Fixed
- **SpotBugs HIGH**: `HicPitExportService.generateInventoryId()` — `.getBytes()` → `.getBytes(StandardCharsets.UTF_8)` to prevent platform-dependent UUID generation.
- **SpotBugs HIGH**: `TotpEncryptionService` — `new SecureRandom()` per call → class-level field to avoid wasteful entropy gathering.
- **Dependabot HIGH + MEDIUM**: lodash 4.17.23 → 4.18.1 via npm overrides (transitive via vite-plugin-pwa → workbox-build).
- **CI Gatling job**: Added `FABT_TOTP_ENCRYPTION_KEY` to performance test backend environment.

### Security
- TOTP secrets AES-256-GCM encrypted at rest. Encryption key from `FABT_TOTP_ENCRYPTION_KEY` env var — MUST be configured before deploy.
- mfaToken is NOT an access token — JwtAuthenticationFilter skips tokens with `purpose: "mfa"` (D9)
- External QR code service removed — QR rendered client-side via `qrcode` npm, TOTP secret never leaves browser
- OWASP ZAP scan: 116 PASS, 0 FAIL, 2 WARN (unchanged from baseline)
- Designed to support NIST 800-63B AAL2 and CJIS Security Policy MFA requirements

### Issues Found During Implementation
- Jackson 3.x namespace (`tools.jackson.*` not `com.fasterxml.jackson.*`) — broke entire Spring context
- `@Transactional` + synchronous `@EventListener` — audit event marked transaction rollback-only even when caught
- JCache cache definitions required for new bucket4j rate limit rules
- TOTP encryption key gap: ALL TOTP tests silently skipped without key — found by Riley's verify, fixed with D16/D17

---

## [v0.26.0] — 2026-04-01 — Overflow Beds Management

### Fixed
- **Holds rejected at overflow-only shelters**: A shelter with 0 permanent beds available but 20 temporary cots showed "no beds available" and rejected hold requests. `ReservationService` now uses `effectiveAvailable = bedsAvailable + overflowBeds`. Three code paths updated (initial check, post-hold INV-5 verification, snapshot preservation).
- **INV-5 invariant too restrictive**: `occupied + on_hold <= total` did not account for overflow capacity. Updated to `occupied + on_hold <= total + overflow` in both `AvailabilityService` and `ReservationService`.
- **Overflow wiped on hold/confirm/cancel**: `createSnapshot` calls in `ReservationService` passed `overflowBeds=0`, erasing coordinator's reported temporary capacity. Now preserves overflow from the latest snapshot.
- **Shelter detail API missing overflow**: `ShelterDetailResponse.AvailabilityDto` and `AvailabilitySnapshot` record did not include `overflowBeds`. Coordinator dashboard couldn't pre-populate the value.

### Added
- **Coordinator overflow stepper**: "Temporary Beds" input visible only during active surge events. Uses existing `StepperButton` pattern (44px, ±). Pre-populated from latest snapshot. Hint: "Cots, mats, and emergency space during surge."
- **Combined outreach display**: During active surge, outreach workers see one number: `effectiveAvailable = bedsAvailable + overflowBeds`. Transparency note "(includes N temporary beds)" in muted text. No red "+N overflow" jargon.
- **Hold/Referral buttons use effective availability**: A shelter with 0 regular + 10 overflow shows "Hold This Bed" during surge (previously hidden).
- **Search ranking includes overflow during surge**: Shelters with temporary capacity rank higher than empty shelters.
- **9 backend integration tests**: Hold with overflow (positive/negative), search ranking, INV-5 preservation, 3 concurrency tests (last-overflow-bed race, concurrent update+hold, surge deactivation during hold).
- **7 Playwright E2E tests**: Coordinator stepper visibility/persistence, combined outreach display, Hold button on overflow-only, regression (no surge = no stepper/no temporary text).
- **FOR-COORDINATORS.md**: "White Flag Nights and Emergency Capacity" section with step-by-step instructions.

### Changed
- Backend version: 0.25.1 → 0.26.0
- Language: "Temporary Beds" replaces "Overflow Beds" in all user-facing copy (Simone/Keisha: human, not jargon)
- i18n: `surge.overflowBeds` → "Temporary Beds" / "Camas Temporales", new `search.includesTemporary` key (EN + ES)
- Test counts: 341 backend (+9), 207 Playwright (+6), 73 Karate, 15 Vitest, 7 Gatling
- Accessibility: `color.textTertiary` for transparency note (WCAG AA contrast on `successBg` in both modes), aria-labels on stepper

### Architecture
- No cross-module dependencies added. `BedAvailability.getOverflowBeds()` was already on the domain object that `ReservationService` reads via `availability.repository`. No ArchUnit violations.
- `beds_total` stays pure (permanent capacity). `overflow_beds` is additive at the consumption layer only. HIC/PIT export accuracy preserved (Dr. Kenji).
- Cache key unchanged — availability data cached per tenant, ranking computed per-request from `surgeActive` parameter.

---

## [v0.25.1] — 2026-04-01 — DV Referral Offline Guard

### Fixed
- **DV referral silent failure offline**: Request Referral button stayed enabled offline, modal opened, submit failed with error hidden behind modal. Now: button is `aria-disabled` when offline, modal blocked, inline message shows shelter phone as clickable `tel:` link.
- **Captive portal z-index bug**: When `navigator.onLine` lies (captive portals, broken WiFi), `submitReferral()` network errors now render inside the modal, not behind it.

### Added
- **Inline offline referral message**: "Referral requests need a connection. Call [phone] to request a referral by phone." — action-oriented per crisis UX research, shelter phone is a clickable `tel:` link.
- **16 Playwright E2E tests**: 8 positive (aria-disabled, inline message, tel: link, modal blocked, captive portal, banner copy, connectivity restore) + 8 regression (online flow, hold buttons unaffected, rapid toggle)
- **FOR-COORDINATORS.md**: "What works offline" green/red checklist with phone fallback for DV referrals

### Changed
- Offline banner: "DV referral requests require a connection" appended (EN + ES)
- Test counts: 201 Playwright, 73 Karate (corrected from stale counts)
- Documentation sync: DBML (overflow_beds, V25 index, header V30), AsyncAPI (4 user lifecycle channels), ERD (SVG from DBML), CONTRIBUTING (6→14 modules), DV-OPAQUE-REFERRAL + FOR-COC-ADMINS (offline rationale)

### Security
- DV referral data (callback number, household size) intentionally NOT queued in IndexedDB — zero-PII threat model requires server-only storage with 24-hour hard delete (VAWA/FVPSA spirit, NNEDV Safety Net confirms no sector precedent for offline DV referral)
- `aria-disabled="true"` instead of `disabled` attribute — preserves keyboard focus per WCAG (Adrian Roselli guidance)

---

## [v0.25.0] — 2026-04-01 — Sprint 2 Quick Wins

### Added
- **DV outreach worker seed user**: `dv-outreach@dev.fabt.org` (OUTREACH_WORKER, dvAccess=true) — the missing test persona for DV-certified field workers who need DV shelter visibility with address redaction
- **5 Playwright E2E tests for DV outreach worker**: DV shelter visibility, address redaction ("Address withheld for safety"), Request Referral button (not Hold This Bed), referral modal submit, non-DV shelters show full address and Hold This Bed
- **`dvOutreachPage` Playwright auth fixture**: reusable across future DV outreach test scenarios
- **Backend integration test**: DV outreach worker bed search API contract (3 tests)
- **App version endpoint**: `GET /api/v1/version` returns `{"version":"X.Y"}` (major.minor only, `@ConditionalOnResource` for dev-mode graceful degradation)
- **Nginx rate limiting**: `00-rate-limit.conf` defines `limit_req_zone` for public API endpoints (10 req/min/IP, burst=5, HTTP 429 on excess) — reusable zone for future public endpoints
- **Version footer on login page**: inside card with separator line, `data-testid="app-version"`
- **Version footer in Layout**: "Finding A Bed Tonight vX.Y" at bottom of authenticated pages
- **2 Playwright tests for version display**: login page + admin panel

### Fixed
- **QueueStatusIndicator React purity violation**: `Date.now()` called during render — captured in state on panel open instead
- **Layout.tsx stale eslint-disable directive**: rule no longer reports, directive was dead code

### Changed
- Backend version: 0.24.0 → 0.25.0
- Test counts: 332 backend (+4), 193 Playwright (+5 DV outreach, +2 version, -2 removed), 15 Vitest, 26 Karate, 7 Gatling
- SecurityConfig: `GET /api/v1/version` added to permitAll
- Dockerfile.frontend: copies `00-rate-limit.conf` for nginx rate limiting
- dev-start.sh: runs `spring-boot:build-info` during compile so version endpoint works in dev mode; updated seed data count to 4 users; DV outreach worker in credentials printout
- FOR-DEVELOPERS.md: DV outreach worker row in demo credentials table

### Security
- Version endpoint returns major.minor only (OWASP WSTG-INFO-02 mitigation — reduces CVE fingerprinting value)
- Nginx rate limiting on `/api/v1/version` prevents abuse of unauthenticated endpoint
- Rate limit zone (`public_api`) is reusable for future public endpoints (e.g., health, OAuth discovery)

---

## [v0.24.0] — 2026-04-01 — Offline Honesty

### Fixed
- **Offline banner lied**: "Actions will sync when connection returns" was false — only shelter creation queued offline. Bed holds and availability updates silently failed. Replaced with honest copy: "Bed holds and updates will be queued and sent when you reconnect."
- **`replayQueue()` never called**: The offline queue existed but nothing triggered replay on reconnect. Now wired to the `online` DOM event with jittered delay.
- **Misleading Playwright offline tests**: Tests used `context.setOffline()` which does NOT fire DOM events or change `navigator.onLine`. All tests now explicitly dispatch `online`/`offline` events.
- **React hook ordering crash in production build**: `fetchReservations` referenced before initialization in minified bundle. Found via Playwright trace analysis — invisible in Vite dev mode.
- **nginx missing CORP header**: Static assets lacked `Cross-Origin-Resource-Policy: same-origin`, breaking the app when `Cross-Origin-Embedder-Policy: require-corp` was set. Found via nginx E2E testing.

### Added
- **Offline queue wired to bed holds and availability updates**: Darius's test ("hold a bed, lose signal, come back online — what happened to my hold?") now passes. Actions enqueue to IndexedDB when offline or when online requests fail (try/catch fallback for `navigator.onLine` lies).
- **Single-queue architecture**: Removed Workbox BackgroundSyncPlugin. App-level IndexedDB queue is the sole retry mechanism — fully testable, works on all browsers including hospital locked-down Chrome. See design.md for rationale.
- **Idempotency key deduplication**: `X-Idempotency-Key` header on POST /reservations. Backend returns existing hold (200) instead of creating duplicate (201). Flyway V30 adds `idempotency_key` column.
- **Queue status indicator**: Badge in header shows pending queued action count. Click to see details. Disappears after replay.
- **6-state UI for queued actions**: QUEUED (amber clock), SENDING (spinner), CONFIRMED (transitions to countdown), CONFLICTED (red "bed taken"), EXPIRED (gray), FAILED (amber retry).
- **Reconnect jitter**: 0-2 second random delay before replay to prevent thundering herd when shelter WiFi reconnects.
- **Concurrent replay guard**: Module-level lock + synchronous Layout guard prevent duplicate replay from rapid `online` events.
- **Persistent storage request**: `navigator.storage.persist()` at startup protects IndexedDB from browser eviction.
- **Custom service worker**: Switched from `generateSW` to `injectManifest`. SW handles precaching and NetworkFirst GET caching only (no BackgroundSync).
- **15 Vitest unit tests** for offlineQueue.ts: expiry boundaries, conflict handling, concurrent guard, idempotency key uniqueness.
- **14 Playwright E2E tests through nginx**: offline banner, queue/replay, hold flow, expiry, conflict, double-event guard, try/catch fallback, multi-action order, FAILED state, toast notification, hospital SW-blocked.
- **5 Gatling performance scenarios**: concurrent replay (p95=260ms), idempotent dedup (p95=55ms), mixed storm (0% errors), shelter WiFi outage 10 users (p95=304ms), citywide ISP outage 20 users (p95=298ms).

### Changed
- Backend version: 0.23.0 → 0.24.0
- Flyway migrations: 29 → 30 (V30: `idempotency_key` on `reservation`)
- Test counts: 328 backend (+3 idempotency), 188 Playwright (+14 offline, -4 old), 15 Vitest unit (new), 7 Gatling simulations (+1)
- vite-plugin-pwa strategy: `generateSW` → `injectManifest`
- FOR-COORDINATORS.md: honest offline claims
- nginx.conf: added `Cross-Origin-Resource-Policy: same-origin` on static assets

---

## [v0.23.0] — 2026-03-31 — SSE Stability & Cache Fix

### Fixed
- **SSE notification stream causing page refresh**: SseEmitter 5-minute timeout caused periodic disconnects, each triggering full data refetch. Changed to -1L (no timeout) with 20-second heartbeat-based dead connection detection.
- **Stale data after shelter edit**: Workbox `StaleWhileRevalidate` served cached API responses after PUT, showing old phone numbers and settings. Changed to `NetworkFirst` with 5-second timeout — users always see fresh data after saves.
- **Misleading offline test**: Removed Playwright test that asserted "page doesn't crash" as proof of offline queue replay. Real queue tests will come in the offline-honesty change.
- **Test isolation**: Coordinator dashboard/beds/availability-math tests targeted specific shelter by name instead of fragile "first shelter" index.

### Added
- **SSE Last-Event-ID replay buffer**: Server maintains 100 most recent events (5-minute window). On reconnect, replays only missed events filtered by tenant and DV access. Sends `refresh` event if gap too large.
- **@microsoft/fetch-event-source**: Replaces native EventSource. Auth via Authorization header (eliminates query-param token leak), exponential backoff with jitter on reconnect, auto-close when tab backgrounded (Page Visibility).
- **SSE Micrometer metrics**: `sse.connections.active` gauge, `sse.reconnections.total` counter, `sse.event.delivery.duration` timer, `sse.send.failures.total` counter.
- **Grafana SSE health panels**: Reconnection Rate and Send Failures panels in FABT Operations dashboard.
- **SseStabilityTest**: 4 backend integration tests (timeout behavior, initial event format, Last-Event-ID replay, metrics registration).
- **SseTokenFilter deprecation warning**: Logs WARN when query-param token auth is used for SSE.
- **Graceful shutdown**: `@PreDestroy` closes all SSE emitters, triggering immediate client reconnection.
- `test.describe.serial` for import lifecycle tests (dependent test ordering enforced).
- Color-system tests migrated from CDN axe-core injection to `@axe-core/playwright` AxeBuilder (CSP-compatible).

### Changed
- Heartbeat: 30-second SSE comments → 20-second named events with `id:` (advances Last-Event-ID for accurate replay)
- Backend version: 0.22.0 → 0.23.0
- Test counts: 325 backend (+4), 174 Playwright (-1 misleading test removed, +2 SSE connectivity)

---

## [v0.22.2] — 2026-03-30 — Nginx Dev Parity

### Added
- `--nginx` flag on `dev-start.sh`: serves frontend through the real nginx proxy (port 8081) instead of Vite dev server, catching proxy-specific bugs before they reach production
- `--no-build` flag: restart nginx without rebuilding frontend (quick nginx.conf iteration)
- `docker-compose.dev-nginx.yml`: dev override with volume-mounted nginx container using `host-gateway` for backend connectivity
- Playwright `nginx` project: run full E2E suite through nginx (`NGINX=1 npx playwright test --project=nginx`)
- SSE connectivity test (`sse-connectivity.spec.ts`): verifies notification stream stays connected 15+ seconds without reconnecting — would have caught the v0.22.1 SSE buffering bug
- CORS auto-configuration: `--nginx` mode sets `FABT_CORS_ALLOWED_ORIGINS` to include both Vite (:5173) and nginx (:8081) origins
- FOR-DEVELOPERS.md: "Testing with nginx proxy" section with usage guidance

### Changed
- `dev-start.sh stop` now also cleans up nginx dev container
- Playwright config uses `NGINX=1` env var to opt-in to nginx project (keeps default runs on Vite only)

---

## [v0.22.1] — 2026-03-30 — SSE Proxy Fix

### Fixed
- **SSE notification stream broken through nginx proxy**: The frontend container's nginx was buffering SSE responses, causing the EventSource to disconnect and reconnect every ~5 seconds. Each reconnect triggered a full refetch of bed search results and DV referrals, creating constant page refreshing. Added dedicated SSE proxy location with `proxy_buffering off`, `proxy_cache off`, and `proxy_read_timeout 86400s`.
- **catchUp storm on SSE reconnect**: Every `onerror` on the EventSource fired `catchUp()` which dispatched refetch events with no cooldown. Added 30-second debounce to prevent API hammering during flaky connectivity.

---

## [v0.22.0] — 2026-03-30 — Import Hardening

### Fixed
- **Import navigation bug**: Admin panel import links used `<a href="/import/211">` — a full page navigation to a non-existent route. Replaced with React Router `<Link to="/coordinator/import/211">`. The 211 and HSDS import buttons were completely broken for all users.
- **CSV injection (CWE-1236)**: Imported CSV and JSON data was stored unsanitized. A malicious `=CMD('calc')` in a shelter name would execute when exported and opened in Excel. New `CsvSanitizer` strips dangerous `=`, `+`, `@` prefixes while preserving legitimate patterns (`+1-919-555-0100`, `-123 Main St`). Applied to both 211 CSV and HSDS JSON import paths.
- **Headers-only CSV**: Importing a CSV with only headers and no data rows silently proceeded to preview. Now shows "no data rows" error immediately after preview.

### Added
- MIME type validation on import endpoints (rejects non-CSV/JSON uploads)
- Field length validation on all imported fields (matches DB column sizes, row-level errors)
- 18 `CsvSanitizer` unit tests (`@ParameterizedTest`)
- 7 negative backend integration tests (empty file, headers-only, malformed CSV, injection, field length, missing column)
- 3 Playwright E2E negative tests (empty file, headers-only, injection sanitization)
- 1 Playwright admin panel click-through test (the test that would have caught the navigation bug)

---

## [v0.21.0] — 2026-03-29 — Color System, Dark Mode, HIC/PIT FY2024+, Training Materials

### Added
- **Color system**: 30 semantic color tokens as CSS custom properties in `global.css`, shared TypeScript constants in `colors.ts`. Follows Radix/Carbon split pattern: `primaryText` for links/labels, `primary` for button fills — resolves the dark mode dual-contrast problem.
- **Dark mode**: System-only `prefers-color-scheme: dark` support across all views. No manual toggle. Dark palette sourced from Carbon Design System (Blue-60/40, Green-40, Red-40, Yellow-30). `color-scheme: light dark` for native dark scrollbars and form controls.
- **HIC export rewrite**: Matches HUD Inventory.csv schema (FY2024+) — 17 columns, integer codes for all coded fields (HouseholdType 1/3/4, ProjectType 0, Availability 1, ESBedType 1). Veteran bed breakdown columns (CHVet/YouthVet/Vet/CHYouth/Youth/CH/Other). CoCCode, InventoryID, InventoryStartDate. DV aggregate row with small-cell suppression.
- **PIT export update**: Integer codes for ProjectType (0) and HouseholdType (1/3/4). Code comment documents HDX 2.0 submission note.
- **Training materials**: Coordinator quick-start card (print-ready HTML, front: 5-step flow, back: 5 troubleshooting scenarios). Admin onboarding checklist (fillable, per-shelter). Freshness badge tooltips on DataAge component (4 i18n keys EN/ES).
- **Freshness tooltips**: Hover/focus on freshness badges shows plain-language guidance ("Over 8 hours old. Call the shelter before driving there.")
- Playwright TDD test guards: axe-core contrast scan in dark mode, source-level hex grep (501→0), light mode regression guard
- Playwright E2E: click Download HIC/PIT → receive file → validate HUD schema content
- 6 new backend integration tests (HIC row-by-row validation, CSV round-trip, unknown type rejection, DV suppression boundary)

### Changed
- **18 component files migrated** from 501 hardcoded hex values to semantic `color.*` tokens (OAuth provider brand colors excluded)
- HIC CSV columns use HUD integer codes instead of strings (HouseholdType: "Families" → 3)
- `mapHouseholdTypeCode()` throws on unknown population types (was silent pass-through)
- HMIS vendor endpoints return 501 (was fake 200 success)
- WCAG-ACR updated to note dark mode coverage
- SPM mapping doc updated for HIC FY2024+ schema

### Fixed
- **Download bug**: `<a href download>` doesn't send JWT auth headers — changed to `fetch()` + blob download in AnalyticsTab. HIC/PIT downloads were broken for all users.
- 4 pre-existing typography drift fixes (NotificationBell, UserEditDrawer) — hardcoded px → tokens

---

## [v0.20.0] — 2026-03-29 — Shelter Edit & Import/Export Hardening

### Added
- **Shelter edit mode**: ShelterForm supports create and edit via `initialData` prop. PUT on save for edit, POST for create. Route: `/coordinator/shelters/:id/edit`.
- **Edit navigation**: "Edit" link on each Admin Shelters tab row, "Edit Details" button on Coordinator dashboard expanded card. `?from=` param for return navigation.
- **DV shelter safeguards**: `dvShelter` field on `UpdateShelterRequest`. COC_ADMIN+ can change DV flag (403 for COORDINATOR). Confirmation dialog on true→false with "Remove DV Protection?" warning. All DV flag and address changes audit-logged via `audit_events`.
- **Demo 211 import → edit flow**: `e2e/fixtures/nc-211-sample.csv` (3 fictitious NC shelters with iCarol-style headers). Playwright e2e tests for full lifecycle.
- **GitHub Pages**: `demo/shelter-onboarding.html` — 7-card walkthrough (import → edit → DV safeguards). "Shelter Onboarding" link in main walkthrough footer. 7 new screenshots (20-26).
- **Import preview contract fix**: `POST /api/v1/import/211/preview` now accepts file upload (was GET with headerLine). Returns `{columns: [{sourceColumn, targetField, sampleValues}], totalRows, unmapped}` matching frontend.
- **Apache Commons CSV**: Replaced hand-rolled CSV parser. Handles UTF-8 BOM, RFC 4180 escaped quotes, embedded newlines.
- **Coordinate validation**: Import sanitizes lat/lng outside valid ranges (-90..90, -180..180) to null with warning log.
- **File upload size limit**: `spring.servlet.multipart.max-file-size=10MB`. `MaxUploadSizeExceededException` → 413 response.
- **CSV injection protection**: `HicPitExportService.escCsv()` tab-prefixes cells starting with `=`, `+`, `-`, `@` per OWASP guidance.
- 8 backend integration tests (DV safeguards: coordinator phone edit, DV flag 403, COC_ADMIN DV change, unassigned shelter 403; CSV edge cases: BOM, escaped quotes, invalid coordinates; file size limit)
- 4 Playwright e2e tests (admin edit name, coordinator edit phone, DV toggle, DV confirmation dialog)
- 3 Playwright demo lifecycle tests (import → phone edit → DV flag)
- 25 i18n keys (EN/ES) for shelter edit + import pages

### Changed
- `ImportResultResponse.errors` is now `List<String>` (human-readable "Row N: field — message") instead of `int` count. Frontend contract aligned.
- `ImportLogResponse` fields renamed: `createdCount` → `created`, `updatedCount` → `updated`, `skippedCount` → `skipped`, `errorCount` → `errors`. Frontend contract aligned.
- HMIS vendor endpoints (`/api/v1/hmis/vendors`) marked `@Deprecated(forRemoval=true)`, return 501 instead of fake success.
- Card 11 caption in demo walkthrough updated to mention edit and import capability.

### Fixed
- **Import preview endpoint**: Was `GET` with `headerLine` query param but frontend sent `POST` with file upload. Now `POST` matching frontend contract.
- **Import result crash**: Frontend expected `errors: string[]` but backend returned `errors: int`. Import success page would show `[object Object]`.
- **Import history blank values**: Frontend expected `created/updated/skipped` but backend returned `createdCount/updatedCount/skippedCount`.
- Typography drift: 4 hardcoded font sizes in `NotificationBell.tsx` and `UserEditDrawer.tsx` replaced with typography tokens.

---

## [v0.19.0] — 2026-03-29 — Admin User Management

### Added
- User edit drawer: slide-out panel from admin Users table — display name, email, roles (multi-select), dvAccess toggle
- User deactivation: soft-delete with `status` field (ACTIVE/DEACTIVATED), confirmation dialog, admin can reactivate
- JWT token versioning: `ver` claim in JWT, `token_version` column on app_user. Incremented on role change, dvAccess change, deactivation, reactivation. Existing JWTs immediately invalidated.
- Audit trail: `audit_events` table (V29) with BRIN index. Records: ROLE_CHANGED, USER_DEACTIVATED, USER_REACTIVATED, DV_ACCESS_CHANGED, PASSWORD_RESET. Query endpoint: `GET /api/v1/audit-events?targetUserId={id}`
- `UserService` extracted from controller (tech debt — business logic was in controller layer)
- `NotificationService.completeEmitter(userId)` — disconnects SSE on user deactivation
- ArchUnit boundary rule for notification module (22 rules total)
- Deactivated user in seed data (former@dev.fabt.org) for screenshots
- 5 backend integration tests (role edit, deactivate, JWT rejection, audit persistence, reactivate)
- 7 Playwright e2e tests (drawer, edit, deactivate confirm, status badge, Escape, dialog role)
- 11 i18n keys (EN/ES) for user management

### Changed
- `UserResponse` includes `status` field
- `UpdateUserRequest` includes `email` field
- Login rejects deactivated users with "Account deactivated. Contact your administrator."
- `JwtAuthenticationFilter` checks user status + token version before password-change timestamp
- `shared.audit` package with proper ArchUnit boundaries (api, repository sub-packages)

### Fixed
- Playwright test pollution: user-management tests no longer modify seed user roles (edits deactivated test user instead)

---

## [v0.18.1] — 2026-03-29 — SSE Emitter Cleanup Fix

### Fixed
- SSE emitter cleanup double-decrement: Spring fires `onCompletion` after `onTimeout`/`onError`, causing the `activeConnections` gauge to go negative (-234 observed). Guard with `emitters.remove()` return value for idempotent cleanup.
- Cascading SSE send failures on `availability.updated` events eliminated — dead emitters no longer accumulate in the map.
- Gatling AvailabilityUpdate KO rate: 14.1% → 2.05% (remaining 8 are expected 409 Conflict from same-shelter concurrent writes).

---

## [v0.18.0] — 2026-03-29 — SSE Real-Time Notifications

### Added
- Server-Sent Events endpoint: `GET /api/v1/notifications/stream?token=<jwt>` with per-user SseEmitter, tenant-scoped event filtering, 30s keepalive heartbeat
- `NotificationService`: ConcurrentHashMap emitter management, `@EventListener` for DomainEvent dispatch, Spring #33421/#33340 callbacks
- `SseTokenFilter`: JWT extraction from query parameter for EventSource API limitation (standard approach per GitHub, Slack)
- `NotificationController` with `@Operation` annotation for MCP discoverability
- SecurityConfig rule for SSE endpoint, filter chain ordering (JWT → SSE → API key)
- Notification bell UI: WAI-ARIA disclosure pattern (`aria-expanded`, `aria-controls`, Escape-to-close, focus management)
- Connection status banner: Slack disconnect-only model, `role="status"` + `aria-live="polite"`, 3s reconnected toast
- `useNotifications` hook: EventSource connection, window custom events for page-level auto-refresh
- Auto-refresh: OutreachSearch refreshes referral list on `dv-referral.responded`, bed search on `availability.updated`
- Person-centered i18n: "A shelter accepted your referral" (not "Referral response received"), 15 keys EN/ES
- Micrometer metrics: `fabt.sse.connections.active` gauge, `fabt.sse.events.sent.count` counter (tagged by eventType)
- Grafana: SSE Active Connections gauge + Events Sent rate panels on operations dashboard
- Gatling `SseSearchConcurrentSimulation`: concurrent SSE + bed search load test (p99=116ms with 20 SSE connections)
- 5 backend integration tests (HttpClient.sendAsync wire-level DV safety assertion)
- 13 Playwright e2e tests (bell visibility, WCAG disclosure, Escape-to-close, connection status)
- 3 dedicated notification screenshot captures
- Runbook: SSE architecture, metrics, troubleshooting (proxy blocking, connection accumulation)
- `SchedulingConfig`: `@EnableScheduling` gated by `fabt.scheduling.enabled` property (disabled in tests)
- Testcontainers PostgreSQL `max_connections=200` for multi-context test safety

### Changed
- DV referral demo caption: "Darius's notification bell lights up instantly" (was "when Darius refreshes")
- `@EnableScheduling` moved from Application.java to dedicated SchedulingConfig (ConditionalOnProperty)
- Virtual screen reader test: added `globalThis.CSS` polyfill for @guidepup/virtual-screen-reader

### Fixed
- SSE integration tests: `HttpClient.shutdownNow()` + `awaitTermination()` + `NotificationService.completeAll()` prevent Tomcat shutdown hang and JDBC pool exhaustion
- `@Scheduled` DV canary firing on test context startup, exhausting HikariCP pool (gated by property)
- Accessibility scan: ConnectionStatusBanner green toast contrast ratio 3.76:1→5.48:1 (#047857)

---

## [v0.17.0] — 2026-03-28 — Story-Aligned Seed Data + Observability

### Added
- 13-shelter Wake County CoC network (was 10): 3 family, 3 DV, 7 general/specialized
- Day-in-the-life seed data timestamps: Crabtree Valley ~12min, Capital Blvd ~10min, New Beginnings ~7min
- 28-day demo activity with deterministic metrics: 47 zero-result searches on target Tuesday, upward utilization trend (72%→85%, avg ~78%), reservation conversion trending 65%→80%
- `infra/scripts/seed-reset.sql`: clears all seed data for fresh reload
- `dev-start.sh --fresh`: runs seed-reset.sql before loading seed data
- `e2e/playwright/helpers/test-cleanup.ts`: shared `cleanupTestData()` utility
- `afterAll` cleanup in 6 Playwright test files (admin-panel, password-change, admin-password-reset, dv-referral, outreach-search, coordinator-dashboard)
- TestResetController: now also deletes test-created users (email patterns: pwdtest-*, e2e-*, test-*)
- `publishPercentileHistogram()` on bed search, availability update, and webhook delivery timers
- Availability update timer: `fabt.availability.update.duration` with histogram buckets
- 3 new Grafana operations panels (10→13): Bed Search Latency p50/p95/p99, Availability Update Latency, HikariCP Connection Acquire Time
- `data-testid` on shelter cards in OutreachSearch for reliable Playwright locators
- "Language and Values" section in README
- Cost savings quantification in FOR-FUNDERS.md ($31,545/person, NAEH Housing First source linked)

### Changed
- Walkthrough restructured: bed search at position 1 (was login), split into 4 parts (Darius's Night / Behind the Scenes / Operations / Trust), 19→15 screenshots
- Trust/proof closing section: WCAG ACR, DV privacy, Apache 2.0, deployment tiers
- Audience-specific CTAs: For Funders, For City Officials, For Developers
- Capture scripts: select FAMILY_WITH_CHILDREN filter, click Crabtree Valley by data-testid
- Analytics captions: removed specific numbers that didn't match 30-day dashboard view
- DV referral caption: "notified instantly" corrected to "refreshes referral list" (no notification mechanism)
- Person-first language: 7 instances fixed across both repos
- Karate test shelter IDs updated for new structure (DV 009→011, availability 001→004)

### Fixed
- WCAG color-contrast: Call button #059669→#047857 (3.76:1→5.48:1)
- Spring Batch 6 sequence names in demo-activity-seed.sql (BATCH_JOB_SEQ→batch_job_instance_seq)
- Target Tuesday calculation in demo-activity-seed.sql (was producing Sunday)
- Grafana datasource UID mismatch on new panels

---

## [v0.16.0] — 2026-03-28 — Self-Service Password Management

### Added
- `PUT /api/v1/auth/password` — self-service password change (current + new, min 12 chars per NIST 800-63B)
- `POST /api/v1/users/{id}/reset-password` — admin-initiated password reset (COC_ADMIN/PLATFORM_ADMIN, same-tenant)
- JWT invalidation via `password_changed_at` timestamp — tokens issued before password change are rejected
- SSO-only users return 409 Conflict (no local password to change)
- Rate limiting: 5/15min password change, 10/15min admin reset (bucket4j + Caffeine JCache)
- Micrometer metrics: `fabt.auth.password_change.count`, `fabt.auth.password_reset.count`, `fabt.auth.token_invalidated.count`
- Change Password modal (header button, all roles), Admin Reset Password button per user row
- 24 i18n keys (EN + ES), Flyway V27, `@Operation` annotations for MCP
- 11 backend integration tests, 4 Playwright e2e tests, OWASP ZAP scan (0 new findings)
- Login form `data-testid` attributes for reliable Playwright locators

### Fixed
- JWT `iat` vs `password_changed_at` sub-second precision mismatch (truncate to seconds)
- False pilot/deployment claims removed from FOR-FUNDERS.md, sustainability-narrative.md

---

## [v0.15.3] — 2026-03-28 — README Restructure + Raw Enum Fix

README slimmed from 1,300 to 123 lines with 5 audience-specific pages. Raw API enum values removed from all user-facing display text.

### Added
- 5 audience-specific documentation pages: FOR-COORDINATORS, FOR-COC-ADMINS, FOR-CITIES, FOR-DEVELOPERS (full technical reference), FOR-FUNDERS
- `populationTypeLabels.ts` shared utility — maps API enum values to i18n display labels with multi-language support
- 2 Playwright "raw enum prevention" tests — scan visible text as admin for any raw API enum values across all views

### Fixed
- 8 locations displaying raw `DV_SURVIVOR`, `SINGLE_ADULT` etc. as visible text — now use i18n labels with full Spanish translation
- CVE-2026-34043: serialize-javascript 7.0.4 → 7.0.5 (medium severity, CPU exhaustion DoS)

### Changed
- README restructured: parking lot story first, "No more midnight phone calls," audience routing within 25 lines
- All technical content preserved in `docs/FOR-DEVELOPERS.md` (1,175 lines)

### Tests
- 124 Playwright (+2 raw enum prevention), 256 backend, 26 Karate — all green

**Diff:** [v0.15.2...v0.15.3](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.2...v0.15.3)

---

## [v0.15.2] — 2026-03-28 — Dignity-Centered Copy

User-facing labels reviewed through Keisha Thompson (AI persona, Lived Experience) and Simone Okafor (AI persona, Brand Strategist) lenses.

### Changed
- Population type filter: "DV Survivors" → "Safety Shelter" (EN) / "Refugio Seguro" (ES) — protects client dignity when outreach worker's screen is visible. Internal enum `DV_SURVIVOR` unchanged.
- `DataAge` component refactored from hardcoded strings to i18n — freshness badges now translate to Spanish
- Offline banner: added "your last search is still available" reassurance
- Error messages humanized: "Failed to load shelters" → "Couldn't reach shelter data. Check your connection and try again." (EN + ES)

### Tests
- 4 new Playwright copy-dignity tests (Safety Shelter dropdown, no-DV-terminology, freshness age, Spanish locale)
- 122 total Playwright, 256 backend, 26 Karate — all green

**Diff:** [v0.15.1...v0.15.2](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.1...v0.15.2)

---

## [v0.15.1] — 2026-03-27 — Typography System + Lint Cleanup

Centralized typography system with CSS custom properties, consistent font rendering across all platforms, and zero ESLint errors.

### Added
- `global.css`: system font stack (`system-ui`), CSS custom properties for font sizes/weights/line-heights, universal font-family inheritance, base line-height 1.5 (WCAG 1.4.12)
- `typography.ts`: shared TypeScript constants referencing CSS custom properties — single source of truth for all typography
- Monospace fallback chain: `ui-monospace, Cascadia Code, Source Code Pro, Menlo, Consolas, Courier New`
- 4 Playwright typography tests: font consistency, no-serif verification, form element inheritance, WCAG 1.4.12 text spacing override

### Changed
- All 13 component files migrated from hardcoded font values to `var()` token references
- All `fontFamily: 'monospace'` replaced with `var(--font-mono)` (proper fallback chain)
- WCAG-ACR updated: criteria 1.4.4 and 1.4.12 now reference typography system + automated Playwright verification
- Government adoption guide updated: design token system noted in WCAG posture

### Fixed
- 16 pre-existing ESLint errors resolved (AuthContext window mutation, AuthGuard export, Layout route announcement, SessionTimeoutWarning Date.now purity, AdminPanel `any` types, AnalyticsTab catch types, OutreachSearch dep array)
- 2 fragile Playwright selectors replaced with `data-testid` (admin-panel API key reveal, outreach-search freshness badge)
- `AdminPanel.tsx`: 3 `any` types replaced with proper `HmisStatus`/`HmisVendorStatus` interfaces + null safety fixes

### Tests
- 256 backend, 118 Playwright (+4 typography), 26 Karate — all green
- ESLint: 0 errors (was 16)
- TypeScript: 0 errors

**Diff:** [v0.15.0...v0.15.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.0...v0.15.1)

---

## [v0.15.0] — 2026-03-27 — Security Hardening Pre-Pilot

Security hardening based on Marcus Webb AI persona (AppSec) static review. 12 findings addressed, 20 new security tests, OWASP ZAP baseline established.

### Added
- JWT startup validation: `@PostConstruct` rejects empty, short, or default-dev secret with actionable error message
- Universal exception handler: catch-all with Micrometer counter (`fabt.error.unhandled.count`), honors `@ResponseStatus` annotations
- Access denied handler: 403 with `fabt.http.access_denied.count` metric (role + path_prefix tags)
- Not-found handler: 404 with `fabt.http.not_found.count` metric (path_prefix tag for endpoint probing detection)
- Security headers: X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy (Spring Security DSL + nginx defense-in-depth)
- Auth rate limiting: bucket4j, 10 req / 15 min per IP on login/refresh (disabled in lite profile for Karate compatibility)
- Rate limit logging: custom `RateLimitLoggingFilter` — WARN with client IP (bucket4j has no built-in logging)
- Cross-tenant isolation test: 100 concurrent virtual threads with CountDownLatch barrier, database-level SQL verification, connection pool stress test
- DV shelter concurrent isolation test: 100 simultaneous dvAccess=true/false requests, bed search endpoint coverage, unique tenant per run
- OWASP ZAP API scan baseline: 116 PASS, 0 HIGH/CRITICAL (local dev — TLS/infra scanning deferred to deployment)
- SecurityConfig `permitAll()` audit documented with justification per path
- Runbook: rate limiting profile behavior, JWKS circuit breaker graceful degradation
- Government adoption guide: SSO resilience, rate limiting, ZAP baseline (with honest scope limitations)

### Changed
- `server.error.include-stacktrace: never`, `include-message: never`, `include-binding-errors: never`

### Security
- DV shelter data: verified not leaked via bed search endpoint under concurrent dvAccess toggling (100 iterations)
- Multi-tenant: verified no cross-contamination under 100 concurrent virtual thread requests (CountDownLatch barrier)
- Connection pool: verified `applyRlsContext()` correctly resets `app.dv_access` on every connection checkout

### Tests
- 256 backend (+20), 26 Karate (+1 security-headers), 114 Playwright — all green
- CI: DV Canary, E2E (Playwright+Karate), Gatling — all passed

**Diff:** [v0.14.1...v0.15.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.14.1...v0.15.0)

---

## [v0.14.1] — 2026-03-27 — Shutdown Fixes + Release Notes

Post-migration fixes for Java 25 deployment reliability and project release infrastructure.

### Added
- `CHANGELOG.md` with full backfill (v0.1.0 through v0.14.0)
- `scripts/create-github-releases.sh` for populating GitHub Releases tab
- README: table of contents, Grafana Dashboards section (5 dashboards documented)
- README: Guides & Policy Documents moved after Business Value for visibility

### Fixed
- `Dockerfile.backend`: stale `eclipse-temurin:21-jre-alpine` updated to Java 25
- `e2e/gatling/pom.xml`: upgraded Gatling 3.11.5 → 3.14.9 + plugin 4.9.6 → 4.21.5 (ASM Java 25 support)
- Spring Boot graceful shutdown: `server.shutdown=graceful`, 30s lifecycle timeout
- `VirtualThreadConfig`: added `taskTerminationTimeout(30s)` — scheduler now interrupts tasks on shutdown
- `BoundedFanOut`: await reduced from 5 minutes to 60 seconds with `shutdownNow` escalation
- `dev-start.sh stop`: finds actual JVM PID by port (not Maven PID), platform-aware shutdown (PowerShell on Windows, POSIX kill on Linux/macOS), graceful stop in ~1s under load

### Tests
- 236 backend, 114 Playwright, 25 Karate, 1 Gatling — all green on JDK 25
- CI: all 3 E2E jobs passed (DV Canary, Playwright+Karate, Gatling)

**Diff:** [v0.14.0...v0.14.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.14.0...v0.14.1)

---

## [v0.14.0] — 2026-03-26 — Java 25 + Spring Boot 4.0

Runtime migration from Java 21 / Spring Boot 3.4 to Java 25 / Spring Boot 4.0 with virtual threads.

### Added
- Virtual thread support: `VirtualThreadConfig` with `SimpleAsyncTaskScheduler`
- `BoundedFanOut` utility: semaphore-bounded virtual thread fan-out for multi-tenant operations
- `ConnectionPoolMonitor`: HikariCP pool metrics exposed via Micrometer gauges
- Gatling virtual thread performance test plan (`fabt-virtual-thread-performance.json`)

### Changed
- Java 21 → 25 LTS (Temurin), Spring Boot 3.4.13 → 4.0.5
- `TenantContext` migrated from `ThreadLocal` to `ScopedValue` (JEP 506)
- `TenantContextCleanupFilter` removed (scoped values are garbage-collected automatically)
- `ApiKeyAuthenticationFilter` and `JwtAuthenticationFilter` rewritten for Spring Security 6.x
- `RlsDataSourceConfig` simplified — removed manual connection wrapping
- Spring Batch 5 → 6 schema migration
- Karate 1.x → 2.0, Surefire/Failsafe updated for JUnit Platform
- CI workflows updated: JDK 25 in all GitHub Actions jobs

### Database
- V26: Spring Batch 5 → 6 schema migration

### Tests
- 236 backend integration tests, 114 Playwright, 25 Karate, 1 Gatling simulation

**Diff:** [v0.13.5...v0.14.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.5...v0.14.0)

---

## [v0.13.5] — 2026-03-26 — Theory of Change Evidence Basis

### Added
- "How do you know?" evidence basis section in theory-of-change document

**Diff:** [v0.13.4...v0.13.5](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.4...v0.13.5)

---

## [v0.13.4] — 2026-03-26 — Documentation Audit

### Changed
- README: fixed test counts, added glossary terms, linked all Tier 2 documents

**Diff:** [v0.13.3...v0.13.4](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.3...v0.13.4)

---

## [v0.13.3] — 2026-03-26 — UX Polish

### Changed
- App title is now a clickable home link (reviewer feedback)

**Diff:** [v0.13.2...v0.13.3](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.2...v0.13.3)

---

## [v0.13.2] — 2026-03-26 — Frontend Null Safety

### Fixed
- 19 potential null/undefined crashes on API responses across 4 frontend pages

**Diff:** [v0.13.1...v0.13.2](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.1...v0.13.2)

---

## [v0.13.1] — 2026-03-26 — Hold Duration Admin UI

Configurable hold duration per tenant, default increased from 45 to 90 minutes.

### Added
- Admin UI hold duration config section (per-tenant, reads/writes tenant config JSONB)
- Tenant name and user name displayed in header bar
- Hospital PWA resilience test (service worker blocked scenario)
- 8 Tier 2 pre-demo policy documents (AI-generated, attributed)

### Changed
- Default hold duration: 45 → 90 minutes (ReservationService + seed data)
- JWT now includes `tenantName` claim

### Fixed
- Swagger UI 401: `web.ignoring()` for static resources
- Test stability: data-testid locators, scoped selectors, JWT expiry handling

**Diff:** [v0.13.0...v0.13.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.0...v0.13.1)

---

## [v0.13.0] — 2026-03-26 — WCAG 2.1 AA Accessibility

Self-assessed WCAG 2.1 Level AA support with automated testing enforcement and conformance documentation.

### Added
- axe-core CI gate: 8-page scan blocks builds on any WCAG violation
- Session timeout warning with ARIA live region (215-line React component)
- Virtual screen reader test suite (6 tests using `@guidepup/virtual-screen-reader`)
- Recharts accessibility: table toggle for chart data
- Accessibility Conformance Report (`docs/WCAG-ACR.md`, VPAT 2.5 WCAG edition)
- Skip-to-content link, focus management on route changes
- Touch targets: minimum 44x44px on all interactive elements

### Changed
- Color-independent status indicators (text labels alongside color, WCAG 1.4.1)
- Keyboard navigation follows W3C APG tabs pattern
- `lang` attribute switches between `en`/`es` for screen reader voice selection

### Security
- picomatch upgraded 2.3.1 → 2.3.2 and 4.0.3 → 4.0.4 (Dependabot)

### Tests
- 236 backend, 114 Playwright (+8 accessibility, +6 screen reader), 25 Karate

**Diff:** [v0.12.1...v0.13.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.1...v0.13.0)

---

## [v0.12.1] — 2026-03-25 — Search Optimization + Observability

### Added
- Exception logging added to 22 previously silent catch blocks across 12 files
- Demo activity seed: 28 days of realistic analytics data (407-line SQL)

### Changed
- Bed search optimized: V25 composite index, lateral join, connection pool sized via Little's Law

### Database
- V25: Composite index for bed search lateral join optimization

**Diff:** [v0.12.0...v0.12.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.0...v0.12.1)

---

## [v0.12.0] — 2026-03-25 — CoC Analytics

Analytics pipeline designed to support HUD reporting, with Spring Batch, aggregate dashboards, and HIC/PIT export.

### Added
- Analytics dashboard: utilization trends, demand signals, shelter performance
- Spring Batch jobs: daily aggregation, HIC export, PIT export, HMIS push scheduling
- HIC/PIT one-click CSV export designed to align with HUD format specifications
- Admin UI: batch job management (schedule, history, manual trigger, restart)
- Unmet demand tracking: bed search zero-result logging (`bed_search_log` table)
- Grafana CoC Analytics dashboard (utilization gauge, zero-result rate, batch metrics)
- Separate HikariCP pool for analytics (3 read-only connections, 30s timeout)
- Recharts lazy-loaded via `React.lazy()` (~200KB bundle, on-demand)
- Gatling mixed-load simulation: bed search p99 136ms under concurrent analytics

### Security
- DV small-cell suppression (D18): minimum 3 distinct DV shelters AND 5 beds required before aggregated data is displayed — designed to prevent individual DV shelter identification

### Database
- V23: Analytics tables (`bed_search_logs`, `daily_utilization_summaries`, + 3)
- V24: Spring Batch standard metadata schema

### Tests
- 236 backend (+13 analytics), 114 Playwright (+7 analytics), 25 Karate (+19 analytics), 2 Gatling

**Diff:** [v0.11.0...v0.12.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.11.0...v0.12.0)

---

## [v0.11.0] — 2026-03-24 — HMIS Bridge

Async push of bed inventory data to HMIS vendors with DV shelter aggregation.

### Added
- HMIS push adapter: outbox-pattern async delivery to Clarity (REST), WellSky (CSV), ClientTrack (REST)
- DV shelter aggregation: individual DV shelter occupancy never pushed — summed across all DV shelters
- Admin UI: HMIS Export tab (status, data preview, history, push controls)
- Grafana HMIS Bridge dashboard (push rate, failures, latency, queue depth)

### Database
- V22: 4 new tables (`hmis_outbox_entries`, `hmis_audit_entries`, `hmis_vendor_configs`, `hmis_inventory_records`)

### Tests
- 10 integration tests, 5 Playwright, 6 Karate scenarios

**Diff:** [v0.10.1...v0.11.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.1...v0.11.0)

---

## [v0.10.1] — 2026-03-24 — DV Address Redaction

### Added
- Configurable tenant-level DV address visibility policy: ADMIN_AND_ASSIGNED (default), ADMIN_ONLY, ALL_DV_ACCESS, NONE
- API-level address redaction on shelter detail, list, and HSDS export
- Policy change endpoint: PLATFORM_ADMIN + confirmation header (internal only)

### Tests
- 13 integration tests, 6 Karate scenarios

**Diff:** [v0.10.0...v0.10.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.0...v0.10.1)

---

## [v0.10.0] — 2026-03-23 — DV Opaque Referral

Privacy-preserving referral system for domestic violence shelters.

### Added
- Token-based opaque referral: designed to avoid client PII persistence, designed to support VAWA/FVPSA
- Warm handoff: shelter phone shared on acceptance, address shared verbally only
- Token purge: hard-delete within 24 hours, no individual audit trail
- Defense-in-depth RLS: restricted DB role + service-layer `dvAccess` check
- DV Referral Grafana dashboard (separate from operations)
- Test data reset endpoint: `DELETE /api/v1/test/reset` (dev/test profile only)
- Documentation: `docs/DV-OPAQUE-REFERRAL.md` with VAWA checklist
- Demo walkthrough: 7 DV referral screenshots

### Tests
- 12 integration tests, 7 Playwright, 6 Karate scenarios

**Diff:** [v0.9.2...v0.10.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.2...v0.10.0)

---

## [v0.9.2] — 2026-03-23 — Availability Hardening

### Added
- 9 server-side invariants validated in `AvailabilityService.createSnapshot()` (422 on violation)
- Single source of truth: eliminated `shelter_capacity` table (V20)
- Concurrent hold safety: PostgreSQL advisory locks + `clock_timestamp()`
- 27 integration tests (Groups 1-7), `AvailabilityInvariantChecker` utility

**Diff:** [v0.9.1...v0.9.2](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.1...v0.9.2)

---

## [v0.9.1] — 2026-03-22 — Security Dependency Upgrade

### Security
- Spring Boot 3.4.4 → 3.4.13, springdoc 2.8.6 → 2.8.16
- 16 CVEs resolved (CVE-2024-38819, CVE-2024-38820, CVE-2025-22228, and 13 others)

### Tests
- Full regression: 179 backend, 62 Playwright, 36 Karate, 3 Gatling

**Diff:** [v0.9.0...v0.9.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.0...v0.9.1)

---

## [v0.9.0] — 2026-03-22 — OAuth2 Single Sign-On

### Added
- OAuth2 authorization code flow with PKCE (Google, Microsoft, Keycloak)
- Dynamic client registration per tenant with Caffeine cache
- Branded SSO buttons on login page
- Admin UI OAuth2 Providers tab (CRUD, OIDC test connection)
- Keycloak dev profile (`docker-compose --oauth2`)
- JWKS circuit breaker with auto-recovery

### Tests
- 9 integration tests, 5 Playwright

**Diff:** [v0.8.0...v0.9.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.8.0...v0.9.0)

---

## [v0.8.0] — 2026-03-22 — Operational Monitoring

### Added
- 10 custom Micrometer metrics (bed search, availability, reservation, surge, webhook, DV canary)
- OpenTelemetry tracing with runtime toggle via tenant config
- 3 @Scheduled monitors: stale shelter (5min), DV canary (15min), temperature/surge gap (1hr)
- Resilience4J circuit breakers: NOAA API + webhook delivery
- Admin UI Observability tab (toggles, intervals, temperature display)
- Grafana dashboard: 10 panels
- Docker Compose observability profile (Prometheus, Grafana, Jaeger, OTel Collector)
- Operational runbook (`docs/runbook.md`)

### Tests
- 24 backend tests, 4 Playwright, 5 Karate @observability

**Diff:** [v0.7.1...v0.8.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.1...v0.8.0)

---

## [v0.7.1] — 2026-03-21 — Security Patch

### Security
- Fixed high-severity serialize-javascript RCE vulnerability (Dependabot #1)

**Diff:** [v0.7.0...v0.7.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.0...v0.7.1)

---

## [v0.7.0] — 2026-03-21 — Surge Mode

### Added
- Surge event lifecycle (ACTIVE → DEACTIVATED/EXPIRED) with auto-expiry
- Overflow capacity: coordinators report temporary beds during surges
- Bed search: surgeActive flag + overflowBeds per population type
- Frontend: surge banner, admin Surge tab, overflow field
- 4 Karate scenarios + 1 Playwright admin surge tab test

**Diff:** [v0.6.0...v0.7.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.6.0...v0.7.0)

---

## [v0.6.0] — 2026-03-21 — RLS Enforcement + E2E Hardening

### Added
- Row Level Security: JDBC connection interceptor, restricted `fabt_app` DB role
- DV canary blocking CI gate
- Concurrent hold safety tests

### Tests
- Playwright + Karate + Gatling CI pipeline

**Diff:** [v0.5.0...v0.6.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.5.0...v0.6.0)

---

## [v0.5.0] — 2026-03-21 — E2E Test Automation

### Added
- Playwright UI tests, Karate API tests, Gatling performance suite
- CI pipeline with blocking quality gates

**Diff:** [v0.4.0...v0.5.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.4.0...v0.5.0)

---

## [v0.4.0] — 2026-03-21 — Security Hardening

### Added
- OWASP dependency check gate
- AsyncAPI event contract
- Infrastructure security hardening

**Diff:** [v0.3.0...v0.4.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.3.0...v0.4.0)

---

## [v0.3.0] — 2026-03-20 — Reservation System

### Added
- Soft-hold bed reservations (HELD → CONFIRMED/CANCELLED/EXPIRED)
- Configurable hold duration, dual-tier auto-expiry
- Frontend: "Hold This Bed" buttons, countdown timer, confirm/cancel flow

**Diff:** [v0.2.0...v0.3.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.2.0...v0.3.0)

---

## [v0.2.0] — 2026-03-20 — Bed Availability

### Added
- Append-only bed availability snapshots
- Bed search endpoint with ranked results and constraint filters
- Data freshness indicators (FRESH/AGING/STALE/UNKNOWN)
- Cache-aside pattern with synchronous invalidation

**Diff:** [v0.1.0-foundation...v0.2.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.1.0-foundation...v0.2.0)

---

## [v0.1.0-foundation] — 2026-03-20 — Platform Foundation

### Added
- Modular monolith backend (Java, Spring Boot, 6 modules, ArchUnit boundaries)
- PostgreSQL 16 with Row Level Security for DV shelters
- Multi-tenant auth: JWT + API keys, 4 roles
- Shelter module: CRUD, constraints, HSDS 3.0 export
- React PWA: outreach search, coordinator dashboard, admin panel
- CI/CD (GitHub Actions), Docker, dev-start.sh

---

[Unreleased]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.3...HEAD
[v0.15.3]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.2...v0.15.3
[v0.15.2]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.1...v0.15.2
[v0.15.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.15.0...v0.15.1
[v0.15.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.14.1...v0.15.0
[v0.14.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.14.0...v0.14.1
[v0.14.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.5...v0.14.0
[v0.13.5]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.4...v0.13.5
[v0.13.4]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.3...v0.13.4
[v0.13.3]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.2...v0.13.3
[v0.13.2]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.1...v0.13.2
[v0.13.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.0...v0.13.1
[v0.13.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.1...v0.13.0
[v0.12.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.0...v0.12.1
[v0.12.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.11.0...v0.12.0
[v0.11.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.1...v0.11.0
[v0.10.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.0...v0.10.1
[v0.10.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.2...v0.10.0
[v0.9.2]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.1...v0.9.2
[v0.9.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.0...v0.9.1
[v0.9.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.8.0...v0.9.0
[v0.8.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.1...v0.8.0
[v0.7.1]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.0...v0.7.1
[v0.7.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.6.0...v0.7.0
[v0.6.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.5.0...v0.6.0
[v0.5.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.4.0...v0.5.0
[v0.4.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.3.0...v0.4.0
[v0.3.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.2.0...v0.3.0
[v0.2.0]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.1.0-foundation...v0.2.0
[v0.1.0-foundation]: https://github.com/ccradle/finding-a-bed-tonight/releases/tag/v0.1.0-foundation
