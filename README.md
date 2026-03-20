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

The backend is a Spring Boot 3.4 modular monolith. Each bounded context (tenant, auth, shelter, dataimport, observability) lives in its own top-level package under `org.fabt.*` with enforced boundaries. A shared kernel provides cross-cutting infrastructure (security filters, caching, event bus, JDBC configuration).

Three deployment tiers allow the same codebase to serve communities of vastly different size and budget:

```
┌──────────────────────────────────────────────────────────────────┐
│                     PWA (React + Vite)                            │
│  Coordinator │ Outreach Worker │ CoC Admin                       │
└──────────────┬──────────────────┬────────────────────────────────┘
               │ REST API (/api/v1)│
┌──────────────▼──────────────────▼────────────────────────────────┐
│              Spring Boot 3.4 (Modular Monolith)                  │
│                                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐       │
│  │  tenant   │ │   auth   │ │ shelter  │ │  dataimport  │       │
│  │          │ │          │ │          │ │              │       │
│  │ api/     │ │ api/     │ │ api/     │ │ api/         │       │
│  │ domain/  │ │ domain/  │ │ domain/  │ │ domain/      │       │
│  │ repo/    │ │ repo/    │ │ repo/    │ │ repo/        │       │
│  │ service/ │ │ service/ │ │ service/ │ │ service/     │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘       │
│                                                                  │
│  ┌─────────────────── shared kernel ───────────────────────┐     │
│  │ config │ cache │ event │ security │ web                 │     │
│  └─────────────────────────────────────────────────────────┘     │
└──────┬──────────────┬───────────────────┬────────────────────────┘
       │              │                   │
  ┌────▼────┐   ┌─────▼─────┐      ┌─────▼─────┐
  │PostgreSQL│   │   Redis   │      │   Kafka   │
  │  16 +RLS │   │  (Std/Full)│      │  (Full)   │
  └─────────┘   └───────────┘      └───────────┘
```

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
| Database | PostgreSQL 16, Flyway, Row Level Security (DV shelters) |
| Cache | Caffeine L1 / + Redis L2 (Standard/Full) |
| Events | Spring Events (Lite) / Kafka (Full) |
| Auth | JWT + OAuth2/OIDC + API Keys (hybrid) |
| Frontend | React 18, Vite, TypeScript, Workbox PWA, react-intl (EN/ES) |
| Testing | JUnit 5, Testcontainers, ArchUnit (85 tests) |
| Infra | Docker, GitHub Actions CI/CD, Terraform (pending) |

---

## Module Boundaries

The backend is a **modular monolith** — not a flat package-by-layer structure. Each module owns its own `api/`, `domain/`, `repository/`, and `service/` packages. Cross-module access is prohibited and enforced at build time by ArchUnit tests.

**Modules:**

| Module | Package | Responsibility |
|---|---|---|
| `tenant` | `org.fabt.tenant` | CoC tenant CRUD, configuration, multi-tenancy |
| `auth` | `org.fabt.auth` | JWT login/refresh, user CRUD, API key management, OAuth2 linking |
| `shelter` | `org.fabt.shelter` | Shelter profiles, constraints, HSDS export, coordinator assignments |
| `dataimport` | `org.fabt.dataimport` | HSDS JSON import, 211 CSV import (fuzzy matching), import audit log |
| `observability` | `org.fabt.observability` | Structured JSON logging, Micrometer metrics, health probes, data freshness, i18n |
| `subscription` | `org.fabt.subscription` | Webhook subscriptions, HMAC-SHA256 event delivery, MCP-ready |

**Shared kernel:** `org.fabt.shared` — config, cache (`CacheService`, `CacheNames`), event (`EventBus`, `DomainEvent`), security (`JwtAuthenticationFilter`, `ApiKeyAuthenticationFilter`, `SecurityConfig`), web (`TenantContext`, `GlobalExceptionHandler`).

**ArchUnit enforcement:** 11 architecture tests verify that modules do not access each other's `domain`, `repository`, or `service` packages. Only `api` and `shared` packages are accessible across module boundaries.

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

12 Flyway migrations (V1–V11 + V8.1):

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

> **Note:** DBML schema file and ERD diagram are coming (task 14.1-14.2).

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

