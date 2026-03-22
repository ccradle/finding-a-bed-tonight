# Operational Runbook — Finding A Bed Tonight

## Monitors

### Monitor 1: Stale Shelter Detection

**Metric:** `fabt.shelter.stale.count` (gauge)
**Schedule:** Every 5 minutes
**Log level:** WARNING

**What it means:** One or more shelters have not published an availability snapshot in 8+ hours. Outreach workers may be seeing stale data.

**Investigation:**
1. Check the WARNING log for the specific shelter IDs and last update times
2. Verify the shelter coordinator is still active (check app_user table)
3. Check if the shelter's availability API endpoint is reachable
4. Review recent bed_availability table entries for the shelter: `SELECT * FROM bed_availability WHERE shelter_id = '<id>' ORDER BY snapshot_ts DESC LIMIT 5;`

**Resolution:**
- Contact the shelter coordinator to confirm operations
- If the shelter is temporarily closed, update its status
- If the coordinator is unresponsive, escalate to the CoC admin
- For systematic issues (many shelters stale), check if backend is processing availability updates correctly

### Monitor 2: DV Canary Check

**Metric:** `fabt.dv.canary.pass` (gauge: 1=pass, 0=fail)
**Schedule:** Every 15 minutes
**Log level:** CRITICAL on failure

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

### Monitor 3: Temperature/Surge Gap Detection

**Metric:** `fabt.temperature.surge.gap` (gauge: 1=gap detected, 0=no gap)
**Schedule:** Every hour (configurable via Admin UI → Observability → Temperature polling interval)
**Log level:** WARNING
**Default station:** KRDU (Raleigh-Durham, NC)

**What it means:** The NOAA API reports ambient temperature below the configured threshold (default 32°F) at the pilot city location, but no surge event is active. During freezing weather, unsheltered individuals face hypothermia risk and surge mode should typically be activated.

**Configuration:**
- Temperature threshold is configurable via Admin UI → Observability tab → "Surge activation threshold"
- Polling frequency is configurable via Admin UI → Observability tab → "Temperature polling interval"
- Current temperature and gap status visible in Admin UI → Observability tab → Temperature Status section
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
- Webhook Delivery Rate — success/failure rates
- Circuit Breaker State — CLOSED/OPEN/HALF_OPEN for each breaker

## Prometheus Queries

| Metric | PromQL |
|--------|--------|
| Bed search rate | `rate(fabt_bed_search_count_total[1m])` |
| Availability update rate | `rate(fabt_availability_update_count_total[1m])` |
| Reservation rate by status | `rate(fabt_reservation_count_total[1m])` |
| Stale shelters | `fabt_shelter_stale_count` |
| DV canary | `fabt_dv_canary_pass` |
| Surge active | `fabt_surge_active` |
| Temperature/surge gap | `fabt_temperature_surge_gap` |
| Webhook success rate | `rate(fabt_webhook_delivery_count_total{status="success"}[1m])` |
| Circuit breaker state | `resilience4j_circuitbreaker_state` |

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
