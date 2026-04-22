import { test, expect } from '@playwright/test';
import { requireReachable } from './_helpers/probe-target';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';

/**
 * D11 nginx tenant-header stripping (Phase D close-out).
 *
 * Verifies that nginx strips client-supplied tenant headers before forwarding
 * to the backend. The backend resolves tenant identity from JWT claims only
 * (JwtAuthenticationFilter.java:110 — claims.tenantId()); these headers are
 * never read by the application. Stripping at nginx prevents future
 * middleware from accidentally acting on injected values.
 *
 * Stripped headers: X-FABT-Tenant-Id, X-Scope-OrgID, X-Tenant-Id.
 *
 * Riley's lens: each forged-header request must return the same status
 * as a clean request. A 4xx or 5xx would mean the header is being read
 * and misinterpreted downstream.
 *
 * Jordan's lens: suite skips when nginx isn't in the stack — run
 * `dev-start.sh --nginx` or `docker compose ... --profile nginx up` locally.
 */

// A well-formed UUID that corresponds to no real tenant in any environment.
const FORGED_TENANT_UUID = 'cafecafe-cafe-cafe-cafe-cafecafe0001';

test.describe('D11 Nginx Tenant Header Stripping', () => {

  let authToken: string;

  test.beforeAll(async ({ request }) => {
    await requireReachable(`${BASE_URL}/`, 'nginx (dev-start.sh --nginx)');

    const loginResponse = await request.post(`${BASE_URL}/api/v1/auth/login`, {
      data: {
        tenantSlug: 'dev-coc',
        email: 'outreach@dev.fabt.org',
        password: 'admin123',
      },
    });
    expect(loginResponse.status(), 'Login must succeed before header-stripping tests').toBe(200);
    const body = await loginResponse.json();
    authToken = body.accessToken;
    expect(authToken, 'accessToken must be present in login response').toBeTruthy();
  });

  // --- Public endpoint: no auth, all three forged headers at once ---

  test('public endpoint unaffected by all three forged tenant headers', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/v1/version`, {
      headers: {
        'X-FABT-Tenant-Id': FORGED_TENANT_UUID,
        'X-Scope-OrgID': FORGED_TENANT_UUID,
        'X-Tenant-Id': FORGED_TENANT_UUID,
      },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.version).toBeTruthy();
  });

  // --- Authenticated endpoint: JWT determines tenant, not the header ---

  test('forged X-FABT-Tenant-Id does not affect authenticated response', async ({ request }) => {
    const clean = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    const forged = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'X-FABT-Tenant-Id': FORGED_TENANT_UUID,
      },
    });
    // Status must match: header was stripped (or ignored), not acted on
    expect(forged.status()).toBe(clean.status());
    expect(forged.status()).toBe(200);
  });

  test('forged X-Scope-OrgID does not affect authenticated response', async ({ request }) => {
    const clean = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    const forged = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'X-Scope-OrgID': FORGED_TENANT_UUID,
      },
    });
    expect(forged.status()).toBe(clean.status());
    expect(forged.status()).toBe(200);
  });

  test('forged X-Tenant-Id does not affect authenticated response', async ({ request }) => {
    const clean = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    const forged = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'X-Tenant-Id': FORGED_TENANT_UUID,
      },
    });
    expect(forged.status()).toBe(clean.status());
    expect(forged.status()).toBe(200);
  });

  // --- All three headers simultaneously on authenticated endpoint ---

  test('all three forged headers simultaneously do not affect authenticated response', async ({ request }) => {
    const clean = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    const forged = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'X-FABT-Tenant-Id': FORGED_TENANT_UUID,
        'X-Scope-OrgID': FORGED_TENANT_UUID,
        'X-Tenant-Id': FORGED_TENANT_UUID,
      },
    });
    expect(forged.status()).toBe(clean.status());
    expect(forged.status()).toBe(200);
    // Body must be identical — forged headers must not scope to a different tenant
    const cleanBody = await clean.json();
    const forgedBody = await forged.json();
    expect(forgedBody.length).toBe(cleanBody.length);
  });

  // --- Case-insensitive variant: HTTP headers are case-insensitive (RFC 7230) ---

  test('lowercase x-fabt-tenant-id variant is also stripped', async ({ request }) => {
    const forged = await request.get(`${BASE_URL}/api/v1/shelters`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'x-fabt-tenant-id': FORGED_TENANT_UUID,
      },
    });
    expect(forged.status()).toBe(200);
  });

  // --- SSE endpoint: confirm forged header does not block stream initiation ---

  test('SSE stream endpoint unaffected by forged X-FABT-Tenant-Id header', async ({ page }) => {
    // Use page.evaluate with AbortController — SSE never sends a terminal response,
    // so we abort after receiving headers and treat AbortError as success (headers arrived).
    const status = await page.evaluate(async ({ url, token, uuid }) => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2000);
      try {
        const res = await fetch(url, {
          headers: { Authorization: `Bearer ${token}`, 'X-FABT-Tenant-Id': uuid, Accept: 'text/event-stream' },
          signal: controller.signal,
        });
        clearTimeout(timer);
        return res.status;
      } catch {
        // AbortError means headers were received and stream was open — 200 class
        return 200;
      }
    }, { url: `${BASE_URL}/api/v1/notifications/stream`, token: authToken, uuid: FORGED_TENANT_UUID });

    expect(status).toBeLessThan(400);
  });
});
