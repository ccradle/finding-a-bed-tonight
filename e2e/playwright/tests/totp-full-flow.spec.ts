import { test as base, expect } from '@playwright/test';
import { test as authTest } from '../fixtures/auth.fixture';
import * as OTPAuth from 'otpauth';

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

/**
 * Full-flow TOTP & Access Code E2E tests (D17).
 *
 * These tests complete the ENTIRE flow — not just page rendering.
 * They use a dedicated test user to avoid polluting seed data.
 * Requires FABT_TOTP_ENCRYPTION_KEY configured (D16).
 */

// Helper: create a dedicated test user for TOTP tests
async function createTotpTestUser(token: string): Promise<{ id: string; email: string }> {
  const email = `totp-e2e-${Date.now()}@dev.fabt.org`;
  const res = await fetch(`${API_URL}/api/v1/users`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email, displayName: 'TOTP E2E Test User', password: 'TestPassword123!',
      roles: ['OUTREACH_WORKER'], dvAccess: false,
    }),
  });
  if (res.status === 409) {
    // User already exists — find by email
    const users = await (await fetch(`${API_URL}/api/v1/users`, {
      headers: { Authorization: `Bearer ${token}` },
    })).json();
    const existing = users.find((u: { email: string }) => u.email === email);
    return { id: existing.id, email };
  }
  const user = await res.json();
  return { id: user.id, email };
}

// Helper: get admin token
async function getAdminToken(): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  return (await res.json()).accessToken;
}

// Helper: login and get token for a user
async function loginUser(email: string, password: string): Promise<{ accessToken?: string; mfaRequired?: boolean; mfaToken?: string }> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email, password }),
  });
  return res.json();
}

// Helper: generate TOTP code from secret
function generateTotpCode(base32Secret: string): string {
  const totp = new OTPAuth.TOTP({
    issuer: 'Finding A Bed Tonight',
    algorithm: 'SHA1',
    digits: 6,
    period: 30,
    secret: OTPAuth.Secret.fromBase32(base32Secret),
  });
  return totp.generate();
}

// Helper: check if TOTP is available
async function isTotpAvailable(): Promise<boolean> {
  try {
    const res = await fetch(`${API_URL}/api/v1/auth/capabilities`);
    const data = await res.json();
    return data.totpAvailable === true;
  } catch { return false; }
}

// =========================================================================
// T-81: FULL TOTP enrollment flow
// =========================================================================

