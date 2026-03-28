import { test, expect } from '@playwright/test';
import { LoginPage } from '../pages/LoginPage';

const OUTREACH_EMAIL = 'outreach@dev.fabt.org';
const ORIGINAL_PASSWORD = 'admin123';
const NEW_PASSWORD = 'NewPassword12!!';

test.describe('Password Change', () => {
  test('outreach worker can change password via modal', async ({ page }) => {
    // Login
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(OUTREACH_EMAIL, ORIGINAL_PASSWORD);

    // Open change password modal
    await page.locator('[data-testid="change-password-button"]').click();
    await expect(page.locator('[data-testid="current-password-input"]')).toBeVisible();

    // Fill form
    await page.locator('[data-testid="current-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();

    // Should redirect to login after success
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });

    // Login with NEW password should work
    await loginPage.loginAndWaitForRedirect(OUTREACH_EMAIL, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/outreach/);

    // Restore: change back to original password
    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
  });

  test('wrong current password shows error', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(OUTREACH_EMAIL, ORIGINAL_PASSWORD);

    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill('WrongPassword99');
    await page.locator('[data-testid="new-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();

    // Should show error, not redirect
    await expect(page.locator('[role="alert"]')).toBeVisible({ timeout: 5000 });
    await expect(page).toHaveURL(/\/outreach/);
  });

  test('mismatched passwords shows client-side error', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(OUTREACH_EMAIL, ORIGINAL_PASSWORD);

    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(ORIGINAL_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill('DifferentPassword12');
    await page.locator('[data-testid="change-password-submit"]').click();

    await expect(page.locator('[role="alert"]')).toBeVisible();
    await expect(page).toHaveURL(/\/outreach/);
  });
});
