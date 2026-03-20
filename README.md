# Finding A Bed Tonight

![CI](https://img.shields.io/badge/CI-not%20yet%20configured-lightgrey)
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
| Frontend | React 18, Vite, Workbox PWA, react-intl (not yet implemented) |
| Testing | JUnit 5, Testcontainers, ArchUnit |
| Infra | Docker, Terraform, GitHub Actions (not yet configured) |

---

## Module Boundaries

The backend is a **modular monolith** — not a flat package-by-layer structure. Each module owns its own `api/`, `domain/`, `repository/`, and `service/` packages. Cross-module access is prohibited and enforced at build time by ArchUnit tests.

**Modules:**

| Module | Package | Responsibility |
|---|---|---|
| `tenant` | `org.fabt.tenant` | CoC tenant CRUD, configuration, multi-tenancy |
| `auth` | `org.fabt.auth` | JWT login/refresh, user CRUD, API key management, OAuth2 linking |
| `shelter` | `org.fabt.shelter` | Shelter profiles, bed availability, constraints (not yet implemented) |
| `dataimport` | `org.fabt.dataimport` | HSDS data import pipeline (not yet implemented) |
| `observability` | `org.fabt.observability` | Health checks, metrics, structured logging (not yet implemented) |

**Shared kernel:** `org.fabt.shared` — config, cache (`CacheService`, `CacheNames`), event (`EventBus`, `DomainEvent`), security (`JwtAuthenticationFilter`, `ApiKeyAuthenticationFilter`, `SecurityConfig`), web (`TenantContext`, `GlobalExceptionHandler`).

**ArchUnit enforcement:** 9 architecture tests verify that modules do not access each other's `domain`, `repository`, or `service` packages. Only `api` and `shared` packages are accessible across module boundaries.

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

11 Flyway migrations (V1-V10 + V8.1):

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

> **Note:** DBML schema file and ERD diagram are coming (task 14.1-14.2).

---

## OpenSpec Workflow

Specifications and planning artifacts live in the companion [docs repo (ccradle/findABed)](https://github.com/ccradle/findABed). All features are spec-driven: proposal, design, specs, tasks, implementation, verification.

New to OpenSpec? See [https://openspec.dev](https://openspec.dev) and [https://github.com/Fission-AI/OpenSpec](https://github.com/Fission-AI/OpenSpec).

---

## Prerequisites

- **Java:** 21+ (OpenJDK or Eclipse Temurin)
- **Maven:** 3.9+
- **Docker:** Latest version (required for Testcontainers — engine 29.x+ on Windows requires `api.version=1.44` config)
- **Node.js:** 20+ (for frontend — not yet built)

---

## Quick Start

```bash
git clone https://github.com/ccradle/finding-a-bed-tonight.git
cd finding-a-bed-tonight/backend
mvn test                 # Runs 43 tests (Testcontainers PostgreSQL — Docker required)
mvn spring-boot:run      # Starts on localhost:8080 (requires local PostgreSQL)
```

> **Note:** `mvn spring-boot:run` requires a running PostgreSQL instance. For tests, Testcontainers manages PostgreSQL automatically via Docker.

---

## Running Tests

```bash
# Run all 43 tests
mvn test

# Run a specific test class
mvn test -Dtest=TenantIntegrationTest

# Run with verbose output
mvn test -X
```

**Docker is required.** Tests use Testcontainers to start a PostgreSQL 16 container automatically.

### Test Breakdown

| Test Class | Tests | What It Covers |
|---|---|---|
| `ApplicationTest` | 1 | Spring context loads successfully |
| `ArchitectureTest` | 9 | ArchUnit module boundary enforcement |
| `TenantIntegrationTest` | 8 | Tenant CRUD (create, list, get, update) |
| `AuthIntegrationTest` | 7 | JWT login, refresh token flow |
| `ApiKeyAuthTest` | 6 | API key creation, authentication, rotation, deactivation |
| `DvAccessRlsTest` | 3 | DV shelter Row Level Security enforcement |
| `RoleBasedAccessTest` | 9 | Role-based access control across all roles |
| **Total** | **43** | |

---

## REST API Reference

All endpoints are under `/api/v1`. Authentication is via JWT Bearer token (from `/auth/login`) or API key (via `X-API-Key` header) unless noted otherwise.

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | None | Authenticate with email/password, returns JWT access + refresh tokens |
| `POST` | `/api/v1/auth/refresh` | None | Refresh an access token using a refresh token |

### Tenants

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/tenants` | PLATFORM_ADMIN | Create a new tenant (CoC) |
| `GET` | `/api/v1/tenants` | PLATFORM_ADMIN | List all tenants |
| `GET` | `/api/v1/tenants/{id}` | PLATFORM_ADMIN | Get tenant by ID |
| `PUT` | `/api/v1/tenants/{id}` | PLATFORM_ADMIN | Update tenant name |

### Tenant Configuration

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/v1/tenants/{id}/config` | PLATFORM_ADMIN | Get tenant configuration (JSONB) |
| `PUT` | `/api/v1/tenants/{id}/config` | PLATFORM_ADMIN | Update tenant configuration |

### Users

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/users` | COC_ADMIN | Create a user within the authenticated tenant |
| `GET` | `/api/v1/users` | COC_ADMIN | List users in the authenticated tenant |
| `GET` | `/api/v1/users/{id}` | COC_ADMIN | Get user by ID |
| `PUT` | `/api/v1/users/{id}` | COC_ADMIN | Update user roles, display name, DV access |

### API Keys

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/api-keys` | COC_ADMIN | Create an API key (shelter-scoped or org-level) |
| `GET` | `/api/v1/api-keys` | COC_ADMIN | List API keys for the authenticated tenant |
| `DELETE` | `/api/v1/api-keys/{id}` | COC_ADMIN | Deactivate an API key |
| `POST` | `/api/v1/api-keys/{id}/rotate` | COC_ADMIN | Rotate an API key (deactivates old, creates new) |

### curl Examples

**Login:**

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "tenantSlug": "nc-507",
    "email": "admin@example.com",
    "password": "your-password"
  }'
```

**Create a tenant:**

```bash
TOKEN="<access_token from login response>"

curl -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Wake County CoC",
    "slug": "nc-507"
  }'
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
├── LICENSE                                            # Apache 2.0
└── backend/
    ├── pom.xml                                        # Maven build (Spring Boot 3.4.4 parent)
    └── src/
        ├── main/
        │   ├── java/org/fabt/
        │   │   ├── Application.java                   # Spring Boot entry point
        │   │   ├── tenant/                            # Tenant module
        │   │   │   ├── api/                           # TenantController, TenantConfigController
        │   │   │   ├── domain/                        # Tenant entity
        │   │   │   ├── repository/                    # TenantRepository (Spring Data JDBC)
        │   │   │   └── service/                       # TenantService
        │   │   ├── auth/                              # Auth module
        │   │   │   ├── api/                           # AuthController, UserController, ApiKeyController
        │   │   │   ├── domain/                        # User, ApiKey, Role enum
        │   │   │   ├── repository/                    # UserRepository, ApiKeyRepository
        │   │   │   └── service/                       # JwtService, PasswordService, ApiKeyService
        │   │   ├── shelter/                           # Shelter module (package-info only)
        │   │   ├── dataimport/                        # Data import module (package-info only)
        │   │   ├── observability/                     # Observability module (package-info only)
        │   │   └── shared/                            # Shared kernel
        │   │       ├── cache/                         # CacheService, CacheNames, Caffeine/Tiered impls
        │   │       ├── config/                        # DeploymentTier, JdbcConfig, JsonString
        │   │       ├── event/                         # EventBus, DomainEvent, Spring/Kafka impls
        │   │       ├── security/                      # JWT filter, API key filter, SecurityConfig
        │   │       └── web/                           # TenantContext, GlobalExceptionHandler, ErrorResponse
        │   └── resources/
        │       ├── application.yml                    # Default config
        │       ├── application-lite.yml               # Lite tier profile
        │       ├── application-standard.yml           # Standard tier profile
        │       ├── application-full.yml               # Full tier profile
        │       └── db/migration/                      # Flyway migrations (V1-V10 + V8.1)
        └── test/
            ├── java/org/fabt/
            │   ├── ApplicationTest.java               # Context load test
            │   ├── ArchitectureTest.java              # 9 ArchUnit module boundary tests
            │   ├── BaseIntegrationTest.java           # Testcontainers PostgreSQL base class
            │   ├── TestAuthHelper.java                # JWT token generation for tests
            │   ├── auth/
            │   │   ├── AuthIntegrationTest.java       # Login + refresh token tests
            │   │   ├── ApiKeyAuthTest.java            # API key auth tests
            │   │   ├── DvAccessRlsTest.java           # DV shelter RLS tests
            │   │   └── RoleBasedAccessTest.java       # Role-based access tests
            │   └── tenant/
            │       └── TenantIntegrationTest.java     # Tenant CRUD tests
            └── resources/
                └── docker-java.properties             # api.version=1.44 for Docker Engine 29+
```

---

## Project Status

**Platform Foundation** — in progress

- [x] Project scaffold (Spring Boot 3.4, Maven, modular monolith)
- [x] Flyway migrations V1-V10 + V8.1
- [x] Deployment profiles (Lite/Standard/Full)
- [x] CacheService + EventBus abstractions
- [x] ArchUnit module boundary tests (9 tests)
- [x] Tenant CRUD + config management
- [x] JWT authentication + refresh tokens
- [x] API key authentication (shelter-scoped + org-level)
- [x] OAuth2 account link tables (V10)
- [x] Role-based access enforcement
- [x] DV shelter RLS integration tests
- [ ] Shelter module implementation
- [ ] Data import module implementation
- [ ] Observability module implementation
- [ ] PWA shell (React frontend)
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Infrastructure as Code (Terraform)
- [ ] Documentation standards (DBML, AsyncAPI, architecture diagrams)

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

See [CONTRIBUTING.md](CONTRIBUTING.md) (coming soon). Project specifications, proposals, and designs live in the [docs repo](https://github.com/ccradle/findABed).

---

## License

[Apache License 2.0](LICENSE)
