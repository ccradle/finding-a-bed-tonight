import { describe, expect, it } from 'vitest';
import { decodeJwtPayload } from './AuthContext';

/**
 * Round 5 §16.C.6 — DecodedUser.reentryMode parses correctly from the
 * JWT payload emitted by the backend's JwtService at §16.A.
 *
 * <p>Pure-function test: builds JWTs by hand with various reentryMode
 * shapes and asserts the decoded value matches the production-correct
 * fail-safe semantics (any non-true value → false).
 *
 * <p>The four gating sites in §16.C all do `{user?.reentryMode && (...)}`.
 * If decodeJwtPayload misparsed the claim — e.g., truthy-coerced
 * {@code "true"} or fell back to undefined — the gates would either over-
 * or under-render. This test pins the exact contract.
 */

function makeJwt(payload: Record<string, unknown>): string {
  const b64url = (s: string) =>
    btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify(payload));
  return `${header}.${body}.signature-not-verified-clientside`;
}

describe('decodeJwtPayload — reentryMode claim', () => {
  it('reentryMode=true → decoded user has reentryMode true', () => {
    const token = makeJwt({
      sub: 'u',
      tenantId: 't',
      roles: ['COC_ADMIN'],
      dvAccess: false,
      reentryMode: true,
      exp: 9999999999,
    });
    const user = decodeJwtPayload(token);
    expect(user?.reentryMode).toBe(true);
  });

  it('reentryMode=false → decoded user has reentryMode false', () => {
    const token = makeJwt({
      sub: 'u',
      tenantId: 't',
      reentryMode: false,
      exp: 9999999999,
    });
    expect(decodeJwtPayload(token)?.reentryMode).toBe(false);
  });

  it('reentryMode missing → decoded user has reentryMode false (safe default)', () => {
    const token = makeJwt({ sub: 'u', tenantId: 't', exp: 9999999999 });
    expect(decodeJwtPayload(token)?.reentryMode).toBe(false);
  });

  it('reentryMode="true" (string) → decoded user has reentryMode false (strict comparison)', () => {
    const token = makeJwt({
      sub: 'u',
      tenantId: 't',
      reentryMode: 'true',
      exp: 9999999999,
    });
    // Strict === true comparison rejects truthy-string coercion. This pins
    // the contract — a future "convenience" change to truthy coercion
    // would silently surface PII for tenants whose legacy config values
    // are stringified.
    expect(decodeJwtPayload(token)?.reentryMode).toBe(false);
  });

  it('reentryMode=null → decoded user has reentryMode false', () => {
    const token = makeJwt({
      sub: 'u',
      tenantId: 't',
      reentryMode: null,
      exp: 9999999999,
    });
    expect(decodeJwtPayload(token)?.reentryMode).toBe(false);
  });

  it('reentryMode coexists with dvAccess (independent flags)', () => {
    const token = makeJwt({
      sub: 'u',
      tenantId: 't',
      dvAccess: true,
      reentryMode: true,
      exp: 9999999999,
    });
    const user = decodeJwtPayload(token);
    expect(user?.dvAccess).toBe(true);
    expect(user?.reentryMode).toBe(true);
  });

  it('malformed JWT (no second segment) → null user', () => {
    expect(decodeJwtPayload('garbage')).toBeNull();
  });
});