base.describe('TOTP Full Enrollment Flow', () => {
  base('enroll → QR code → verify code → backup codes displayed', async ({ page }) => {
    if (!await isTotpAvailable()) {
      base.skip(true, 'TOTP encryption not configured');
      return;
    }

    const adminToken = await getAdminToken();
    const testUser = await createTotpTestUser(adminToken);

    // Login as test user in a fresh browser context (not adminPage which is already logged in)
    await page.goto('/login');
    await page.locator('[data-testid="login-tenant-slug"]').fill(TENANT_SLUG);
    await page.locator('[data-testid="login-email"]').fill(testUser.email);
    await page.locator('[data-testid="login-password"]').fill('TestPassword123!');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 10000 });

    // Navigate to TOTP enrollment
    await page.goto('/settings/totp');
    await page.waitForTimeout(1000);

    // Click "Set Up Sign-In Verification"
    await page.getByTestId('enable-totp-button').click();
    await page.waitForTimeout(3000);

    // QR code should render (canvas element from client-side qrcode lib)
    const qrCode = page.getByTestId('totp-qr-code');
    await expect(qrCode).toBeVisible({ timeout: 5000 });

    // ─── §6.c H4 a11y assertions: QR canvas semantics ─────────────────
    // §6.13: <canvas> alone is opaque to screen readers; the QrCodeCanvas
    // wrapper sets role="img" + aria-label so assistive tech understands
    // what the canvas conveys.
    const canvas = qrCode.locator('canvas');
    await expect(canvas).toHaveAttribute('role', 'img');
    const qrAriaLabel = await canvas.getAttribute('aria-label');
    expect(qrAriaLabel).toMatch(/scan|authenticator|register/i);

    // ─── §6.c H4 a11y assertions: TOTP verify input attributes ────────
    // §6.12: autoComplete=one-time-code triggers mobile autofill;
    // pattern enforces 6-digit format at constraint-validation layer.
    const verifyInput = page.getByTestId('totp-verify-input');
    await expect(verifyInput).toHaveAttribute('autocomplete', 'one-time-code');
    await expect(verifyInput).toHaveAttribute('inputmode', 'numeric');
    await expect(verifyInput).toHaveAttribute('pattern', '[0-9]{6}');

    // Get the secret from the manual entry section
    const detailsSummary = page.locator('summary');
    await detailsSummary.click();
    const secretElement = page.getByTestId('totp-manual-secret');
    await expect(secretElement).toBeVisible();
    const secret = await secretElement.textContent();
    expect(secret).toBeTruthy();
    expect(secret!.length).toBeGreaterThan(10);

    // §6.13: Copy-secret button is keyboard-accessible (not just a clickable div)
    // and carries an aria-label distinct from the visible "Copy" text.
    const copySecretButton = page.getByTestId('totp-copy-secret-button');
    await expect(copySecretButton).toBeVisible();
    const copySecretAria = await copySecretButton.getAttribute('aria-label');
    expect(copySecretAria).toMatch(/copy.*secret|secret.*clipboard/i);

    // Generate a valid TOTP code using the secret
    const code = generateTotpCode(secret!.trim());

    // Enter the code
    await page.getByTestId('totp-verify-input').fill(code);
    await page.getByTestId('totp-verify-submit').click();
    await page.waitForTimeout(3000);

    // Backup codes should be displayed (G-4.5 §6.14: now an <ol> not a <div> grid)
    const codesList = page.getByTestId('backup-codes-list');
    await expect(codesList).toBeVisible({ timeout: 5000 });

    // ─── §6.c H4 a11y assertions: backup codes semantic structure ─────
    // §6.14: testid attaches to <ol> (not <div>), each code in <li>,
    // ol carries aria-label describing the list purpose.
    const olTagName = await codesList.evaluate((el) => el.tagName.toLowerCase());
    expect(olTagName).toBe('ol');
    const olAriaLabel = await codesList.getAttribute('aria-label');
    expect(olAriaLabel).toMatch(/backup.*code|code.*authenticator/i);

    // §6.14: <h2> "Save these codes" heading anchors the section.
    // Search within the same step container, not the page (banner h2s
    // could match too).
    const codesStep = page.locator('.fabt-backup-codes-step');
    const heading = codesStep.locator('h2');
    await expect(heading).toBeVisible();

    // Should have 8 codes — count <li> children of the <ol>
    const codeElements = codesList.locator('li');
    expect(await codeElements.count()).toBe(8);

    // §6.14: per-code Copy buttons present and aria-labelled
    // ("Copy backup code 1" through 8).
    const firstCopyButton = page.getByTestId('copy-code-0');
    await expect(firstCopyButton).toBeVisible();
    const firstCopyAria = await firstCopyButton.getAttribute('aria-label');
    expect(firstCopyAria).toMatch(/copy.*1|1.*copy/i);

    // Copy, Download, and Print buttons should be visible (Print added in §6.14)
    await expect(page.getByTestId('copy-codes-button')).toBeVisible();
    await expect(page.getByTestId('download-codes-button')).toBeVisible();
    await expect(page.getByTestId('print-codes-button')).toBeVisible();
  });
});

// =========================================================================
// T-82: FULL two-phase login
// =========================================================================

