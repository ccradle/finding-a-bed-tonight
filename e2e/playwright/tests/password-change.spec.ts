import { test, expect } from '@playwright/test';
import { LoginPage } from '../pages/LoginPage';

// Dedicated test user — never touches seed users
const TEST_EMAIL = 'pwdtest-change@dev.fabt.org';
const TEST_PASSWORD = 'OriginalPass12!';
const NEW_PASSWORD = 'NewPassword12!!';
const TENANT_SLUG = 'dev-coc';

async function getAdminToken(): Promise<string> {
  const res = await fetch('http://localhost:8080/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const data = await res.json();
  return data.accessToken;
}

async function createTestUser(token: string): Promise<void> {
  const res = await fetch('http://localhost:8080/api/v1/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      email: TEST_EMAIL,
      displayName: 'Password Test User',
      password: TEST_PASSWORD,
      roles: ['OUTREACH_WORKER'],
      dvAccess: false,
    }),
  });
  // 201 = created, 409/400 = already exists (idempotent across retries)
  if (!res.ok && res.status !== 409 && res.status !== 400) {
    throw new Error(`Failed to create test user: ${res.status} ${await res.text()}`);
  }
}

test.describe('Password Change', () => {
  test.beforeAll(async () => {
    const token = await getAdminToken();
    await createTestUser(token);
  });

  test('user can change password via modal', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(TEST_EMAIL, TEST_PASSWORD);

    // Open change password modal
    await page.locator('[data-testid="change-password-button"]').click();
    await expect(page.locator('[data-testid="current-password-input"]')).toBeVisible();

    // Fill form
    await page.locator('[data-testid="current-password-input"]').fill(TEST_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();

    // Should redirect to login after success
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });

    // Login with NEW password should work
    await loginPage.loginAndWaitForRedirect(TEST_EMAIL, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/outreach/);

    // Restore password for subsequent runs
    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(TEST_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill(TEST_PASSWORD);
    await page.locator('[data-testid="change-password-submit"]').click();
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 });
  });

  test('wrong current password shows error', async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.goto();
    await loginPage.loginAndWaitForRedirect(TEST_EMAIL, TEST_PASSWORD);

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
    await loginPage.loginAndWaitForRedirect(TEST_EMAIL, TEST_PASSWORD);

    await page.locator('[data-testid="change-password-button"]').click();
    await page.locator('[data-testid="current-password-input"]').fill(TEST_PASSWORD);
    await page.locator('[data-testid="new-password-input"]').fill(NEW_PASSWORD);
    await page.locator('[data-testid="confirm-password-input"]').fill('DifferentPassword12');
    await page.locator('[data-testid="change-password-submit"]').click();

    await expect(page.locator('[role="alert"]')).toBeVisible();
    await expect(page).toHaveURL(/\/outreach/);
  });
});
