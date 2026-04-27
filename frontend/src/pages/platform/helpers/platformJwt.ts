/**
 * Platform-operator JWT helpers (F11 v0.54).
 *
 * sessionStorage R/W under a namespaced+versioned key so a future schema
 * bump (e.g. adding refresh-token tracking in v0.55) doesn't collide with
 * existing v1 tokens. Tab close clears sessionStorage by design — see
 * design.md Decision D1.
 *
 * Claim parsing reads the JWT payload only — signature validation is
 * NEVER attempted client-side. The backend's PlatformJwtService is the
 * authoritative validator. Client code uses the parsed claims for
 * routing decisions (guard, expiry countdown, banner display) and
 * trusts the server to reject any forged token at the next request.
 */

export const PLATFORM_JWT_STORAGE_KEY = 'fabt.platform.jwt.v1';

/**
 * Decoded payload of a platform access token. Mirrors
 * {@code PlatformJwtService.PlatformJwtClaims} on the backend, minus the
 * cryptographic envelope (header + signature).
 */
export interface PlatformJwtClaims {
  iss: string;
  sub: string;
  roles?: string[];
  mfaVerified?: boolean;
  scope?: string;
  iat: number;
  exp: number;
}

export function readPlatformJwt(): string | null {
  return sessionStorage.getItem(PLATFORM_JWT_STORAGE_KEY);
}

export function writePlatformJwt(token: string): void {
  sessionStorage.setItem(PLATFORM_JWT_STORAGE_KEY, token);
}

export function clearPlatformJwt(): void {
  sessionStorage.removeItem(PLATFORM_JWT_STORAGE_KEY);
}

/**
 * Parses a JWT's payload segment without verifying the signature. Returns
 * null on any structural failure (not 3 segments, payload not base64url,
 * payload not JSON). Never throws.
 */
export function parseClaims(token: string | null): PlatformJwtClaims | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const payloadB64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = payloadB64 + '='.repeat((4 - (payloadB64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded));
    if (typeof payload !== 'object' || payload === null) return null;
    return payload as PlatformJwtClaims;
  } catch {
    return null;
  }
}

/**
 * Returns true if the token is missing, malformed, or has `exp` in the past.
 * Synchronous + cheap so the route guard can call it on every render
 * before initiating any child fetch (prevents 401-vs-expiry-redirect race).
 */
export function isExpired(token: string | null): boolean {
  const claims = parseClaims(token);
  if (!claims || typeof claims.exp !== 'number') return true;
  return Date.now() >= claims.exp * 1000;
}

/**
 * Returns whole seconds remaining until the token's `exp`. Negative if
 * already expired. Used by the banner countdown timer.
 */
export function secondsUntilExpiry(token: string | null): number {
  const claims = parseClaims(token);
  if (!claims || typeof claims.exp !== 'number') return -1;
  return Math.floor(claims.exp - Date.now() / 1000);
}
