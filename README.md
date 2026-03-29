# Finding A Bed Tonight

*A family of five is sitting in a parking lot at midnight. A social worker has 30 minutes before the family stops cooperating. Right now, that social worker is making phone calls — to shelters that may be closed, full, or unable to serve that family's specific needs.*

**No more midnight phone calls.**

An open-source platform that shows real-time shelter bed availability across an entire community. Outreach workers search on their phone, hold a bed in three taps, and the shelter knows they're coming.

[![CI](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml/badge.svg)](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-green.svg)](https://spring.io/projects/spring-boot)

---

## Who It's For

| If you are a... | Start here |
|---|---|
| **Shelter coordinator or volunteer** | [For Coordinators](docs/FOR-COORDINATORS.md) — plain-English guide, 3-tap updates, offline mode |
| **CoC administrator** | [For CoC Admins](docs/FOR-COC-ADMINS.md) — HUD reporting, onboarding, DV protection, HMIS |
| **City official or IT department** | [For Cities](docs/FOR-CITIES.md) — data ownership, WCAG, security, procurement |
| **Developer or contributor** | [For Developers](docs/FOR-DEVELOPERS.md) — architecture, API, running tests, full technical reference |
| **Funder or grantor** | [For Funders](docs/FOR-FUNDERS.md) — theory of change, sustainability, what funding enables |

---

## See It in Action

- **[Platform Walkthrough](https://ccradle.github.io/findABed/demo/index.html)** — 15 annotated screenshots with story-driven narrative
- **[DV Referral Flow](https://ccradle.github.io/findABed/demo/dvindex.html)** — privacy-preserving referral for DV shelters
- **[HMIS Bridge](https://ccradle.github.io/findABed/demo/hmisindex.html)** — automated data push to HMIS vendors
- **[CoC Analytics](https://ccradle.github.io/findABed/demo/analyticsindex.html)** — utilization trends, demand signals, HIC/PIT export

---

## Quick Start (Developers)

```bash
# Prerequisites: Java 25, Maven 3.9+, Docker, Node.js 20+
git clone https://github.com/ccradle/finding-a-bed-tonight.git
cd finding-a-bed-tonight
./dev-start.sh                    # PostgreSQL + backend + frontend
./dev-start.sh --observability    # + Prometheus, Grafana, Jaeger
```

- **Frontend:** http://localhost:5173
- **Swagger:** http://localhost:8080/api/v1/docs
- **Login:** `admin@dev.fabt.org` / `admin123` (tenant: `dev-coc`)

Full setup guide: [For Developers](docs/FOR-DEVELOPERS.md#prerequisites)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.0, Spring MVC, Spring Data JDBC, Virtual Threads |
| Database | PostgreSQL 16, Flyway (29 migrations), Row Level Security (DV shelters) |
| Frontend | React 19, Vite, TypeScript, Workbox PWA, react-intl (EN/ES), CSS custom properties design tokens |
| Testing | JUnit 5, Testcontainers, ArchUnit (296 tests), Playwright (165+ UI tests), Karate (26 API scenarios), Gatling (5 simulations) |
| Infra | Docker, GitHub Actions CI/CD + E2E pipeline, Terraform (3 tiers) |

---

## What's Complete

**Current version: v0.21.0** — 33 archived OpenSpec changes, 82 specs, 13 modules.

- Real-time bed search with freshness indicators and constraint filters
- Soft-hold reservations with configurable hold duration (default 90 min)
- Self-service password change and admin-initiated password reset (NIST 800-63B, JWT invalidation)
- DV shelter protection: zero-PII opaque referral, database-level Row Level Security, address redaction
- HMIS bridge: automated push to Clarity, WellSky, ClientTrack with DV aggregation
- CoC analytics: utilization trends, demand signals, HIC/PIT export (HUD Inventory.csv FY2024+ format)
- WCAG 2.1 AA: self-assessed ACR, axe-core CI gate, session timeout warning, virtual screen reader tests
- OAuth2 SSO: Google, Microsoft, Keycloak with per-tenant provider management
- Security hardening: JWT validation, rate limiting, security headers, OWASP ZAP baseline
- Design token system: typography + color tokens as CSS custom properties, automatic dark mode (prefers-color-scheme), WCAG AA verified in both modes
- SSE real-time notifications: bell UI with WCAG disclosure pattern, connection status banner, auto-refresh on events
- Admin user management: edit drawer, deactivation/reactivation, JWT token versioning, audit trail
- Shelter edit: admin and coordinator edit paths, DV safeguards (role-gated, confirmation dialog, audit-logged), 211 CSV import with preview
- Import/export hardening: Apache Commons CSV parser, file size limits, CSV injection protection, UTF-8 BOM handling
- Dignity-centered copy: "Safety Shelter" label, i18n freshness badges, human error messages

Full feature details: [For Developers — Project Status](docs/FOR-DEVELOPERS.md#project-status)

---

## Deployment Tiers

| Tier | Infrastructure | Target | Cost |
|---|---|---|---|
| **Lite** | PostgreSQL only | Rural counties, volunteer-run CoCs | $15-30/mo |
| **Standard** | PostgreSQL + Redis | Mid-size CoCs, city IT departments | $30-75/mo |
| **Full** | PostgreSQL + Redis + Kafka | Metro areas, multi-service agencies | $100+/mo |

---

## Guides & Policy Documents

| Document | Audience |
|---|---|
| [Government Adoption Guide](docs/government-adoption-guide.md) | City/county IT — procurement, security, ADA |
| [Hospital Privacy Summary](docs/hospital-privacy-summary.md) | Hospital compliance — HIPAA applicability |
| [Partial Participation Guide](docs/partial-participation-guide.md) | Shelter operators — levels of participation |
| [What Does Free Mean](docs/what-does-free-mean.md) | Decision-makers — license, hosting, support |
| [Theory of Change](docs/theory-of-change.md) | Funders — logic model, measurable outcomes |
| [Support Model](docs/support-model.md) | Adopters — community support, SLA options |
| [WCAG ACR](docs/WCAG-ACR.md) | Accessibility reviewers — conformance report |
| [DV Opaque Referral](docs/DV-OPAQUE-REFERRAL.md) | DV advocates — legal basis, architecture |

> *Policy documents were generated with AI assistance and include attribution. They are intended as starting points and should be reviewed by subject matter experts before use in formal procurement or grant applications.*

---

## Language and Values

This project uses person-first language: "individuals experiencing homelessness," not "the homeless" or "homeless individuals." Population type labels in the application use dignity-centered terminology ("Safety Shelter" instead of "DV Survivors"). These choices reflect the project's commitment to centering the people the platform serves. See [PERSONAS.md](../PERSONAS.md) for the full values framework.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

---

*Finding A Bed Tonight — No more midnight phone calls.*
