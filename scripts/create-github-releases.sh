#!/usr/bin/env bash
#
# create-github-releases.sh — Create GitHub Releases for all existing tags
#
# Usage:
#   ./scripts/create-github-releases.sh              Dry run (prints what would be created)
#   ./scripts/create-github-releases.sh --execute     Actually create the releases
#
# Requires: gh CLI authenticated (gh auth login)
#
set -euo pipefail

DRY_RUN=true
if [[ "${1:-}" == "--execute" ]]; then
    DRY_RUN=false
fi

REPO="ccradle/finding-a-bed-tonight"

create_release() {
    local tag="$1"
    local title="$2"
    local body="$3"

    if $DRY_RUN; then
        echo "=== DRY RUN: $tag ==="
        echo "  Title: $title"
        echo "  Body: $(echo "$body" | head -3)..."
        echo ""
    else
        echo "Creating release $tag..."
        gh release create "$tag" \
            --repo "$REPO" \
            --title "$title" \
            --notes "$body" \
            --latest=false 2>/dev/null && echo "  Created $tag" || echo "  SKIPPED $tag (already exists?)"
    fi
}

# --- v0.1.0-foundation ---
create_release "v0.1.0-foundation" "v0.1.0 — Platform Foundation" "$(cat <<'EOF'
Multi-tenant platform with role-based access, shelter management, and React PWA.

### Added
- Modular monolith backend (Java, Spring Boot, 6 modules, ArchUnit boundaries)
- PostgreSQL 16 with Row Level Security for DV shelters
- Multi-tenant auth: JWT + API keys, 4 roles
- Shelter module: CRUD, constraints, HSDS 3.0 export
- React PWA: outreach search, coordinator dashboard, admin panel
- CI/CD (GitHub Actions), Docker, dev-start.sh
EOF
)"

# --- v0.2.0 ---
create_release "v0.2.0" "v0.2.0 — Bed Availability" "$(cat <<'EOF'
Real-time bed search with freshness indicators and coordinator updates.

### Added
- Append-only bed availability snapshots
- Bed search with ranked results, population type and constraint filters
- Data freshness indicators (FRESH/AGING/STALE/UNKNOWN)
- Cache-aside pattern with synchronous invalidation

**Diff:** [v0.1.0-foundation...v0.2.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.1.0-foundation...v0.2.0)
EOF
)"

# --- v0.3.0 ---
create_release "v0.3.0" "v0.3.0 — Reservation System" "$(cat <<'EOF'
Soft-hold bed reservations with countdown timer and auto-expiry.

### Added
- Soft-hold bed reservations (HELD → CONFIRMED/CANCELLED/EXPIRED)
- Configurable hold duration, dual-tier auto-expiry
- Frontend: "Hold This Bed" buttons, countdown timer, confirm/cancel flow

**Diff:** [v0.2.0...v0.3.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.2.0...v0.3.0)
EOF
)"

# --- v0.4.0 ---
create_release "v0.4.0" "v0.4.0 — Security Hardening" "$(cat <<'EOF'
OWASP dependency check, AsyncAPI event contract, infrastructure security.

### Added
- OWASP dependency check gate
- AsyncAPI event contract
- Infrastructure security hardening

**Diff:** [v0.3.0...v0.4.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.3.0...v0.4.0)
EOF
)"

# --- v0.5.0 ---
create_release "v0.5.0" "v0.5.0 — E2E Test Automation" "$(cat <<'EOF'
Playwright UI tests, Karate API tests, Gatling performance suite, CI pipeline.

### Added
- Playwright UI tests with data-testid locators
- Karate API tests (auth, shelters, availability, search)
- Gatling performance suite (BedSearch, AvailabilityUpdate, SurgeLoad)
- CI pipeline with blocking quality gates

**Diff:** [v0.4.0...v0.5.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.4.0...v0.5.0)
EOF
)"

# --- v0.6.0 ---
create_release "v0.6.0" "v0.6.0 — RLS Enforcement + E2E Hardening" "$(cat <<'EOF'
Database-level Row Level Security enforcement and DV canary CI gate.

### Added
- RLS enforcement: JDBC connection interceptor, restricted `fabt_app` DB role
- DV canary blocking CI gate
- Concurrent hold safety tests

**Diff:** [v0.5.0...v0.6.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.5.0...v0.6.0)
EOF
)"

# --- v0.7.0 ---
create_release "v0.7.0" "v0.7.0 — Surge Mode" "$(cat <<'EOF'
White Flag surge activation with overflow capacity for extreme weather events.

### Added
- Surge event lifecycle (ACTIVE → DEACTIVATED/EXPIRED) with auto-expiry
- Overflow capacity: coordinators report temporary beds during surges
- Bed search: surgeActive flag + overflowBeds per population type
- Frontend: surge banner, admin Surge tab, overflow field

**Diff:** [v0.6.0...v0.7.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.6.0...v0.7.0)
EOF
)"

# --- v0.7.1 ---
create_release "v0.7.1" "v0.7.1 — Security Patch" "$(cat <<'EOF'
### Security
- Fixed high-severity serialize-javascript RCE vulnerability (Dependabot #1)

**Diff:** [v0.7.0...v0.7.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.0...v0.7.1)
EOF
)"

