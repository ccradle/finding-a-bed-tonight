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

/**
 * Coalescing flag for auth redirects. Two states:
 *   'none'        — no redirect in flight
 *   'mfa-enroll'  — a 403/me handler queued an enrollment redirect
 *   'login'       — a 401 handler queued a login redirect (force-clear)
 * 401 ALWAYS wins over 403: if a 403 already set the flag to 'mfa-enroll'
 * and a parallel 401 arrives, the 401 wipes sessionStorage and overrides
 * the navigation target to /platform/login. Otherwise the operator could
 * land on /mfa-enroll holding a revoked token.
 */
type AuthRedirectState = 'none' | 'mfa-enroll' | 'login';
let authRedirectState: AuthRedirectState = 'none';

function navigateToLogin(): void {
  // 401 always wins — overrides any prior 'mfa-enroll' state.
  if (authRedirectState === 'login') return;
  authRedirectState = 'login';
  clearPlatformJwt();
  // Use window.location instead of react-router because the wrapper has
  // no router context. Triggers a full reload, which also clears any
  // stale in-memory state from React components mid-render.
  window.location.href = '/platform/login';
}

function navigateToMfaEnroll(): void {
  // 403 yields if a 401 has already won the race.
  if (authRedirectState !== 'none') return;
  authRedirectState = 'mfa-enroll';
  // Don't wipe sessionStorage — the MFA-setup-scoped token is still
  // valid for the enrollment flow.
  window.location.href = '/platform/mfa-enroll';
}

/**
 * Tightened path check for the /me-only 403 redirect. Substring matching
 * (`input.includes('/auth/platform/me')`) would also match `/me-history`,
 * `/messages`, query strings containing the substring, etc. Use URL
 * parsing so only the exact `/api/v1/auth/platform/me` pathname matches.
 */
function isPlatformMePath(input: string): boolean {
  try {
    // Resolve against window.location for relative URLs (the typical case).
    const url = new URL(input, window.location.origin);
    return url.pathname === '/api/v1/auth/platform/me';
  } catch {
    return false;
  }
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

  if (response.status === 403 && isPlatformMePath(input)) {
    navigateToMfaEnroll();
    return response;
  }

  return response;
}

/**
 * Test-only reset for the module-level authRedirectState. Real navigation
 * triggers a full page reload (which resets module state implicitly) but
 * unit tests don't actually navigate, so they need a manual reset between
 * cases.
 */
export function __resetAuthRedirectStateForTests(): void {
  authRedirectState = 'none';
}
