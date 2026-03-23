# Operational Runbook — Finding A Bed Tonight

## Service Overview

| Field | Value |
|-------|-------|
| **Service** | finding-a-bed-tonight |
| **Owner** | Platform team |
| **Repo** | github.com/ccradle/finding-a-bed-tonight |
| **Tech stack** | Java 21, Spring Boot 3.4, PostgreSQL 16, React PWA |
| **Deployment tiers** | Lite (JAR + PG), Standard (+Redis), Full (+Kafka) |
| **Default port** | 8080 (application), 9091 (management, when observability profile active) |
| **Dashboard** | Grafana → FABT Operations (`:3000` dev, production varies) |
| **Logs** | Structured JSON (Logstash encoder), fields: timestamp, level, tenantId, userId, traceId, spanId |

---

## Health Checks

### Endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /actuator/health/liveness` | None | Is the JVM running? Used by load balancer / k8s liveness probe |
| `GET /actuator/health/readiness` | None | Is PostgreSQL reachable and migrations complete? Used by k8s readiness probe |
| `GET /actuator/health` | Authenticated | Full health with component details (db, diskSpace) |

### Investigation: Health check failing

1. **Liveness failing** — JVM is down or unresponsive. Check container/process status. Check for OOM kills (`dmesg | grep -i oom`). Review logs for fatal errors.
2. **Readiness failing** — PostgreSQL unreachable. Check `pg_isready`, connection pool (`HikariCP` logs), network path, firewall rules. Check if Flyway migration is stuck.
3. **Both passing but app unresponsive** — Thread exhaustion or deadlock. Check `/actuator/metrics/jvm.threads.live`. Review thread dump.

---

## Startup & Shutdown

### Starting the stack (dev)

```bash
./dev-start.sh                     # PostgreSQL + backend + frontend
./dev-start.sh --observability     # + Prometheus, Grafana, Jaeger, OTel Collector
./dev-start.sh backend             # No frontend
./dev-start.sh stop                # Stops everything including observability containers
```

When `--observability` is used, the backend starts with `management.server.port=9091` so Prometheus can scrape without JWT auth.

### Startup sequence

1. PostgreSQL starts, waits for `pg_isready`
2. Observability containers start (if `--observability`)
3. Backend compiles and starts (`mvn spring-boot:run`)
4. Flyway runs migrations
5. Health check waits for liveness on management port (9091) or app port (8080)
6. `fabt_app` role granted permissions, seed data loaded
7. Frontend starts (if not `backend` mode)
8. Grafana health wait (if `--observability`)

### Investigation: Backend won't start

1. **Port conflict** — Check `netstat -tlnp | grep 8080` (or 9091). Kill conflicting process.
2. **Flyway migration failure** — Check logs for `FlywayException`. May need manual DB fix. Never delete migration files.
3. **Bean creation failure** — Check for missing dependencies in logs. Usually a config issue.
4. **HikariCP connection timeout** — PostgreSQL not reachable or pool exhausted. Check `spring.datasource.url` and `hikari.maximum-pool-size`.

---

## Admin UI Configuration

### Observability Tab (Admin Panel → Observability)

The Observability tab allows PLATFORM_ADMIN users to configure monitoring at runtime without restart.

| Setting | Default | Effect |
|---------|---------|--------|
| **Prometheus Metrics** | ON | Enables/disables custom metric collection |
| **OpenTelemetry Tracing** | OFF | When ON, API requests produce OTel spans exported to OTLP endpoint |
| **OTLP Endpoint** | `http://localhost:4318/v1/traces` | Where spans are sent. Change to OTel Collector address in production |
| **Stale shelter check interval** | 5 min | How often the stale shelter monitor runs |
| **DV canary check interval** | 15 min | How often the DV canary monitor runs |
| **Temperature check interval** | 60 min | How often NOAA temperature is polled |
| **Surge activation threshold** | 32°F | Temperature below which a missing surge triggers a warning |

**Impact of toggling tracing ON:**
- Spans are generated for every API request and exported to the configured OTLP endpoint
- If no OTLP collector is listening, spans are silently dropped (no error, no performance impact beyond span creation)
- Traces appear in Jaeger (`:16686`) within seconds
- Sampling is 100% (appropriate for pilot scale; adjust for production volume)

