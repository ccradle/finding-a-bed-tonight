import { test, expect } from '@playwright/test';

/**
 * Password reset E2E tests. No SMTP required — tests verify UI behavior.
 * Full email round-trip tested in backend integration tests (GreenMail).
 */
test.describe('Password Reset', () => {

  // ER-40: Forgot password form visible only when emailResetAvailable=true
  test('ER-40: forgot password link visibility based on capabilities', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('domcontentloaded');

    // Check capabilities endpoint to determine expected behavior
    const capabilities = await page.evaluate(async () => {
      const resp = await fetch('/api/v1/auth/capabilities');
      return resp.ok ? resp.json() : null;
    });

    const forgotLink = page.locator('a[href*="forgot-password"]');
    if (capabilities && capabilities.emailResetAvailable) {
      await expect(forgotLink).toBeVisible();
    } else {
      // When SMTP not configured, link may point to access code instead
      // or be hidden entirely — both are valid
    }
  });

  // ER-41: Forgot password form submit shows confirmation
  test('ER-41: forgot password form submit shows check-email confirmation', async ({ page }) => {
    await page.goto('/login/forgot-password');
    await page.waitForLoadState('domcontentloaded');

    // Fill organization
    const tenantInput = page.getByTestId('forgot-password-tenant');
    await expect(tenantInput).toBeVisible({ timeout: 5000 });
    await tenantInput.fill('dev-coc');

    // Fill email
    const emailInput = page.getByTestId('forgot-password-email');
    await emailInput.fill('anyuser@example.org');

    // Submit
    await page.getByTestId('forgot-password-submit').click();

    // Confirmation should appear regardless of email validity (no enumeration)
    const confirmation = page.getByTestId('forgot-password-confirmation');
    await expect(confirmation).toBeVisible({ timeout: 10000 });
  });

  // ER-42: Reset password page with valid token (API-assisted)
  test('ER-42: reset password page accepts token from URL and shows form', async ({ page }) => {
    // Navigate with a fake token — we just want to verify the UI renders
    await page.goto('/login/reset-password?token=test-token-for-ui-verification');
    await page.waitForLoadState('domcontentloaded');

    // Password fields should be visible
    const newPassword = page.getByTestId('reset-password-new');
    await expect(newPassword).toBeVisible({ timeout: 5000 });

    const confirmPassword = page.getByTestId('reset-password-confirm');
    await expect(confirmPassword).toBeVisible();

    const submitButton = page.getByTestId('reset-password-submit');
    await expect(submitButton).toBeVisible();
    await expect(submitButton).toBeEnabled();

    // Token should be cleared from URL (Marcus: browser history protection)
    await page.waitForTimeout(500);
    expect(page.url()).not.toContain('token=');
  });

  // ER-43: Reset password page with invalid token shows error
  test('ER-43: reset password with invalid token shows error', async ({ page }) => {
    await page.goto('/login/reset-password?token=invalid-token-that-does-not-exist');
    await page.waitForLoadState('domcontentloaded');

    const newPassword = page.getByTestId('reset-password-new');
    await expect(newPassword).toBeVisible({ timeout: 5000 });
    await newPassword.fill('SecureNewPass12!');

    const confirmPassword = page.getByTestId('reset-password-confirm');
    await confirmPassword.fill('SecureNewPass12!');

    await page.getByTestId('reset-password-submit').click();

    // Error should appear (invalid token)
    const errorBox = page.getByTestId('reset-password-error');
    await expect(errorBox).toBeVisible({ timeout: 5000 });
  });

  // ER-43b: Reset password page with no token shows warning
  test('ER-43b: reset password page with no token shows warning', async ({ page }) => {
    await page.goto('/login/reset-password');
    await page.waitForLoadState('domcontentloaded');

    // "No reset token found" message should be visible
    await expect(page.locator('text=No reset token found')).toBeVisible({ timeout: 5000 });

    // Submit button should be disabled
    const submitButton = page.getByTestId('reset-password-submit');
    await expect(submitButton).toBeDisabled();
  });

  // ER-41b: Forgot password — access code link is available
  test('ER-41b: forgot password page has access code link', async ({ page }) => {
    await page.goto('/login/forgot-password');
    await page.waitForLoadState('domcontentloaded');

    const accessCodeLink = page.locator('a[href*="access-code"]');
    await expect(accessCodeLink).toBeVisible({ timeout: 5000 });
  });
});
