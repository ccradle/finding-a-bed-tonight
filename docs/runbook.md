# Operational Runbook — Finding A Bed Tonight

## Service Overview

| Field | Value |
|-------|-------|
| **Service** | finding-a-bed-tonight |
| **Owner** | Platform team |
| **Repo** | github.com/ccradle/finding-a-bed-tonight |
| **Tech stack** | Java 25, Spring Boot 4.0, PostgreSQL 16, React PWA, Virtual Threads |
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
./dev-start.sh --fresh             # Reset seed data before loading (use when shelter structure changes)
./dev-start.sh backend             # No frontend
./dev-start.sh stop                # Stops everything including observability containers
```

### Resetting seed data

When shelter structure changes (new shelters, renamed shelters, changed IDs), run with `--fresh` to clear old seed data before reloading. This runs `infra/scripts/seed-reset.sql` which deletes all seed-loaded data (shelters, availability, activity, batch history) while preserving users, tenant config, and OAuth2 providers. Without `--fresh`, seed-data.sql uses `ON CONFLICT DO NOTHING` and old shelters persist alongside new ones.

```bash
./dev-start.sh --fresh --frontend            # Full fresh reload
./dev-start.sh --fresh --observability       # Fresh reload + observability stack
# Or manually:
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-reset.sql
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/seed-data.sql
docker compose exec -T postgres psql -U fabt -d fabt < infra/scripts/demo-activity-seed.sql
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

**What it means:** The NOAA API reports ambient temperature below the configured threshold (default 32°F) at the configured city location, but no surge event is active. During freezing weather, unsheltered individuals face hypothermia risk and surge mode should typically be activated.

**Configuration:**
- Temperature threshold: Admin UI → Observability → "Surge activation threshold"
- Polling frequency: Admin UI → Observability → "Temperature polling interval"
- Current status: Admin UI → Observability → Temperature Status section (live display)
- API: `GET /api/v1/monitoring/temperature` returns cached status

**Investigation:**
1. Check the WARNING log for the reported temperature, threshold, and tenant
2. Check the Admin UI Observability tab for current temperature and gap status
3. Verify the NOAA station ID is correct for the configured city
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

### DV Referral Dashboard

A separate **FABT DV Referrals** dashboard is provisioned alongside the operations dashboard. It is intentionally separate — DV referral volume patterns are sensitive even in aggregate and may require different Grafana access controls.