**Impact of toggling tracing OFF:**
- Sampling probability drops to 0.0 — zero overhead, no spans created
- Existing traces in Jaeger remain visible but no new ones are added

**Config refresh:** Changes take effect within 60 seconds (ObservabilityConfigService cache refresh interval).

---

## Operational Monitors

### Monitor 1: Stale Shelter Detection

**Metric:** `fabt.shelter.stale.count` (gauge)
**Schedule:** Every 5 minutes (configurable)
**Log level:** WARNING
**Severity:** Medium

**What it means:** One or more shelters have not published an availability snapshot in 8+ hours. Outreach workers may be seeing stale data.

**Investigation:**
1. Check the WARNING log for the specific shelter IDs and last update times
2. Verify the shelter coordinator is still active (check app_user table)
3. Check if the shelter's availability API endpoint is reachable
4. Review recent bed_availability table entries: `SELECT * FROM bed_availability WHERE shelter_id = '<id>' ORDER BY snapshot_ts DESC LIMIT 5;`

**Resolution:**
- Contact the shelter coordinator to confirm operations
- If the shelter is temporarily closed, update its status
- If the coordinator is unresponsive, escalate to the CoC admin
- For systematic issues (many shelters stale), check if backend is processing availability updates correctly

### Monitor 2: DV Canary Check

**Metric:** `fabt.dv.canary.pass` (gauge: 1=pass, 0=fail)
**Schedule:** Every 15 minutes (configurable)
**Log level:** CRITICAL on failure
**Severity:** Critical — privacy/safety violation

**What it means:** A non-DV user query returned a DV (domestic violence) shelter in search results. This is a **privacy/safety violation** — DV shelters must never appear in non-DV search results.

**Investigation:**
1. Check the CRITICAL log for the specific DV shelter ID and tenant
2. Verify RLS policies are active: `SELECT * FROM pg_policies WHERE tablename = 'shelter';`
3. Check the shelter's `dv_shelter` flag: `SELECT id, name, dv_shelter FROM shelter WHERE id = '<id>';`
4. Test the bed search API manually as a non-DV user
5. Review recent deployments or migrations that may have affected RLS

**Resolution:**
- **IMMEDIATE:** If RLS is broken, take the search API offline until fixed
- Verify `fabt_app` role has correct RLS context setting
- Check that `TenantMdcFilter` is setting DV access correctly
- Run the full e2e DV access control test suite
- Post-incident: document root cause and add regression test

**Rollback:** If caused by a recent deployment, revert to the previous version immediately. DV data exposure is a safety incident.

### Monitor 3: Temperature/Surge Gap Detection

**Metric:** `fabt.temperature.surge.gap` (gauge: 1=gap detected, 0=no gap)
**Schedule:** Every hour (configurable via Admin UI)
**Log level:** WARNING
**Default station:** KRDU (Raleigh-Durham, NC)
**Severity:** Medium — potential safety concern in freezing weather

**What it means:** The NOAA API reports ambient temperature below the configured threshold (default 32°F) at the pilot city location, but no surge event is active. During freezing weather, unsheltered individuals face hypothermia risk and surge mode should typically be activated.

**Configuration:**
- Temperature threshold: Admin UI → Observability → "Surge activation threshold"
- Polling frequency: Admin UI → Observability → "Temperature polling interval"
- Current status: Admin UI → Observability → Temperature Status section (live display)
- API: `GET /api/v1/monitoring/temperature` returns cached status

**Investigation:**
1. Check the WARNING log for the reported temperature, threshold, and tenant
2. Check the Admin UI Observability tab for current temperature and gap status
3. Verify the NOAA station ID is correct for the pilot city
4. Check if a surge was recently deactivated (intentional)
5. Confirm the temperature reading is accurate (cross-reference with weather.gov)

**Resolution:**
- Contact the CoC admin to determine if surge mode should be activated
- If weather is expected to improve shortly, the warning may be informational
- If the threshold needs adjustment for the local climate, update via Admin UI → Observability → Surge activation threshold
- If the NOAA API is returning bad data, check circuit breaker status via `resilience4j_circuitbreaker_state{name="noaa-api"}`

---

## Circuit Breakers

Two Resilience4J circuit breakers protect external API calls.

| Breaker | Target | Window | Failure Threshold | Wait (open) |
|---------|--------|--------|-------------------|-------------|
| `noaa-api` | NOAA Weather API | 10 calls | 50% | 60s |
| `webhook-delivery` | Webhook callback URLs | 10 calls | 50% | 30s |
| `fabt-jwks-endpoint` | Keycloak JWKS | 5 calls | 50% | 10s |

