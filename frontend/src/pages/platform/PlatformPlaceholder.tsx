/**
 * Stub renderer for `/platform/*` routes that don't yet have a real
 * component (tasks 4.1-4.10 in OpenSpec change `platform-operator-ui`).
 *
 * This file is the Section 3 "frontend foundation" placeholder so the
 * route wiring + build-time tree-shaking + protected-route guard can
 * be verified end-to-end before the page components are written. It
 * will be DELETED in Section 4 once PlatformLogin / PlatformMfaEnroll /
 * PlatformMfaVerify / PlatformDashboard are landed.
 */

import { color } from '../../theme/colors';

export default function PlatformPlaceholder() {
  return (
    <div
      style={{
        padding: '2rem',
        backgroundColor: color.bg,
        color: color.text,
        minHeight: '100vh',
      }}
      data-testid="platform-placeholder"
    >
      <header
        style={{
          backgroundColor: color.platform,
          color: color.textInverse,
          padding: '0.75rem 1.25rem',
          marginBottom: '1.5rem',
          fontWeight: 'bold',
        }}
        data-testid="platform-banner-stub"
      >
        PLATFORM OPERATOR MODE — placeholder banner
      </header>
      <h1>Platform routes are wired up.</h1>
      <p>
        This is a Section 3 placeholder. Section 4 ships the real
        login / MFA / dashboard pages.
      </p>
    </div>
  );
}