### 1. Start infrastructure + load seed data

```bash
git clone https://github.com/ccradle/finding-a-bed-tonight.git
cd finding-a-bed-tonight

# Start PostgreSQL via Docker Compose
docker compose up -d postgres

# Wait for PostgreSQL to be ready
docker compose exec postgres pg_isready -U fabt

# Load seed data (10 shelters, 3 users, 1 tenant)
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-data.sql
```

### 2. Start the backend

```bash
cd backend
mvn spring-boot:run
```

The backend starts on **http://localhost:8080**. Flyway automatically runs all 12 migrations on first startup.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on **http://localhost:5173** and proxies API requests to the backend.

### 4. Verify the stack is running

```bash
# Health check
curl http://localhost:8080/actuator/health/liveness

# Swagger UI
open http://localhost:8080/api/v1/docs
```

---

## UI Sanity Check

After starting the stack, open **http://localhost:5173** in your browser.

### Login

Use one of the seed data accounts:

| Role | Email | Password | What you'll see |
|------|-------|----------|----------------|
| **Platform Admin** | `admin@dev.fabt.org` | `admin123` | Admin panel (tenant/user management) |
| **CoC Admin** | `cocadmin@dev.fabt.org` | `cocadmin123` | Admin panel + shelter management |
| **Outreach Worker** | `outreach@dev.fabt.org` | `outreach123` | Outreach search interface |

**Tenant slug:** `dev-coc`

### What to verify

1. **Login page** loads with email/password form and tenant slug field
2. **Login with admin credentials** → redirected to admin panel
3. **Navigation** — sidebar (desktop) or bottom nav (mobile) shows role-appropriate links
4. **Offline banner** — toggle airplane mode or disconnect network → yellow "You are offline" banner appears
5. **Language selector** — switch to Español → UI text changes to Spanish
6. **Shelter form** (CoC Admin) — navigate to shelter creation, verify form fields render
7. **Logout** → returns to login page

### API verification (via curl)

```bash
# Login as admin
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug": "dev-coc", "email": "admin@dev.fabt.org", "password": "admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# List shelters (10 seed shelters)
curl -s http://localhost:8080/api/v1/shelters \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Get shelter detail with constraints
curl -s http://localhost:8080/api/v1/shelters/d0000000-0000-0000-0000-000000000001 \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# HSDS export
curl -s "http://localhost:8080/api/v1/shelters/d0000000-0000-0000-0000-000000000001?format=hsds" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Health check (no auth needed)
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# Prometheus metrics
curl -s http://localhost:8080/actuator/prometheus | head -20
```

---

## Running Tests

```bash
cd backend

# Run all 85 tests
mvn test

# Run a specific test class
mvn test -Dtest=ShelterIntegrationTest

# Run a specific test method
mvn test -Dtest="AuthIntegrationTest#test_login_success"
```

**Docker is required.** Tests use Testcontainers to start a PostgreSQL 16 container automatically.

### Test Breakdown

