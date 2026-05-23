# FABT Testing Guide

**Audience:** Junior developers joining the FABT codebase, or anyone writing their first test in this repo.

**Goal:** by the end of this guide you can (a) pick the right kind of test for the change you're making, (b) write it following the patterns the rest of the codebase uses, and (c) run it locally and in CI without surprises.

This guide covers four kinds of tests we use in FABT:

| Kind | What it tests | What's real | What's faked | Wall-clock |
|---|---|---|---|---|
| **Unit test** | One class, one method, one rule at a time | Just the class under test | Every collaborator (via Mockito) | ~milliseconds |
| **Integration test with mock** | A request flowing through the whole Spring app, with one or two external boundaries stubbed | Full Spring context + real Postgres + real HTTP | One specific bean (e.g. an SSRF guard) or an outbound HTTP endpoint | ~seconds |
| **Integration test with Testcontainer** | Same as above, but with *nothing* faked — real Postgres, real Flyway, real services end-to-end | Everything | Nothing | ~seconds |
| **Karate API test** | The shipped REST API contract from a black-box client's point of view | Whatever's running at `baseUrl` | Nothing — Karate is a real HTTP client | ~seconds |

The rest of this guide walks each kind end-to-end with a real exemplar from the codebase you can read and copy.

---

## Before you start: one-time setup

You need these tools installed (the [`dev-start.sh`](../dev-start.sh) script will check for them and complain if any are missing):

