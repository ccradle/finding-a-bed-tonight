# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Fixed
- Stale Java 21 references in `Dockerfile.backend` and `e2e/gatling/pom.xml` updated to Java 25
- Spring Boot shutdown hang: graceful shutdown config, task termination timeout, kill-by-port in dev-start.sh
- `BoundedFanOut` await reduced from 5 minutes to 60 seconds with `shutdownNow` escalation

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

Full WCAG 2.1 Level AA conformance with automated enforcement and compliance documentation.

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

HUD-aligned analytics pipeline with Spring Batch, aggregate dashboards, and HIC/PIT export.

### Added
- Analytics dashboard: utilization trends, demand signals, shelter performance
- Spring Batch jobs: daily aggregation, HIC export, PIT export, HMIS push scheduling
- HIC/PIT one-click CSV export in HUD format
- Admin UI: batch job management (schedule, history, manual trigger, restart)
- Unmet demand tracking: bed search zero-result logging (`bed_search_log` table)
- Grafana CoC Analytics dashboard (utilization gauge, zero-result rate, batch metrics)
- Separate HikariCP pool for analytics (3 read-only connections, 30s timeout)
- Recharts lazy-loaded via `React.lazy()` (~200KB bundle, on-demand)
- Gatling mixed-load simulation: bed search p99 136ms under concurrent analytics

### Security
- DV small-cell suppression (D18): minimum 3 distinct DV shelters AND 5 beds for aggregation — individual DV shelter counts never displayed

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
- Token-based opaque referral: zero client PII, designed to support VAWA/FVPSA
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

[Unreleased]: https://github.com/ccradle/finding-a-bed-tonight/compare/v0.14.0...HEAD
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
