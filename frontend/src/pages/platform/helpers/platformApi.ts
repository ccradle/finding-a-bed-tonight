/**
 * Platform-operator fetch wrapper (F11 v0.54).
 *
 * Injects `Authorization: Bearer <platform-jwt>` from sessionStorage on
 * every request to /api/v1/auth/platform/* or /api/v1/platform/*.
 *
 * Response handling:
 * - 401: any in-flight request that gets 401 from the platform layer
 *   means the token is bad (revoked, malformed, signature mismatch, or
 *   server-side invalidation). Wipe sessionStorage and redirect to
 *   /platform/login. Concurrent 401s are coalesced via a module-level
 *   `isHandling401` flag so multiple tabs/cards firing simultaneously
 *   don't double-navigate (some routers throw on duplicate navigations).
 * - 403 from /me specifically: indicates a wrong-scope token (typically
 *   MFA-setup-only). Redirect to /platform/mfa-enroll WITHOUT wiping
 *   sessionStorage — the MFA-setup token remains valid for enrollment.
 * - 410: handled by the caller (dashboard); wrapper passes through.
 */

import { readPlatformJwt, clearPlatformJwt } from './platformJwt';

let isHandling401 = false;

function navigateToLogin(): void {
  if (isHandling401) return;
  isHandling401 = true;
  clearPlatformJwt();
  // Use window.location instead of react-router because the wrapper has
  // no router context. Triggers a full reload, which also clears any
  // stale in-memory state from React components mid-render.
  window.location.href = '/platform/login';
}

function navigateToMfaEnroll(): void {
  // Don't wipe sessionStorage — the MFA-setup-scoped token is still
  // valid for the enrollment flow. Set the same coalescing flag because
  // multiple components shouldn't all kick off enrollment redirects.
  if (isHandling401) return;
  isHandling401 = true;
  window.location.href = '/platform/mfa-enroll';
}

/**
 * Wraps `fetch()` with platform-JWT injection and centralized 401/403
 * handling. Returns the raw Response for callers that need status codes
 * outside the auth flow (e.g., 410 → "operator gone, contact support").
 */
export async function platformFetch(
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const token = readPlatformJwt();
  const headers = new Headers(init.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(input, { ...init, headers });

  if (response.status === 401) {
    navigateToLogin();
    return response;
  }

  if (response.status === 403 && input.includes('/auth/platform/me')) {
    navigateToMfaEnroll();
    return response;
  }

  return response;
}

/**
 * Test-only reset for the module-level isHandling401 flag. Real navigation
 * triggers a full page reload (which resets the flag implicitly) but unit
 * tests don't actually navigate, so they need a manual reset between cases.
 */
export function __resetIsHandling401ForTests(): void {
  isHandling401 = false;
}