- **Java 25+** — backend builds and runs on Java 25.
- **Maven 3.9+** — the build tool. We **never** use `gradle` (see [`feedback_maven_not_gradle.md`](https://github.com/anthropics/claude-code/) memory).
- **Docker Desktop** — required for Testcontainers (real Postgres) and the dev stack.
- **Node 20+** — for the frontend and the Karate runner.
- **Git Bash** (Windows) or any POSIX shell (macOS/Linux).

If you can run this, you're ready:

```bash
cd finding-a-bed-tonight
mvn -version          # Maven + Java 25
docker info           # Docker daemon reachable
node --version        # v20+
```

Don't worry about installing JUnit, Mockito, Testcontainers, or Karate — Maven pulls them in from `backend/pom.xml` and `e2e/karate/pom.xml`.

---

## Where tests live

```
finding-a-bed-tonight/
├── backend/
│   └── src/
│       └── test/
│           └── java/
│               ├── org/fabt/                          ← backend tests
│               │   ├── shelter/service/
│               │   │   └── ShelterServiceIsValidCountyTest.java     [unit]
│               │   ├── subscription/
│               │   │   └── WebhookTimeoutTest.java                  [integration + mock]
│               │   └── ...
│               └── db/migration/
│                   └── V97MigrationIntegrationTest.java             [integration + testcontainer]
└── e2e/
    └── karate/
        └── src/test/java/features/
            └── dv-access/
                └── dv-access-control.feature                        [karate API]
```

The rule of thumb:

- A test for **one Java class** goes in `backend/src/test/java/<same package>/<ClassName>Test.java`.
- A test that **boots Spring** goes in the same package and ends in `IntegrationTest.java` (Maven Surefire is configured to recognize this suffix).
- A test for the **REST API contract from outside** goes in `e2e/karate/src/test/java/features/<area>/<thing>.feature`.

---

## 1. Unit tests

### What a unit test is

A unit test exercises **one class** in isolation. Every collaborator the class depends on is replaced by a Mockito mock so the test fails for exactly one reason: a bug in the class under test. No Spring. No database. No HTTP. No filesystem.

If your test takes more than a few hundred milliseconds, it's almost certainly not a unit test anymore.

### When to write one

- You added a new method on a service class that branches on inputs.
- You added a validator, a parser, or any pure function.
- You changed a class's contract and want to pin the new behavior.
- You're fixing a bug — write the failing unit test first, watch it fail, then fix the code.

### Exemplar to read

[`backend/src/test/java/org/fabt/shelter/service/ShelterServiceIsValidCountyTest.java`](../backend/src/test/java/org/fabt/shelter/service/ShelterServiceIsValidCountyTest.java)

This test pins the 4-branch state machine inside `ShelterService.isValidCounty(...)`:

1. `county == null` → always true (no constraint).
2. `active_counties: []` configured → true (validation disabled).
3. `active_counties: ["Wake"]` → match-or-miss.
4. Key absent → fall back to a hard-coded NC county list.

What makes it a good template:

- **The class-level Javadoc enumerates every branch** so the next person knows where new cases slot in (lines 28–47).
- **Each branch has both a positive and a negative test** (e.g. `countyInExplicitList_isValid` + `countyNotInExplicitList_isInvalid_evenIfInNcDefaults`). Without the negative test you don't know your code rejects what it should.
- **`.as("...")` descriptions** on every AssertJ assertion explain *why* the rule exists, not what the code does — so when CI fails 6 months from now, you can read the failure message and understand the design intent (line 137: *"Wake is in NC defaults but excluded from this tenant's active_counties"*).
- **The constructor passes `null` for every unused dependency** (lines 65–77). If a future change makes `isValidCounty` start calling, say, `reservationService`, this test will throw NPE and you'll know immediately. This is the cheapest way to detect a hidden coupling.
- **Defensive cases live alongside the happy paths** — malformed JSON, null config, tenant-not-found. They're not in a separate file; they're right where the next reader will see them.

### Skeleton

```java
package org.fabt.<area>.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThingService#doStuff(Input)}.
 *
 * <p>Branches under test:
 * <ol>
 *   <li>Input is null → throw IllegalArgumentException.</li>
 *   <li>Input passes validation → returns transformed value.</li>
 *   <li>Repository returns empty → returns default fallback.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ThingServiceTest {

    @Mock private ThingRepository repo;

    @Test
    void doStuff_validInput_returnsTransformedValue() {
        // Arrange
        when(repo.findFor("hello")).thenReturn(Optional.of(new Thing("HELLO")));
        ThingService svc = new ThingService(repo);

        // Act
        String result = svc.doStuff(new Input("hello"));

        // Assert
        assertThat(result)
            .as("uppercased Thing value should be returned verbatim")
            .isEqualTo("HELLO");
    }
}
```

### How to run

```bash
# All backend tests (slow — boots Spring contexts for IT classes)
cd finding-a-bed-tonight/backend
mvn test

# Just this one class (fast — pure unit, no Spring)
mvn test -Dtest=ShelterServiceIsValidCountyTest

# One test method
mvn test -Dtest=ShelterServiceIsValidCountyTest#nullCounty_isAlwaysValid_andSkipsTenantLookup
```

### Common pitfalls

- **Over-mocking.** If you find yourself mocking 10 dependencies to test one method, you've probably found a class that's doing too much. Consider splitting it before writing the test.
- **Verifying every interaction.** `verify(mock, times(1)).somethingCalled()` for *every* call makes the test a re-implementation of the production code. Verify only the interactions that are part of the contract (e.g. "this method must NOT hit the DB when the input is null" is worth verifying).
- **Adding `@SpringBootTest` for convenience.** That makes it an integration test, takes 30s to boot, and is no longer a unit test. If a class can't be unit-tested without Spring, that's design feedback — the class probably has too many responsibilities.
- **Asserting on strings without `.as()`.** `assertThat(x).isEqualTo("HELLO")` tells you nothing when it fails. `assertThat(x).as("uppercased value").isEqualTo("HELLO")` tells you which rule broke.

---

## 2. Integration tests with mocks

### What this is

A Spring `@SpringBootTest` that boots the full backend application, talks to a real Testcontainers Postgres, and exercises a real HTTP request end-to-end — **but with one or two boundaries surgically replaced** by a Mockito mock or a WireMock stub.

You use it when:

- You need the full Spring wiring to be real (security filters, transaction boundaries, JSON serialization, JdbcTemplate, etc.).
- *But* one external collaborator would otherwise make the test impossible — typically because it would refuse to talk to a test fixture (an SSRF guard rejecting `localhost`), or because the real collaborator is a network service you can't run in CI.

### Exemplar to read

[`backend/src/test/java/org/fabt/subscription/WebhookTimeoutTest.java`](../backend/src/test/java/org/fabt/subscription/WebhookTimeoutTest.java)

This test verifies that an outbound webhook delivery fails fast when the subscriber takes too long to respond. The full Spring app runs. Postgres runs. The real `WebhookDeliveryService` runs against a real `RestClient`. Only **two** things are faked:

1. The **outbound HTTP destination** — a real WireMock server bound to a random localhost port, configured to wait 3 seconds before replying (line 101).
2. The **SSRF guard** — `@MockitoBean private SafeOutboundUrlValidator urlValidator;` (line 61). Without this, the production guard would (correctly) reject `http://localhost:<random>/webhook` and the test couldn't reach WireMock.

What makes it a good template:

- **The `@MockitoBean` is a *single* bean replacement, and there's a comment explaining why** (lines 58–60). Every other test in the suite keeps the SSRF guard armed — see [`BaseIntegrationTest.java:67-74`](../backend/src/test/java/org/fabt/BaseIntegrationTest.java).
- **`@TestPropertySource` overrides config for the rule under test** (lines 49–52: `fabt.webhook.read-timeout-seconds=1`). The timeout logic is real; only the value is shrunk so the test runs in <2s instead of 30.
- **Negative case + positive case in the same class** (lines 99 + 148). The negative test proves the timeout fires; the positive test proves a fast endpoint within the same configured timeout still succeeds. Without the positive case, a regression where *every* delivery fails would still pass the negative test.
- **A wall-clock assertion proves the timeout actually fired** (line 137: `elapsedMs < 2500`). The upstream stub waits 3000ms. If your code waited for it anyway, the test would take >3s — the bound makes that visible.
- **DB-side-effect verification** (line 140: `SELECT COUNT(*) FROM webhook_delivery_log`). The test asserts the failure was *recorded*, not silently dropped. A common bug class is "we report success to the caller but log a failure" — only a DB query catches it.
- **Per-test WireMock lifecycle** (`@BeforeEach` starts it, `@AfterEach` stops it). No global state leaking between tests.

### Skeleton

```java
@DisplayName("My Feature With An External Dependency")
@TestPropertySource(properties = "fabt.my-feature.timeout-seconds=1")
class MyFeatureIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private TheExternalGuardBean guard;  // documented: WHY?

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void positivePath_doesTheThing() { /* ... */ }

    @Test
    void negativePath_recordsFailureWhenUpstreamMisbehaves() { /* ... */ }
}
```

### How to run

Identical to unit tests — Maven Surefire picks it up from the `*Test.java` / `*IntegrationTest.java` suffix:

```bash
cd finding-a-bed-tonight/backend
mvn test -Dtest=WebhookTimeoutTest
```

The first run downloads the Postgres Docker image (~150MB) — slow once, fast forever after.

### Common pitfalls

- **Mocking too many beans.** Each `@MockitoBean` is one boundary the test no longer verifies. Two is usually a smell; three is almost always wrong. If you find yourself reaching for a fourth, step back: maybe this should be a unit test against a smaller class, or a fully-real integration test against Testcontainers.
- **Forgetting to document *why* a bean is mocked.** Future you will not remember. The comment is the whole point.
- **`@MockBean` instead of `@MockitoBean`.** `@MockBean` is deprecated as of Spring Boot 3.4. Always use `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`.
- **WireMock at a fixed port.** Always use `WireMockConfiguration.options().dynamicPort()` — fixed ports collide when tests run in parallel.

---

## 3. Integration tests with Testcontainers

### What this is

A `@SpringBootTest` that runs against a **real Postgres** (via Testcontainers) and **real Flyway migrations** with **nothing mocked**. The most production-faithful kind of test we have. Slower than unit tests, but the test result is high-fidelity: if it passes here, it will almost certainly pass in prod.

You use it when:

- You're changing a SQL migration.
- You're testing logic that involves transactions, RLS policies, or JSONB column manipulation that Hibernate / JdbcTemplate alone can't model in-memory.
- You're testing security rules that have to flow through the real Spring Security filter chain.
- You want the strongest possible regression guard for a change.

### How the Postgres container works

Every IT class in this repo extends [`BaseIntegrationTest`](../backend/src/test/java/org/fabt/BaseIntegrationTest.java). The base class starts **one** `postgres:16-alpine` container per JVM (static initializer at line 31), and every test class reuses it. This avoids the 5-second container-startup cost per class.

The container's JDBC URL is injected into Spring's `DataSource` via `@DynamicPropertySource` at line 44. Flyway runs all migrations against this fresh DB once at boot. Subsequent tests use the same schema; per-test isolation comes from creating fresh tenants/users with random UUIDs (see exemplar below).

### Exemplar to read

[`backend/src/test/java/db/migration/V97MigrationIntegrationTest.java`](../backend/src/test/java/db/migration/V97MigrationIntegrationTest.java)

This test pins three invariants of a Flyway migration that backfills `dv_policy_enabled = true` on tenants that own at least one DV shelter:

1. **Backfill correctness** — affected tenants land on `true`.
2. **No incorrect spread** — unaffected tenants don't get the key at all.
3. **Idempotency** — re-running the migration produces identical state.

What makes it a good template:

- **The test loads the migration SQL from the classpath at runtime** (lines 80–93). It does not copy the SQL into a Java string. If the production migration file changes, this test runs the new SQL automatically — no drift possible.
- **A 4-state fixture matrix** covers every meaningful migration input (lines 98–101): tenant with *active* DV shelters, tenant with *inactive-only* DV shelters, tenant with *non-DV* shelters only, tenant with *zero* shelters. The migration's WHERE clause is verified in every direction at once.
- **Each test creates its own tenants with UUID-suffixed slugs** (lines 105–111). No reliance on the `dev-coc-*` seed tenants. Tests can be reordered, re-run, or run in parallel without interference. This is the [`feedback_isolated_test_data`](memory link) discipline.
- **RLS context discipline is documented in the class Javadoc** (lines 46–61). FABT's `shelter` table has Row-Level Security; an INSERT outside a tenant context silently fails. The doc explains *which* setup code needs `TenantContext.runWithContext(tenantId, dvAccess=true, ...)` and *why*, with citations.
- **An idempotency test AND a non-clobber test** (lines 178 + 188). These are the migration tests that are usually missing. The migration could be perfectly correct on first run and still clobber existing JSONB keys on second run, or vice versa. Both directions need pinning.
- **Helpers are thin, named for intent, and have Javadoc** (lines 211–248: `runV97For`, `readDvPolicyKey`, `clearDvPolicyKey`).

### Skeleton

```java
@DisplayName("V<N> <what the migration does>")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V<N>MigrationIntegrationTest extends BaseIntegrationTest {

    private static final String V<N>_SQL = loadMigrationSql();

    private static String loadMigrationSql() {
        try (var stream = V<N>MigrationIntegrationTest.class.getResourceAsStream(
                "/db/migration/V<N>__<name>.sql")) {
            if (stream == null) throw new IllegalStateException("not found");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load V<N> SQL", e);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthHelper authHelper;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = authHelper.setupSecondaryTenant("v<N>-a-" + suffix).getId();
        tenantB = authHelper.setupSecondaryTenant("v<N>-b-" + suffix).getId();
        // ...prime fixture state...
    }

    @Test
    void affectedTenantBackfills() {
        runMigrationFor(tenantA);
        assertThat(readMigratedColumn(tenantA)).isEqualTo("expected-value");
    }

    @Test
    void unaffectedTenantIsNotModified() {
        runMigrationFor(tenantB);
        assertThat(readMigratedColumn(tenantB)).isNull();
    }

    @Test
    void migrationIsIdempotent() {
        runMigrationFor(tenantA);
        String first = readMigratedColumn(tenantA);
        runMigrationFor(tenantA);
        assertThat(readMigratedColumn(tenantA)).isEqualTo(first);
    }

    private void runMigrationFor(UUID tenantId) {
        TenantContext.runWithContext(tenantId, true, () -> jdbc.execute(V<N>_SQL));
    }
    private String readMigratedColumn(UUID tenantId) { /* ... */ }
}
```

### How to run

Same Maven command — Surefire treats it as a normal test:

```bash
cd finding-a-bed-tonight/backend
mvn test -Dtest=V97MigrationIntegrationTest
```

First run downloads `postgres:16-alpine` if you don't already have it (~150MB, ~30s on a typical home connection). Subsequent runs reuse the cached image.

### Common pitfalls

- **Forgetting RLS context.** If you `INSERT INTO shelter (...)` from a test without wrapping it in `TenantContext.runWithContext(...)`, the row goes in but is invisible to your assertions because RLS hides it. Use [`fabt` owner role rather than `fabt_app`](https://github.com/anthropics/claude-code/) when diagnosing — `fabt_app` is the RLS-restricted role.
- **Depending on seed-data tenants.** The `dev-coc`, `dev-coc-west`, `dev-coc-east` tenants exist in test DBs but their state is shared across the whole test run. Mutating them in one test breaks another test that ran first. Always `setupSecondaryTenant("<unique-slug>")`.
- **Copy-pasting migration SQL into a Java string.** The whole point of a migration IT is to test the actual migration file. Load it from the classpath.
- **Asserting only the happy path of a migration.** Migrations need three assertions minimum: (1) it does the right thing, (2) it doesn't do the wrong thing, (3) running it twice is safe.
- **Using `@Transactional` to "clean up" state.** Spring's `@Transactional` test rollback doesn't play well with how Flyway runs migrations. Prefer creating fresh-per-test fixtures.

---

## 4. Karate API tests

### What this is

A **black-box test of the running HTTP API**. Karate is a tiny domain-specific language for writing API contract tests in `.feature` files (Gherkin-flavored). It speaks real HTTP at a real server, asserts on real JSON responses, and knows nothing about Spring, Postgres, or Java classes.

You use it when:

- You added a new REST endpoint and want to pin its contract from the outside.
- You changed an endpoint's response shape and want to be sure no client breaks.
- You want a fast-fail "is the API up and behaving sanely?" check for CI.
- You're defending a security invariant that lives at the API boundary (the `dv-access-control.feature` test halts the entire CI pipeline if any DV shelter ever appears in a public response).

### Exemplar to read

[`e2e/karate/src/test/java/features/dv-access/dv-access-control.feature`](../e2e/karate/src/test/java/features/dv-access/dv-access-control.feature)

This is the canary that runs first in CI. If any of its scenarios fail, the entire E2E pipeline halts — a DV shelter appearing in a public query is a data-protection failure.

What makes it a good template:

- **The header comment says what's at stake** (lines 3–5). Anyone modifying this file knows they're touching a blocking gate.
- **One invariant per scenario** — five scenarios, each verifying the *absence* of DV data on a different API surface (bed search, shelter list, direct GET, HSDS export, and a role-without-flag check). Five surfaces × one rule = the rule pinned everywhere.
- **Asserts on absence with `karate.filter(...) == '#[0]'`** (line 18). This is the Karate idiom for "no element in this list matches the predicate." Both the UUID and the human-readable name are checked (lines 17 + 20) — defends against a regression that renames the shelter but keeps the UUID, and vice versa.
- **"404 not 403" is a deliberate assertion** (lines 31–36 with the inline comment). `403 Forbidden` would confirm the resource exists, which is the leak being defended against. The comment names the rule so future readers don't "fix" it to 403.
- **The last scenario builds its own test user inline** (lines 45–76) instead of trusting seed assumptions. The seed `cocadmin` user has `dvAccess=true` because the DV escalation queue needs it — this test creates a `COC_ADMIN` with `dvAccess=false` to prove that the role alone isn't the gate; the flag is.
- **Per-run-unique email** (line 54: `'dv-canary-noaccess-' + java.util.UUID.randomUUID() + '@...'`) prevents the `(tenant_id, email)` UNIQUE constraint from biting on re-runs.

### Skeleton

```gherkin
Feature: <What this API contract pins>

  <One-sentence statement of what failure of this test means in production terms.>

  Background:
    * url baseUrl
    * def someConstant = '0000-0000-0000-...'

  Scenario: <Single contract under test, in plain English>
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/your-endpoint'
    And request { "field": "value" }
    When method POST
    Then status 200
    And match response.someField == 'expected'

  Scenario: <Negative case proving the rule rejects the wrong input>
    * configure headers = { Authorization: '#(outreachAuthHeader)' }
    Given path '/api/v1/your-endpoint'
    And request { "field": "bad-value" }
    When method POST
    Then status 400
```

### Auth setup

The `outreachAuthHeader`, `adminAuthHeader`, `tenantSlug`, `loginUrl`, and `baseUrl` variables come from `e2e/karate/src/test/java/karate-config.js` — Karate loads it once per run and exposes the variables to every feature. To use a custom user, log in inline (see lines 63–67 of the DV exemplar).

### How to run

Karate runs against whatever's at `baseUrl`. You need the backend running. The simplest local setup:

```bash
# Terminal 1 — bring up the backend + nginx
cd finding-a-bed-tonight
./dev-start.sh --nginx          # nginx proxy on :8081, NOT the default :5173

# Terminal 2 — run Karate against it
cd finding-a-bed-tonight/e2e/karate
mvn test                                                       # whole suite
mvn test -Dkarate.options="--name DV-access"                   # one feature by tag/name
mvn test -Dkarate.options="classpath:features/auth/login.feature"   # one file
```

The Karate runner is the `karate-junit5` engine; results appear in `e2e/karate/target/karate-reports/karate-summary.html`.

### Common pitfalls

- **Trusting seed data.** The same rule as integration tests: if your scenario assumes "user X exists with role Y and flag Z", another scenario can mutate that. Create your own user inline with a UUID-suffixed email.
- **Asserting only on status codes.** `Then status 200` tells you the call returned. It tells you nothing about the body. Add `match response.field == 'expected'` for any field that matters to the contract.
- **Forgetting to URL-encode IDs in paths.** Karate's `path '/api/v1/shelters', dvShelterId` handles this for you — use that form, not string concatenation.
- **Running Karate against bare Vite (`:5173`).** Karate needs the backend at `:8080` or the nginx proxy at `:8081`. The dev-start `--nginx` flag is the safe default — it's what CI runs against (see [`feedback_check_ports_before_assuming.md`](../docs/) memory).
- **`Background` doing too much.** If every scenario re-runs an expensive setup, the suite gets slow. Push that to `karate-config.js` (once-per-run) or into a single `Scenario:` that other scenarios link to.

---

## Decision tree: which kind of test should I write?

```
Is the thing I'm testing pure logic in one class?
├── YES  → Unit test (#1)
└── NO   → Does it need the Spring context to be real?
          ├── NO   → Probably still a unit test — try harder to inject deps
          └── YES  → Does it need a real Postgres / real Flyway / real RLS?
                    ├── YES  → Does any boundary make it untestable as-is
                    │         (e.g. SSRF guard blocks localhost, real upstream is a network service)?
                    │         ├── YES  → Integration test with mock (#2)
                    │         └── NO   → Integration test with Testcontainers (#3)
                    └── NO   → Are you testing the REST API contract from outside?
                              ├── YES  → Karate (#4)
                              └── NO   → Re-read this tree — you probably want #1 or #3
```

Most days you'll write more #1 than anything else. Reach for #2 only when an SSRF guard or a network dependency forces it. Reach for #3 whenever you're touching SQL or RLS. Reach for #4 when you're shipping a new endpoint or pinning an API security rule.

---

## Cheat sheet

```bash
# Run one backend test (unit or integration)
cd finding-a-bed-tonight/backend
mvn test -Dtest=ClassName
mvn test -Dtest=ClassName#methodName

# Run all backend tests
mvn test

# Run a specific Karate feature against a running stack
cd finding-a-bed-tonight
./dev-start.sh --nginx                # Terminal 1
cd e2e/karate && mvn test -Dkarate.options="classpath:features/auth/login.feature"  # Terminal 2

# Stop the dev stack when done
cd finding-a-bed-tonight && ./dev-start.sh stop
```

```java
// Annotations you'll use most
@ExtendWith(MockitoExtension.class)  // unit tests
@Mock                                 // unit-test mocks
@SpringBootTest                       // integration tests (inherited via BaseIntegrationTest)
@MockitoBean                          // surgical bean replacement (NOT @MockBean — deprecated)
@TestPropertySource                   // per-class config overrides
@DisplayName("Human-readable name")   // shows up in test reports
@BeforeEach / @AfterEach              // per-test setup/teardown

// Assertions — use AssertJ
import static org.assertj.core.api.Assertions.assertThat;
assertThat(actual).as("why this rule matters").isEqualTo(expected);

// Mocking
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
when(mock.thing(any())).thenReturn(value);
verify(mock, times(1)).thingShouldBeCalledOnce();
```

---

## Where to go next

- Read [`docs/FOR-DEVELOPERS.md`](FOR-DEVELOPERS.md) for the broader architecture.
- Read [`backend/src/test/java/org/fabt/BaseIntegrationTest.java`](../backend/src/test/java/org/fabt/BaseIntegrationTest.java) to understand how Testcontainers is wired into every IT class.
- Read [`backend/src/test/java/org/fabt/TestAuthHelper.java`](../backend/src/test/java/org/fabt/TestAuthHelper.java) to see how to create tenants, users, and authenticated request headers in any IT.
- Skim a handful of existing `*Test.java` and `*IntegrationTest.java` files near the code you're about to change. The patterns in your area will be more specific than this general guide.

When in doubt, ask in the warroom channel or open a draft PR and tag a senior — both are cheaper than guessing.
