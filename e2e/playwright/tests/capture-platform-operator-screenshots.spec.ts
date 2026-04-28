import { test } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

/**
 * F11 §7.2 — Platform-operator user-guide screenshot capture.
 *
 * Captures the 6 screenshots referenced from
 * `docs/operations/platform-operator-user-guide.md`. Output:
 * `docs/operations/screenshots/platform-operator/` in this repo.
 *
 * Mocked at the network layer — no real backend dependency, no
 * shared-seed flake. Viewport pinned at 1440×900 + light theme per
 * task §7.2 ("Chrome stable, 1440x900, default zoom; theme: light
 * mode"). Re-run whenever the captured surfaces change copy / layout
 * (e.g. after Slice E ships destructive flow, retake 05).
 *
 * Run with:
 *   cd e2e/playwright
 *   BASE_URL=http://localhost:8081 npx playwright test capture-platform-operator-screenshots.spec.ts
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';
const SESSION_EXPIRED_TOAST_KEY = 'fabt.platform.toast.session-expired';

const OUT_DIR = path.join(
  __dirname,
  '..',
  '..',
  '..',
  'docs',
  'operations',
  'screenshots',
  'platform-operator',
);

test.beforeAll(() => {
  if (!fs.existsSync(OUT_DIR)) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
  }
});

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify(payload)),
    'sig',
  ].join('.');
}

/**
 * Shared mock setup for the enrollment flow (tests 03 + 05). Critically
 * mocks `/me` too: after `/mfa-confirm` returns a `mfaVerified=true`
 * JWT and `setPhase('codes')` runs, the PlatformLayout's
 * `isMfaVerified` flips true and mounts PlatformMetadataProvider,
 * which fires `/me`. Without a mock for `/me`, the real backend
 * rejects the fake JWT (signature check) → 401 →
 * `platformFetch.navigateToLogin()` → page navigates away → codes
 * phase tears down before we can capture it.
 */
async function setupEnrollMocks(page: import('@playwright/test').Page) {
  const setupJwt = fakeJwt({
    iss: 'fabt-platform',
    sub: 'op-1',
    scope: 'mfa-setup',
    exp: Math.floor(Date.now() / 1000) + 600,
  });
  await page.addInitScript(([key, value]) => {
    window.sessionStorage.setItem(key, value);
  }, [PLATFORM_JWT_KEY, setupJwt]);

  await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        secret: 'JBSWY3DPEHPK3PXP',
        qrUri: 'otpauth://totp/Fabt:test-op@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fabt',
        backupCodes: Array.from({ length: 10 }, (_, i) => `code-${String(i).padStart(4, '0')}`),
      }),
    });
  });

  const accessJwt = fakeJwt({
    iss: 'fabt-platform',
    sub: 'op-1',
    mfaVerified: true,
    exp: Math.floor(Date.now() / 1000) + 900,
  });
  await page.route('**/api/v1/auth/platform/mfa-confirm', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: accessJwt, expiresInSeconds: 900 }),
    });
  });

  // Mocking /me prevents the post-confirm redirect loop: PlatformLayout
  // gates the metadata provider on isMfaVerified, which flips true after
  // login(accessJwt). The provider then fires /me. Without a mock, the
  // real backend returns 401 on the fake JWT and platformFetch redirects
  // the page to /platform/login.
  await page.route('**/api/v1/auth/platform/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-0000-0000-000000000fab',
        email: 'platform-ops@dev.fabt.org',
        mfaEnabled: true,
        lastLoginAt: '2026-04-28T12:00:00Z',
        mfaEnrolledAt: '2026-04-26T12:00:00Z',
        backupCodesRemaining: 10,
      }),
    });
  });
}