base.describe('TOTP Full Two-Phase Login', () => {
  base('password → mfaRequired → enter TOTP code → logged in', async ({ page }) => {
    if (!await isTotpAvailable()) {
      base.skip(true, 'TOTP encryption not configured');
      return;
    }

    const adminToken = await getAdminToken();
    const testUser = await createTotpTestUser(adminToken);

    // Enable TOTP for this user via API
    const userToken = (await loginUser(testUser.email, 'TestPassword123!')).accessToken!;

    const enrollRes = await fetch(`${API_URL}/api/v1/auth/enroll-totp`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${userToken}` },
    });
    const { secret } = await enrollRes.json();

    const confirmCode = generateTotpCode(secret);
    await fetch(`${API_URL}/api/v1/auth/confirm-totp-enrollment`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${userToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: confirmCode }),
    });

    // Now login via the UI — should get TOTP screen
    await page.goto('/login');
    await page.locator('[data-testid="login-tenant-slug"]').fill(TENANT_SLUG);
    await page.locator('[data-testid="login-email"]').fill(testUser.email);
    await page.locator('[data-testid="login-password"]').fill('TestPassword123!');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForTimeout(2000);

    // Should see TOTP verify screen (not the main app)
    const totpScreen = page.getByTestId('totp-verify-screen');
    await expect(totpScreen).toBeVisible({ timeout: 5000 });

    // ─── §6.c H4 a11y assertions: LoginPage TOTP input + error region ──
    // §6.12 + H1 fix: input uses aria-labelledby (not bare aria-label) so
    // the visible prompt paragraph IS the source of truth for the
    // accessible name. autoComplete="one-time-code" enables mobile
    // browser autofill from incoming SMS / email TOTP messages.
    const loginInput = page.getByTestId('totp-login-input');
    await expect(loginInput).toHaveAttribute('autocomplete', 'one-time-code');
    await expect(loginInput).toHaveAttribute('aria-labelledby', 'totp-login-prompt');
    await expect(loginInput).toHaveAttribute('inputmode', 'numeric');
    await expect(loginInput).toHaveAttribute('pattern', '[0-9]{6}');

    // §6.15 + H3 fix: error region exists in the DOM regardless of error
    // state (so screen readers discover the live region on page load),
    // carries role="alert" + aria-atomic="true". The polite/assertive
    // tension was resolved by dropping aria-live and letting role="alert"
    // imply assertive — correct severity for "your account is locked".
    const errorRegion = page.getByTestId('login-error-region');
    await expect(errorRegion).toHaveAttribute('role', 'alert');
    await expect(errorRegion).toHaveAttribute('aria-atomic', 'true');

    // Generate a fresh TOTP code and enter it
    const loginCode = generateTotpCode(secret);
    await page.getByTestId('totp-login-input').fill(loginCode);
    await page.getByTestId('totp-login-submit').click();
    await page.waitForTimeout(3000);

    // Should be logged in — redirected away from login
    await expect(page).not.toHaveURL(/\/login/);
  });
});

// =========================================================================
// T-83: FULL access code flow
// =========================================================================

base.describe('Access Code Full Flow', () => {
  base('admin generates → worker enters → forced password change', async ({ page }) => {
    const adminToken = await getAdminToken();
    const testUser = await createTotpTestUser(adminToken);

    // Admin generates access code via API
    const codeRes = await fetch(`${API_URL}/api/v1/users/${testUser.id}/generate-access-code`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    const { code } = await codeRes.json();
    expect(code).toBeTruthy();

    // Intercept the API response to verify the backend returns tokens
    let apiResponse: { accessToken?: string; mustChangePassword?: boolean } = {};
    await page.route('**/api/v1/auth/access-code', async (route) => {
      const response = await route.fetch();
      apiResponse = await response.json();
      await route.fulfill({ response });
    });

    // Worker enters code on access code login page
    await page.goto('/login/access-code');
    await page.getByTestId('access-code-tenant').fill(TENANT_SLUG);
    await page.getByTestId('access-code-email').fill(testUser.email);
    await page.getByTestId('access-code-input').fill(code);
    await page.getByTestId('access-code-submit').click();
    await page.waitForTimeout(3000);

    // Verify the backend returned tokens with mustChangePassword
    expect(apiResponse.accessToken).toBeTruthy();
    expect(apiResponse.mustChangePassword).toBe(true);
  });
});