For the full DV referral architecture, legal basis (VAWA/FVPSA), and VAWA self-assessment checklist, see [docs/DV-OPAQUE-REFERRAL.md](DV-OPAQUE-REFERRAL.md). For a visual walkthrough, see the [DV Referral Demo](https://ccradle.github.io/findABed/demo/dvindex.html).

**Panels:**
- Referral Request Rate — incoming referral volume
- Acceptance Rate (%) — are shelters accepting? Below 50% warrants investigation
- Avg Response Time — minutes from request to shelter response. Above 2 hours suggests shelters aren't monitoring
- Rejection Rate — rejection volume (normal if shelters are at capacity)
- Expired Rate — high rate means shelters aren't responding before token expiry. **Operational alert threshold: >20% expiry rate**
- Referral Totals — cumulative counts by status

**Investigation: high expiry rate**
1. Check if DV shelter coordinators are assigned and active
2. Verify coordinators have `dvAccess=true` on their user profile
3. Check if the shelter's notification mechanism is working (event bus logs)
4. Consider adjusting `dv_referral_expiry_minutes` in tenant config if 4 hours is too short

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

## DV Opaque Referral Operations

### Token Expiry

`ReferralTokenService.expireTokens()` runs every 60 seconds. PENDING tokens past their `expires_at` are marked EXPIRED. Default expiry: 240 minutes (4 hours), configurable per tenant via `dv_referral_expiry_minutes` in tenant config JSONB.

To change expiry for a tenant:
```bash
curl -X PUT http://localhost:8080/api/v1/tenants/<id>/observability \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"dv_referral_expiry_minutes": 120}'
```

### Token Purge

`ReferralTokenPurgeService.purgeTerminalTokens()` runs every hour. Hard-deletes tokens in terminal state (ACCEPTED/REJECTED/EXPIRED) older than 24 hours. Monitor via log:
```
Purged N DV referral tokens (terminal state older than 24h)
```

**If purge stops running:** Tokens accumulate but have no security impact (they contain zero PII). Restart the backend to resume. Check for scheduler thread exhaustion in logs.

### RLS Defense-in-Depth (D14)

Every JDBC connection executes `SET ROLE fabt_app` to enforce RLS. Additionally, `ReferralTokenService.createToken()` checks `TenantContext.getDvAccess()` independently. If either layer fails, the other blocks unauthorized DV access.

**Verify RLS is enforcing:** Check backend startup logs for `SET ROLE fabt_app` execution. If RLS is misconfigured, DV shelter data may leak — this is a CRITICAL incident.

### Address Visibility Policy

DV shelter addresses are redacted in API responses based on `dv_address_visibility` in tenant config. Default: `ADMIN_AND_ASSIGNED`. Change via:

```bash
PUT /api/v1/tenants/{id}/dv-address-policy
Header: X-Confirm-Policy-Change: CONFIRM
Body: {"policy": "ADMIN_AND_ASSIGNED"}
```

**This endpoint should NOT be exposed outside the corporate firewall.** Requires PLATFORM_ADMIN role. Policy changes are logged at WARN level.

Valid policies: `ADMIN_AND_ASSIGNED` (default), `ADMIN_ONLY`, `ALL_DV_ACCESS`, `NONE`.

---

## HMIS Bridge Operations

### Push Schedule

`HmisPushScheduler` runs every hour. For each tenant with enabled HMIS vendors, it creates outbox entries and processes them. Push frequency per vendor is controlled by `push_interval_hours` in the vendor config.

### Outbox Lifecycle

`PENDING` → `SENT` (success) or `FAILED` (retry) → `DEAD_LETTER` (after 3 failures)

Dead letter entries are visible in the Admin UI (HMIS Export tab) and can be retried manually.

### DV Shelter Data

DV shelter bed counts are **aggregated** before push — individual DV shelter occupancy is never sent to HMIS vendors. This prevents small-n inference that could identify survivors.

### Monitoring

- **Grafana HMIS Bridge dashboard** (observability-dependent): push rate, failure rate, latency, circuit breaker state, dead letter count
- **Log search**: `HmisPushService` logs at INFO on success, ERROR on failure

### Investigation: High failure rate

1. Check circuit breaker state in Grafana — is the vendor endpoint down?
2. Check dead letter entries in Admin UI — what error messages?
3. Verify vendor API credentials in tenant config
4. Check vendor's status page for maintenance windows

### Investigation: Dead letters accumulating

1. View dead letter entries in Admin UI (HMIS Export tab)
2. Check error messages — common: authentication failure, rate limit, endpoint not found
3. Fix the root cause (credentials, URL, vendor downtime)
4. Retry via Admin UI or API: `POST /api/v1/hmis/retry/{outboxId}`

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

### Drift query (v0.34.0 — bed-hold-integrity)

The canonical smoking-gun query for phantom `beds_on_hold` drift (Issue #102). Run against prod or dev; expect zero rows in a healthy system:

```sql
WITH latest AS (
  SELECT DISTINCT ON (shelter_id, population_type)
    shelter_id, population_type, beds_on_hold, updated_by
  FROM bed_availability
  ORDER BY shelter_id, population_type, snapshot_ts DESC
),
held_counts AS (
  SELECT shelter_id, population_type, COUNT(*)::int AS held_count
  FROM reservation WHERE status = 'HELD'
  GROUP BY shelter_id, population_type
)
SELECT l.shelter_id, l.population_type, l.beds_on_hold, COALESCE(h.held_count, 0), l.updated_by
FROM latest l
LEFT JOIN held_counts h ON h.shelter_id = l.shelter_id AND h.population_type = l.population_type
WHERE l.beds_on_hold <> COALESCE(h.held_count, 0);
```

If this query ever returns rows in production, the reconciliation tasklet (`BedHoldsReconciliationJobConfig`, 5-min cadence) will correct them on the next cycle and write `BED_HOLDS_RECONCILED` audit rows. If the query returns rows AFTER two full reconciliation cycles (> 10 min), investigate the tasklet logs and filter for `bedHoldsReconciliation` lines.

### v0.34.0 post-deploy smoke: coordinator `/manual-hold` curl

The new `POST /api/v1/shelters/{id}/manual-hold` endpoint is gated on shelter assignment for the `COORDINATOR` role. An earlier implementation draft had a `SecurityConfig.java:172` filter rule that excluded `COORDINATOR` from `POST /shelters/**`, which silently 403'd every coordinator call before the controller ran. That bug was caught in the pre-ship smoke and fixed in v0.34.0. The runbook curl below is the regression guard for any future SecurityConfig refactor that narrows the rule again. **Run after every release that touches the bed-hold-integrity paths**; add to the standard `post-deploy-smoke-v0.XX.X.log` sequence.

```bash
# Step 1: Log in as a real coordinator with dv_access=true (dv-coordinator@dev.fabt.org
# on the demo site is the canonical test account).
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"email":"dv-coordinator@dev.fabt.org","password":"admin123","tenantSlug":"dev-coc"}' \
  https://findabed.org/api/v1/auth/login | grep -oE '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"$//')

# Step 2: POST to an ASSIGNED DV shelter — expect HTTP 201.
curl -s -w "\nHTTP=%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"populationType":"DV_SURVIVOR","reason":"post-deploy smoke"}' \
  https://findabed.org/api/v1/shelters/d0000000-0000-0000-0000-000000000011/manual-hold

# Expected:
#   {"id":"...","shelterId":"d0000000-...-000011","populationType":"DV_SURVIVOR","status":"HELD",...}
#   HTTP=201

# Step 3 (optional): POST to an UNASSIGNED shelter — expect HTTP 403.
# The 403 must come from the controller's isAssigned branch (proof: the
# fabt_http_access_denied_count_total counter in /actuator/prometheus
# increments by 1 with tag role=ROLE_COORDINATOR). If the counter does NOT
# increment, the rejection came from the filter chain — rollback immediately.
```

If Step 2 returns 403 instead of 201, the SecurityConfig fix has regressed. **Rollback to the previous release immediately and file an incident.** The live demo cannot ship with a broken coordinator path.

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

## Rate Limiting (Authentication Endpoints)

Brute force protection is enforced on `/api/v1/auth/login` and `/api/v1/auth/refresh` using bucket4j with a Caffeine-backed JCache cache. Each client IP is limited to **10 requests per 15-minute window**. Exceeding the limit returns HTTP 429 with a `Retry-After` header.

### Profile behavior

| Profile | Rate Limiting | Notes |
|---|---|---|
| `prod` (or no `lite`) | **Enabled** — 10 req / 15 min per IP | Default from `application.yml` |
| `lite` (dev, test) | **Disabled** | Karate e2e tests make 72+ login calls per run |

### Deployment checklist

- **Demo and production deployments MUST NOT use the `lite` profile** unless `bucket4j.enabled=true` is explicitly overridden. The `lite` profile disables rate limiting for development convenience.
- If a deployment requires `lite` for its deployment tier (PostgreSQL only, no Redis/Kafka), override rate limiting:
  ```bash
  java -jar app.jar --spring.profiles.active=lite --bucket4j.enabled=true
  ```
- **Verify after deployment:** Make 11 POST requests to `/api/v1/auth/login` from the same IP. The 11th must return 429. If it returns 401 instead, rate limiting is not active.

### Monitoring

The 429 responses are standard HTTP — they will appear in ALB access logs and nginx logs. bucket4j does not emit Micrometer metrics by default. To monitor rate limiting volume, check:
- Backend logs: rate-limited attempts are logged at WARN level with client IP
- Access logs: count of 429 responses per time window

### Adjusting thresholds

The rate limit is configured in `application.yml`:
```yaml
bucket4j:
  filters:
    - rate-limits:
        - bandwidths:
            - capacity: 10    # max requests per window
              time: 15        # window duration
              unit: minutes   # window unit
```

For environments with shared IP (corporate NAT, shelter with multiple coordinators), increase capacity to 30-50. Do not set below 10 — legitimate users may hit the limit during password reset flows.

### Cache behavior

Rate limit state is stored in an in-memory JCache cache (`rate-limit-login`). State is lost on application restart — this is acceptable because the security goal is protecting against sustained brute force, not surviving restarts. The cache is configured in `application.conf` (Typesafe HOCON, read by Caffeine's JCache provider).

---

## OAuth2 / Keycloak Troubleshooting

### IdP outage — graceful degradation behavior

If an OAuth2 identity provider (Keycloak, Google, Microsoft) goes down:

- **Password-authenticated users continue working.** Local JWT validation uses HMAC-SHA256 with `FABT_JWT_SECRET`. No dependency on any external IdP or JWKS endpoint.
- **SSO users are locked out** until the IdP recovers. The JWKS circuit breaker (`fabt-jwks-endpoint`) opens after repeated failures, and SSO token validation fails.
- **Existing SSO sessions continue** until their JWT expires (default 15 min in production). The token is self-contained — validation only needs the cached JWKS key, not a live IdP connection.

The two authentication paths are architecturally independent:

| Path | Mechanism | External dependency |
|------|-----------|-------------------|
| Password login | `JwtAuthenticationFilter` → `JwtService.validateToken()` (HMAC-SHA256) | None |
| SSO login | `JwtDecoderConfig` → `NimbusJwtDecoder` (RSA via JWKS) | IdP JWKS endpoint |

**During a White Flag surge event with an IdP outage:** All accounts provisioned with password login continue working. Recommendation: ensure at least one COC_ADMIN account per tenant has password login as a fallback for emergencies.

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

## Test Data Reset Endpoint

> **PRODUCTION SAFETY: This endpoint does not exist in production.** It is gated by `@Profile("dev | test")` — the Spring bean is not created unless the profile is active. The `dev` profile is only activated by `dev-start.sh`. Production uses `lite` only.

`DELETE /api/v1/test/reset` cleans up transient E2E test data:
- Deletes all `referral_token` rows
- Cancels all `HELD` reservations
- Deletes test-created shelters (names matching `E2E Test*`, `Invariant Test*`, etc.)

**Three safeguards:**
1. `@Profile("dev | test")` — bean doesn't exist in production
2. `PLATFORM_ADMIN` role required
3. `X-Confirm-Reset: DESTROY` header required

**If this endpoint responds in an environment where it shouldn't:** The Spring profile configuration is wrong. Check `SPRING_PROFILES_ACTIVE` — it should NOT include `dev` or `test` in production.

---

## CoC Analytics Operations

### Pre-Aggregation Schedule
The `dailyAggregation` Spring Batch job runs at 3 AM by default. It reads `bed_availability` snapshots for the previous day, computes utilization metrics, and upserts to `daily_utilization_summary`. Analytics dashboard queries hit the summary table (365 rows/shelter/year) instead of raw snapshots (1,460+/shelter/year).

**Cron default:** `0 0 3 * * *` — configurable via Admin UI or tenant config JSONB `batch_schedules.dailyAggregation.cron`.

### Batch Job Monitoring
Spring Batch execution metadata is stored in `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, etc. (V24 migration). View from Admin UI → Analytics → Batch Jobs, or query directly:
```sql
SELECT job_name, status, start_time, end_time FROM batch_job_execution ORDER BY start_time DESC LIMIT 10;
```

**Grafana panels:** `FABT — CoC Analytics` dashboard includes batch job success/failure rate and duration trends.

### Connection Pool Tuning
Analytics queries run on a separate HikariCP pool (`analytics-pool`, max 3 connections, read-only, 30s statement_timeout, 256MB work_mem). OLTP pool (10 connections) is unaffected.

**Verify pool separation:**
```
SELECT pool_name, active_connections, idle_connections FROM pg_stat_activity WHERE application_name LIKE 'analytics%';
```

**If analytics queries are slow:** Check `pg_stat_activity` for long-running analytics queries. The 30s statement_timeout kills runaway queries automatically.

### HIC/PIT Generation
One-click CSV export from Admin UI → Analytics → HIC/PIT Export. Also available via API:
```
GET /api/v1/analytics/hic?date=2026-01-29  (returns CSV)
GET /api/v1/analytics/pit?date=2026-01-29  (returns CSV)
```

### DV Suppression Rules (Design D18)
DV aggregate data is suppressed when:
1. Fewer than 3 distinct DV shelters exist in the CoC (prevents "aggregate = individual")
2. Total DV beds < 5 (minimum cell size)

Applied to: analytics DV summary endpoint, HMIS push (HmisTransformer), HIC export, PIT export. When suppressed, DV data is omitted entirely — no partial data.

### HMIS Push (Spring Batch)
The `hmisPush` batch job replaced the old `@Scheduled` hourly push. Default cron: `0 0 */6 * * *` (every 6 hours). Business logic unchanged (outbox pattern, 3 retries, dead letter). Execution history visible in Admin UI → Batch Jobs.

### Bed Search Demand Logging
Every bed search is logged to `bed_search_log`. Zero-result searches increment the `fabt_search_zero_results_total` Micrometer counter. This data feeds the demand analytics and Grafana zero-result rate panel.

---

## Password Management

### Self-Service Password Change
Users change their own password via `PUT /api/v1/auth/password`. Requires current password. Minimum 12 characters (NIST 800-63B). On success, all existing JWT tokens for that user are invalidated (`password_changed_at` timestamp checked against token `iat`). The user is redirected to login.

SSO-only users (no local `password_hash`) receive 409 Conflict. The "Change Password" button appears in the header for all users; the error is shown if they attempt it.

Rate limited: 5 attempts per 15 minutes per IP (`rate-limit-password-change` bucket).

### Admin-Initiated Password Reset
Admins reset passwords via `POST /api/v1/users/{id}/reset-password`. Requires COC_ADMIN or PLATFORM_ADMIN role. Same-tenant enforcement — admin cannot reset passwords for users in other tenants (returns 404). Same password strength validation and JWT invalidation.

Rate limited: 10 attempts per 15 minutes per IP (`rate-limit-admin-reset` bucket).

### Monitoring Suspicious Activity
- `fabt.auth.password_change.count{outcome=wrong_password}` — high rate may indicate brute force attempt against a known user
- `fabt.auth.password_reset.count` — high count in a short window from a single admin suggests compromised admin credentials
- `fabt.auth.token_invalidated.count{reason=admin_reset}` — unexpected spikes warrant investigation

### Investigation: All users suddenly logged out
**Symptom:** Multiple users reporting forced re-login simultaneously
**Cause:** If an admin mass-reset passwords, `password_changed_at` timestamps invalidate all prior tokens
**Resolution:** Check `fabt.auth.password_reset.count` and `fabt.auth.token_invalidated.count` metrics. Review `password_changed_at` timestamps: `SELECT email, password_changed_at FROM app_user WHERE password_changed_at > NOW() - INTERVAL '1 hour' ORDER BY password_changed_at DESC;`

## Common Issues

### Webhook delivery failures
**Symptom:** `fabt.webhook.delivery.count{status="failure"}` increasing
**Cause:** Callback URL unreachable, SSL certificate issues, slow endpoint exceeding the read timeout, or 410 Gone (permanent deactivation).
**Resolution:** Check subscription callback URLs. 410 responses auto-deactivate the subscription. Other failures are retried automatically by resilience4j (3 attempts, exponential backoff 1s × 3 — see `webhook-delivery` retry config in `application.yml`); after 5 consecutive failures the subscription is auto-disabled and `subscription.status` becomes `DEACTIVATED`. The admin can re-enable via `PATCH /api/v1/subscriptions/{id}/status` (resets the failure counter).

**If a partner reports timeouts on a slow callback endpoint:**
The default read timeout is 30 seconds (10s connect + 30s read). For partners with legitimately slow endpoints, override per-deployment via env vars:

```bash
FABT_WEBHOOK_CONNECT_TIMEOUT_SECONDS=10   # default 10
FABT_WEBHOOK_READ_TIMEOUT_SECONDS=60      # default 30 — bump for slow partners
```

These map to `fabt.webhook.connect-timeout-seconds` and `fabt.webhook.read-timeout-seconds`. Setting the read timeout too high can starve the virtual-thread pool under load, so prefer fixing the partner's endpoint when possible.

**Webhook delivery test:** use `POST /api/v1/subscriptions/{id}/test` to send a synthetic event to a subscription's callback URL. The response includes the actual status code, response time, and (truncated, redacted) body. This is the right way to verify a subscription is wired correctly without waiting for a real domain event.

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

---

## User Deactivation

### Procedure
1. Admin navigates to Administration → Users tab
2. Clicks "Edit" on the user row → drawer opens
3. Clicks "Deactivate" → confirmation dialog appears
4. Confirms → user status set to DEACTIVATED

### What happens on deactivation
- `token_version` incremented → all existing JWTs immediately rejected
- SSE notification stream disconnected (if connected)
- Login attempts return "Account deactivated. Contact your administrator."
- Audit event recorded: action=USER_DEACTIVATED, actor, timestamp, IP

### Reactivation
- Same flow: Edit → "Reactivate" button (green, replaces Deactivate)
- `token_version` incremented again → user must log in fresh
- Audit event recorded: action=USER_REACTIVATED

### JWT invalidation troubleshooting
**Symptom:** User reports "still logged in" after role change or deactivation
**Cause:** JWT claims cache (Caffeine, 30s TTL) may hold stale claims
**Resolution:** Token version check runs on every request. Maximum delay is the cache TTL (30s). If the user's client doesn't refresh within 30s, force-expire by incrementing `token_version` again.

---

## SSE Notifications

### Architecture
- Endpoint: `GET /api/v1/notifications/stream?token=<jwt>` (Server-Sent Events)
- One `SseEmitter` per authenticated user, stored in `ConcurrentHashMap<UUID, SseEmitter>`
- Virtual threads handle long-lived connections — no thread pool sizing concern
- Keepalive: SSE comment sent every 30 seconds to prevent proxy/LB idle timeout
- Emitter timeout: 5 minutes. Client `EventSource` auto-reconnects with `retry: 5000ms`

### Metrics
| Metric | Type | Description |
|--------|------|-------------|
| `fabt_sse_connections_active` | Gauge | Current number of connected SSE clients |
| `fabt_sse_events_sent_count_total` | Counter (tag: eventType) | Total SSE events pushed to clients |

### Expected connection count
- One SSE connection per authenticated browser tab
- In a typical CoC deployment (10-50 concurrent users), expect 10-50 active connections
- Connections are cleaned up on timeout (5 min), disconnect, or error

### Troubleshooting

#### SSE connections accumulating
**Symptom:** `fabt_sse_connections_active` climbing without plateau
**Cause:** Emitter cleanup callbacks not firing (Spring #33421/#33340), client reconnecting without closing old connection
**Resolution:** Check for `SseEmitter` error logs. The `NotificationService` registers `onCompletion`/`onTimeout`/`onError` callbacks. If connections accumulate, restart the service — emitters are in-memory and will be re-established by clients.

#### Proxy blocking SSE
**Symptom:** SSE connection opens but no events received, or connection drops immediately
**Cause:** Reverse proxy (nginx, CloudFlare) buffering responses or closing idle connections
**Resolution:** Ensure proxy config disables buffering for the SSE path:
- nginx: `proxy_buffering off;` and `proxy_read_timeout 3600;` for `/api/v1/notifications/stream`
- CloudFlare: disable "Rocket Loader" and response buffering for the SSE path

#### Keepalive not preventing disconnects
**Symptom:** SSE connections drop every 60-90 seconds despite 30s keepalive
**Cause:** Proxy idle timeout is shorter than keepalive interval, or keepalive failing silently
**Resolution:** Check for keepalive errors in logs (`"Keepalive failed for user"`). Verify proxy idle timeout is > 30 seconds.

---

## Disclosures

### v0.32.3 notification-bell accept/reject render bug (fixed 2026-04-11)

**What the bug did.** Between the v0.31.0 deploy (around 2026-04-08) and the v0.32.3 hotfix deploy (2026-04-11), every user who logged into findabed.org and opened the notification bell saw **"A shelter rejected your referral"** as the text for every persisted `dv-referral.responded` notification — regardless of whether the shelter had actually accepted or rejected. The backend correctly recorded the status; the frontend rendered the wrong text because it was reading a field on the wrong shape of the notification row. Live SSE push notifications received during an active session were unaffected — the bug only manifested for notifications loaded from the database after a fresh login.

The secondary effect: on the same version range, persistent `availability.updated` notifications displayed without a shelter name (just "A shelter updated availability" with no attribution), because of the same payload-shape bug in a sibling render path.

**Who is at risk of a broken mental model.** This is the demo site with synthetic data — no real survivors are involved. But:

- **Trainers** (Devon Kessler persona, real external trainers) who walked cohorts through the DV referral flow during this window may have shown the wrong text to learners.
- **Funders and board members** who did a self-guided tour of the demo to understand the program may have believed the system was rejecting referrals that were actually accepted.
- **Onboarding CoC admins** and **partner organization leads** who were evaluating FABT during this window may have formed the wrong impression of how the workflow reports outcomes.

**Disclosure template for trainers.** If you trained anyone between 2026-04-08 and the v0.32.3 deploy, a short email is recommended. Suggested copy:

> Subject: Quick correction on the DV referral notifications you saw in training
>
> Hi [name],
>
> A small visual bug in the demo site made every "shelter responded" notification display as "rejected" even when the referral was actually accepted. I wanted to flag this directly because you saw the notification bell during our session on [date] and the text did not match what the system actually did.
>
> The backend was always recording the correct outcome. The notification bell in the demo UI was rendering the wrong text for any notification loaded after a fresh login. This has been fixed today (v0.32.3). If you log in again, you will see the correct "A shelter accepted your referral" text for accepted referrals.
>
> Sorry for the confusion. Happy to do a quick re-run of the flow if that would help.
>
> [your name]

**What NOT to do.** Do not publish a broad public notice — this is a demo, no real survivors were affected, and a broad notice would overclaim the incident's scope. A targeted email to the specific people who were trained during the window is the right blast radius.

**Tracking.** This disclosure note is the operational record of the incident. The CHANGELOG entry for v0.32.3 has the technical details (affected versions, bug introduction commit, fix mechanism). The commit `8ebb666` is the code-level bisection point. No postmortem document is required beyond these two artifacts for a demo-site visual bug.

---

### v0.34.0 coordinator `/manual-hold` endpoint unavailable on bugfix branch (fixed 2026-04-11)

**What the bug was.** During development of the bed-hold-integrity change (Issue #102 RCA), the new `POST /api/v1/shelters/{id}/manual-hold` endpoint was unreachable for `COORDINATOR`-role users due to a `SecurityConfig.java:172` filter rule that excluded `COORDINATOR` from `POST /shelters/**`. Every coordinator call was silently 403'd at the Spring Security filter chain, before the controller's `@PreAuthorize` or `CoordinatorAssignmentRepository.isAssigned` check could run. The integration test gave a false pass because the test-level `coordinator_not_assigned_to_shelter_403` expectation matched the filter-level 403 response (both returned the same JSON shape). The IMPLEMENTATION-NOTES documented this initially as a "test infrastructure wrinkle" — that framing was wrong; it was a production bug that would have silently 403'd every real coordinator in every real-tenant deployment.

**What we did wrong during development.** The `OfflineHoldEndpointTest.coordinator_creates_offline_hold_succeeds` test was originally written to use `cocAdminHeaders()` as a workaround for the "test infra wrinkle" — which meant the production-path coverage for the COORDINATOR role didn't exist in the gating test suite. That's how the bug slipped through 517/517 green tests.

**Who is at risk of a broken mental model.** Nobody on the live findabed.org demo: this bug was caught during pre-ship smoke testing on the `bugfix/issue-102-phantom-beds-on-hold` branch and never deployed. The coordinator path has been production-validated at 2026-04-11 22:21 UTC against the new v0.34.0 code. **But** — if any coordinator was trained or onboarded on `/manual-hold` between the `bugfix/issue-102-phantom-beds-on-hold` branch being merged and v0.34.0 deploying — the window during which the feature existed in source control but hadn't been fixed — that's the population to flag. Probably zero people, but the discipline is the disclosure.

**Disclosure template for trainers / operators.** If you demonstrated the new coordinator `/manual-hold` flow between [date the feature was merged to main] and the v0.34.0 deploy, and any coordinator saw a 403 error, suggested email copy:

> Subject: Quick note on the manual offline hold feature you saw in our last session
>
> Hi [name],
>
> During the demo, you may have seen a "Insufficient permissions" error when I tried to show the coordinator path for creating a manual offline hold. That was a real bug in the draft code of our v0.34.0 release — the filter chain for that endpoint was rejecting coordinator accounts even when they had a valid shelter assignment.
>
> The bug was caught in our pre-ship smoke test and is now fixed. As of the v0.34.0 deploy, a coordinator assigned to a shelter can create an offline hold for that shelter via the POST /api/v1/shelters/{id}/manual-hold endpoint. The demo flow I walked you through is now the real production flow.
>
> Happy to do a quick re-run of the manual hold creation if it would help solidify what the feature actually does.
>
> [your name]

**Regression guard.** The post-deploy smoke test in the "Bed Availability Invariants → v0.34.0 post-deploy smoke" section of this runbook exists to catch any future SecurityConfig narrowing that reintroduces this bug. It is non-optional and should be added to the standard post-deploy smoke sequence. The `OfflineHoldEndpointTest.coordinator_creates_offline_hold_succeeds_when_assigned` test exists in the v0.34.0 backend test suite with an assertion on the `fabt.http.access_denied.count` counter to distinguish a filter-level 403 from a controller-level 403 — this is the test-suite-level regression guard for the same bug class.

**Tracking.** CHANGELOG v0.34.0 entry has the technical details. `SecurityConfig.java:172` is the code-level fix location. `openspec/changes/bed-hold-integrity/IMPLEMENTATION-NOTES.md` § A has the full RCA narrative. No postmortem document is required — the fix shipped in the same change as the feature.

---

## v0.39 Deploy — Issue #106 Phases 1–4 combined release

**Scope.** v0.39 is the first deployment to findabed.org to carry any of Issue #106 (notification deep-linking). Because Phases 1, 2, and 3 were merged to main between v0.38.0 and v0.39.0 without intermediate deploys, ALL four phases ship together. This is the largest user-visible delta since v0.21 (shelter-edit).

**Schema changes.** One migration lands: `V55__referral_token_pending_created_at_idx.sql` — a partial index `(created_at ASC) WHERE status='PENDING'` on `referral_token`. Uses `CREATE INDEX IF NOT EXISTS` so it is idempotent; on clean environments Flyway applies and records it, on environments where a DBA manually pre-created the index during the 16.1.5 perf probe the `IF NOT EXISTS` is a no-op. Non-concurrent CREATE INDEX (Flyway wraps in a transaction); build time is seconds even at NYC pilot scale.

**User-visible changes operators should expect reports about.**

| Behavior | Before v0.39 | After v0.39 |
|---|---|---|
| Clicking a notification in the bell | URL stayed at `/coordinator`; referral was NOT auto-expanded | Deep-links to the specific referral row (coordinator) / escalation modal (admin) / `/outreach/my-holds` row (outreach) |
| `CoordinatorReferralBanner` click | Opened the alphabetically-first DV shelter regardless of where the pending referral lived (the **Harbor House genesis gap** that motivated this entire change) | Navigates to `/coordinator?referralId=<firstPending>` via the count endpoint's routing hint — specific shelter, specific row |
| Notification bell visuals | Two states (unread, read) | Three states (unread, read-but-not-acted, acted) with ✓ icon on acted rows |
| "Hide acted" filter | Did not exist | New toggle in bell header; preference persisted to `localStorage` |
| Outreach worker nav | No dedicated "past holds" view | NEW top-nav entry "My Past Holds" at `/outreach/my-holds` — HELD + terminal holds with status-specific actions |
| `SHELTER_DEACTIVATED` / `HOLD_CANCELLED_SHELTER_DEACTIVATED` / `referral.reassigned` notifications | Rendered as `"notifications.unknown"` | Render human-readable copy |
| `CriticalNotificationBanner` | Red banner with count, no actionable CTA for coordinators | New "Review N pending escalations" CTA for admins; coordinators get a one-click path to their oldest CRITICAL referral |
| `CriticalNotificationBanner` transition from 0 to ≥1 CRITICAL | Threw `Minified React error #310` and blanked the page (rules-of-hooks violation — useMemo after early return) | Fixed |
| Cross-tenant access on `/api/v1/dv-referrals/{id}` and `accept`/`reject` | dv-access coordinator in Tenant A could read/accept/reject Tenant B referrals by UUID | Returns 404 (not 403 — no existence leak) — tasks 8.5/8.6 hardening |

**Build prerequisites (per `feedback_deploy_old_jars.md` + `feedback_deploy_checklist_v031.md`).** Always use `mvn clean package` — never incremental — to prevent stale JARs on the VM. Always build the Docker image with `--no-cache` so layer cache does not serve an old JAR. Unlike v0.34 (backend-only), **the frontend MUST also be rebuilt** for v0.39 — ~50+ frontend files changed across bell, banner, deep-link hooks, My Past Holds, and Carbon-token contrast fixes. See `docs/oracle-update-notes-v0.39.0.md` for the full deploy sequence.

**v0.39 post-deploy smoke sequence.** Run after every deployment of v0.39.x or later. Tee to `logs/post-deploy-smoke-v0.XX.X.log` per established pattern.

```bash
# 1. Version endpoint returns v0.39
curl -s https://findabed.org/api/v1/version
# expected: {"version":"0.39..."}

# 2. Flyway recorded V55 (run from VM via docker exec)
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
  "SELECT version, description, success FROM flyway_schema_history WHERE version = '55';"
# expected: one row, description='referral token pending created at idx', success=t

# 3. The partial index exists with the correct shape
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c \
  "\d idx_referral_token_pending_created_at"
# expected: btree on (created_at) WHERE status='PENDING'

# 4. Planner actually uses the new index (Sam's gate)
docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt -c "
EXPLAIN ANALYZE
  SELECT * FROM referral_token
  WHERE shelter_id = ANY(ARRAY[
    (SELECT id FROM shelter WHERE dv_shelter = TRUE LIMIT 1)
  ])
    AND status = 'PENDING'
  ORDER BY created_at ASC LIMIT 1;
"
# expected: plan includes 'Index Scan using idx_referral_token_pending_created_at'
# failure mode: 'Seq Scan on referral_token' — stats are cold. Remediate with:
#   docker exec ... psql ... -c "ANALYZE referral_token;"

# 5. Count-endpoint response shape (firstPending field MUST be present — null or object)
TOKEN=$(curl -s -X POST https://findabed.org/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"dev-coc","email":"dv-coordinator@dev.fabt.org","password":"admin123"}' \
  | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" https://findabed.org/api/v1/dv-referrals/pending/count | jq .
# expected: {"count": N, "firstPending": {"referralId":"...","shelterId":"..."} | null}
# Critical: the firstPending key MUST be present (null or object) — not omitted.

# 6. Cross-tenant 404 live probe (Casey's security gate for findByIdOrThrow hardening)
curl -s -H "Authorization: Bearer $TOKEN" \
  -o /dev/null -w "%{http_code}\n" \
  https://findabed.org/api/v1/dv-referrals/00000000-0000-0000-0000-000000000000
# expected: 404 (not 403, not 200). Confirms findByIdAndTenantId is active.
# failure mode: any other status → rollback immediately and reopen tasks 8.5/8.6.

# 7. Metrics registered in Prometheus scrape
curl -s https://findabed.org/actuator/prometheus | grep -E "fabt_notification_(deeplink_click|time_to_action|stale_referral)" | head
# expected: at least one matching metric line per metric name.
# NOTE (Micrometer lazy registration): if this returns empty, the metrics have
# not yet been emitted. Fire one bell-click in the manual UI section below,
# then re-run this grep. Metrics appear on first emission.
```

**Manual UI verification (the authoritative post-deploy gates).** Run in an **incognito window** per the PWA service-worker cache caveat below.

1. **Version footer** — `https://findabed.org` shows **v0.39** in the footer.
2. **Genesis-gap regression (Section 16 — the authoritative gate).** Login as `dv-coordinator@dev.fabt.org / admin123`. If count > 0 in the red banner, click it once. Verify the URL gains `?referralId=<UUID>`, the specific referral row scrolls into view, and focus lands on the row heading (NOT the Accept button — S-2 safety). Cross-check the expanded shelter matches `firstPending.shelterId` from step 5 above. The canonical automated regression lives at `e2e/playwright/tests/persistent-notifications.spec.ts:243` — Corey runs it locally against dev; it is not suitable for prod because its `afterAll` cleans up DV referral state.
3. **URL-stale regression (D-BP, 2026-04-14 fix).** Open a second incognito tab and navigate to `https://findabed.org/coordinator?referralId=00000000-0000-0000-0000-c0ffee000106` (deliberately-bogus UUID). The stale toast should appear; the banner should still render if count > 0. Click the banner. Verify the URL **rewrites** to the real `firstPending.referralId` — NOT stays at the stale UUID. Pre-fix behavior was no-op on click; post-fix server-current wins over stale URL.
4. **Admin escalation deep-link.** Login as `cocadmin@dev.fabt.org / admin123`. In the admin panel's DV Escalations tab, if any row is present click it — the detail modal should auto-open via the URL hash fragment.
5. **Outreach My Past Holds.** Login as `dv-outreach@dev.fabt.org / admin123` → verify "My Past Holds" appears in the top nav. Click it — page loads without 404.
6. **Three-state bell.** In any logged-in session, open the bell dropdown. Unread rows have highlighted background. Click one → it transitions to read-but-not-acted (normal weight, still visible). If the underlying target has been acted via deep-link, it shows with a ✓ icon.

**Rollback criteria.**
- Any post-deploy smoke step fails → rollback to v0.38.0 JAR + bundle. V55 index stays in place (harmless, old code ignores it).
- 5xx spike > 2× baseline in first 30 min after deploy → rollback.
- Any report of a banner click routing to the wrong shelter → rollback + reopen Section 16 investigation.

**Known operator-awareness items.**
- **Service worker cache.** Tell pilots / demo audiences to hard-reload (Ctrl+Shift+R) or test in incognito. The v0.39 bundle will not replace an open tab's cached one until the PWA registration cycles.
- **Three-state bell.** First pilot session after deploy will see acted notifications carrying forward from before the deploy (they'll all render as "read-but-not-acted" initially because their `actedAt` is null — a user can click through to flip any they want to acted state).
- **New top-nav "My Past Holds".** If an outreach worker asks "where's this new link", answer: their past holds were always recorded in the system, this view just surfaces them.

**Cross-tenant hardening flag.** v0.39 fixes the DV referral cross-tenant leak but an audit of the broader `findById(UUID)` pattern (Subscription, ApiKey, other services) is tracked in **GitHub issue #117** as a required gate for any multi-tenant production deployment. Demo site (single-tenant) is unaffected.

**Tracking.** `openspec/changes/notification-deep-linking/` has the full phase breakdown. v0.39 release notes should call out the five operator-awareness items above (service worker, three-state bell, new nav entry, cross-tenant hardening disclosure, genesis-gap fix).

## Cross-Tenant Isolation Observability (Issue #117)

The `cross-tenant-isolation-audit` change ships three operational signals.

### `fabt.security.cross_tenant_404s`

Counter incremented on every `NoSuchElementException`-derived 404. Per design D9, cross-tenant probes and legitimate "not found" responses are **intentionally indistinguishable** — both look the same on this counter. Tagged by `resource_type`.

**Tag vocabulary** (extracted from exception message prefix):
- `shelter`, `user`, `api_key`, `oauth2_provider`, `subscription`, `referral_token`, `tenant`, `target_user`, `unknown`

**Alert threshold (Jordan):** spike-vs-baseline, NOT absolute rate. Steady-state baseline ~5/min during business hours (typos, race conditions during referral expiry, stale browser tabs). Alert when 1-minute rate > 3× rolling 24h average. One typo per hour is fine; 100 in a minute warrants investigation.

**Investigation playbook:**
1. Check `resource_type` tag — which surface is being probed?
2. Cross-reference with `fabt.http.access_denied.count{role=...}` — same actor pattern?
3. Check audit_events for the tenant_id of the affected resources — same actor probing multiple tenants?
4. If `cross_tenant_404s` rate > 100/min and `access_denied` rate normal → likely a bot scanning UUIDs. Add IP rate-limit rule.

### `fabt.webhook.delivery.failures{reason="ssrf_blocked"}`

Counter incremented when `SafeOutboundUrlValidator` blocks a webhook delivery (creation-time or dial-time). Per design D12.

**Alert threshold:** any non-zero rate during normal operations. SSRF blocks on legitimate webhooks indicate either (a) an admin misconfigured a URL (typed `localhost`, `192.168.x.x`, etc.) or (b) a real DNS rebinding attempt. Both warrant investigation.

**Investigation playbook:**
1. Check WARN logs for the URL that was blocked (validator logs URL + resolved IP + category).
2. If category is `loopback` or `rfc1918` — admin config error, contact tenant.
3. If category is `link-local` (cloud metadata) or the URL matches a previously-good public URL → DNS rebinding suspected. Capture the URL + DNS history; consider rate-limiting the source IP.

### Tenant-tagged metrics (D16)

9 per-request metrics now carry a `tenant_id` tag. In Grafana, use `$tenant` as a dashboard variable to filter:
- `fabt.bed.search.count`, `fabt.availability.update.count`, `fabt.reservation.count`
- `fabt.webhook.delivery.count`, `fabt.hmis.push.total`, `fabt.dv.referral.total`
- `sse.send.failures.total`, `fabt.http.not_found.count`
- `fabt.notification.deeplink.click.count`

Batch timers (`fabt.escalation.batch.duration`, `fabt.bed.hold.reconciliation.batch.duration`) are NOT tagged — they aggregate across tenants by design.

**Cardinality budget:** 9 metrics × ≤200 tenants = ≤1800 series. Within single-instance Prometheus budget. If tenants exceed 200, downsample with `tenant_id="other"` bucket for the long tail.

### `app.tenant_id` PostgreSQL session variable (D13)

Set on every connection borrow alongside `app.dv_access` and `app.current_user_id`. No RLS policy currently reads it — installed as defense-in-depth infrastructure for the companion change `multi-tenant-production-readiness` (D14 — tenant-RLS on regulated tables). Verify with:

```sql
-- From a backend-issued query (any connection):
SELECT current_setting('app.tenant_id', true);
-- Expected: tenant UUID for normal request, empty string for batch jobs
```

`TenantIdPoolBleedTest` runs 100 sequential alternating-tenant iterations to confirm no bleed across pool checkouts.

**Tracking.** `openspec/changes/cross-tenant-isolation-audit/` has the full phase breakdown.
