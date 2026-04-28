import { test, expect } from '@playwright/test';

/**
 * F11 PlatformMfaVerify error states (warroom round 6 A1 + spec
 * "MFA verify error states" requirement).
 *
 * Mocks the backend response shape that V90's record_failure_with_state
 * function + PlatformAuthController.mfaVerify produce, then asserts the
 * SPA renders the spec-mandated copy verbatim.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';

function fakeMfaVerifyJwt(): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify({
      iss: 'fabt-platform',
      sub: 'op-1',
      scope: 'mfa-verify',
      exp: Math.floor(Date.now() / 1000) + 300,
    })),
    'sig',
  ].join('.');
}

test.describe('Platform-operator UI — MFA verify error states', () => {
  test.beforeEach(async ({ page }) => {
    const jwt = fakeMfaVerifyJwt();
    await page.addInitScript(([key, value]) => {
      window.sessionStorage.setItem(key, value);
    }, [PLATFORM_JWT_KEY, jwt]);
  });

  test('wrong code with attemptsRemaining renders "X attempts remaining"', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login/mfa-verify', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'invalid_mfa_code',
          attemptsRemaining: 4,
        }),
      });
    });

    await page.goto('/platform/mfa-verify');
    await page.getByTestId('platform-mfa-totp-input').fill('000000');
    await page.getByTestId('platform-mfa-submit').click();

    const error = page.getByTestId('platform-mfa-error');
    await expect(error).toBeVisible();
    await expect(error).toHaveText('Code invalid. 4 attempts remaining before lockout.');
    // Input is cleared + we're still on /mfa-verify so the operator can retry
    await expect(page).toHaveURL('/platform/mfa-verify');
    await expect(page.getByTestId('platform-mfa-totp-input')).toHaveValue('');
  });

  test('account_locked response renders the lockout copy + disables TOTP input', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login/mfa-verify', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'account_locked' }),
      });
    });

    await page.goto('/platform/mfa-verify');
    await page.getByTestId('platform-mfa-totp-input').fill('000000');
    await page.getByTestId('platform-mfa-submit').click();

    const error = page.getByTestId('platform-mfa-error');
    await expect(error).toBeVisible();
    await expect(error).toContainText(/Too many failed attempts/);
    await expect(error).toContainText(/locked for 15 minutes/);
    await expect(error).toContainText(/use a backup code/);
    // TOTP input now disabled; backup-code switch link still active
    await expect(page.getByTestId('platform-mfa-totp-input')).toBeDisabled();
    await expect(page.getByTestId('platform-mfa-use-backup-code')).toBeVisible();
  });

  test('switching to backup-code mode unlocks input + retries with backup code', async ({ page }) => {
    let firstCall = true;
    await page.route('**/api/v1/auth/platform/login/mfa-verify', async (route) => {
      if (firstCall) {
        firstCall = false;
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'account_locked' }),
        });
      } else {
        // After switching to backup mode, fresh attempt with a working code
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            token: 'access.jwt.value',
            expiresInSeconds: 900,
          }),
        });
      }
    });

    await page.goto('/platform/mfa-verify');
    await page.getByTestId('platform-mfa-totp-input').fill('000000');
    await page.getByTestId('platform-mfa-submit').click();
    // Expect lockout state
    await expect(page.getByTestId('platform-mfa-totp-input')).toBeDisabled();

    // Switch to backup-code mode
    await page.getByTestId('platform-mfa-use-backup-code').click();
    const backupInput = page.getByTestId('platform-mfa-backup-input');
    await expect(backupInput).toBeVisible();
    await expect(backupInput).toBeEnabled();
    // The error message should be cleared by the mode toggle
    await expect(page.getByTestId('platform-mfa-error')).toHaveCount(0);

    // Submit a backup code; mocked response is 200, navigates to dashboard.
    // We won't verify the dashboard URL because that needs the post-MFA
    // token to validate against the guard — instead assert no error
    // appears for the second attempt.
    await backupInput.fill('XXXX-XXXX');
    await page.getByTestId('platform-mfa-submit').click();
    // The 200 response writes the token + navigates. The route guard at
    // /dashboard will then redirect since the new fake token isn't a
    // real platform JWT in this mocked setup. Either way, no error.
    await expect(page.getByTestId('platform-mfa-error')).toHaveCount(0);
  });

  test('invalid_platform_token (scoped token expired) → redirect to /login + toast key set', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login/mfa-verify', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'invalid_platform_token' }),
      });
    });

    await page.goto('/platform/mfa-verify');
    await page.getByTestId('platform-mfa-totp-input').fill('000000');
    await page.getByTestId('platform-mfa-submit').click();

    // A6: distinct from "code wrong" — operator's scoped token expired,
    // so we route them back to login with the session-expired toast key.
    await expect(page).toHaveURL('/platform/login');
    await expect(page.getByTestId('platform-login-session-expired-toast')).toBeVisible();
  });

  test('network error renders "Couldn\'t reach server" without redirecting', async ({ page }) => {
    await page.route('**/api/v1/auth/platform/login/mfa-verify', async (route) => {
      await route.abort('failed');
    });

    await page.goto('/platform/mfa-verify');
    await page.getByTestId('platform-mfa-totp-input').fill('000000');
    await page.getByTestId('platform-mfa-submit').click();

    const error = page.getByTestId('platform-mfa-error');
    await expect(error).toBeVisible();
    await expect(error).toContainText(/Couldn't reach server/);
    // sessionStorage NOT wiped — operator can retry on the same page
    await expect(page).toHaveURL('/platform/mfa-verify');
  });
});
