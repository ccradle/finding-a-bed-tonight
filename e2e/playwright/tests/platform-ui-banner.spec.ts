import { test, expect } from '@playwright/test';

/**
 * F11 PlatformOperatorBanner — masked email, countdown, logout,
 * 410-anonymized force-logout (warroom round 6 J2 + warroom round 5 H5
 * coverage gaps).
 *
 * Mocks /me responses to drive banner state without a real backend.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';
const SESSION_EXPIRED_TOAST_KEY = 'fabt.platform.toast.session-expired';

function fakeAccessJwt(opts: { expSeconds?: number } = {}): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + (opts.expSeconds ?? 900);
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify({
      iss: 'fabt-platform',
      sub: 'op-1',
      mfaVerified: true,
      exp,
    })),
    'sig',
  ].join('.');
}

test.describe('Platform-operator UI — banner', () => {
  test.beforeEach(async ({ page }) => {
    const jwt = fakeAccessJwt();
    // Round 8 fix: addInitScript fires on EVERY page load, including
    // the post-logout navigation. Without this guard, the post-logout
    // sessionStorage assertion `toBeNull()` would fail because the
    // script re-injects the JWT after `clearPlatformJwt()` removed it.
    // Track injection via a separate sessionStorage marker that is
    // NOT cleared by `clearPlatformJwt` (which removes only the JWT
    // key). One-shot per test.
    await page.addInitScript(([key, value, markerKey]) => {
      if (window.sessionStorage.getItem(markerKey) === '1') return;
      window.sessionStorage.setItem(key, value);
      window.sessionStorage.setItem(markerKey, '1');
    }, [PLATFORM_JWT_KEY, jwt, 'fabt.test.platform-jwt.injected']);
  });

  test('renders masked email + countdown + logout button', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: '00000000-0000-0000-0000-000000000fab',
          email: 'test-op@example.com',
          mfaEnabled: true,
          lastLoginAt: '2026-04-28T12:00:00Z',
          mfaEnrolledAt: '2026-04-26T12:00:00Z',
          backupCodesRemaining: 10,
        }),
      });
    });

    await page.goto('/platform/dashboard');

    const banner = page.getByTestId('platform-operator-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('PLATFORM OPERATOR MODE');

    // Operator decision 9.2 — email is masked: t***@example.com
    const emailEl = page.getByTestId('platform-banner-email');
    await expect(emailEl).toHaveText('t***@example.com');

    // Countdown is present + non-zero (15-min token; should show ~14:5x or 15:00)
    const countdown = page.getByTestId('platform-banner-countdown');
    await expect(countdown).toBeVisible();
    await expect(countdown).toContainText(/Session expires in 1[45]:/);

    // Logout button is present
    await expect(page.getByTestId('platform-banner-logout')).toBeVisible();
  });

  test('logout button POSTs /logout, clears sessionStorage, redirects to /login', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: '00000000-0000-0000-0000-000000000fab',
          email: 'test-op@example.com',
          mfaEnabled: true,
          lastLoginAt: '2026-04-28T12:00:00Z',
          mfaEnrolledAt: '2026-04-26T12:00:00Z',
          backupCodesRemaining: 10,
        }),
      });
    });

    let logoutCalled = false;
    await page.route('**/api/v1/auth/platform/logout', async (route) => {
      logoutCalled = true;
      await route.fulfill({ status: 204 });
    });

    await page.goto('/platform/dashboard');
    await page.getByTestId('platform-banner-logout').click();

    await expect(page).toHaveURL('/platform/login');
    expect(logoutCalled).toBeTruthy();

    // sessionStorage should be cleared regardless of logout response
    const storedJwt = await page.evaluate(
      (key) => window.sessionStorage.getItem(key),
      PLATFORM_JWT_KEY,
    );
    expect(storedJwt).toBeNull();
  });

  test('logout still wipes session if backend POST fails', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'op-1',
          email: 'ops@example.com',
          mfaEnabled: true,
          lastLoginAt: '2026-04-28T12:00:00Z',
          mfaEnrolledAt: '2026-04-26T12:00:00Z',
          backupCodesRemaining: 10,
        }),
      });
    });
    await page.route('**/api/v1/auth/platform/logout', async (route) => {
      await route.abort('failed');
    });

    await page.goto('/platform/dashboard');
    await page.getByTestId('platform-banner-logout').click();

    // Local-side logout still happens
    await expect(page).toHaveURL('/platform/login');
    const storedJwt = await page.evaluate(
      (key) => window.sessionStorage.getItem(key),
      PLATFORM_JWT_KEY,
    );
    expect(storedJwt).toBeNull();
  });

  test('410 anonymized → force logout to /platform/login', async ({ page }) => {
    // Backend says the operator's row is anonymized (or no longer present).
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 410,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'gone' }),
      });
    });

    await page.goto('/platform/dashboard');

    // Banner's anonymized-effect should trigger the redirect.
    await expect(page).toHaveURL('/platform/login');
  });

  test('session-expired toast key triggers toast on /platform/login', async ({ page }) => {
    // Simulate the banner having previously set the toast key (e.g. on
    // a tick-zero redirect from a different page-load).
    await page.addInitScript(([key]) => {
      window.sessionStorage.removeItem('fabt.platform.jwt.v1');
      window.sessionStorage.setItem(key, 'true');
    }, [SESSION_EXPIRED_TOAST_KEY]);

    await page.goto('/platform/login');

    await expect(page.getByTestId('platform-login-session-expired-toast')).toBeVisible();
    // Toast key is consumed (one-shot)
    const remaining = await page.evaluate(
      (k) => window.sessionStorage.getItem(k),
      SESSION_EXPIRED_TOAST_KEY,
    );
    expect(remaining).toBeNull();
  });
});
