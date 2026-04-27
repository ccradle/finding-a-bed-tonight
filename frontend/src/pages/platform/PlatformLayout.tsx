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

import { Outlet } from 'react-router-dom';
import { PlatformOperatorBanner } from './components/PlatformOperatorBanner';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';
import { color } from '../../theme/colors';

export default function PlatformLayout() {
  const { jwt } = usePlatformAuth();
  return (
    <div style={{ minHeight: '100vh', backgroundColor: color.bg }}>
      {jwt && <PlatformOperatorBanner />}
      <Outlet />
    </div>
  );
}
