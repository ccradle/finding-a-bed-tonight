import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Cross-Tenant Isolation Smoke Test (Issue #117)
 *
 * Verifies the 5 admin surfaces fixed in cross-tenant-isolation-audit
 * Phase 2 still return 404 (D3) when called with a UUID the caller's
 * tenant does not own. Per the same rationale as the Karate smoke spec
 * (e2e/karate/.../cross-tenant-isolation.feature): random UUIDs exercise
 * the same `findByIdAndTenantId` → empty Optional → 404 code path as
 * actual Tenant B UUIDs do, without polluting the deployed env.
 *
 * Per Phase 5.3.3: this entire spec must add ≤ 30 seconds to post-deploy
 * smoke runtime.
 *
 * Usage:
 *   cd e2e/playwright
 *   FABT_BASE_URL=https://findabed.org npx playwright test cross-tenant-isolation --project chromium
 */

const BASE = process.env.FABT_BASE_URL ?? 'https://findabed.org';

type LoginResp = { accessToken: string; refreshToken: string };

async function login(req: APIRequestContext, email: string): Promise<string> {
  const resp = await req.post(`${BASE}/api/v1/auth/login`, {
    data: { tenantSlug: 'dev-coc', email, password: 'admin123' },
  });
  expect(resp.status()).toBe(200);
  const body = (await resp.json()) as LoginResp;
  return body.accessToken;
}

function randomUuid(): string {
  // RFC4122 v4 — sufficient for "definitely doesn't exist in any tenant"
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

test.describe('Cross-tenant isolation (Issue #117)', () => {
  let adminToken: string;
  let cocadminToken: string;

  test.beforeAll(async ({ request }) => {
    adminToken = await login(request, 'admin@dev.fabt.org');
    cocadminToken = await login(request, 'cocadmin@dev.fabt.org');
  });

  test('OAuth2 provider update with foreign UUID → 404', async ({ request }) => {
    const resp = await request.put(`${BASE}/api/v1/oauth2-providers/${randomUuid()}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { clientId: 'attacker', clientSecret: 'attacker-secret', issuerUri: 'https://accounts.google.com' },
    });
    expect(resp.status()).toBe(404);
    const body = await resp.json();
    expect(body.error).toBe('not_found');
    // Defense-in-depth: response must NOT echo the attacker's input
    expect(JSON.stringify(body)).not.toContain('attacker');
  });

  test('OAuth2 provider delete with foreign UUID → 404', async ({ request }) => {
    const resp = await request.delete(`${BASE}/api/v1/oauth2-providers/${randomUuid()}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(resp.status()).toBe(404);
    expect((await resp.json()).error).toBe('not_found');
  });

  test('API key rotate with foreign UUID → 404', async ({ request }) => {
    const resp = await request.post(`${BASE}/api/v1/api-keys/${randomUuid()}/rotate`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(resp.status()).toBe(404);
    const body = await resp.json();
    expect(body.error).toBe('not_found');
    // New plaintext key MUST NOT be in response (would be account takeover)
    expect(body.plaintextKey).toBeUndefined();
  });

  test('API key deactivate with foreign UUID → 404', async ({ request }) => {
    const resp = await request.delete(`${BASE}/api/v1/api-keys/${randomUuid()}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(resp.status()).toBe(404);
    expect((await resp.json()).error).toBe('not_found');
  });

  test('Subscription delete with foreign UUID → 404', async ({ request }) => {
    const resp = await request.delete(`${BASE}/api/v1/subscriptions/${randomUuid()}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(resp.status()).toBe(404);
    expect((await resp.json()).error).toBe('not_found');
  });

  test('Generate access code for foreign user → 404, no code leaked', async ({ request }) => {
    const resp = await request.post(`${BASE}/api/v1/users/${randomUuid()}/generate-access-code`, {
      headers: { Authorization: `Bearer ${cocadminToken}` },
    });
    expect(resp.status()).toBe(404);
    const body = await resp.json();
    expect(body.error).toBe('not_found');
    // Plaintext code MUST NOT be in response (would be account takeover)
    expect(body.code).toBeUndefined();
    expect(body.plaintextCode).toBeUndefined();
  });

  test('TOTP admin disable with foreign user → 404', async ({ request }) => {
    const resp = await request.delete(`${BASE}/api/v1/auth/totp/${randomUuid()}`, {
      headers: { Authorization: `Bearer ${cocadminToken}` },
    });
    expect(resp.status()).toBe(404);
    expect((await resp.json()).error).toBe('not_found');
  });

  test('TOTP regenerate codes for foreign user → 404, no codes leaked', async ({ request }) => {
    const resp = await request.post(`${BASE}/api/v1/auth/totp/${randomUuid()}/regenerate-recovery-codes`, {
      headers: { Authorization: `Bearer ${cocadminToken}` },
    });
    expect(resp.status()).toBe(404);
    const body = await resp.json();
    expect(body.error).toBe('not_found');
    // Backup codes MUST NOT be in response (account takeover pivot)
    expect(body.backupCodes).toBeUndefined();
  });
});
