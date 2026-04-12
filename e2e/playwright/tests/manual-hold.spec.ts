import { test, expect } from '@playwright/test';

/**
 * T-55a — API-level Playwright coverage for POST /api/v1/shelters/{id}/manual-hold.
 *
 * Subsumes Task #3 from the v0.34.0 work queue, rolled into coc-admin-escalation
 * Session 7 per founder direction 2026-04-11.
 *
 * API-only for now; upgrade to user-flow when the Create Manual Hold UI ships
 * in a future change. Riley Cho Option B (2026-04-11 war room): "The manual-hold
 * endpoint has no frontend UI yet — test the HTTP contract through the real
 * nginx wire, not a simulated click-through."
 *
 * Four scenarios:
 *  (a) Assigned coordinator   → 201 CREATED, note has "Manual offline hold" prefix,
 *                                expiresAt is a future timestamp > 9 minutes out
 *                                (sanity: default hold duration is 10 minutes).
 *  (b) Unassigned coordinator → 403 FORBIDDEN, AND fabt_http_access_denied_count_total
 *                                increments proving the rejection came from the
 *                                controller body (isAssigned branch), not the
 *                                SecurityConfig filter chain. This is the load-bearing
 *                                regression guard for Issue #102 RCA
 *                                (SecurityConfig.java:172 was missing COORDINATOR).
 *  (c) COC_ADMIN bypass       → 201 CREATED, admin short-circuits the assignment
 *                                check per the two-layer auth contract in
 *                                ManualHoldController.java.
 *  (d) Rolled into (a)        → The expiresAt countdown assertion is part of
 *                                scenario (a); a dedicated fourth test would add
 *                                no signal. When the Manual Hold UI lands, this
 *                                becomes a separate test that reads a countdown
 *                                element via data-testid.
 *
 * The backend integration test `OfflineHoldEndpointTest` already covers the same
 * scenarios against Testcontainer-isolated state. This Playwright spec is the
 * nginx-level wire-contract verification — it catches gateway misconfig,
 * CORS regressions, JWT propagation bugs, and prod-deploy smoke failures that
 * Testcontainer cannot see.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';

// Observability mode (dev-start.sh --observability) separates the management
// port. When off, actuator is on 8080 but requires a JWT. Default assumes dev
// stack runs with observability on (PROMETHEUS_URL can override for CI).
const PROMETHEUS_URL = process.env.PROMETHEUS_URL
    || 'http://localhost:9091/actuator/prometheus';

const TENANT_SLUG = 'dev-coc';

// Seed users from infra/scripts/seed-data.sql. dv-coordinator is assigned to
// all three seed DV shelters and has dvAccess=true. cocadmin has COC_ADMIN
// role which bypasses the coordinator_assignment check in ManualHoldController.
const SEED_USERS = {
  dvCoordinator: { email: 'dv-coordinator@dev.fabt.org', password: 'admin123' },
  cocadmin: { email: 'cocadmin@dev.fabt.org', password: 'admin123' },
  admin: { email: 'admin@dev.fabt.org', password: 'admin123' },
};

/** Login helper — returns a raw JWT for the given seed user. */
async function loginAndGetToken(user: { email: string; password: string }): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: user.email,
      password: user.password,
      tenantSlug: TENANT_SLUG,
    }),
  });
  if (!res.ok) {
    throw new Error(`Login failed for ${user.email}: ${res.status} ${await res.text()}`);
  }
  const body = await res.json();
  return body.accessToken as string;
}

/**
 * List all shelters visible to the token's tenant.
 *
 * The list endpoint returns {@code ShelterListResponse} which wraps the
 * shelter in a {@code .shelter} sub-object and does NOT include
 * {@code capacities} — that's only on the detail endpoint. We unwrap
 * the sub-object here so callers can access {@code .id}, {@code .dvShelter}
 * directly.
 */
async function listShelters(token: string): Promise<Array<{
  id: string;
  name: string;
  dvShelter: boolean;
  totalBedsAvailable: number | null;
}>> {
  const res = await fetch(`${API_URL}/api/v1/shelters`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    throw new Error(`List shelters failed: ${res.status} ${await res.text()}`);
  }
  const body = await res.json();
  // Endpoint returns ShelterListResponse[] — each has { shelter: {...}, availabilitySummary: {...} }.
  // Flatten shelter fields + totalBedsAvailable from the summary so callers
  // can filter by dvShelter AND by "has capacity data."
  const items: any[] = Array.isArray(body) ? body : (body.content ?? body.shelters ?? []);
  return items.map(item => ({
    ...(item.shelter ?? item),
    totalBedsAvailable: item.availabilitySummary?.totalBedsAvailable ?? null,
  }));
}