**States:** CLOSED (normal) → OPEN (failures exceeded threshold) → HALF_OPEN (testing recovery)

**Investigation: Circuit breaker OPEN**
1. Check `resilience4j_circuitbreaker_state` in Prometheus/Grafana
2. For `noaa-api`: Check if api.weather.gov is reachable. Check NOAA status page. The monitor gracefully degrades — returns null, skips temperature check.
3. For `webhook-delivery`: Check the callback URL is responding. Check subscription status. Failed deliveries are logged with the error.

**Resolution:** Circuit breakers auto-recover after the wait duration. If the external service is permanently down, the breaker will cycle between OPEN and HALF_OPEN. No manual intervention needed — the application continues to function.

---

## Grafana Dashboard

When running with `--profile observability`, the FABT Operations dashboard is available at http://localhost:3000 (admin/admin).

**Panels:**
- Bed Search Rate — queries per minute by population type
- Bed Search Latency (p99) — 99th percentile response time
- Availability Updates — snapshot creation rate by shelter
- Reservation Lifecycle — create/confirm/cancel/expire rates
- Stale Shelter Count — current gauge value (green/yellow/red thresholds)
- DV Canary Status — PASS/FAIL indicator
- Surge Active — ACTIVE/INACTIVE indicator
- Temperature/Surge Gap — OK/GAP DETECTED indicator
- Webhook Delivery Rate — success/failure rates
- Circuit Breaker State — CLOSED/OPEN/HALF_OPEN for each breaker

**No data showing?** Check time range selector (top right) — set to "Last 15 minutes". Verify Prometheus target is UP at http://localhost:9090/targets.

---

## Prometheus Queries

| Metric | PromQL |
|--------|--------|
| Bed search rate | `rate(fabt_bed_search_count_total[1m])` |
| Bed search p99 latency | `histogram_quantile(0.99, rate(fabt_bed_search_duration_seconds_bucket[5m]))` |
| Availability update rate | `rate(fabt_availability_update_count_total[1m])` |
| Reservation rate by status | `rate(fabt_reservation_count_total[1m])` |
| Stale shelters | `fabt_shelter_stale_count` |
| DV canary | `fabt_dv_canary_pass` |
| Surge active | `fabt_surge_active` |
| Temperature/surge gap | `fabt_temperature_surge_gap` |
| Webhook success rate | `rate(fabt_webhook_delivery_count_total{status="success"}[1m])` |
| Webhook failure rate | `rate(fabt_webhook_delivery_count_total{status="failure"}[1m])` |
| Circuit breaker state | `resilience4j_circuitbreaker_state` |
| JVM heap used | `jvm_memory_used_bytes{area="heap"}` |
| HikariCP active connections | `hikaricp_connections_active` |
| HTTP request rate | `rate(http_server_requests_seconds_count[1m])` |

---

## Tracing (Jaeger)

**URL:** http://localhost:16686 (dev), production varies

### Viewing traces

1. Service dropdown → `finding-a-bed-tonight`
2. Click **Find Traces**
3. Click any trace to see the span waterfall

### What to look for

- **Service name** should be `finding-a-bed-tonight` (in the `processes` section)
- **Error spans** show in red — typically 401/403 auth failures or 500 server errors
- **Expected errors:** Prometheus scrape attempts on `:8080` without auth produce `AccessDeniedException` traces — these are harmless (Prometheus scrapes `:9091` instead)
- **Slow spans** — look for database queries or external API calls that dominate request latency

### Tracing not working?

1. Is tracing enabled? Check Admin UI → Observability → "OpenTelemetry Tracing" toggle
2. Is the OTLP endpoint correct? Default: `http://localhost:4318/v1/traces`
3. Is the OTel Collector running? Check `docker ps | grep otel`
4. Is Jaeger receiving data? Check `http://localhost:16686/api/services` — should list `finding-a-bed-tonight`

---

## Database

### Connection details

| Environment | Host | Port | Database | App user | Owner user |
|-------------|------|------|----------|----------|------------|
| Dev | localhost | 5432 | fabt | fabt_app | fabt |
| Test | Testcontainers | dynamic | fabt_test | fabt_test | fabt_test |