# --- v0.8.0 ---
create_release "v0.8.0" "v0.8.0 — Operational Monitoring" "$(cat <<'EOF'
Custom Micrometer metrics, OpenTelemetry tracing, Grafana dashboards, and automated monitors.

### Added
- 10 custom Micrometer metrics (bed search, availability, reservation, surge, webhook, DV canary)
- OpenTelemetry tracing with runtime toggle per tenant
- 3 @Scheduled monitors: stale shelter, DV canary, temperature/surge gap
- Resilience4J circuit breakers for NOAA API and webhooks
- Admin UI Observability tab
- Grafana Operations dashboard (10 panels)
- Docker Compose observability profile (Prometheus, Grafana, Jaeger, OTel Collector)
- Operational runbook

**Diff:** [v0.7.1...v0.8.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.7.1...v0.8.0)
EOF
)"

# --- v0.9.0 ---
create_release "v0.9.0" "v0.9.0 — OAuth2 Single Sign-On" "$(cat <<'EOF'
OAuth2 login via Google, Microsoft, and Keycloak with per-tenant provider management.

### Added
- OAuth2 authorization code flow with PKCE
- Dynamic client registration per tenant
- Branded SSO buttons on login page
- Admin UI OAuth2 Providers tab (CRUD, test connection)
- Keycloak dev profile

**Diff:** [v0.8.0...v0.9.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.8.0...v0.9.0)
EOF
)"

# --- v0.9.1 ---
create_release "v0.9.1" "v0.9.1 — Security Dependency Upgrade" "$(cat <<'EOF'
### Security
- Spring Boot 3.4.4 → 3.4.13, springdoc 2.8.6 → 2.8.16
- 16 CVEs resolved (CVE-2024-38819, CVE-2024-38820, CVE-2025-22228, and 13 others)
- Full regression: 179 backend, 62 Playwright, 36 Karate, 3 Gatling

**Diff:** [v0.9.0...v0.9.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.0...v0.9.1)
EOF
)"

# --- v0.9.2 ---
create_release "v0.9.2" "v0.9.2 — Availability Hardening" "$(cat <<'EOF'
Server-side invariant enforcement with 9 validated constraints and concurrent hold safety.

### Added
- 9 server-side invariants in AvailabilityService (422 on violation)
- Single source of truth: eliminated shelter_capacity table
- PostgreSQL advisory locks for concurrent hold safety
- 27 integration tests

**Diff:** [v0.9.1...v0.9.2](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.1...v0.9.2)
EOF
)"

# --- v0.10.0 ---
create_release "v0.10.0" "v0.10.0 — DV Opaque Referral" "$(cat <<'EOF'
Privacy-preserving referral system for domestic violence shelters, designed to support VAWA and FVPSA.

### Added
- Zero-PII token-based referral system
- Human-in-the-loop safety screening by DV shelter staff
- Warm handoff: shelter address shared verbally only, never in system
- 24-hour hard-delete purge of all referral tokens
- Defense-in-depth RLS enforcement
- Documentation: DV-OPAQUE-REFERRAL.md with VAWA checklist
- Demo walkthrough with 7 screenshots

### Tests
- 12 integration, 7 Playwright, 6 Karate

**Diff:** [v0.9.2...v0.10.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.9.2...v0.10.0)
EOF
)"

# --- v0.10.1 ---
create_release "v0.10.1" "v0.10.1 — DV Address Redaction" "$(cat <<'EOF'
Configurable tenant-level policy for DV shelter address visibility.

### Added
- 4 visibility levels: ADMIN_AND_ASSIGNED (default), ADMIN_ONLY, ALL_DV_ACCESS, NONE
- API-level redaction on shelter detail, list, and HSDS export
- Policy change requires PLATFORM_ADMIN + confirmation header

### Tests
- 13 integration, 6 Karate

**Diff:** [v0.10.0...v0.10.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.0...v0.10.1)
EOF
)"

# --- v0.11.0 ---
create_release "v0.11.0" "v0.11.0 — HMIS Bridge" "$(cat <<'EOF'
Async push of bed inventory data to HMIS vendors with DV shelter aggregation.

### Added
- Outbox-pattern async delivery to Clarity (REST), WellSky (CSV), ClientTrack (REST)
- DV shelter aggregation: individual occupancy never pushed, only aggregated totals
- Admin UI HMIS Export tab (status, preview, history, push controls)
- Grafana HMIS Bridge dashboard

### Database
- V22: 4 new tables (hmis_outbox, hmis_audit, hmis_vendor_configs, hmis_inventory)

### Tests
- 10 integration, 5 Playwright, 6 Karate

**Diff:** [v0.10.1...v0.11.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.10.1...v0.11.0)
EOF
)"

# --- v0.12.0 ---
create_release "v0.12.0" "v0.12.0 — CoC Analytics" "$(cat <<'EOF'
HUD-aligned analytics pipeline with Spring Batch, aggregate dashboards, and HIC/PIT export.

