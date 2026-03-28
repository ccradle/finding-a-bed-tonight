import { test, expect } from '@playwright/test';
import { LoginPage } from '../pages/LoginPage';

const ADMIN_EMAIL = 'admin@dev.fabt.org';
const COORDINATOR_EMAIL = 'cocadmin@dev.fabt.org';
const ADMIN_PASSWORD = 'admin123';
const TEMP_PASSWORD = 'TempPassword12!!';

test.describe('Admin Password Reset', () => {
  test('admin can reset a user password from Users tab', async ({ page }) => {
    // Login as admin
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(ADMIN_EMAIL, ADMIN_PASSWORD);
    await expect(page).toHaveURL(/\/admin/);

    // Find the reset button for cocadmin
    const resetButton = page.locator(`[data-testid="reset-password-${COORDINATOR_EMAIL}"]`);
    await expect(resetButton).toBeVisible({ timeout: 10000 });
    await resetButton.click();

    // Fill the reset modal
    await page.locator('[data-testid="reset-new-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="reset-confirm-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="reset-password-submit"]').click();

    // Modal should close, success message visible
    await expect(page.locator('[data-testid="reset-password-submit"]')).not.toBeVisible({ timeout: 5000 });

    // Verify cocadmin can login with new password
    await page.locator('button', { hasText: /logout|sign out/i }).click();
    await loginPage.loginAndWaitForRedirect(COORDINATOR_EMAIL, TEMP_PASSWORD);
    await expect(page).toHaveURL(/\/(coordinator|admin)/);

    // Restore: change cocadmin back to original via the change password flow
    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(TEMP_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(ADMIN_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(ADMIN_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
  });
});
