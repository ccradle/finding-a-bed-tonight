import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Minimal sessionStorage stub for node-env vitest. We avoid the jsdom
 * dep (heavy + would need persona research per memory rules); the
 * helpers under test only use getItem/setItem/removeItem.
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

import {
  PLATFORM_JWT_STORAGE_KEY,
  clearPlatformJwt,
  isExpired,
  parseClaims,
  readPlatformJwt,
  secondsUntilExpiry,
  writePlatformJwt,
} from './platformJwt';

/**
 * Pure-function tests for the platform JWT helpers (warroom round 5 H5).
 *
 * These functions are load-bearing for the route guard, the API wrapper,
 * and the banner countdown. A regression that breaks the null-on-failure
 * contract or the synchronous expiry check would silently propagate.
 */

/**
 * Build a JWT-shaped string with arbitrary payload + bogus signature.
 * The signature is never verified client-side; what matters for these
 * tests is that parseClaims sees a 3-segment string and decodes the
 * middle segment.
 */
function makeJwt(payload: object): string {
  const b64 = (s: string) =>
    btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64(JSON.stringify(payload));
  return `${header}.${body}.signature-not-verified-clientside`;
}

describe('parseClaims', () => {
  it('returns null on null token', () => {
    expect(parseClaims(null)).toBeNull();
  });

  it('returns null on empty string', () => {
    expect(parseClaims('')).toBeNull();
  });

  it('returns null on token with fewer than 3 segments', () => {
    expect(parseClaims('a.b')).toBeNull();
    expect(parseClaims('only-one')).toBeNull();
  });

  it('returns null on token with more than 3 segments', () => {
    expect(parseClaims('a.b.c.d')).toBeNull();
  });

  it('returns null when middle segment is not base64url', () => {
    expect(parseClaims('h.!!not-base64!!.s')).toBeNull();
  });

  it('returns null when payload is not valid JSON', () => {
    const b64 = (s: string) => btoa(s).replace(/=+$/, '');
    const notJson = `h.${b64('this is not json')}.s`;
    expect(parseClaims(notJson)).toBeNull();
  });

  it('returns null when payload is a JSON primitive (not object)', () => {
    const b64 = (s: string) => btoa(s).replace(/=+$/, '');
    expect(parseClaims(`h.${b64('"a string"')}.s`)).toBeNull();
    expect(parseClaims(`h.${b64('42')}.s`)).toBeNull();
    expect(parseClaims(`h.${b64('true')}.s`)).toBeNull();
    // null parses to typeof "object" but our explicit `payload === null` check rejects.
    expect(parseClaims(`h.${b64('null')}.s`)).toBeNull();
  });

  it('returns parsed claims for a well-formed token', () => {
    const claims = parseClaims(
      makeJwt({ iss: 'fabt-platform', sub: 'op-1', exp: 1234567890 }),
    );
    expect(claims).not.toBeNull();
    expect(claims?.iss).toBe('fabt-platform');
    expect(claims?.sub).toBe('op-1');
    expect(claims?.exp).toBe(1234567890);
  });

  it('returns null on a token longer than the 8KB cap (DoS defense)', () => {
    // Build a payload that pushes the token past 8192 chars total.
    const huge = 'x'.repeat(10_000);
    const oversized = makeJwt({ iss: 'fabt-platform', filler: huge });
    expect(oversized.length).toBeGreaterThan(8192);
    expect(parseClaims(oversized)).toBeNull();
  });
});

describe('isExpired', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-27T18:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns true on null token', () => {
    expect(isExpired(null)).toBe(true);
  });

  it('returns true on malformed token', () => {
    expect(isExpired('not.a.token')).toBe(true);
  });

  it('returns true when exp claim is missing', () => {
    expect(isExpired(makeJwt({ iss: 'fabt-platform', sub: 'op-1' }))).toBe(true);
  });

  it('returns true when exp claim is non-numeric', () => {
    // Type-coerced to any to test runtime defensive behavior.
    const t = makeJwt({ exp: 'not-a-number' as unknown as number });
    expect(isExpired(t)).toBe(true);
  });

  it('returns true when exp = 0 (epoch / 1970)', () => {
    expect(isExpired(makeJwt({ exp: 0 }))).toBe(true);
  });

  it('returns true when exp is in the past', () => {
    const yesterday = Math.floor(Date.now() / 1000) - 86_400;
    expect(isExpired(makeJwt({ exp: yesterday }))).toBe(true);
  });

  it('returns false when exp is in the future', () => {
    const tomorrow = Math.floor(Date.now() / 1000) + 86_400;
    expect(isExpired(makeJwt({ exp: tomorrow }))).toBe(false);
  });

  it('returns true at exactly the exp instant (Date.now() >= exp*1000)', () => {
    const now = Math.floor(Date.now() / 1000);
    expect(isExpired(makeJwt({ exp: now }))).toBe(true);
  });
});

describe('secondsUntilExpiry', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-27T18:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns -1 on null token', () => {
    expect(secondsUntilExpiry(null)).toBe(-1);
  });

  it('returns -1 on malformed token', () => {
    expect(secondsUntilExpiry('a.b.c')).toBe(-1);
  });

  it('returns -1 when exp is missing', () => {
    expect(secondsUntilExpiry(makeJwt({}))).toBe(-1);
  });

  it('returns negative seconds for expired token', () => {
    const tenMinAgo = Math.floor(Date.now() / 1000) - 600;
    expect(secondsUntilExpiry(makeJwt({ exp: tenMinAgo }))).toBe(-600);
  });

  it('returns ~900 for a freshly-issued 15-min access token', () => {
    const fifteenMinFromNow = Math.floor(Date.now() / 1000) + 900;
    const result = secondsUntilExpiry(makeJwt({ exp: fifteenMinFromNow }));
    // Allow ±1s for floor truncation noise.
    expect(result).toBeGreaterThanOrEqual(899);
    expect(result).toBeLessThanOrEqual(900);
  });
});

describe('sessionStorage R/W helpers', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('writePlatformJwt + readPlatformJwt round-trip under the v1 key', () => {
    writePlatformJwt('token-abc');
    expect(readPlatformJwt()).toBe('token-abc');
    expect(sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY)).toBe('token-abc');
  });

  it('clearPlatformJwt removes the key', () => {
    writePlatformJwt('token-xyz');
    clearPlatformJwt();
    expect(readPlatformJwt()).toBeNull();
  });

  it('readPlatformJwt returns null when no token stored', () => {
    expect(readPlatformJwt()).toBeNull();
  });

  it('storage key is namespaced + versioned (regression: silent collision)', () => {
    expect(PLATFORM_JWT_STORAGE_KEY).toBe('fabt.platform.jwt.v1');
  });
});
