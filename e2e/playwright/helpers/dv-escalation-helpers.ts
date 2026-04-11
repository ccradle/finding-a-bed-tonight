/**
 * Test helpers for the coc-admin-escalation Playwright specs (Session 6).
 *
 * These wrap the backend's REST API to set up test data without going
 * through the UI — fast, isolated, and parallel-safe (per memory
 * `feedback_isolated_test_data`). Each spec creates its own DV shelter
 * + referral so concurrent test runs do not collide on the
 * "one PENDING per user per shelter" guard.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

const SEED_USERS = {
  admin: { email: 'admin@dev.fabt.org', password: 'admin123' },
  cocadmin: { email: 'cocadmin@dev.fabt.org', password: 'admin123' },
  dvOutreach: { email: 'dv-outreach@dev.fabt.org', password: 'admin123' },
};

type SeedUser = keyof typeof SEED_USERS;

/**
 * Login as a seed user via the REST API and return the access token.
 * Used by helpers that need to make authenticated API calls outside the
 * browser context (e.g. creating test fixtures before a test).
 */
export async function loginAsSeedUser(role: SeedUser): Promise<string> {
  const user = SEED_USERS[role];
  const resp = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: user.email, password: user.password }),
  });
  if (!resp.ok) {
    throw new Error(`loginAsSeedUser(${role}): ${resp.status} ${resp.statusText}`);
  }
  const { accessToken } = await resp.json();
  return accessToken;
}

/**
 * Create a fresh DV shelter via the admin REST API. Returns the shelter id.
 * Each test gets its own shelter so the "one PENDING per user per shelter"
 * guard doesn't fight parallel test execution.
 */
export async function createTestDvShelter(adminToken: string, label: string): Promise<string> {
  const uniqueSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  // Prefix is 'E2E Test DV ' so TestResetController's DELETE pattern
  // (WHERE name LIKE 'E2E Test%') picks these up via cleanupTestData().
  // Without the prefix, accumulated test shelters crowd dv-outreach-worker's
  // bed-search results and break the seed-shelter assertions downstream.
  const shelterBody = {
    name: `E2E Test DV ${label} ${uniqueSuffix}`,
    addressStreet: '123 Test St',
    addressCity: 'Raleigh',
    addressState: 'NC',
    addressZip: '27601',
    phone: '919-555-0099',
    dvShelter: true,
    constraints: { populationTypesServed: ['DV_SURVIVOR'] },
    capacities: [{ populationType: 'DV_SURVIVOR', bedsTotal: 10 }],
  };

  const createResp = await fetch(`${API_URL}/api/v1/shelters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify(shelterBody),
  });
  if (!createResp.ok) {
    throw new Error(`createTestDvShelter: ${createResp.status} ${createResp.statusText} — ${await createResp.text()}`);
  }
  const shelter = await createResp.json();

  // Patch availability so referrals can target the shelter (capacity > 0).
  const availResp = await fetch(`${API_URL}/api/v1/shelters/${shelter.id}/availability`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify({
      populationType: 'DV_SURVIVOR',
      bedsTotal: 10,
      bedsOccupied: 3,
      bedsOnHold: 0,
      acceptingNewGuests: true,
    }),
  });
  if (!availResp.ok) {
    throw new Error(`createTestDvShelter availability: ${availResp.status} ${availResp.statusText}`);
  }

  return shelter.id;
}

/**
 * Create a fresh DV referral targeting the given shelter, posted by the
 * dv-outreach seed user. Returns the referral_token id.
 */
export async function createTestDvReferral(shelterId: string): Promise<string> {
  const dvOutreachToken = await loginAsSeedUser('dvOutreach');
  const resp = await fetch(`${API_URL}/api/v1/dv-referrals`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${dvOutreachToken}` },
    body: JSON.stringify({
      shelterId,
      householdSize: 2,
      populationType: 'DV_SURVIVOR',
      urgency: 'URGENT',
      specialNeeds: null,
      callbackNumber: '919-555-0099',
    }),
  });
  if (!resp.ok) {
    throw new Error(`createTestDvReferral: ${resp.status} ${resp.statusText} — ${await resp.text()}`);
  }
  const referral = await resp.json();
  return referral.id;
}

/**
 * One-shot helper: create a DV shelter AND a pending referral against it,
 * return both ids. Most tests need this combo.
 */
export async function setupTestReferral(label: string): Promise<{ shelterId: string; referralId: string }> {
  const adminToken = await loginAsSeedUser('admin');
  const shelterId = await createTestDvShelter(adminToken, label);
  const referralId = await createTestDvReferral(shelterId);
  return { shelterId, referralId };
}

/**
 * Read the current escalation policy for an event type via the admin API.
 * Used by the policy spec to verify version increments.
 */
export async function getEscalationPolicy(eventType: string): Promise<{ id: string; version: number; thresholds: Array<{ id: string; at: string; severity: string; recipients: string[] }> }> {
  const cocadminToken = await loginAsSeedUser('cocadmin');
  const resp = await fetch(`${API_URL}/api/v1/admin/escalation-policy/${eventType}`, {
    headers: { Authorization: `Bearer ${cocadminToken}` },
  });
  if (!resp.ok) {
    throw new Error(`getEscalationPolicy: ${resp.status}`);
  }
  return resp.json();
}

/**
 * Platform default thresholds for the {@code dv-referral} event type, mirroring
 * the V40 seed. Kept in sync with
 * {@code backend/src/main/resources/db/migration/V40__create_escalation_policy.sql}
 * — if the seed changes, update this constant.
 *
 * <p>Used by {@link resetDvReferralPolicyToDefault} so every coc-escalation-policy
 * spec test starts from a known clean baseline, regardless of mutations from
 * prior test runs. Per memory <code>feedback_isolated_test_data</code>: tests
 * must not depend on leftover state from previous runs.</p>
 */
const DV_REFERRAL_DEFAULT_THRESHOLDS = [
  { id: '1h',   at: 'PT1H',    severity: 'ACTION_REQUIRED', recipients: ['COORDINATOR'] },
  { id: '2h',   at: 'PT2H',    severity: 'CRITICAL',        recipients: ['COC_ADMIN'] },
  { id: '3_5h', at: 'PT3H30M', severity: 'CRITICAL',        recipients: ['COORDINATOR', 'OUTREACH_WORKER'] },
  { id: '4h',   at: 'PT4H',    severity: 'ACTION_REQUIRED', recipients: ['OUTREACH_WORKER'] },
];

/**
 * Reset the tenant's {@code dv-referral} escalation policy to the platform
 * default. Writes a new policy version (append-only storage), so the effect
 * is idempotent: whatever extra thresholds a previous test added no longer
 * appear in the CURRENT version.
 *
 * <p>Call this from {@code test.beforeEach} in the escalation-policy spec
 * so each test starts with a known 4-threshold baseline.</p>
 */
export async function resetDvReferralPolicyToDefault(): Promise<void> {
  const cocadminToken = await loginAsSeedUser('cocadmin');
  const resp = await fetch(`${API_URL}/api/v1/admin/escalation-policy/dv-referral`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cocadminToken}` },
    body: JSON.stringify({ thresholds: DV_REFERRAL_DEFAULT_THRESHOLDS }),
  });
  if (!resp.ok) {
    throw new Error(`resetDvReferralPolicyToDefault: ${resp.status} ${resp.statusText} — ${await resp.text()}`);
  }
}
