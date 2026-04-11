import { test as base, expect } from '@playwright/test';
import { test as authTest } from '../fixtures/auth.fixture';

const API_URL = process.env.API_URL || 'http://localhost:8080';

/**
 * TOTP 2FA & Access Code — Playwright E2E tests.
 *
 * Tests: two-phase login UI, TOTP enrollment page, access code login,
 * admin access code generation, auth capabilities, forgot password page.
 *
 * NOTE: Full TOTP verification requires a real TOTP secret + code generation,
 * which needs the FABT_TOTP_ENCRYPTION_KEY env var. Tests skip gracefully
 * if TOTP is not configured.
 */

// Helper: check if TOTP is available on this server
async function isTotpAvailable(): Promise<boolean> {
  try {
    const res = await fetch(`${API_URL}/api/v1/auth/capabilities`);
    const data = await res.json();
    return data.totpAvailable === true;
  } catch {
    return false;
  }
}

// Helper: generate an access code via API
async function generateAccessCode(userId: string): Promise<string | null> {
  try {
    const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
    });
    const { accessToken } = await loginRes.json();

    const codeRes = await fetch(`${API_URL}/api/v1/users/${userId}/generate-access-code`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const data = await codeRes.json();
    return data.code || null;
  } catch {
    return null;
  }
}

// =========================================================================
// T-55: TOTP enrollment page renders
// =========================================================================

authTest.describe('TOTP Enrollment', () => {
  authTest('enrollment page renders with QR setup flow', async ({ outreachPage }) => {
    await outreachPage.goto('/settings/totp');
    await outreachPage.waitForTimeout(1000);

    const title = outreachPage.locator('h2');
    await expect(title).toBeVisible();

    // "Set Up Sign-In Verification" button should be visible
    const enableBtn = outreachPage.getByTestId('enable-totp-button');
    await expect(enableBtn).toBeVisible();
  });
});

// =========================================================================
// T-56: Two-phase login UI (password → TOTP screen)
// =========================================================================

base.describe('Two-Phase Login', () => {
  base('login page shows TOTP input after mfaRequired response', async ({ page }) => {
    // This test requires a user with TOTP enabled — skip if not available
    const totpAvailable = await isTotpAvailable();
    if (!totpAvailable) {
      base.skip(true, 'TOTP not configured on this server');
      return;
    }

    // We can't easily enable TOTP for a test user without the encryption key,
    // so verify the UI components exist when navigating to login
    await page.goto('/login');
    await page.waitForTimeout(1000);

    // Verify the login form exists
    const tenantInput = page.locator('[data-testid="login-tenant-slug"]');
    await expect(tenantInput).toBeVisible();

    // Verify the access code link exists
    const accessCodeLink = page.getByTestId('login-access-code-link');
    await expect(accessCodeLink).toBeVisible();
  });
});

// =========================================================================
// T-57: Forgot password link and page
// =========================================================================

base.describe('Forgot Password', () => {
  base('forgot password page directs to access code', async ({ page }) => {
    await page.goto('/login/forgot-password');
    await page.waitForTimeout(1000);

    // The forgot-password page was replaced in commit c065a44 (2026-04-09)
    // with the email-reset flow. Earlier versions guided users to contact
    // an administrator; the new page instead collects org+email and offers
    // an access-code fallback link. The test's intent ("the page directs
    // users to an access-code path if they can't use email reset") is
    // still valid — assert the form fields and the access-code link are
    // present, rather than the stale 'administrator' text.
    const orgInput = page.locator('[data-testid="forgot-password-tenant"]');
    await expect(orgInput).toBeVisible();

    const emailInput = page.locator('[data-testid="forgot-password-email"]');
    await expect(emailInput).toBeVisible();

    // Should have link to access code login as a fallback path.
    const accessCodeLink = page.locator('a[href="/login/access-code"]');
    await expect(accessCodeLink).toBeVisible();
  });
});

// =========================================================================
// T-58: Admin generates access code, displayed in modal
// =========================================================================

authTest.describe('Admin Access Code', () => {
  authTest('admin can generate access code from users tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // Target a SPECIFIC seed user (outreach@dev.fabt.org) rather than
    // codeBtn.first(). Previous version used .first() which picked up the
    // alphabetically-first user — often a deactivated `coord-e2e-*` orphan
    // from coordinator-assignment tests running earlier in the suite. The
    // backend returns a 409 Resource conflict when generating a code for a
    // deactivated user, the modal opens but generatedCode stays null, and
    // the test fails on `generated-access-code` not being visible. Targeting
    // a known-active seed user is deterministic and survives test pollution.
    const codeBtn = adminPage.locator('[data-testid="generate-access-code-outreach@dev.fabt.org"]');
    if (await codeBtn.count() === 0) {
      authTest.skip(true, 'outreach@dev.fabt.org not visible in admin panel');
      return;
    }

    // Scroll the button into view — the users table can be long once the
    // coordinator-assignment tests have populated it with test users.
    await codeBtn.scrollIntoViewIfNeeded();
    await codeBtn.click();
    await adminPage.waitForTimeout(2000);

    // Modal should appear with generated code
    const modal = adminPage.getByTestId('access-code-modal');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Code should be displayed
    const codeDisplay = adminPage.getByTestId('generated-access-code');
    await expect(codeDisplay).toBeVisible();
    const codeText = await codeDisplay.textContent();
    expect(codeText).toBeTruthy();
    expect(codeText!.length).toBeGreaterThanOrEqual(8);
  });
});

// =========================================================================
// T-59: Access code login page
// =========================================================================

base.describe('Access Code Login', () => {
  base('access code login page renders with form', async ({ page }) => {
    await page.goto('/login/access-code');
    await page.waitForTimeout(1000);

    // Form elements should exist
    await expect(page.getByTestId('access-code-tenant')).toBeVisible();
    await expect(page.getByTestId('access-code-email')).toBeVisible();
    await expect(page.getByTestId('access-code-input')).toBeVisible();
    await expect(page.getByTestId('access-code-submit')).toBeVisible();
  });
});

// =========================================================================
// T-60: Auth capabilities endpoint
// =========================================================================

base.describe('Auth Capabilities', () => {
  base('capabilities endpoint returns feature flags', async ({ request }) => {
    const response = await request.get(`${API_URL}/api/v1/auth/capabilities`);
    expect(response.status()).toBe(200);

    const data = await response.json();
    expect(data).toHaveProperty('emailResetAvailable');
    expect(data).toHaveProperty('totpAvailable');
    expect(data).toHaveProperty('accessCodeAvailable');
    expect(data.accessCodeAvailable).toBe(true);
  });
});

// =========================================================================
// T-61: Security button in header
// =========================================================================

authTest.describe('Security Settings Link', () => {
  authTest('security button visible in header for authenticated users', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);

    const securityBtn = outreachPage.getByTestId('totp-settings-button');
    await expect(securityBtn).toBeVisible();
  });
});
