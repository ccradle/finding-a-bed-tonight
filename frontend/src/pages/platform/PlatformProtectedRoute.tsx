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

interface Props {
  children: ReactNode;
  /**
   * If true, accept tokens with `scope=mfa-setup` (used by the
   * /platform/mfa-enroll route). Default false (dashboard requires post-MFA).
   */
  allowMfaSetupScope?: boolean;
}

export function PlatformProtectedRoute({ children, allowMfaSetupScope = false }: Props) {
  // Subscribe to context so a child-triggered logout() (e.g. banner countdown
  // hits zero, dashboard logout button, cross-tab storage event) re-renders
  // this guard and triggers the redirect. Mirrors the existing tenant
  // {@link AuthGuard} pattern at frontend/src/auth/AuthGuard.tsx:25.
  const { jwt, claims } = usePlatformAuth();

  // 1 + 2: must have a structurally-parseable platform JWT
  if (!jwt || !claims || claims.iss !== 'fabt-platform') {
    return <Navigate to="/platform/login" replace />;
  }

  // 4: synchronous expiry — runs BEFORE any child fetch
  if (isExpired(jwt)) {
    return <Navigate to="/platform/login" replace />;
  }

  // 3: scope/mfaVerified gating
  if (allowMfaSetupScope) {
    // Accept either a fresh access token (post-MFA) OR an mfa-setup-scoped
    // token. Reject mfa-verify-scoped (those route through verify, not enroll).
    if (claims.scope && claims.scope !== 'mfa-setup') {
      return <Navigate to="/platform/login" replace />;
    }
  } else {
    // Default: require fully-authenticated access token (mfaVerified=true,
    // no scope claim). MFA-setup or mfa-verify scoped tokens are routed
    // back to their respective enroll/verify pages.
    if (claims.scope === 'mfa-setup') {
      return <Navigate to="/platform/mfa-enroll" replace />;
    }
    if (claims.scope === 'mfa-verify') {
      return <Navigate to="/platform/mfa-verify" replace />;
    }
    if (claims.mfaVerified !== true) {
      return <Navigate to="/platform/login" replace />;
    }
  }

  return <>{children}</>;
}
