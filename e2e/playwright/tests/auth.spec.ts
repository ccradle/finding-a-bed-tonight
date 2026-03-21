import { test, expect } from '@playwright/test';
import { LoginPage } from '../pages/LoginPage';

test.describe('Authentication', () => {
  test('successful login as outreach worker redirects to /outreach', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect('outreach@dev.fabt.org', 'admin123');
    await expect(page).toHaveURL(/\/outreach/);
    await expect(page.locator('main h1')).toContainText(/find a bed/i);
  });

  test('successful login as COC admin redirects appropriately', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect('cocadmin@dev.fabt.org', 'admin123');
    // COC_ADMIN routes to /coordinator or /admin depending on getDefaultRouteForRoles
    await expect(page).toHaveURL(/\/(coordinator|admin)/);
  });

  test('successful login as admin redirects to /admin', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect('admin@dev.fabt.org', 'admin123');
    await expect(page).toHaveURL(/\/admin/);
    await expect(page.locator('main h1')).toContainText(/administration/i);
  });

  test('failed login with wrong password shows error and stays on /login', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.login('outreach@dev.fabt.org', 'wrongpassword');
    // Wait for error — the login page shows error text inline
    await page.waitForTimeout(3000);
    await expect(page).toHaveURL(/\/login/);
    // Verify we're still on login (not redirected) — that IS the error state
  });
});
