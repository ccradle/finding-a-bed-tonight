# Finding A Bed Tonight

[![CI](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml/badge.svg)](https://github.com/ccradle/finding-a-bed-tonight/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-green.svg)](https://spring.io/projects/spring-boot)

Open-source emergency shelter bed availability platform. Matches homeless individuals and families to available shelter beds in real time.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     PWA (React + Vite)                       │
│  Coordinator │ Outreach Worker │ CoC Admin                   │
└──────────────┬──────────────────┬────────────────────────────┘
               │ REST API (/api/v1)│
┌──────────────▼──────────────────▼────────────────────────────┐
│              Spring Boot 3.4 (Modular Monolith)              │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │  tenant   │ │   auth   │ │ shelter  │ │  dataimport  │   │
│  │          │ │          │ │          │ │              │   │
│  │ api/     │ │ api/     │ │ api/     │ │ api/         │   │
│  │ domain/  │ │ domain/  │ │ domain/  │ │ domain/      │   │
│  │ repo/    │ │ repo/    │ │ repo/    │ │ repo/        │   │
│  │ service/ │ │ service/ │ │ service/ │ │ service/     │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
│                                                              │
│  ┌─────────────────── shared kernel ───────────────────────┐ │
│  │ config │ cache │ event │ security │ web                 │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────┬──────────────┬───────────────────┬────────────────────┘
       │              │                   │
  ┌────▼────┐   ┌─────▼─────┐      ┌─────▼─────┐
  │PostgreSQL│   │   Redis   │      │   Kafka   │
  │  16 +RLS │   │  (Std/Full)│      │  (Full)   │
  └─────────┘   └───────────┘      └───────────┘
```

## Deployment Tiers

| Tier | Infrastructure | Target | Cost |
|------|---------------|--------|------|
| **Lite** | PostgreSQL only | Rural counties, volunteer-run | $15-30/mo |
| **Standard** | PostgreSQL + Redis | Mid-size CoCs, city IT | $30-75/mo |
| **Full** | PostgreSQL + Redis + Kafka | Metro areas, multi-service | $100+/mo |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.4, Spring MVC, Spring Data JDBC |
| Database | PostgreSQL 16, Flyway, Row Level Security (DV shelters) |
| Cache | Caffeine L1 / + Redis L2 (Standard/Full) |
| Events | Spring Events (Lite) / Kafka (Full) |
| Auth | JWT + OAuth2/OIDC + API Keys (hybrid) |
| Frontend | React 18, Vite, Workbox PWA, react-intl |
| Testing | JUnit 5, Testcontainers, ArchUnit |
| Infra | Docker, Terraform, GitHub Actions |

## Module Boundaries

The backend is a **modular monolith** with ArchUnit-enforced boundaries:

- Modules: `tenant`, `auth`, `shelter`, `dataimport`, `observability`
- Shared kernel: `shared/` (config, cache, event, security, web)
- Modules communicate through published service interfaces and `EventBus`
- No cross-module repository or domain entity access

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Testcontainers and local PostgreSQL)

### Run locally

```bash
# Start PostgreSQL
docker compose up -d

# Build and run (defaults to Lite profile)
cd backend
mvn spring-boot:run

# Run with Standard profile
mvn spring-boot:run -Dspring-boot.run.profiles=standard

# Run tests
mvn test
```

### API Documentation

When running locally: [http://localhost:8080/api/v1/docs](http://localhost:8080/api/v1/docs)

## Database Schema

10 Flyway migrations (V1–V10):

| Migration | Tables |
|-----------|--------|
| V1 | `tenant` |
| V2 | `app_user` |
| V3 | `api_key` |
| V4 | `shelter` |
| V5 | `shelter_constraints` |
| V6 | `shelter_capacity` |
| V7 | `coordinator_assignment` |
| V8 | Row Level Security policies (DV shelter protection) |
| V9 | `import_log` |
| V10 | `tenant_oauth2_provider`, `user_oauth2_link` |

## Project Status

**Platform Foundation** — in progress

- [x] Project scaffold (Spring Boot, Maven, modular monolith)
- [x] Flyway migrations V1–V10
- [x] Deployment profiles (Lite/Standard/Full)
- [x] CacheService + EventBus abstractions
- [x] ArchUnit module boundary tests
- [x] Tenant CRUD + config management
- [x] JWT authentication + refresh tokens
- [x] API key authentication (shelter-scoped + org-level)
- [x] OAuth2 account link service
- [ ] Role-based access enforcement
- [ ] DV shelter RLS integration tests
- [ ] Shelter module
- [ ] Data import module
- [ ] Observability
- [ ] PWA shell
- [ ] CI/CD pipeline
- [ ] Infrastructure as Code
- [ ] Documentation standards (DBML, AsyncAPI, diagrams)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (coming soon).

## License

[Apache License 2.0](LICENSE)
