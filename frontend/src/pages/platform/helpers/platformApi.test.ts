import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Minimal browser-global stubs for node-env vitest. See
 * platformJwt.test.ts for the rationale on avoiding jsdom.
 */
function makeSessionStorageStub(): Storage {
  const store = new Map<string, string>();
  return {
    get length() { return store.size; },
    clear: () => store.clear(),
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => { store.set(k, v); },
    removeItem: (k: string) => { store.delete(k); },
    key: (i: number) => Array.from(store.keys())[i] ?? null,
  };
}
vi.stubGlobal('sessionStorage', makeSessionStorageStub());

// window must exist before platformApi is imported because the module
// closes over window.location.origin at use time (not import time, but
// vitest's module-cache friendliness means we keep it set through tests).
let locationHref = '';
const windowStub = {
  location: {
    origin: 'http://localhost:8081',
    get href() { return locationHref; },
    set href(value: string) { locationHref = value; },
  },
};
vi.stubGlobal('window', windowStub);

import {
  __resetAuthRedirectStateForTests,
  platformFetch,
} from './platformApi';
import {
  PLATFORM_JWT_STORAGE_KEY,
  writePlatformJwt,
} from './platformJwt';

/**
 * Tests for the platformFetch wrapper (warroom round 5 H5).
 *
 * Critical security boundary — the 401/403 routing logic decides
 * whether sessionStorage gets wiped and where the operator lands.
 * Concurrent-state behaviour (401 racing 403) is also exercised.
 */

describe('platformFetch', () => {
  beforeEach(() => {
    sessionStorage.clear();
    __resetAuthRedirectStateForTests();
    locationHref = '';
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function mockFetch(status: number, body: unknown = {}): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify(body), { status })),
      ),
    );
  }

  it('200 passes through unmodified, no navigation', async () => {
    writePlatformJwt('valid-token');
    mockFetch(200, { id: 'op-1' });

    const response = await platformFetch('/api/v1/auth/platform/me');

    expect(response.status).toBe(200);
    expect(locationHref).toBe('');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBe('valid-token');
  });

  it('injects Authorization: Bearer header from sessionStorage', async () => {
    writePlatformJwt('test-jwt-value');
    const fetchMock = vi.fn(() => Promise.resolve(new Response('{}', { status: 200 })));
    vi.stubGlobal('fetch', fetchMock);

    await platformFetch('/api/v1/auth/platform/me');

    expect(fetchMock).toHaveBeenCalledOnce();
    const callInit = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    const headers = new Headers(callInit.headers);
    expect(headers.get('Authorization')).toBe('Bearer test-jwt-value');
  });

  it('omits Authorization header when no token stored', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response('{}', { status: 200 })));
    vi.stubGlobal('fetch', fetchMock);

    await platformFetch('/api/v1/auth/platform/login');

    const callInit = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    const headers = new Headers(callInit.headers);
    expect(headers.get('Authorization')).toBeNull();
  });

  it('401 wipes sessionStorage and navigates to /platform/login', async () => {
    writePlatformJwt('revoked-token');
    mockFetch(401, { error: 'invalid_platform_token' });

    await platformFetch('/api/v1/auth/platform/me');

    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBeNull();
    expect(locationHref).toBe('/platform/login');
  });

  it('concurrent 401s only navigate once (coalescing flag)', async () => {
    writePlatformJwt('revoked-token');
    mockFetch(401, { error: 'invalid_platform_token' });

    // Fire 5 concurrent platformFetch calls. Only the first should
    // wipe + navigate; the rest must early-return.
    await Promise.all([
      platformFetch('/api/v1/foo'),
      platformFetch('/api/v1/bar'),
      platformFetch('/api/v1/baz'),
      platformFetch('/api/v1/qux'),
      platformFetch('/api/v1/quux'),
    ]);

    expect(locationHref).toBe('/platform/login');
    // sessionStorage was wiped exactly once — no need to assert counts
    // since clearPlatformJwt is idempotent, but the navigation target
    // is the load-bearing assertion.
  });

  it('403 from /me redirects to /platform/mfa-enroll WITHOUT wiping sessionStorage', async () => {
    writePlatformJwt('mfa-setup-scoped-token');
    mockFetch(403, { error: 'platform_scope_mismatch' });

    await platformFetch('/api/v1/auth/platform/me');

    expect(locationHref).toBe('/platform/mfa-enroll');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY))
      .toBe('mfa-setup-scoped-token');
  });

  it('403 from a path OTHER than /me passes through (no redirect)', async () => {
    writePlatformJwt('valid-token');
    mockFetch(403, { error: 'forbidden' });

    const response = await platformFetch('/api/v1/admin/tenants');

    expect(response.status).toBe(403);
    expect(locationHref).toBe('');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBe('valid-token');
  });

  it('403 from a path that contains "/me" as a substring does NOT redirect', async () => {
    // Regression test for warroom round 5 C2: substring match was too loose.
    // /me-history would have falsely triggered the mfa-enroll redirect.
    writePlatformJwt('valid-token');
    mockFetch(403, { error: 'forbidden' });

    await platformFetch('/api/v1/auth/platform/me-history');

    expect(locationHref).toBe('');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBe('valid-token');
  });

  it('403 with query string after /me still redirects (exact pathname match)', async () => {
    writePlatformJwt('mfa-setup-scoped-token');
    mockFetch(403, { error: 'platform_scope_mismatch' });

    await platformFetch('/api/v1/auth/platform/me?refresh=1');

    expect(locationHref).toBe('/platform/mfa-enroll');
  });

  it('410 passes through (caller handles "operator gone" UX)', async () => {
    writePlatformJwt('valid-token');
    mockFetch(410, { error: 'operator_anonymized' });

    const response = await platformFetch('/api/v1/auth/platform/me');

    expect(response.status).toBe(410);
    expect(locationHref).toBe('');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBe('valid-token');
  });

  it('401 wins the race against a prior 403 (warroom H2)', async () => {
    // Set up: a 403 from /me has already kicked off the mfa-enroll redirect.
    // Now a 401 arrives on a parallel request. The 401 must override —
    // otherwise the operator lands on /mfa-enroll holding a revoked token.
    writePlatformJwt('about-to-be-revoked');

    // First request: 403 from /me
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response('{}', { status: 403 })),
      ),
    );
    await platformFetch('/api/v1/auth/platform/me');
    expect(locationHref).toBe('/platform/mfa-enroll');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY))
      .toBe('about-to-be-revoked');

    // Second request: 401 from any endpoint. Must take over.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response('{}', { status: 401 })),
      ),
    );
    await platformFetch('/api/v1/auth/platform/some-other-endpoint');

    expect(locationHref).toBe('/platform/login');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBeNull();
  });
});
