/**
 * Shared API helpers for transitional-reentry-support slice 4 §14
 * Playwright specs. Every E2E test in §14.* must create its own
 * shelters/users via API rather than depending on seed data
 * (§14.11 + memory: feedback_isolated_test_data.md).
 *
 * <p>All shelters created via {@link createReentryShelter} use names
 * starting with {@code "E2E Test"} so the dev/test
 * {@code DELETE /api/v1/test/reset} endpoint cleans them up
 * (TestResetController.java NAME LIKE 'E2E Test%' clause).
 *
 * <p>The {@code eligibilityCriteria} field on the wire is
 * {@link org.fabt.shared.config.JsonString} — a serialized JSON STRING,
 * not a JSON object. Pass a structured object to
 * {@link createReentryShelter} and this helper will JSON.stringify it
 * before posting; otherwise the controller returns a 500 with a
 * Jackson deserialization error.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

const ADMIN_USER = { email: 'admin@dev.fabt.org', password: 'admin123' };

export interface CriminalRecordPolicy {
  accepts_felonies?: boolean | null;
  excluded_offense_types?: string[];
  individualized_assessment?: boolean | null;
  vawa_protections_apply?: boolean | null;
  notes?: string;
}

export interface EligibilityCriteria {
  criminal_record_policy?: CriminalRecordPolicy;
  program_requirements?: string[];
  documentation_required?: string[];
  intake_hours?: string;
}

export interface ReentryShelterInput {
  /** Required. Will be prefixed with "E2E Test " if not already so cleanup picks it up. */
  name: string;
  /** Default "EMERGENCY". One of the {@code ShelterType} enum values. */
  shelterType?: string;
  /** Default null. */
  county?: string | null;
  /** Default false. */
  requiresVerificationCall?: boolean;
  /**
   * Optional structured object — will be JSON.stringify'd into the
   * wire {@code eligibilityCriteria} string. Pass {@code null}/omit
   * to leave the JSONB column null (BedSearchService H1 branch (c)
   * any-null path stays reachable).
   */
  eligibilityCriteria?: EligibilityCriteria | null;
  /** Default {@code SINGLE_ADULT}. */
  populationType?: string;
  /** Default 10. */
  bedsTotal?: number;
  /** Default 2. */
  bedsOccupied?: number;
}

export interface CreatedShelter {
  id: string;
  name: string;
  shelterType: string;
  county: string | null;
  requiresVerificationCall: boolean;
  populationType: string;
}

export async function loginAndGetToken(user: { email: string; password: string } = ADMIN_USER): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: user.email, password: user.password, tenantSlug: TENANT_SLUG }),
  });
  if (!res.ok) {
    throw new Error(`Login failed for ${user.email}: ${res.status} ${await res.text()}`);
  }
  const body = await res.json();
  return body.accessToken as string;
}

/**
 * Create a shelter via {@code POST /api/v1/shelters} with the slice 4
 * fields populated. Returns the created shelter id + key fields for
 * test assertions. Always uses an "E2E Test" name prefix so the dev
 * test/reset endpoint cleans up.
 */
export async function createReentryShelter(
  adminToken: string,
  input: ReentryShelterInput,
): Promise<CreatedShelter> {
  const populationType = input.populationType || 'SINGLE_ADULT';
  const name = input.name.startsWith('E2E Test') ? input.name : `E2E Test ${input.name}`;
  const shelterType = input.shelterType || 'EMERGENCY';

  const constraints: Record<string, unknown> = {
    sobrietyRequired: false,
    idRequired: false,
    referralRequired: false,
    petsAllowed: false,
    wheelchairAccessible: true,
    populationTypesServed: [populationType],
  };

  // eligibilityCriteria is a JsonString on the wire — backend Jackson
  // deserializer rejects raw objects. Stringify if present.
  if (input.eligibilityCriteria) {
    constraints.eligibilityCriteria = JSON.stringify(input.eligibilityCriteria);
  }

  const payload = {
    name,
    addressStreet: '100 Test St',
    addressCity: 'Smithfield',
    addressState: 'NC',
    addressZip: '27577',
    phone: '919-555-0100',
    latitude: 35.5085,
    longitude: -78.3394,
    dvShelter: false,
    shelterType,
    county: input.county === undefined ? null : input.county,
    requiresVerificationCall: input.requiresVerificationCall ?? false,
    constraints,
    capacities: [
      {
        populationType,
        bedsTotal: input.bedsTotal ?? 10,
        bedsOccupied: input.bedsOccupied ?? 2,
      },
    ],
  };

  const res = await fetch(`${API_URL}/api/v1/shelters`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(`Create shelter failed: ${res.status} ${await res.text()}`);
  }
  const body = await res.json();
  return {
    id: body.id,
    name: body.name,
    shelterType: body.shelterType,
    county: body.county,
    requiresVerificationCall: body.requiresVerificationCall,
    populationType,
  };
}

/**
 * Create a dedicated COORDINATOR-role test user. Email pattern matches
 * {@code TestResetController}'s cleanup regex ({@code coord-e2e-} prefix
 * → caught by {@code email LIKE 'e2e-%'} after Date.now suffix; we use
 * the {@code e2e-} prefix to be explicit).
 */
export async function createTestCoordinator(
  adminToken: string,
  shelterId?: string,
): Promise<{ id: string; email: string; password: string }> {
  const ts = Date.now();
  const email = `e2e-coord-${ts}@dev.fabt.org`;
  const password = 'TestPassword123!';
  const res = await fetch(`${API_URL}/api/v1/users`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email,
      displayName: `E2E Coord ${ts}`,
      password,
      roles: ['COORDINATOR'],
      dvAccess: false,
    }),
  });
  if (!res.ok) {
    throw new Error(`Create test coordinator failed: ${res.status} ${await res.text()}`);
  }
  const body = await res.json();
  const userId: string = body.id;

  if (shelterId) {
    const assignRes = await fetch(`${API_URL}/api/v1/shelters/${shelterId}/coordinators`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId }),
    });
    if (!assignRes.ok) {
      throw new Error(`Assign coordinator to shelter failed: ${assignRes.status} ${await assignRes.text()}`);
    }
  }

  return { id: userId, email, password };
}

export const TENANT = TENANT_SLUG;
export const API_BASE = API_URL;