| Test Class | Tests | What It Covers |
|---|---|---|
| `ApplicationTest` | 1 | Spring context loads successfully |
| `ArchitectureTest` | 11 | ArchUnit module boundary enforcement |
| `TenantIntegrationTest` | 8 | Tenant CRUD, config defaults, config update |
| `AuthIntegrationTest` | 7 | JWT login, refresh, wrong password/email/tenant |
| `ApiKeyAuthTest` | 6 | API key auth, rotation, deactivation, role resolution |
| `DvAccessRlsTest` | 3 | PostgreSQL RLS for DV shelter data protection |
| `RoleBasedAccessTest` | 10 | 4-role access control (PLATFORM_ADMIN, COC_ADMIN, COORDINATOR, OUTREACH_WORKER) |
| `OAuth2ProviderTest` | 6 | OAuth2 provider CRUD, public endpoint, tenant leakage prevention |
| `OAuth2AccountLinkTest` | 4 | Account linking, rejection of unknown emails, JWT identity |
| `ShelterIntegrationTest` | 11 | Shelter CRUD, constraints, HSDS export, coordinator assignment |
| `ImportIntegrationTest` | 7 | HSDS import, 211 CSV import, fuzzy matching, duplicate detection |
| `ObservabilityIntegrationTest` | 6 | Health endpoints, i18n error responses, error structure |
| `SubscriptionIntegrationTest` | 5 | Webhook subscription CRUD, error validation |
| **Total** | **85** | |

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
| `PUT` | `/api/v1/tenants/{id}/config` | COC_ADMIN+ | Update tenant configuration |

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
| `GET` | `/api/v1/shelters` | Any authenticated | List shelters (filterable: petsAllowed, wheelchairAccessible, etc.) |
| `GET` | `/api/v1/shelters/{id}` | Any authenticated | Shelter detail with constraints + capacities |
| `GET` | `/api/v1/shelters/{id}?format=hsds` | Any authenticated | HSDS 3.0 export with fabt: extensions |
| `PUT` | `/api/v1/shelters/{id}` | COORDINATOR+ | Update shelter (coordinators must be assigned) |
| `POST` | `/api/v1/shelters/{id}/coordinators` | COC_ADMIN+ | Assign coordinator to shelter |
| `DELETE` | `/api/v1/shelters/{id}/coordinators/{uid}` | COC_ADMIN+ | Unassign coordinator |

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

### curl Examples

**Login:**

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug": "dev-coc", "email": "admin@dev.fabt.org", "password": "admin123"}'
```

**Create a shelter:**

```bash
TOKEN="<accessToken from login response>"

curl -X POST http://localhost:8080/api/v1/shelters \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "New Hope Shelter",
    "addressStreet": "100 Main St",
    "addressCity": "Raleigh",
    "addressState": "NC",
    "addressZip": "27601",
    "constraints": {
      "petsAllowed": true,
      "wheelchairAccessible": true,
      "populationTypesServed": ["FAMILY_WITH_CHILDREN"]
    },
    "capacities": [{"populationType": "FAMILY_WITH_CHILDREN", "bedsTotal": 20}]
  }'
```

**Import 211 CSV:**

```bash
curl -X POST http://localhost:8080/api/v1/import/211 \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@shelters.csv"
```

---

## Domain Glossary

**CoC (Continuum of Care)** — HUD-defined regional body coordinating homeless services. Each CoC has a unique ID (e.g., NC-507 for Wake County). Maps to a tenant in the platform.

**Tenant** — A CoC or administrative boundary served by a single platform deployment. Multi-tenant design allows one deployment to serve multiple CoCs.

**DV Shelter (Domestic Violence)** — Shelter serving DV survivors. Location and existence protected by PostgreSQL Row Level Security. Never exposed through public queries.

**HSDS (Human Services Data Specification)** — Open Referral standard (v3.0) for describing social services. FABT extends HSDS with bed availability objects.

**Surge Event / White Flag** — Emergency activation when weather or crisis requires expanded shelter capacity. CoC-admin triggered, broadcast to all outreach workers.

**PIT Count (Point-in-Time)** — Annual HUD-mandated count of sheltered and unsheltered homeless individuals.

**Bed Availability** — Real-time count of open beds by population type at a shelter. Append-only snapshots, never updated in place.

**Population Type** — Category of individuals a shelter serves: `SINGLE_ADULT`, `FAMILY_WITH_CHILDREN`, `WOMEN_ONLY`, `VETERAN`, `YOUTH_18_24`, `YOUTH_UNDER_18`, `DV_SURVIVOR`.

**Outreach Worker** — Frontline staff who connects homeless individuals to services. Primary user of the bed search interface.

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
├── docker-compose.yml                                 # Local dev: PostgreSQL, Redis, Kafka
│
├── backend/                                           # Spring Boot modular monolith
│   ├── pom.xml                                        # Maven build (Spring Boot 3.4.4)
│   └── src/
│       ├── main/java/org/fabt/
│       │   ├── Application.java                       # Entry point
│       │   ├── tenant/                                # Tenant module (CRUD, config)
│       │   ├── auth/                                  # Auth module (JWT, API keys, OAuth2, users)
│       │   ├── shelter/                               # Shelter module (CRUD, constraints, HSDS)
│       │   ├── dataimport/                            # Import module (HSDS, 211 CSV)
│       │   ├── subscription/                          # Webhook subscription module
│       │   ├── observability/                         # Logging, metrics, health, i18n
│       │   └── shared/                                # Shared kernel (cache, event, security, web)
│       ├── main/resources/
│       │   ├── application.yml                        # Base config
│       │   ├── application-{lite,standard,full}.yml   # Deployment tier profiles
│       │   ├── db/migration/                          # 12 Flyway migrations (V1–V11 + V8.1)
│       │   ├── logback-spring.xml                     # Structured JSON logging
│       │   └── messages/                              # i18n (EN, ES)
│       └── test/java/org/fabt/                        # 85 integration tests
│
├── frontend/                                          # React + Vite + TypeScript PWA
│   ├── src/
│   │   ├── auth/                                      # AuthContext, AuthGuard
│   │   ├── pages/                                     # Login, Admin, Coordinator, Outreach, Shelter, Import
│   │   ├── components/                                # Layout, DataAge, OfflineBanner, LocaleSelector
│   │   ├── services/                                  # API client, offline queue
│   │   ├── hooks/                                     # useOnlineStatus
│   │   └── i18n/                                      # en.json, es.json
│   └── vite.config.ts                                 # PWA manifest, workbox, API proxy
│
├── infra/
│   ├── docker/                                        # Dockerfiles (backend, frontend, nginx)
│   └── scripts/                                       # seed-data.sql, dev-setup.sh
│
└── .github/workflows/ci.yml                           # CI: backend test, frontend build, Docker push
```

