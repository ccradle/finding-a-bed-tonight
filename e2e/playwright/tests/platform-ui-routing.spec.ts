import { test, expect } from '@playwright/test';

/**
 * F11 platform-operator UI — routing + login flow (warroom round 6
 * coverage gaps T1, T2, T3, T7).
 *
 * Mocks the backend `/api/v1/auth/platform/*` responses so the spec
 * runs against the frontend in isolation — no backend / no DB / no
 * seed-user collision with the existing platform-totp-lockout spec.
 *
 * Covers:
 *   - /platform/login renders distinct heading + tenant cross-link
 *   - Login with mfa_enabled=false response → /platform/mfa-enroll
 *   - Login with mfa_enabled=true response → /platform/mfa-verify
 *   - Failed login (401) → "Invalid credentials" (generic)
 *   - Direct nav to /platform/dashboard without auth → /platform/login
 *   - mfa-setup-scoped JWT routed to /mfa-enroll, NOT dashboard
 *   - mfa-verify-scoped JWT routed to /mfa-verify, NOT dashboard
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';

/**
 * Mint a fake platform JWT with whatever payload is useful. Server
 * never validates client-side — guard reads claims via base64 only.
 * Signature byte is junk, structure is real.
 */
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify(payload)),
    'sig-not-verified-clientside',
  ].join('.');
}

test.describe('Platform-operator UI — routing + login flow', () => {
  test('login page renders distinct heading + tenant cross-link', async ({ page }) => {
    await page.goto('/platform/login');

    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Platform Operator Sign-In/);
    const crossLink = page.getByTestId('platform-login-tenant-crosslink');
    await expect(crossLink).toBeVisible();
    await expect(crossLink).toHaveAttribute('href', '/login');
  });

  test('successful first-login (mfa-setup) redirects to /platform/mfa-enroll', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          scope: 'mfa-setup',
          token: fakeJwt({ iss: 'fabt-platform', sub: 'op-1', scope: 'mfa-setup', exp: Math.floor(Date.now() / 1000) + 600 }),
          expiresInSeconds: 600,
        }),
      });
    });
    // Mock the /mfa-setup the enroll page fires on mount so it doesn't
    // hang on a real-fetch attempt against a non-running backend.
    await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          secret: 'JBSWY3DPEHPK3PXP',
          qrUri: 'otpauth://totp/Fabt:test?secret=JBSWY3DPEHPK3PXP&issuer=Fabt',
          backupCodes: Array.from({ length: 10 }, (_, i) => `code-${i}`),
        }),
      });
    });

    await page.goto('/platform/login');
    await page.getByTestId('platform-login-email').fill('test-op@example.com');
    await page.getByTestId('platform-login-password').fill('any-password');
    await page.getByTestId('platform-login-submit').click();

    await expect(page).toHaveURL('/platform/mfa-enroll');
  });

  test('successful subsequent-login (mfa-verify) redirects to /platform/mfa-verify', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          scope: 'mfa-verify',
          token: fakeJwt({ iss: 'fabt-platform', sub: 'op-1', scope: 'mfa-verify', exp: Math.floor(Date.now() / 1000) + 300 }),
          expiresInSeconds: 300,
        }),
      });
    });

    await page.goto('/platform/login');
    await page.getByTestId('platform-login-email').fill('test-op@example.com');
    await page.getByTestId('platform-login-password').fill('any-password');
    await page.getByTestId('platform-login-submit').click();

    await expect(page).toHaveURL('/platform/mfa-verify');
  });

  test('failed login displays generic "Invalid credentials" without leaking', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'invalid_credentials' }),
      });
    });

    await page.goto('/platform/login');
    await page.getByTestId('platform-login-email').fill('test-op@example.com');
    await page.getByTestId('platform-login-password').fill('wrong');
    await page.getByTestId('platform-login-submit').click();

    const error = page.getByTestId('platform-login-error');
    await expect(error).toBeVisible();
    await expect(error).toHaveText(/Invalid credentials/);
    await expect(error).not.toContainText(/email/);
    await expect(error).not.toContainText(/password/);
    // Critically: still on /login (no leak via redirect)
    await expect(page).toHaveURL('/platform/login');
  });

  test('direct nav to /platform/dashboard without auth → /platform/login', async ({ page }) => {
    // No JWT in sessionStorage. The route guard's synchronous check
    // should redirect before any fetch fires.
    await page.goto('/platform/dashboard');
    await expect(page).toHaveURL('/platform/login');
  });

  test('mfa-setup-scoped JWT navigating to /platform/dashboard → /platform/mfa-enroll', async ({ page }) => {
    const jwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      scope: 'mfa-setup',
      exp: Math.floor(Date.now() / 1000) + 600,
    });
    // Set sessionStorage BEFORE navigating so the guard reads it on first paint.
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);
    // Mock the /mfa-setup the enroll page fires on mount.
    await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          secret: 'JBSWY3DPEHPK3PXP',
          qrUri: 'otpauth://totp/Fabt:test?secret=JBSWY3DPEHPK3PXP',
          backupCodes: Array.from({ length: 10 }, (_, i) => `code-${i}`),
        }),
      });
    });

    await page.goto('/platform/dashboard');

    // Guard should redirect — operator with mfa-setup token belongs at enroll.
    await expect(page).toHaveURL('/platform/mfa-enroll');
  });

  test('mfa-verify-scoped JWT navigating to /platform/dashboard → /platform/mfa-verify', async ({ page }) => {
    const jwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      scope: 'mfa-verify',
      exp: Math.floor(Date.now() / 1000) + 300,
    });
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);

    await page.goto('/platform/dashboard');

    await expect(page).toHaveURL('/platform/mfa-verify');
  });

  test('expired JWT navigating to /platform/dashboard → /platform/login', async ({ page }) => {
    // exp set to 1 hour ago
    const jwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      mfaVerified: true,
      exp: Math.floor(Date.now() / 1000) - 3600,
    });
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);

    await page.goto('/platform/dashboard');

    await expect(page).toHaveURL('/platform/login');
  });
});
