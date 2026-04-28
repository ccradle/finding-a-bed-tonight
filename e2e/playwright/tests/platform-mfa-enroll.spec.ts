import { test, expect } from '@playwright/test';

/**
 * F11 §6.2 — End-to-end MFA enrollment flow.
 *
 * Mocks the backend `/mfa-setup`, `/mfa-confirm`, and `/me` endpoints
 * so the full enroll → codes-display → continue → dashboard flow runs
 * against the SPA in isolation. Coverage:
 *
 *   - First mount fetches /mfa-setup; QR canvas renders; manual secret
 *     visible.
 *   - Submitting a 6-digit code transitions to the `codes` phase.
 *   - All 10 backup codes render exactly once (tied to the
 *     idempotent /mfa-setup mock — re-mounting would return the same
 *     codes; the test captures the one-shot intent at the UI layer).
 *   - The "Continue" button is gated by the acknowledgement checkbox.
 *   - Clicking Continue lands on /platform/dashboard.
 *   - A second login (operator already enrolled) routes directly to
 *     /platform/mfa-verify, NOT /platform/mfa-enroll. This is the
 *     "second login no longer offers enroll" assertion — proven via
 *     the /platform/login → mfa-verify branch in the routing spec but
 *     re-pinned here to keep the §6.2 coverage self-contained.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify(payload)),
    'sig',
  ].join('.');
}

const TEST_BACKUP_CODES = Array.from(
  { length: 10 },
  (_, i) => `code-${String(i).padStart(4, '0')}`,
);

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
        backupCodes: TEST_BACKUP_CODES,
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

  // /me is fetched by PlatformMetadataProvider once the post-MFA token
  // lands in sessionStorage (which only happens on Continue, not on
  // confirm — see the deferred-login fix in PlatformMfaEnroll).
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

test.describe('Platform-operator UI — MFA enrollment end-to-end', () => {
  test('scan phase renders QR + manual secret + supported authenticators', async ({ page }) => {
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');

    await expect(page.getByTestId('platform-mfa-qr-canvas')).toBeVisible();
    await expect(page.getByTestId('platform-mfa-manual-secret')).toHaveText(
      'JBSWY3DPEHPK3PXP',
    );
    // Supported-authenticators copy verifies cross-app compatibility
    // claim (also covered by §6.10 manual QA).
    await expect(page.locator('main')).toContainText(/Google Authenticator/);
    await expect(page.locator('main')).toContainText(/1Password/);
  });

  test('submitting a 6-digit code transitions to the codes phase with all 10 codes', async ({ page }) => {
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');
    await page.waitForSelector('[data-testid="platform-mfa-confirm-input"]');

    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();

    await expect(page.getByTestId('platform-backup-codes-list')).toBeVisible();
    // Verify ALL 10 codes render (not just the first) — the one-shot
    // contract is "see them now or never again".
    for (let i = 0; i < TEST_BACKUP_CODES.length; i++) {
      await expect(page.getByTestId(`platform-backup-code-${i}`)).toHaveText(
        TEST_BACKUP_CODES[i],
      );
    }
  });

  test('Continue is disabled until the acknowledgement checkbox is checked', async ({ page }) => {
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');
    await page.waitForSelector('[data-testid="platform-mfa-confirm-input"]');
    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();
    await page.waitForSelector('[data-testid="platform-backup-codes-list"]');

    const continueBtn = page.getByTestId('platform-backup-codes-continue');
    await expect(continueBtn).toBeDisabled();

    await page.getByTestId('platform-backup-codes-confirm-checkbox').check();
    await expect(continueBtn).toBeEnabled();
  });

  test('Continue lands on /platform/dashboard with the post-MFA session active', async ({ page }) => {
    await setupEnrollMocks(page);
    await page.goto('/platform/mfa-enroll');
    await page.waitForSelector('[data-testid="platform-mfa-confirm-input"]');
    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();
    await page.waitForSelector('[data-testid="platform-backup-codes-list"]');
    await page.getByTestId('platform-backup-codes-confirm-checkbox').check();
    await page.getByTestId('platform-backup-codes-continue').click();

    await expect(page).toHaveURL('/platform/dashboard');
    // Banner mounts now that isMfaVerified=true (deferred-login fix).
    await expect(page.getByTestId('platform-operator-banner')).toBeVisible();
    await expect(page.getByTestId('platform-dashboard-email')).toContainText(
      'platform-ops@dev.fabt.org',
    );
  });

  test('second login (operator already enrolled) routes to /platform/mfa-verify, not /platform/mfa-enroll', async ({ page }) => {
    // Mock the /login endpoint to return the mfa-verify scope (the
    // already-enrolled path) instead of mfa-setup. This is the
    // backend's branch when `mfa_enabled=true` for the looked-up user.
    await page.route('**/api/v1/auth/platform/login', async (route) => {
      const verifyJwt = fakeJwt({
        iss: 'fabt-platform',
        sub: 'op-1',
        scope: 'mfa-verify',
        exp: Math.floor(Date.now() / 1000) + 300,
      });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          scope: 'mfa-verify',
          token: verifyJwt,
          expiresInSeconds: 300,
        }),
      });
    });

    await page.goto('/platform/login');
    await page.getByTestId('platform-login-email').fill('test-op@example.com');
    await page.getByTestId('platform-login-password').fill('any-password');
    await page.getByTestId('platform-login-submit').click();

    // Confirm: mfa-verify, not mfa-enroll. The "second login no longer
    // offers enroll" claim is proven by URL transition — the SPA never
    // touches /mfa-enroll because the login response carries the
    // mfa-verify scope.
    await expect(page).toHaveURL('/platform/mfa-verify');
    await expect(page).not.toHaveURL(/mfa-enroll/);
  });
});