/**
 * Fetch shelter detail and return the first valid populationType from its
 * capacities. The detail endpoint ({@code GET /api/v1/shelters/{id}}) returns
 * {@code ShelterDetailResponse} which includes {@code capacities[]} — the list
 * endpoint does not.
 *
 * War room finding (2026-04-12): the original test assumed capacities were on
 * the list response. Alex Chen: "read the actual API contract, not a guess."
 */
async function getFirstPopulationType(token: string, shelterId: string): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/shelters/${shelterId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    throw new Error(`Shelter detail failed for ${shelterId}: ${res.status} ${await res.text()}`);
  }
  const detail = await res.json();
  // ShelterDetailResponse shape: { shelter: {...}, constraints: {...}, capacities: [...], availability: [...] }
  const capacities: Array<{ populationType: string }> = detail.capacities ?? [];
  if (capacities.length === 0) {
    throw new Error(`Shelter ${shelterId} has no capacities — cannot determine a valid populationType for the manual-hold request`);
  }
  return capacities[0].populationType;
}

/** POST the manual-hold endpoint. Returns the raw Response so tests can inspect status + body. */
async function postManualHold(token: string, shelterId: string, body: {
  populationType: string;
  reason: string;
}): Promise<Response> {
  return fetch(`${API_URL}/api/v1/shelters/${shelterId}/manual-hold`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
}

/**
 * Scrape the Prometheus text-format endpoint and sum all samples of
 * fabt_http_access_denied_count_total across all tag combinations. The
 * counter is tagged by {role, path_prefix} in GlobalExceptionHandler, so
 * there can be multiple lines in the scrape output. We sum them for a
 * before/after diff.
 *
 * Returns 0 if the metric is absent (cold counter — hasn't fired yet in
 * this JVM lifetime). Throws on network failure so a misconfigured
 * PROMETHEUS_URL surfaces loudly.
 */
async function getAccessDeniedCounter(): Promise<number> {
  let res: Response;
  try {
    res = await fetch(PROMETHEUS_URL);
  } catch (err) {
    throw new Error(
        `Could not reach Prometheus endpoint at ${PROMETHEUS_URL}. `
        + `Run dev-start.sh --observability to expose actuator on 9091, `
        + `or set PROMETHEUS_URL env var. Underlying error: ${err}`);
  }
  if (!res.ok) {
    throw new Error(
        `Prometheus endpoint returned ${res.status} at ${PROMETHEUS_URL}. `
        + `If running without --observability, the actuator is on 8080 and `
        + `needs a JWT — set PROMETHEUS_URL or run with the observability profile.`);
  }
  const text = await res.text();

  // Prometheus text format: lines like
  //   fabt_http_access_denied_count_total{role="ROLE_COORDINATOR",path_prefix="/api/v1/shelters/..."} 3.0
  // Sum the numeric trailing token across all matching lines.
  let total = 0;
  for (const line of text.split('\n')) {
    if (!line.startsWith('fabt_http_access_denied_count_total')) continue;
    if (line.startsWith('#')) continue; // HELP/TYPE comment
    // Last whitespace-separated token is the value
    const parts = line.trim().split(/\s+/);
    const value = parseFloat(parts[parts.length - 1]);
    if (!Number.isNaN(value)) total += value;
  }
  return total;
}

test.describe('T-55a: Manual hold endpoint (API-level regression guards)', () => {

  test('(a) assigned coordinator creates a manual hold on a DV shelter — 201 with countdown', async () => {
    const token = await loginAndGetToken(SEED_USERS.dvCoordinator);
    const shelters = await listShelters(token);
    const dvShelter = shelters.find(s => s.dvShelter);
    expect(dvShelter, 'Seed data must include at least one DV shelter').toBeDefined();
    const populationType = await getFirstPopulationType(token, dvShelter!.id);

    const start = Date.now();
    const res = await postManualHold(token, dvShelter!.id, {
      populationType,
      reason: 'T-55a scenario (a) — assigned coordinator regression guard',
    });

    expect(res.status, 'Assigned coordinator must reach the controller and create the hold. '
        + 'If this is 403, SecurityConfig.java filter rule may have dropped COORDINATOR — '
        + 'that is the Issue #102 RCA regression').toBe(201);

    const body = await res.json();
    expect(body.status, 'Manual hold must be HELD').toBe('HELD');
    expect(body.notes, 'Notes must be prefixed with the manual-hold marker')
        .toContain('Manual offline hold');
    expect(body.id, 'Response must include the new reservation id').toBeTruthy();
    expect(body.expiresAt, 'Response must include an expiresAt timestamp').toBeTruthy();

    // Countdown assertion — rolled in from scenario (d). Default hold duration
    // is 10 minutes (fabt.dv-referral.claim-duration-minutes default — same
    // config powers the admin-configurable reservation hold minutes). Require
    // expiresAt to be > 9 minutes out from the pre-call time so we tolerate
    // small request latency and clock drift without masking a real bug where
    // expiresAt == createdAt.
    const expiresAtMs = Date.parse(body.expiresAt);
    const countdownMs = expiresAtMs - start;
    expect(countdownMs, `expiresAt (${body.expiresAt}) must be at least 9 minutes in the future`)
        .toBeGreaterThan(9 * 60 * 1000);
  });

  test('(b) unassigned coordinator is rejected 403 — counter proves controller-level denial', async () => {
    const token = await loginAndGetToken(SEED_USERS.dvCoordinator);
    const shelters = await listShelters(token);

    // Pick a shelter the dv-coordinator is NOT assigned to. Seed data has
    // dv-coordinator assigned to DV shelters only, so any non-DV shelter
    // satisfies the "unassigned" condition.
    // Filter by totalBedsAvailable != null to skip shelters with no capacity
    // data (e.g., 211-imported shells or test leftovers with no seed rows).
    const nonDvShelter = shelters.find(s => !s.dvShelter && s.totalBedsAvailable != null);
    expect(nonDvShelter,
        'Seed data must include at least one non-DV shelter with capacity data '
        + 'that dv-coordinator is not assigned to').toBeDefined();
    const populationType = await getFirstPopulationType(token, nonDvShelter!.id);

    // Baseline the counter BEFORE the call. Delta must be exactly 1 because
    // GlobalExceptionHandler.handleAccessDenied increments once per 403 that
    // reaches the controller. A filter-chain 403 does NOT increment this
    // counter — SecurityConfig uses an inline accessDeniedHandler that writes
    // the same JSON shape without hitting @ExceptionHandler. That's the
    // distinction we care about here (Issue #102 RCA regression guard).
    const counterBefore = await getAccessDeniedCounter();

    const res = await postManualHold(token, nonDvShelter!.id, {
      populationType,
      reason: 'T-55a scenario (b) — unassigned coordinator must be rejected',
    });
    expect(res.status, 'Coordinator not assigned to shelter must be rejected 403').toBe(403);

    // Small delay: Prometheus is scraped with a cache. In the SimpleMeterRegistry
    // default config the counter is immediate, but the actuator endpoint may
    // serve a cached text dump depending on metric registry implementation.
    // 300ms is well within micrometer's default cache window.
    await new Promise(r => setTimeout(r, 300));

    const counterAfter = await getAccessDeniedCounter();
    expect(counterAfter - counterBefore,
        'fabt_http_access_denied_count_total must increment by exactly 1. '
        + 'If this delta is 0, the rejection came from the SecurityConfig filter '
        + 'chain instead of the controller body (GlobalExceptionHandler) — '
        + 'which is the Issue #102 RCA regression. '
        + 'If the delta is > 1, another concurrent test denied access during '
        + 'this call; re-run in serial mode.').toBe(1);
  });

  test('(c) cocadmin bypasses the assignment check — 201 on any shelter', async () => {
    const adminToken = await loginAndGetToken(SEED_USERS.cocadmin);
    const shelters = await listShelters(adminToken);

    // cocadmin can create manual holds on ANY shelter in the tenant — the
    // controller body explicitly excepts admin roles from the assignment
    // check. Pick the FIRST non-DV shelter (cocadmin is not assigned to any
    // shelter by default; if they happened to be assigned the test would still
    // pass because the admin bypass triggers before the assignment check).
    const anyShelter = shelters.find(s => !s.dvShelter && s.totalBedsAvailable != null);
    expect(anyShelter, 'Seed data must include at least one non-DV shelter with capacity data').toBeDefined();
    const populationType = await getFirstPopulationType(adminToken, anyShelter!.id);

    const res = await postManualHold(adminToken, anyShelter!.id, {
      populationType,
      reason: 'T-55a scenario (c) — cocadmin admin bypass',
    });

    expect(res.status, 'COC_ADMIN must bypass the coordinator_assignment check').toBe(201);

    const body = await res.json();
    expect(body.status).toBe('HELD');
    expect(body.notes).toContain('Manual offline hold');
    expect(body.id).toBeTruthy();
    expect(body.expiresAt).toBeTruthy();
  });
});