test.describe('F11 platform-operator user guide — screenshot capture', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('01-login — /platform/login', async ({ page }) => {
    await page.goto('/platform/login');
    await page.waitForSelector('[data-testid="platform-login-submit"]');
    await page.screenshot({
      path: path.join(OUT_DIR, '01-login.png'),
      fullPage: true,
    });
  });

  test('02-mfa-enroll — QR + manual secret', async ({ page }) => {
    const jwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      scope: 'mfa-setup',
      exp: Math.floor(Date.now() / 1000) + 600,
    });
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);
    await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          secret: 'JBSWY3DPEHPK3PXP',
          qrUri: 'otpauth://totp/Fabt:test-op@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fabt',
          backupCodes: Array.from({ length: 10 }, (_, i) => `code-${String(i).padStart(4, '0')}`),
        }),
      });
    });

    await page.goto('/platform/mfa-enroll');
    // Wait for the QR canvas to render — guarantees the scan phase
    // is fully painted before we capture.
    await page.waitForSelector('[data-testid="platform-mfa-qr-canvas"]');
    await page.waitForSelector('[data-testid="platform-mfa-manual-secret"]');
    // QRCode.toCanvas is async; one paint frame is enough.
    await page.waitForTimeout(200);
    await page.screenshot({
      path: path.join(OUT_DIR, '02-mfa-enroll.png'),
      fullPage: true,
    });
  });

  test('03-backup-codes — one-shot codes display', async ({ page }) => {
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');
    await page.waitForSelector('[data-testid="platform-mfa-confirm-input"]');
    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();
    await page.waitForSelector('[data-testid="platform-backup-codes-list"]', {
      timeout: 10000,
    });
    await page.screenshot({
      path: path.join(OUT_DIR, '03-backup-codes.png'),
      fullPage: true,
    });
  });

  test('04-dashboard — operator landing page', async ({ page }) => {
    const jwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      mfaVerified: true,
      exp: Math.floor(Date.now() / 1000) + 900,
    });
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: '00000000-0000-0000-0000-000000000fab',
          email: 'platform-ops@dev.fabt.org',
          mfaEnabled: true,
          lastLoginAt: '2026-04-28T12:00:00Z',
          mfaEnrolledAt: '2026-04-26T12:00:00Z',
          backupCodesRemaining: 10,
        }),
      });
    });

    await page.goto('/platform/dashboard');
    await page.waitForSelector('[data-testid="platform-dashboard-email"]');
    // Wait for all 7 action cards to mount + tooltips to settle.
    await page.waitForSelector('[data-testid="platform-action-tenant-list"]');
    await page.waitForSelector('[data-testid="platform-action-system-version"]');
    await page.screenshot({
      path: path.join(OUT_DIR, '04-dashboard.png'),
      fullPage: true,
    });
  });

  test('05-confirm-action — print-codes confirmation modal', async ({ page }) => {
    // v0.54 ships ConfirmActionModal in the print/copy variants
    // (reachable from the backup-codes flow) AND in the destructive
    // variant (mounted by Slice E). The user guide screenshot 05 is
    // captioned "Destructive-action confirmation modal" but the
    // shipped UX in v0.54 is the print variant — same component,
    // identical chrome (Cancel default-focused, action-button on
    // right, single body paragraph). Re-take this screenshot when
    // Slice E wires the destructive variant.
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');
    await page.waitForSelector('[data-testid="platform-mfa-confirm-input"]');
    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();
    await page.waitForSelector('[data-testid="platform-backup-codes-list"]', {
      timeout: 10000,
    });
    // Click the Print button to mount ConfirmActionModal (print variant).
    await page.getByTestId('platform-backup-codes-print').click();
    await page.waitForSelector('[data-testid="platform-confirm-modal"]');
    await page.screenshot({
      path: path.join(OUT_DIR, '05-confirm-action.png'),
      fullPage: true,
    });
  });

  test('06-expired-session — login with toast', async ({ page }) => {
    // Simulate the banner having set the session-expired toast key
    // before redirecting to /platform/login (the production flow).
    await page.addInitScript(([key]) => {
      window.sessionStorage.setItem(key, 'true');
    }, [SESSION_EXPIRED_TOAST_KEY]);

    await page.goto('/platform/login');
    await page.waitForSelector('[data-testid="platform-login-session-expired-toast"]');
    await page.screenshot({
      path: path.join(OUT_DIR, '06-expired-session.png'),
      fullPage: true,
    });
  });
});
