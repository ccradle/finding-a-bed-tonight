# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

*(Nothing yet)*

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
