/**
 * Layout wrapper for `/platform/*` routes (F11 v0.54).
 *
 * Renders the persistent operator banner above an `<Outlet />` so every
 * page component (login, mfa-enroll, mfa-verify, dashboard, placeholder)
 * gets the banner without per-page wiring. Spec scenario "Banner visible
 * on every platform route" is satisfied by this layout pattern.
 *
 * Note: the banner reads JWT from sessionStorage via usePlatformAuth().
 * On the /platform/login route the operator is not yet authenticated —
 * the banner is suppressed when no JWT exists, so the unauthenticated
 * login screen is uncluttered.
 */

import { Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { PlatformOperatorBanner } from './components/PlatformOperatorBanner';
import { PlatformMetadataProvider } from './PlatformMetadataContext';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';
import { color } from '../../theme/colors';

/**
 * A2 (warroom round 6): the parent App.tsx Suspense covers the layout
 * chunk only. Once the layout is mounted, nested route chunks (Login,
 * MfaEnroll, MfaVerify, Placeholder, Dashboard) are still lazy — without
 * an inner Suspense, React's nearest already-resolved boundary above the
 * Layout will not re-catch the inner pending-promise → blank screen
 * (Chrome/Firefox) or thrown error (some React versions). This inner
 * Suspense wraps the Outlet so cold-load of any nested route shows the
 * fallback content, not blank.
 *
 * Round 7 H4 / round 8 N2: PlatformMetadataProvider is mounted ONLY
 * after MFA-verify completes (i.e. when the JWT has `mfaVerified=true`).
 * Gating on bare `jwt` was wrong — operators on `/platform/mfa-enroll`
 * or `/platform/mfa-verify` hold scoped tokens that 403 against `/me`,
 * which `platformFetch` then turns into a `window.location.href`
 * redirect (full page reload, wrong destination on mfa-verify). Gating
 * on `isMfaVerified` keeps the provider scoped to the dashboard subtree
 * where /me is legitimate. The banner is also gated this way because it
 * lives inside the provider — the banner's countdown wasn't useful on
 * mfa-* routes anyway (those tokens are short-lived, not 15-min).
 */
function Fallback() {
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        padding: '2rem',
        textAlign: 'center',
        color: 'var(--color-text-secondary)',
      }}
    >
      Loading…
    </div>
  );
}

export default function PlatformLayout() {
  const { isMfaVerified } = usePlatformAuth();
  return (
    <div style={{ minHeight: '100vh', backgroundColor: color.bg }}>
      {isMfaVerified ? (
        <PlatformMetadataProvider>
          <PlatformOperatorBanner />
          <Suspense fallback={<Fallback />}>
            <Outlet />
          </Suspense>
        </PlatformMetadataProvider>
      ) : (
        <Suspense fallback={<Fallback />}>
          <Outlet />
        </Suspense>
      )}
    </div>
  );
}
