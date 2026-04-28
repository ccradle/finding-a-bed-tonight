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
 */
export default function PlatformLayout() {
  const { jwt } = usePlatformAuth();
  return (
    <div style={{ minHeight: '100vh', backgroundColor: color.bg }}>
      {jwt && <PlatformOperatorBanner />}
      <Suspense
        fallback={
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
        }
      >
        <Outlet />
      </Suspense>
    </div>
  );
}
