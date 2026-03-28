import { test, expect } from '@playwright/test';
import { LoginPage } from '../pages/LoginPage';
import { cleanupTestData } from '../helpers/test-cleanup';

// Dedicated test user — never touches seed users
const RESET_TARGET_EMAIL = 'pwdtest-reset@dev.fabt.org';
const ORIGINAL_PASSWORD = 'OriginalPass12!';
const TEMP_PASSWORD = 'TempPassword12!!';
const ADMIN_EMAIL = 'admin@dev.fabt.org';
const ADMIN_PASSWORD = 'admin123';
const TENANT_SLUG = 'dev-coc';

async function getAdminToken(): Promise<string> {
  const res = await fetch('http://localhost:8080/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
  });
  const data = await res.json();
  return data.accessToken;
}

async function createTestUser(token: string): Promise<void> {
  const res = await fetch('http://localhost:8080/api/v1/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      email: RESET_TARGET_EMAIL,
      displayName: 'Reset Target User',
      password: ORIGINAL_PASSWORD,
      roles: ['COORDINATOR'],
      dvAccess: false,
    }),
  });
  if (!res.ok && res.status !== 409 && res.status !== 400) {
    throw new Error(`Failed to create test user: ${res.status} ${await res.text()}`);
  }
}

test.describe('Admin Password Reset', () => {
  test.beforeAll(async () => {
    const token = await getAdminToken();
    await createTestUser(token);
  });

  test.afterAll(async () => { await cleanupTestData(); });

  test('admin can reset a user password from Users tab', async ({ page }) => {
    // Login as admin
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(ADMIN_EMAIL, ADMIN_PASSWORD);
    await expect(page).toHaveURL(/\/admin/);

    // Find the reset button for the test user
    const resetButton = page.locator(`[data-testid="reset-password-${RESET_TARGET_EMAIL}"]`);
    await expect(resetButton).toBeVisible({ timeout: 10000 });
    await resetButton.click();

    // Fill the reset modal
    await page.locator('[data-testid="reset-new-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="reset-confirm-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="reset-password-submit"]').click();

    // Modal should close, success message visible
    await expect(page.locator('[data-testid="reset-password-submit"]')).not.toBeVisible({ timeout: 5000 });

    // Verify target user can login with new password
    await page.locator('button', { hasText: /logout|sign out/i }).click();
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(RESET_TARGET_EMAIL, TEMP_PASSWORD);
    await expect(page).toHaveURL(/\/(coordinator|admin)/);

    // Restore: change back to original password via self-service
    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
  });
});