---

## Project Status

**Platform Foundation** — 117/131 tasks complete (89%)

- [x] Project scaffold (Spring Boot 3.4, Java 21, modular monolith, ArchUnit)
- [x] 12 Flyway migrations (V1–V11 + V8.1)
- [x] 3 deployment profiles (Lite / Standard / Full)
- [x] CacheService + EventBus abstractions with profile-based implementations
- [x] Multi-tenant CRUD + JSONB config management
- [x] JWT authentication + refresh tokens (HMAC-SHA256 with Caffeine claims cache)
- [x] API key authentication (shelter-scoped COORDINATOR / org-level COC_ADMIN)
- [x] OAuth2 provider management + account linking (Option C: pre-created accounts)
- [x] Role-based access (dual-layer: URL + @PreAuthorize, 4 roles)
- [x] DV shelter RLS (PostgreSQL Row Level Security, enforced at data layer)
- [x] Shelter module (CRUD, constraints, capacities, HSDS 3.0 export, coordinator assignments)
- [x] Data import module (HSDS JSON, 211 CSV with fuzzy column matching, audit log)
- [x] Observability (structured JSON logging, Micrometer metrics, health probes, data freshness, i18n)
- [x] Webhook subscriptions (CRUD, HMAC-SHA256 delivery, event matching)
- [x] React PWA frontend (role-gated routing, offline queue, i18n EN/ES, service worker)
- [x] CI/CD pipeline (GitHub Actions: backend test, frontend build, Docker push)
- [x] Docker (backend + frontend Dockerfiles, docker-compose, nginx SPA proxy)
- [x] Seed data (10 shelters, 3 users, dev-setup script, CONTRIBUTING.md)
- [x] MCP-ready error responses + self-describing domain events
- [ ] OAuth2 redirect flow (dynamic ClientRegistrationRepository — provider CRUD done, browser redirect pending)
- [ ] @Operation annotations on all controllers (done on SubscriptionController only)
- [ ] Infrastructure as Code (Terraform modules — 8 tasks)
- [ ] Documentation standards (DBML, ERD, AsyncAPI, draw.io — 4 tasks)

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

**Fix:** Start a local PostgreSQL instance (Docker is the easiest path):

```bash
docker run -d --name fabt-postgres \
  -e POSTGRES_DB=fabt \
  -e POSTGRES_USER=fabt \
  -e POSTGRES_PASSWORD=fabt \
  -p 5432:5432 \
  postgres:16
```

Flyway will apply all migrations automatically on application startup.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, architecture overview, and pull request guidelines. Project specifications, proposals, and designs live in the [docs repo](https://github.com/ccradle/findABed).

---

## License

[Apache License 2.0](LICENSE)
