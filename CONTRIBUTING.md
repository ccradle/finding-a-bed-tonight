# Contributing to Finding A Bed Tonight

Thank you for your interest in contributing to Finding A Bed Tonight. This project helps connect homeless individuals and families to available shelter beds in real time.

## Getting Started

### Prerequisites

- Java 25+ (OpenJDK or Eclipse Temurin)
- Maven 3.9+
- Node.js 20+
- Docker (for PostgreSQL via Testcontainers and local dev)

### Setup

```bash
# Clone the repository
git clone https://github.com/ccradle/finding-a-bed-tonight.git
cd finding-a-bed-tonight

# Start local infrastructure and load seed data
./infra/scripts/dev-setup.sh

# Run backend
cd backend
mvn spring-boot:run

# In another terminal, run frontend
cd frontend
npm install
npm run dev
```

### Running Tests

```bash
# Backend integration tests (requires Docker for Testcontainers)
cd backend
mvn test

# E2E tests (requires dev-start.sh stack running)
cd e2e/playwright && npx playwright test    # 17+ UI tests
cd e2e/karate && mvn test                   # 19+ API tests

# Performance tests (requires dev-start.sh stack running)
cd e2e/gatling && mvn verify -Pperf         # Gatling simulations with SLO assertions

# Frontend build
cd frontend
npm run build
```

## Architecture

The backend is a **modular monolith** built with Spring Boot 4.0 and Java 25 (virtual threads enabled). Each module owns its domain:

| Module | Package | Responsibility |
|--------|---------|---------------|
| tenant | `org.fabt.tenant` | CoC tenant management, configuration |
| auth | `org.fabt.auth` | JWT, API keys, OAuth2, user management |
| shelter | `org.fabt.shelter` | Shelter profiles, constraints, HSDS export |
| dataimport | `org.fabt.dataimport` | HSDS/211 data import pipelines |
| observability | `org.fabt.observability` | Logging, metrics, health, data freshness |
| shared | `org.fabt.shared` | Cache, events, security, web utilities |

**Module boundaries are enforced by ArchUnit tests.** Modules communicate through published service interfaces — never by accessing another module's repository or domain entities directly.

## Spec-Driven Development

All features follow the [OpenSpec](https://openspec.dev) workflow. Specifications live in the companion [docs repo](https://github.com/ccradle/findABed).

Before implementing a new feature:
1. Check existing specs in `openspec/changes/`
2. Create a new change with `/opsx:new` if needed
3. Write specs before code
4. Verify with `/opsx:verify` after implementation

## Pull Request Guidelines

1. **One concern per PR** — don't mix features with refactoring
2. **Tests required** — every PR must include tests for new behavior
3. **ArchUnit must pass** — module boundary violations will fail CI
4. **No PII** — never log or store personally identifiable information of people experiencing homelessness
5. **DV shelter safety** — domestic violence shelter data is protected by PostgreSQL Row Level Security. Never bypass RLS in application code.

## Code Style

- Java: follow existing patterns in the codebase (no Javadoc on trivial methods, constructor injection)
- TypeScript: functional components with hooks, all text via react-intl
- Colors: use `import { color } from '../theme/colors'` — never hardcode hex values. Use `color.primaryText` for links/labels, `color.primary` for button fills. See `colors.ts` header comment.
- Typography: use `import { text, weight } from '../theme/typography'` — never hardcode font sizes or weights.
- SQL: Flyway migrations are immutable once merged — use new migration files for changes

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