### Key tables

| Table | Purpose | RLS |
|-------|---------|-----|
| `tenant` | CoC organizations, config JSONB | No |
| `shelter` | Shelter profiles, `dv_shelter` flag | Yes |
| `bed_availability` | Append-only snapshots | Yes |
| `reservation` | Soft-hold lifecycle | Yes |
| `surge_event` | White Flag activations | Yes |
| `subscription` | Webhook subscriptions | No |

### RLS enforcement

Row Level Security prevents DV shelter data from leaking. The `fabt_app` DB role has restricted permissions (no SUPERUSER, DML only). The JDBC connection interceptor sets `fabt.tenant_id` and `fabt.dv_access` on each request.

**Verifying RLS is active:**
```sql
SELECT * FROM pg_policies WHERE tablename IN ('shelter', 'bed_availability', 'reservation', 'surge_event');
```

### Useful queries

```sql
-- Stale shelters (no snapshot in 8+ hours)
SELECT s.id, s.name, MAX(ba.snapshot_ts) as last_snapshot
FROM shelter s LEFT JOIN bed_availability ba ON s.id = ba.shelter_id
GROUP BY s.id, s.name
HAVING MAX(ba.snapshot_ts) < NOW() - INTERVAL '8 hours' OR MAX(ba.snapshot_ts) IS NULL;

-- Active reservations about to expire
SELECT id, shelter_id, expires_at, EXTRACT(EPOCH FROM (expires_at - NOW())) as seconds_remaining
FROM reservation WHERE status = 'HELD' ORDER BY expires_at;

-- Tenant observability config
SELECT id, name, config->'observability' as obs_config FROM tenant;
```

---

## Bed Availability Invariants

The `bed_availability` table is the **single source of truth** for bed counts. There is no separate capacity table — `beds_total`, `beds_occupied`, and `beds_on_hold` all live in append-only snapshots.

### The 9 invariants

| ID | Rule | Description |
|----|------|-------------|
| INV-1 | `beds_available >= 0` | Available beds can never be negative |
| INV-2 | `beds_occupied <= beds_total` | Occupied cannot exceed total |
| INV-3 | `beds_on_hold <= (beds_total - beds_occupied)` | Holds cannot exceed remaining capacity |
| INV-4 | `beds_total >= 0` | Total beds cannot be negative |
| INV-5 | `beds_occupied + beds_on_hold <= beds_total` | Combined occupied + held cannot exceed total |
| INV-6 | Confirm does not change available | Confirming a hold converts hold→occupied |
| INV-7 | Cancel increases available by 1 | Cancelling a hold releases the bed |
| INV-8 | Hold decreases available by 1 | Placing a hold reserves a bed |
| INV-9 | `beds_available == beds_total - beds_occupied - beds_on_hold` | The formula always holds |

### Enforcement

- **Server-side**: `AvailabilityService.createSnapshot()` validates INV-1 through INV-5 before writing. Returns 422 on violation.
- **Coordinator hold protection**: PATCH availability cannot reduce `beds_on_hold` below active HELD reservation count.
- **Concurrent holds**: PostgreSQL advisory locks (`pg_advisory_xact_lock`) prevent double-holds on the last bed.
- **UI bounds**: Coordinator dashboard enforces stepper bounds; on-hold is read-only (system-managed).

### Investigation: Wrong available count

1. Check the latest snapshot: `SELECT * FROM bed_availability WHERE shelter_id = '<id>' ORDER BY snapshot_ts DESC LIMIT 5;`
2. Verify `beds_available = beds_total - beds_occupied - beds_on_hold` in the snapshot
3. Check for active held reservations: `SELECT COUNT(*) FROM reservation WHERE shelter_id = '<id>' AND status = 'HELD';`
4. If values don't match, the invariant checker in tests (`AvailabilityInvariantChecker`) can help diagnose

---

## Management Port Security (Production)

The `/actuator/prometheus` endpoint on the main application port (`:8080`) requires JWT authentication — it is **not** `permitAll()`. This is intentional: FABT handles DV shelter data and must not expose business metrics publicly.

### Dev mode (`--observability`)

When started with `./dev-start.sh --observability`, the backend runs actuator endpoints on a **separate management port** (`:9091`). A conditional security config (`ManagementSecurityConfig`) allows unauthenticated access on this port only, so Prometheus can scrape without JWT tokens. The main API on `:8080` remains fully secured.

