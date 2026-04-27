/**
 * Route guard for `/platform/*` (F11 v0.54).
 *
 * Synchronous checks BEFORE child rendering, in this order:
 *   1. JWT present in sessionStorage
 *   2. JWT issuer is `fabt-platform` (defense vs renamed claim)
 *   3. mfaVerified === true (post-MFA access tokens only)
 *   4. exp > now (synchronous expiry check — prevents 401-vs-expiry race
 *      where a stale token would let the dashboard fire `/me` and get
 *      back a redundant 401 before the banner countdown notices)
 *
 * Any failure → redirect to `/platform/login` BEFORE any child component
 * mounts or fetches.
 *
 * Distinct from {@link AuthGuard} (tenant) on purpose — see
 * {@link PlatformAuthContext} note about NOT generalizing.
 */

import { Navigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';
import { isExpired } from './helpers/platformJwt';

/**
 * Scope tier required to render the guarded route.
 *   - 'access'     — fully-authenticated post-MFA token (no scope claim,
 *                    mfaVerified=true). Default. Used by dashboard +
 *                    /me-bound routes.
 *   - 'mfa-setup'  — first-login scoped token only. Used by /mfa-enroll.
 *   - 'mfa-verify' — subsequent-login scoped token only. Used by /mfa-verify.
 */
export type RequiredScope = 'access' | 'mfa-setup' | 'mfa-verify';

interface Props {
  children: ReactNode;
  /**
   * Required scope tier. Defaults to 'access'. Mismatched-scope tokens
   * are routed back to their natural completion page (mfa-setup tokens
   * → /mfa-enroll; mfa-verify tokens → /mfa-verify) rather than dumped
   * back at /login, so the operator can finish what they started.
   */
  requiredScope?: RequiredScope;
}

export function PlatformProtectedRoute({ children, requiredScope = 'access' }: Props) {
  // Subscribe to context so a child-triggered logout() (e.g. banner countdown
  // hits zero, dashboard logout button, cross-tab storage event) re-renders
  // this guard and triggers the redirect. Mirrors the existing tenant
  // {@link AuthGuard} pattern at frontend/src/auth/AuthGuard.tsx:25.
  const { jwt, claims } = usePlatformAuth();

  // 1 + 2: must have a structurally-parseable platform JWT
  if (!jwt || !claims || claims.iss !== 'fabt-platform') {
    return <Navigate to="/platform/login" replace />;
  }

  // 3: synchronous expiry — runs BEFORE any child fetch
  if (isExpired(jwt)) {
    return <Navigate to="/platform/login" replace />;
  }

  // 4: scope-tier gating
  if (requiredScope === 'access') {
    // Reject scoped tokens — route them to their natural completion page.
    if (claims.scope === 'mfa-setup') {
      return <Navigate to="/platform/mfa-enroll" replace />;
    }
    if (claims.scope === 'mfa-verify') {
      return <Navigate to="/platform/mfa-verify" replace />;
    }
    if (claims.mfaVerified !== true) {
      return <Navigate to="/platform/login" replace />;
    }
  } else if (requiredScope === 'mfa-setup') {
    if (claims.scope !== 'mfa-setup') {
      return <Navigate to="/platform/login" replace />;
    }
  } else if (requiredScope === 'mfa-verify') {
    if (claims.scope !== 'mfa-verify') {
      return <Navigate to="/platform/login" replace />;
    }
  }

  return <>{children}</>;
}

/**
 * Inverse guard for the /platform/login page itself: if the operator is
 * already authenticated (any platform JWT, valid or scoped), redirect
 * to the natural next step. Prevents the login page from being a dead
 * end when the operator hits Back after MFA enrollment.
 */
export function PlatformRedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const { jwt, claims } = usePlatformAuth();
  if (jwt && claims && claims.iss === 'fabt-platform' && !isExpired(jwt)) {
    if (claims.scope === 'mfa-setup') {
      return <Navigate to="/platform/mfa-enroll" replace />;
    }
    if (claims.scope === 'mfa-verify') {
      return <Navigate to="/platform/mfa-verify" replace />;
    }
    if (claims.mfaVerified === true) {
      return <Navigate to="/platform/dashboard" replace />;
    }
  }
  return <>{children}</>;
}