### Added
- Analytics dashboard: utilization trends, demand signals, shelter performance
- Spring Batch jobs: daily aggregation, HIC/PIT export, HMIS push scheduling
- One-click HIC/PIT CSV export in HUD format
- Admin UI batch job management
- Unmet demand tracking via zero-result search logging
- Grafana CoC Analytics dashboard
- DV small-cell suppression: minimum 3 shelters AND 5 beds for aggregation

### Database
- V23: Analytics tables
- V24: Spring Batch metadata schema

### Tests
- 236 backend, 114 Playwright, 25 Karate, 2 Gatling

**Diff:** [v0.11.0...v0.12.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.11.0...v0.12.0)
EOF
)"

# --- v0.12.1 ---
create_release "v0.12.1" "v0.12.1 — Search Optimization" "$(cat <<'EOF'
Bed search query optimization and observability improvements.

### Added
- Exception logging to 22 previously silent catch blocks
- 28-day demo activity seed data

### Changed
- Bed search optimized: composite index, lateral join, pool sizing via Little's Law

### Database
- V25: Composite index for bed search optimization

**Diff:** [v0.12.0...v0.12.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.0...v0.12.1)
EOF
)"

# --- v0.13.0 ---
create_release "v0.13.0" "v0.13.0 — WCAG 2.1 AA Accessibility" "$(cat <<'EOF'
Full WCAG 2.1 Level AA conformance with automated enforcement and compliance documentation.

### Added
- axe-core CI gate: blocks builds on any WCAG violation
- Session timeout warning with ARIA live region
- Virtual screen reader test suite
- Recharts table toggle for non-visual access
- Accessibility Conformance Report (VPAT 2.5 WCAG edition)
- Skip-to-content link, focus management, 44x44px touch targets

### Security
- picomatch 2.3.1→2.3.2, 4.0.3→4.0.4

**Diff:** [v0.12.1...v0.13.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.12.1...v0.13.0)
EOF
)"

# --- v0.13.1 ---
create_release "v0.13.1" "v0.13.1 — Hold Duration Admin UI" "$(cat <<'EOF'
Configurable hold duration per tenant (default 90 min) with Admin UI controls.

### Added
- Admin UI hold duration config section
- Tenant/user name in header
- 8 Tier 2 pre-demo policy documents
- Hospital PWA resilience test

### Changed
- Default hold duration: 45 → 90 minutes
- JWT includes tenantName claim

### Fixed
- Swagger UI 401 for static resources
- Test stability improvements

**Diff:** [v0.13.0...v0.13.1](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.0...v0.13.1)
EOF
)"

# --- v0.13.2 through v0.13.5 (minor fixes) ---
create_release "v0.13.2" "v0.13.2 — Frontend Null Safety" "$(cat <<'EOF'
### Fixed
- 19 potential null/undefined crashes on API responses across 4 frontend pages

**Diff:** [v0.13.1...v0.13.2](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.1...v0.13.2)
EOF
)"

create_release "v0.13.3" "v0.13.3 — UX Polish" "$(cat <<'EOF'
### Changed
- App title is now a clickable home link

**Diff:** [v0.13.2...v0.13.3](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.2...v0.13.3)
EOF
)"

create_release "v0.13.4" "v0.13.4 — Documentation Audit" "$(cat <<'EOF'
### Changed
- README: fixed test counts, added glossary terms, linked all Tier 2 documents

**Diff:** [v0.13.3...v0.13.4](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.3...v0.13.4)
EOF
)"

create_release "v0.13.5" "v0.13.5 — Theory of Change" "$(cat <<'EOF'
### Added
- Evidence basis section ("How do you know?") in theory-of-change document

**Diff:** [v0.13.4...v0.13.5](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.4...v0.13.5)
EOF
)"

# --- v0.14.0 ---
create_release "v0.14.0" "v0.14.0 — Java 25 + Spring Boot 4.0" "$(cat <<'EOF'
Runtime migration to Java 25 LTS and Spring Boot 4.0 with virtual threads.

### Added
- Virtual thread support for scheduling and task execution
- BoundedFanOut utility for semaphore-bounded multi-tenant operations
- ConnectionPoolMonitor with Micrometer gauges
- Gatling virtual thread performance test plan

### Changed
- Java 21 → 25 LTS, Spring Boot 3.4.13 → 4.0.5
- TenantContext: ThreadLocal → ScopedValue (JEP 506)
- Security filters rewritten for Spring Security 6.x
- Spring Batch 5 → 6
- Karate 1.x → 2.0

### Database
- V26: Spring Batch 5→6 schema migration

### Tests
- 236 backend, 114 Playwright, 25 Karate, 1 Gatling — all green on JDK 25

**Diff:** [v0.13.5...v0.14.0](https://github.com/ccradle/finding-a-bed-tonight/compare/v0.13.5...v0.14.0)
EOF
)"

echo ""
if $DRY_RUN; then
    echo "DRY RUN complete. Run with --execute to create releases."
else
    echo "All releases created. Visit https://github.com/$REPO/releases"
fi