### Production deployment

In production, the management port **must be secured at the network level**:

1. **Bind to localhost only:**
   ```yaml
   management:
     server:
       port: 9091
       address: 127.0.0.1
   ```
2. **Firewall rules:** Only allow the monitoring stack (Prometheus, internal health checks) to reach port 9091. Block all external access.
3. **Do NOT expose port 9091** in load balancer, reverse proxy, or public DNS.
4. **If using Kubernetes:** Use a `NetworkPolicy` to restrict access to the management port to the monitoring namespace only.

The management port exposes: `/actuator/health`, `/actuator/prometheus`, `/actuator/info`, `/actuator/metrics`. None of these should be reachable from the public internet.

---

## OAuth2 / Keycloak Troubleshooting

### All authenticated endpoints return 401 after restart
**Symptom:** Every API call returns 401, but Keycloak is running and tokens look valid.
**Cause:** JWKS circuit breaker opened during startup because Keycloak realm wasn't ready when the backend started (Portfolio Lesson 37).
**Investigation:**
1. Check `resilience4j_circuitbreaker_state{name="fabt-jwks-endpoint"}` — if OPEN, this is the issue
2. Check startup logs for "JWKS warmup" messages
3. Verify Keycloak realm is ready: `curl http://localhost:8180/realms/fabt-dev/.well-known/openid-configuration`

**Resolution:** The circuit breaker has `automatic-transition-from-open-to-half-open-enabled: true`, so it will self-recover after 10 seconds. If it doesn't, restart the backend after Keycloak is fully ready.

### "No account found" error on OAuth2 login
**Symptom:** User authenticates with Google/Microsoft successfully but gets rejected.
**Cause:** Closed registration — user's email doesn't match any pre-provisioned account in the tenant.
**Resolution:** CoC admin must create the user account first (Admin Panel → Users → Create User with matching email). Then the OAuth2 login will auto-link.

### SSO buttons don't appear on login page
**Symptom:** Login page shows only email/password, no "Sign in with Google" buttons.
**Investigation:**
1. Check if providers are configured: `GET /api/v1/tenants/{slug}/oauth2-providers/public`
2. Check if the tenant slug is entered correctly on the login page
3. Check if the provider is enabled (may have been disabled by admin)

### Token issuer mismatch
**Symptom:** JWT validation fails with "Invalid issuer" after Docker Compose restart.
**Cause:** `KC_HOSTNAME_URL` not set — Keycloak issues tokens with different `iss` depending on how it's accessed (localhost vs container name). Portfolio Lesson 61.
**Resolution:** Ensure `KC_HOSTNAME_URL: http://keycloak:8080` is set in docker-compose.yml.

---

## Common Issues

### Webhook delivery failures
**Symptom:** `fabt.webhook.delivery.count{status="failure"}` increasing
**Cause:** Callback URL unreachable, SSL certificate issues, or 410 Gone (permanent deactivation)
**Resolution:** Check subscription callback URLs. 410 responses auto-deactivate the subscription. Other failures are logged with the error. Retry (with exponential backoff) is planned but not yet implemented.

### Reservation expiry not firing
**Symptom:** Reservations stuck in HELD status past their `expires_at` time
**Cause:** `ReservationExpiryService` @Scheduled task may have stopped (thread pool exhaustion) or backend restarted
**Resolution:** Check if the backend is running. Expired reservations will be cleaned up on next scheduled run (every 30s). Verify with: `SELECT * FROM reservation WHERE status = 'HELD' AND expires_at < NOW();`

### High bed search latency
**Symptom:** `fabt_bed_search_duration_seconds` p99 > 500ms
**Cause:** Cache miss (first request after eviction), large tenant with many shelters, PostgreSQL under load
**Resolution:** Check cache hit rate. Check `hikaricp_connections_active` for pool saturation. Check PostgreSQL `pg_stat_activity` for long-running queries.

### JWT token expiry in tests
**Symptom:** Playwright/Karate tests fail with 401 after running for 15+ minutes
**Cause:** Access tokens have 15-minute lifespan. Cached auth state file contains expired token.
**Resolution:** The auth fixture (`auth.fixture.ts`) checks JWT expiry and re-authenticates automatically. If stale state persists, delete `e2e/playwright/auth/` directory. See Portfolio Lesson 42.
