# Finding A Bed Tonight

[![CI](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml/badge.svg)](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-green.svg)](https://spring.io/projects/spring-boot)

Open-source emergency shelter bed availability platform. Matches homeless individuals and families to available shelter beds in real time.

---

## Problem Statement & Business Value

### The Problem

A family of five is sitting in a parking lot at midnight. A social worker has 30 minutes before the family stops cooperating. Right now, that social worker is making phone calls — to shelters that may be closed, full, or unable to serve that family's specific needs. There is no shared system for real-time shelter bed availability in most US communities.

Commercial software does not serve this space because there is no profit motive. Homeless services operate on tight grants with no margin for per-seat licensing. The result: social workers keep personal spreadsheets, shelter coordinators answer midnight phone calls, and families wait in parking lots while the system fails them.

### The Goal

An open-source platform that matches homeless individuals and families to available shelter beds in real time. Reduce the time from crisis call to bed placement from 2 hours to 20 minutes. Three deployment tiers (Lite, Standard, Full) ensure any community — from a rural volunteer-run CoC to a metro area with 50 shelters — can adopt the platform at a cost they can sustain.

### Business Value

| Stakeholder | Value |
|---|---|
| **Families/individuals in crisis** | Faster placement, fewer nights unsheltered |
| **Social workers/outreach teams** | Reduced cognitive load, real-time availability instead of phone calls |
| **Shelter coordinators** | 3-tap bed count updates, automated reporting |
| **City/county governments** | Data-driven resource allocation, HUD reporting |
| **Foundations/funders** | Measurable impact metrics, cost-effective open-source model |

### How It Fits Together

```
┌──────────────────────────────────────────────────────────────────┐
│                     PWA (React + Vite)                            │
│  Coordinator · Outreach Worker · CoC Admin                       │
└─────────────────────────┬────────────────────────────────────────┘
                          │ REST API (/api/v1)
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│              Spring Boot 3.4 (Modular Monolith)                  │
│                                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐       │
│  │  tenant   │ │   auth   │ │ shelter  │ │  dataimport  │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ availability  │ │ reservation  │ │ subscription │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│  ┌──────────────┐                                                │
│  │ observability │                                               │
│  └──────────────┘                                                │
│  ┌─────────────────── shared kernel ───────────────────────┐     │
│  │ config · cache · event · security · web                 │     │
│  └─────────────────────────────────────────────────────────┘     │
└──────┬──────────────┬───────────────────┬────────────────────────┘
       │              │                   │
  ┌────▼────┐   ┌─────▼─────┐      ┌─────▼─────┐
  │PostgreSQL│   │   Redis   │      │   Kafka   │
  │  16 +RLS │   │ (Std/Full)│      │  (Full)   │
  └─────────┘   └───────────┘      └───────────┘
```

---

## Architecture

The backend is a Spring Boot 3.4 modular monolith. Each bounded context lives in its own top-level package under `org.fabt.*` with enforced boundaries (15 ArchUnit rules). A shared kernel provides cross-cutting infrastructure (security filters, caching, event bus, JDBC configuration).

Three deployment tiers allow the same codebase to serve communities of vastly different size and budget:

---

## Deployment Tiers

| Tier | Infrastructure | Target | Cost |
|---|---|---|---|
| **Lite** | PostgreSQL only | Rural counties, volunteer-run CoCs | $15-30/mo |
| **Standard** | PostgreSQL + Redis | Mid-size CoCs, city IT departments | $30-75/mo |
| **Full** | PostgreSQL + Redis + Kafka | Metro areas, multi-service agencies | $100+/mo |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Spring MVC, Spring Data JDBC |
| Database | PostgreSQL 16, Flyway (15 migrations), Row Level Security (DV shelters) |
| Cache | Caffeine L1 / + Redis L2 (Standard/Full) |
| Events | Spring Events (Lite) / Kafka (Full) |
| Auth | JWT + OAuth2/OIDC + API Keys (hybrid) |
| Frontend | React 19, Vite, TypeScript, Workbox PWA, react-intl (EN/ES) |
| Testing | JUnit 5, Testcontainers, ArchUnit (109 tests, 15 architecture rules) |
| Infra | Docker, GitHub Actions CI/CD, Terraform (3 tiers) |

---

## Module Boundaries

The backend is a **modular monolith** — not a flat package-by-layer structure. Each module owns its own `api/`, `domain/`, `repository/`, and `service/` packages. Cross-module access is prohibited and enforced at build time by ArchUnit tests.

**Modules:**

| Module | Package | Responsibility |
|---|---|---|
| `tenant` | `org.fabt.tenant` | CoC tenant CRUD, configuration, multi-tenancy |
| `auth` | `org.fabt.auth` | JWT login/refresh, user CRUD, API key management, OAuth2 linking |
| `shelter` | `org.fabt.shelter` | Shelter profiles, constraints, capacities, HSDS export, coordinator assignments |
| `availability` | `org.fabt.availability` | Real-time bed availability snapshots, bed search queries, data freshness |
| `reservation` | `org.fabt.reservation` | Soft-hold bed reservations: create, confirm, cancel, auto-expire |
| `dataimport` | `org.fabt.dataimport` | HSDS JSON import, 211 CSV import (fuzzy matching), import audit log |
| `observability` | `org.fabt.observability` | Structured JSON logging, Micrometer metrics, health probes, data freshness, i18n |
| `subscription` | `org.fabt.subscription` | Webhook subscriptions, HMAC-SHA256 event delivery, MCP-ready |

**Shared kernel:** `org.fabt.shared` — config, cache (`CacheService`, `CacheNames`), event (`EventBus`, `DomainEvent`), security (`JwtAuthenticationFilter`, `ApiKeyAuthenticationFilter`, `SecurityConfig`), web (`TenantContext`, `GlobalExceptionHandler`).

**ArchUnit enforcement:** 15 architecture tests verify that modules do not access each other's `domain`, `repository`, or `service` packages. Only `api` and `shared` packages are accessible across module boundaries.

---

## MCP-Ready API Design

The REST API is designed for future AI agent consumption via the Model Context Protocol (MCP). Six design requirements (REQ-MCP-1 through REQ-MCP-6) are satisfied in Phase 1:

1. **Atomic, single-purpose endpoints** — each endpoint does exactly one thing; maps 1:1 to a future MCP tool
2. **Machine-readable error responses** — structured error bodies with context for agent reasoning
3. **Semantic OpenAPI descriptions** — endpoint descriptions written for AI model consumption
4. **Stable UUID identifiers** — all primary keys are UUIDs, forming predictable resource URIs
5. **Structured domain events** — self-describing events on Kafka topics (Full tier)
6. **Stateless query path** — no session state, cookies, or server-side context; every query is self-contained

Phase 2 will add an MCP server as a thin wrapper around the REST API, enabling natural language bed search, proactive availability alerting, and conversational CoC reporting. See [MCP-BRIEFING.md](https://github.com/ccradle/findABed/blob/main/MCP-BRIEFING.md) in the docs repo for the full decision record.

---

## Database Schema

15 Flyway migrations (V1–V15 + V8.1):

| Migration | Description |
|---|---|
| V1 | `tenant` — CoC tenant registration |
| V2 | `app_user` — users with roles and DV access flag |
| V3 | `api_key` — shelter-scoped and org-level API keys |
| V4 | `shelter` — shelter profiles |
| V5 | `shelter_constraints` — accessibility, pets, sobriety requirements |
| V6 | `shelter_capacity` — bed counts by population type |
| V7 | `coordinator_assignment` — shelter-to-coordinator mapping |
| V8 | Row Level Security policies — DV shelter protection |
| V8.1 | RLS fix for empty string tenant context |
| V9 | `import_log` — HSDS data import audit trail |
| V10 | `tenant_oauth2_provider`, `user_oauth2_link` — OAuth2 account linking |
| V11 | `subscription` — webhook subscriptions for event-driven notifications |
| V12 | `bed_availability` — append-only bed availability snapshots |
| V13 | RLS for `bed_availability` — inherits DV-shelter access control |
| V14 | `reservation` — soft-hold bed reservations with status lifecycle |
| V15 | RLS for `reservation` — inherits DV-shelter access control |

### Entity Relationship Diagram

![ERD](docs/erd.png)

### Documentation

| Document | Description |
|---|---|
| [docs/schema.dbml](docs/schema.dbml) | DBML source — paste into [dbdiagram.io](https://dbdiagram.io) to edit |
| [docs/erd.png](docs/erd.png) | ERD image (above) exported from dbdiagram.io |
| [docs/asyncapi.yaml](docs/asyncapi.yaml) | AsyncAPI 3.0 spec — EventBus contract for all 3 deployment tiers |
| [docs/architecture.drawio](docs/architecture.drawio) | Architecture diagram — open in [draw.io](https://app.diagrams.net) |

---

## OpenSpec Workflow

Specifications and planning artifacts live in the companion [docs repo (ccradle/findABed)](https://github.com/ccradle/findABed). All features are spec-driven: proposal, design, specs, tasks, implementation, verification.

New to OpenSpec? See [https://openspec.dev](https://openspec.dev) and [https://github.com/Fission-AI/OpenSpec](https://github.com/Fission-AI/OpenSpec).

---

## Prerequisites

- **Java:** 21+ (OpenJDK or Eclipse Temurin)
- **Maven:** 3.9+
- **Docker:** Latest version (required for PostgreSQL and Testcontainers — engine 29.x+ on Windows requires `api.version=1.44` config)
- **Node.js:** 20+ (for frontend)

---

## Starting the Stack

### Quick start (recommended)

```bash
git clone https://github.com/ccradle/finding-a-bed-tonight.git
cd finding-a-bed-tonight

# Start everything: PostgreSQL, backend, seed data, frontend
./dev-start.sh

# Stop everything
./dev-start.sh stop

# Backend only (no frontend)
./dev-start.sh backend
```

The script starts PostgreSQL via Docker Compose, builds and launches the backend (with Flyway migrations), loads seed data (10 shelters, 3 users, 1 tenant), and starts the frontend dev server.

### Manual start

```bash
# 1. Start PostgreSQL
docker compose up -d postgres

# 2. Load seed data
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-data.sql

# 3. Start backend
cd backend && mvn spring-boot:run

# 4. Start frontend (separate terminal)
cd frontend && npm install && npm run dev
```

### Verify the stack

```bash
# Health check
curl http://localhost:8080/actuator/health/liveness

# Swagger UI
open http://localhost:8080/api/v1/docs

# Frontend
open http://localhost:5173
```

---

## UI Sanity Check

After starting the stack, open **http://localhost:5173** in your browser.

### Login

Use one of the seed data accounts:

| Role | Email | Password | What you'll see |
|------|-------|----------|----------------|
| **Platform Admin** | `admin@dev.fabt.org` | `admin123` | Admin panel (tenant/user management) |
| **CoC Admin** | `cocadmin@dev.fabt.org` | `admin123` | Coordinator dashboard (5 assigned shelters) |
| **Outreach Worker** | `outreach@dev.fabt.org` | `admin123` | Bed search with live availability |

**Tenant slug:** `dev-coc`

### What to verify

1. **Login page** loads with email/password form and tenant slug field
2. **Outreach search** — shelters show beds available (green) and full (red), freshness badges (FRESH/AGING/STALE), "Hold This Bed" buttons
3. **Hold a bed** — click "Hold This Bed" on an available bed, see the reservations panel with countdown timer, confirm or cancel the hold
4. **Coordinator dashboard** — expand a shelter, update occupied/on-hold counts, see availability refresh
5. **Active holds indicator** — coordinator sees which beds are held by outreach workers
6. **Language selector** — switch to Español → UI text changes to Spanish
7. **Offline banner** — toggle airplane mode → yellow "You are offline" banner appears

### API verification (via curl)

```bash
# Login as outreach worker
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug": "dev-coc", "email": "outreach@dev.fabt.org", "password": "admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# List shelters with availability summary (each result wraps shelter + availabilitySummary)
curl -s http://localhost:8080/api/v1/shelters \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# List shelters with pagination (page 0, 5 per page)
curl -s "http://localhost:8080/api/v1/shelters?page=0&size=5" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Search for beds (ranked results with availability)
curl -s -X POST http://localhost:8080/api/v1/queries/beds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"populationType": "SINGLE_ADULT", "limit": 5}' | python3 -m json.tool

# Hold a bed
curl -s -X POST http://localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"shelterId": "d0000000-0000-0000-0000-000000000001", "populationType": "SINGLE_ADULT"}' \
  | python3 -m json.tool

# List my active holds
curl -s http://localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Shelter detail with availability
curl -s http://localhost:8080/api/v1/shelters/d0000000-0000-0000-0000-000000000001 \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Health check (no auth needed)
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

---

## Running Tests

```bash
cd backend

# Run all 109 tests
mvn test

# Run a specific test class
mvn test -Dtest=ReservationIntegrationTest

# Run a specific test method
mvn test -Dtest="AvailabilityIntegrationTest#test_createSnapshot_appendOnly_preservesPreviousSnapshot"
```

**Docker is required.** Tests use Testcontainers to start a PostgreSQL 16 container automatically.

### Test Breakdown

| Test Class | Tests | What It Covers |
|---|---|---|
| `ApplicationTest` | 1 | Spring context loads successfully |
| `ArchitectureTest` | 15 | ArchUnit module boundary enforcement (8 modules) |
| `TenantIntegrationTest` | 8 | Tenant CRUD, config defaults, config update |
| `AuthIntegrationTest` | 7 | JWT login, refresh, wrong password/email/tenant |
| `ApiKeyAuthTest` | 6 | API key auth, rotation, deactivation, role resolution |
| `DvAccessRlsTest` | 3 | PostgreSQL RLS for DV shelter data protection |
| `RoleBasedAccessTest` | 10 | 4-role access control (PLATFORM_ADMIN, COC_ADMIN, COORDINATOR, OUTREACH_WORKER) |
| `OAuth2ProviderTest` | 6 | OAuth2 provider CRUD, public endpoint, tenant leakage prevention |
| `OAuth2AccountLinkTest` | 4 | Account linking, rejection of unknown emails, JWT identity |
| `ShelterIntegrationTest` | 11 | Shelter CRUD, constraints, HSDS export, coordinator assignment, pagination |
| `ImportIntegrationTest` | 7 | HSDS import, 211 CSV import, fuzzy matching, duplicate detection |
| `ObservabilityIntegrationTest` | 6 | Health endpoints, i18n error responses, error structure |
| `SubscriptionIntegrationTest` | 5 | Webhook subscription CRUD, error validation |
| `AvailabilityIntegrationTest` | 10 | Availability snapshots, bed search, ranking, data freshness, events |
| `ReservationIntegrationTest` | 10 | Reservation lifecycle, concurrency, expiry, creator-only access, events |
| **Total** | **109** | |

---

## REST API Reference

All endpoints are under `/api/v1`. Authentication is via JWT Bearer token (from `/auth/login`) or API key (via `X-API-Key` header) unless noted otherwise.

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | None | Authenticate with email/password/tenantSlug, returns JWT |
| `POST` | `/api/v1/auth/refresh` | None | Refresh an access token using a refresh token |

### Tenants

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/tenants` | PLATFORM_ADMIN | Create a new tenant (CoC) |
| `GET` | `/api/v1/tenants` | PLATFORM_ADMIN | List all tenants |
| `GET` | `/api/v1/tenants/{id}` | PLATFORM_ADMIN | Get tenant by ID |
| `PUT` | `/api/v1/tenants/{id}` | PLATFORM_ADMIN | Update tenant name |
| `GET` | `/api/v1/tenants/{id}/config` | COC_ADMIN+ | Get tenant configuration |
| `PUT` | `/api/v1/tenants/{id}/config` | COC_ADMIN+ | Update tenant configuration (incl. hold_duration_minutes) |

### OAuth2 Providers

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/tenants/{id}/oauth2-providers` | COC_ADMIN+ | Add OAuth2 provider (Google, Microsoft, etc.) |
| `GET` | `/api/v1/tenants/{id}/oauth2-providers` | COC_ADMIN+ | List all providers for tenant |
| `PUT` | `/api/v1/tenants/{id}/oauth2-providers/{pid}` | COC_ADMIN+ | Update provider config |
| `DELETE` | `/api/v1/tenants/{id}/oauth2-providers/{pid}` | COC_ADMIN+ | Remove provider |
| `GET` | `/api/v1/tenants/{slug}/oauth2-providers/public` | None | List enabled providers (login page) |

### Users

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/users` | COC_ADMIN+ | Create user (dvAccess defaults false) |
| `GET` | `/api/v1/users` | COC_ADMIN+ | List users in tenant |
| `GET` | `/api/v1/users/{id}` | COC_ADMIN+ | Get user by ID |
| `PUT` | `/api/v1/users/{id}` | COC_ADMIN+ | Update user (roles, dvAccess) |

### API Keys

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/api-keys` | COC_ADMIN+ | Create API key (plaintext returned once) |
| `GET` | `/api/v1/api-keys` | COC_ADMIN+ | List keys (suffix only, no secrets) |
| `DELETE` | `/api/v1/api-keys/{id}` | COC_ADMIN+ | Deactivate key |
| `POST` | `/api/v1/api-keys/{id}/rotate` | COC_ADMIN+ | Rotate key |

### Shelters

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/shelters` | COC_ADMIN+ | Create shelter with constraints + capacities |
| `GET` | `/api/v1/shelters` | Any authenticated | List shelters with availability summary (optional pagination: `?page=0&size=20`) |
| `GET` | `/api/v1/shelters/{id}` | Any authenticated | Shelter detail with constraints, capacities, and live availability |
| `GET` | `/api/v1/shelters/{id}?format=hsds` | Any authenticated | HSDS 3.0 export with fabt: extensions |
| `PUT` | `/api/v1/shelters/{id}` | COORDINATOR+ | Update shelter (coordinators must be assigned) |
| `PATCH` | `/api/v1/shelters/{id}/availability` | COORDINATOR+ | Submit availability snapshot (append-only, cache invalidation, event publish) |
| `POST` | `/api/v1/shelters/{id}/coordinators` | COC_ADMIN+ | Assign coordinator to shelter |
| `DELETE` | `/api/v1/shelters/{id}/coordinators/{uid}` | COC_ADMIN+ | Unassign coordinator |

### Bed Search

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/queries/beds` | Any authenticated | Search beds with filters (populationType, constraints, location, limit). Ranked results with availability, data freshness, and held bed counts |

### Reservations

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/reservations` | OUTREACH_WORKER+ | Create soft-hold reservation (configurable hold duration, default 45 min) |
| `GET` | `/api/v1/reservations` | OUTREACH_WORKER+ | List active (HELD) reservations for current user |
| `PATCH` | `/api/v1/reservations/{id}/confirm` | OUTREACH_WORKER+ | Confirm arrival — converts hold to occupancy |
| `PATCH` | `/api/v1/reservations/{id}/cancel` | OUTREACH_WORKER+ | Cancel hold — releases bed |

### Data Import

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/import/hsds` | COC_ADMIN+ | Import HSDS 3.0 JSON (multipart file) |
| `POST` | `/api/v1/import/211` | COC_ADMIN+ | Import 211 CSV with fuzzy column matching |
| `GET` | `/api/v1/import/211/preview` | COC_ADMIN+ | Preview column mapping for 211 CSV |
| `GET` | `/api/v1/import/history` | COC_ADMIN+ | Import audit log |

### Webhook Subscriptions

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/subscriptions` | Any authenticated | Subscribe to events (HMAC webhook delivery) |
| `GET` | `/api/v1/subscriptions` | Any authenticated | List subscriptions for tenant |
| `DELETE` | `/api/v1/subscriptions/{id}` | Any authenticated | Cancel subscription |

---

## Domain Glossary

**CoC (Continuum of Care)** — HUD-defined regional body coordinating homeless services. Each CoC has a unique ID (e.g., NC-507 for Wake County). Maps to a tenant in the platform.

**Tenant** — A CoC or administrative boundary served by a single platform deployment. Multi-tenant design allows one deployment to serve multiple CoCs.

**DV Shelter (Domestic Violence)** — Shelter serving DV survivors. Location and existence protected by PostgreSQL Row Level Security. Never exposed through public queries.

**HSDS (Human Services Data Specification)** — Open Referral standard (v3.0) for describing social services. FABT extends HSDS with bed availability objects.

**Surge Event / White Flag** — Emergency activation when weather or crisis requires expanded shelter capacity. CoC-admin triggered, broadcast to all outreach workers.

**PIT Count (Point-in-Time)** — Annual HUD-mandated count of sheltered and unsheltered homeless individuals.

**Bed Availability** — Real-time count of open beds by population type at a shelter. Append-only snapshots, never updated in place. `beds_available` is derived: `beds_total - beds_occupied - beds_on_hold`.

**Reservation (Soft-Hold)** — Temporary claim on a bed during transport. Lifecycle: HELD → CONFIRMED (client arrived) | CANCELLED (released) | EXPIRED (timed out). Default hold duration: 45 minutes, configurable per tenant.

**Population Type** — Category of individuals a shelter serves: `SINGLE_ADULT`, `FAMILY_WITH_CHILDREN`, `WOMEN_ONLY`, `VETERAN`, `YOUTH_18_24`, `YOUTH_UNDER_18`, `DV_SURVIVOR`.

**Outreach Worker** — Frontline staff who connects homeless individuals to services. Primary user of the bed search and reservation interfaces.

**Coordinator** — Shelter staff responsible for updating bed counts and managing shelter profile.

**Opaque Referral** — Privacy-preserving DV shelter referral that does not reveal the shelter's location or existence to unauthorized users.

**MCP (Model Context Protocol)** — Open standard by Anthropic for AI agent integration. Platform is MCP-ready for Phase 2 natural language interface.

---

## Project Structure

```
finding-a-bed-tonight/
├── README.md                                          # This file
├── CONTRIBUTING.md                                    # Contributor guide
├── LICENSE                                            # Apache 2.0
├── dev-start.sh                                       # One-command dev stack launcher
├── docker-compose.yml                                 # Local dev: PostgreSQL, Redis, Kafka
│
├── backend/                                           # Spring Boot modular monolith
│   ├── pom.xml                                        # Maven build (Spring Boot 3.4.4)
│   └── src/
│       ├── main/java/org/fabt/
│       │   ├── Application.java                       # Entry point (@EnableScheduling)
│       │   ├── tenant/                                # Tenant module (CRUD, config)
│       │   ├── auth/                                  # Auth module (JWT, API keys, OAuth2, users)
│       │   ├── shelter/                               # Shelter module (CRUD, constraints, HSDS)
│       │   ├── availability/                          # Availability module (snapshots, bed search)
│       │   ├── reservation/                           # Reservation module (soft-hold lifecycle)
│       │   ├── dataimport/                            # Import module (HSDS, 211 CSV)
│       │   ├── subscription/                          # Webhook subscription module
│       │   ├── observability/                         # Logging, metrics, health, i18n
│       │   └── shared/                                # Shared kernel (cache, event, security, web)
│       ├── main/resources/
│       │   ├── application.yml                        # Base config
│       │   ├── application-{lite,standard,full}.yml   # Deployment tier profiles
│       │   ├── db/migration/                          # 15 Flyway migrations (V1–V15 + V8.1)
│       │   ├── logback-spring.xml                     # Structured JSON logging
│       │   └── messages/                              # i18n (EN, ES)
│       └── test/java/org/fabt/                        # 109 integration tests
│
├── frontend/                                          # React + Vite + TypeScript PWA
│   ├── src/
│   │   ├── auth/                                      # AuthContext, AuthGuard
│   │   ├── pages/                                     # Login, Admin, Coordinator, Outreach, Shelter, Import
│   │   ├── components/                                # Layout, DataAge, OfflineBanner, LocaleSelector
│   │   ├── services/                                  # API client (GET/POST/PUT/PATCH/DELETE), offline queue
│   │   ├── hooks/                                     # useOnlineStatus
│   │   └── i18n/                                      # en.json, es.json
│   └── vite.config.ts                                 # PWA manifest, workbox, API proxy
│
├── docs/
│   ├── schema.dbml                                    # Database schema (DBML format)
│   ├── erd.png                                        # Entity relationship diagram
│   ├── asyncapi.yaml                                  # EventBus contract (AsyncAPI 3.0)
│   └── architecture.drawio                            # Architecture diagram
│
├── infra/
│   ├── docker/                                        # Dockerfiles (backend, frontend, nginx)
│   ├── scripts/                                       # seed-data.sql
│   └── terraform/                                     # IaC for all 3 deployment tiers
│       ├── bootstrap/                                 # S3 state backend + DynamoDB lock
│       └── modules/                                   # network, postgres, app
│
└── .github/workflows/ci.yml                           # CI: backend test, frontend build, Docker push
```

---

## Project Status

### Completed: Platform Foundation (archived)

- [x] Modular monolith backend (Java 21, Spring Boot 3.4, 6 modules, ArchUnit boundaries)
- [x] 12 Flyway migrations, PostgreSQL 16, Row Level Security for DV shelters
- [x] 3 deployment profiles (Lite / Standard / Full) with CacheService + EventBus abstractions
- [x] Multi-tenant auth: JWT + API keys + OAuth2 provider management, 4 roles, dual-layer security
- [x] Shelter module: CRUD, constraints, capacities, HSDS 3.0 export, coordinator assignments
- [x] Data import: HSDS JSON, 211 CSV (fuzzy column matching), audit log
- [x] Observability: structured JSON logging, Micrometer metrics, health probes, data freshness, i18n (EN/ES)
- [x] Webhook subscriptions: CRUD, HMAC-SHA256 delivery, event matching
- [x] React PWA: outreach search, coordinator dashboard, admin panel, offline queue, service worker
- [x] MCP-ready: @Operation on all endpoints, structured errors, self-describing events
- [x] CI/CD (GitHub Actions), Docker, Terraform (3 tiers), seed data, dev-start.sh

### Completed: Bed Availability (archived)

- [x] Append-only bed availability snapshots (V12-V13 migrations)
- [x] Bed search endpoint (POST /api/v1/queries/beds) with ranked results, constraint filters
- [x] Coordinator availability update (PATCH /api/v1/shelters/{id}/availability)
- [x] Data freshness (data_age_seconds from snapshot_ts, FRESH/AGING/STALE/UNKNOWN)
- [x] Shelter detail + list enriched with live availability data
- [x] Cache-aside pattern with synchronous invalidation on update
- [x] availability.updated events published to EventBus
- [x] Frontend: bed search with availability badges, freshness indicators, coordinator availability form

### Completed: Reservation System (pending archive)

- [x] Soft-hold bed reservations (V14-V15 migrations, HELD → CONFIRMED/CANCELLED/EXPIRED)
- [x] Reservation API: create hold, confirm arrival, cancel, list active
- [x] Availability integration: holds adjust beds_on_hold/beds_occupied via snapshots
- [x] Configurable hold duration per tenant (default 45 min, read from tenant config JSONB)
- [x] Dual-tier auto-expiry: @Scheduled polling (Lite) + Redis TTL placeholder (Standard/Full)
- [x] 4 domain events: reservation.created, confirmed, cancelled, expired
- [x] Frontend: "Hold This Bed" buttons, countdown timer, confirm/cancel flow, coordinator hold indicator

### In Progress: E2E Test Automation (specced, ready for implementation)

- [ ] Playwright UI tests: login, outreach search, coordinator dashboard, admin panel
- [ ] Karate API tests: auth, shelters, availability, bed search, subscriptions
- [ ] GitHub Actions CI pipeline with parallel execution
- [ ] Test data management and reporting

### Planned: Remaining Phase 1 Capabilities

| Change | Description | Priority |
|--------|-------------|----------|
| **surge-mode** | White Flag / emergency activation, CoC-admin triggered, broadcast to outreach workers | High |
| **oauth2-redirect-flow** | Browser OAuth2 redirect/callback with Keycloak, dynamic provider registration | High |
| **dv-opaque-referral** | Privacy-preserving DV shelter referral with human-in-the-loop confirmation | Medium |
| **hmis-bridge** | Async push adapter to HMIS vendors, circuit breaker isolated | Medium |
| **coc-analytics** | Aggregate anonymized metrics, unmet demand reporting, HUD grant support | Low |

---

## Troubleshooting

### Testcontainers "Could not find valid Docker environment"

**Symptom:** Tests fail at startup with `Could not find a valid Docker environment`.

**Root cause:** Docker Engine 29+ raised the minimum Docker API version. Testcontainers requires explicit API version configuration.

**Fix:** Ensure `src/test/resources/docker-java.properties` contains:

```properties
api.version=1.44
```

This file is already included in the repository. If you still see the error, verify Docker is running:

```bash
docker info
```

### Spring Data JDBC "UpdateRoot" on new entity save

**Symptom:** Saving a new entity throws an exception about `UpdateRoot` or attempts an UPDATE instead of INSERT.

**Root cause:** Spring Data JDBC uses `isNew()` based on whether the entity ID is `null`. If you set the ID before saving, Spring Data JDBC assumes it is an existing entity and issues an UPDATE.

**Fix:** Leave the entity ID `null` and let the database generate it via `gen_random_uuid()`. The Flyway migrations define `id UUID DEFAULT gen_random_uuid()` on all tables.

### PostgreSQL JSONB "column is of type jsonb but expression is of type character varying"

**Symptom:** Saving an entity with a JSONB column fails with a PostgreSQL type mismatch error.

**Root cause:** Spring Data JDBC sends JSON strings as `varchar` by default. PostgreSQL requires explicit JSONB type casting.

**Fix:** The codebase uses a `JsonString` wrapper type with custom `WritingConverter` and `ReadingConverter` registered in `JdbcConfig.java`. If you add a new JSONB column, wrap it with `JsonString` in the domain entity.

### Spring Data JDBC "UpdateRoot" on FK-as-PK entity

**Symptom:** Saving a new entity whose `@Id` is a foreign key (e.g., `ShelterConstraints.shelterId`) throws `IncorrectUpdateSemanticsDataAccessException: Id not found in database`.

**Root cause:** The `@Id` field is always non-null before `save()` (it's a FK reference, not auto-generated), so `isNew()` returns `false` and Spring Data JDBC issues UPDATE instead of INSERT.

**Fix:** Implement `Persistable<UUID>` with a `@Transient boolean isNew = true` flag. Call `markNotNew()` when loading an existing entity for update. See `ShelterConstraints.java` for the canonical pattern.

### Application fails to start with "relation does not exist"

**Symptom:** `mvn spring-boot:run` fails with `relation "tenant" does not exist` or similar.

**Root cause:** No PostgreSQL instance is running or the Flyway migrations have not been applied.

**Fix:** Use `./dev-start.sh` which handles PostgreSQL startup and seed data automatically. For manual setup:

```bash
docker compose up -d postgres
cd backend && mvn spring-boot:run
```

Flyway will apply all migrations automatically on application startup.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, architecture overview, and pull request guidelines. Project specifications, proposals, and designs live in the [docs repo](https://github.com/ccradle/findABed).

---

## License

[Apache License 2.0](LICENSE)
