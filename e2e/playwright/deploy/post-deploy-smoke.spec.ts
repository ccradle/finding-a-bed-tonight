import { test, expect } from '@playwright/test';

/**
 * Post-deploy smoke tests — run against the LIVE site after a deployment.
 *
 * Usage:
 *   cd e2e/playwright
 *   FABT_BASE_URL=https://findabed.org npx playwright test post-deploy-smoke --project chromium --trace on 2>&1 | tee ../../logs/post-deploy-smoke.log
 *
 * Requires FABT_BASE_URL env var (defaults to https://findabed.org).
 * These tests use demo credentials and are safe to run in demo mode.
 */

const BASE = process.env.FABT_BASE_URL ?? 'https://findabed.org';
const LOGIN = `${BASE}/login`;

test.describe('Post-deploy smoke tests', () => {

  test('1. Version shows v0.37', async ({ page }) => {
    await page.goto(LOGIN);
    const version = page.getByTestId('app-version');
    await expect(version).toContainText('v0.37', { timeout: 10_000 });
  });

  test('2. Outreach worker login + search returns results', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('outreach@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // Outreach lands at /outreach
    await expect(page).toHaveURL(/outreach/, { timeout: 15_000 });

    // Filter to SINGLE_ADULT
    await page.getByTestId('population-type-filter').selectOption('SINGLE_ADULT');

    // Results should appear (shelter cards have dynamic testid: shelter-card-*)
    const shelterCard = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(shelterCard).toBeVisible({ timeout: 10_000 });
  });

  test('3. Outreach worker can hold a bed', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('outreach@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/outreach/, { timeout: 15_000 });

    await page.getByTestId('population-type-filter').selectOption('SINGLE_ADULT');

    const shelterCard = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(shelterCard).toBeVisible({ timeout: 10_000 });

    // Hold button has testid: hold-bed-{shelterId}-{populationType}
    const holdButton = page.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.isVisible()) {
      await holdButton.click();
      // 201 (created) or 409 (no beds) are both acceptable — just no 500
      await page.waitForTimeout(2000);
      const pageContent = await page.textContent('body');
      expect(pageContent).not.toContain('Internal Server Error');
    }
  });

  test('4. Demo guard blocks admin user creation', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('admin@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // Admin lands on admin panel — users tab is default
    await page.locator('[data-tab-key="users"]').waitFor({ timeout: 15_000 });
    await page.locator('[data-tab-key="users"]').click();
    await page.waitForTimeout(1000);

    // Click "Create User" button to open the form
    await page.locator('button', { hasText: /Create User/i }).click();
    await page.waitForTimeout(500);

    // Fill the create user form
    await page.getByTestId('create-user-email').fill('smoke-test@example.com');
    await page.getByTestId('create-user-name').fill('Smoke Test');
    await page.getByTestId('create-user-password').fill('TestPass123!');

    // Select a role
    await page.locator('button', { hasText: 'OUTREACH_WORKER' }).click();

    // Submit
    await page.getByTestId('create-user-submit').click();

    // Should see demo_restricted error
    await expect(page.locator('text=/demo/i')).toBeVisible({ timeout: 10_000 });
  });

  test('5. DV coordinator can login and sees notification bell', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('dv-coordinator@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // DV coordinator lands at /coordinator
    await expect(page).toHaveURL(/coordinator/, { timeout: 15_000 });

    // Bell should be visible (persistent notifications feature)
    const bell = page.getByTestId('notification-bell-button');
    await expect(bell).toBeVisible({ timeout: 5_000 });
  });

  test('6. Notification count endpoint responds', async ({ page }) => {
    // Login as dv-coordinator to get a token, then check the API
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('dv-coordinator@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/coordinator/, { timeout: 15_000 });

    // The notification count endpoint should respond (exercised by the bell on mount)
    // Verify the bell has an aria-label (REST count loaded successfully)
    const bell = page.getByTestId('notification-bell-button');
    await expect(bell).toBeVisible();
    const ariaLabel = await bell.getAttribute('aria-label');
    expect(ariaLabel).toBeTruthy();
    expect(ariaLabel).toContain('Notifications');
  });

  test('7. Notification API returns 200 (Flyway V35 migration ran)', async ({ page }) => {
    // Direct API verification — catches Flyway migration failure that UI tests would miss.
    // Login first to get auth context, then call the endpoint directly.
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('dv-coordinator@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/coordinator/, { timeout: 15_000 });

    // Extract the access token from localStorage
    const token = await page.evaluate(() => localStorage.getItem('fabt_access_token'));
    expect(token).toBeTruthy();

    // Call the notification count endpoint directly
    const response = await page.request.get(`${BASE}/api/v1/notifications/count`, {
      headers: { 'Authorization': `Bearer ${token}` },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body).toHaveProperty('unread');
    expect(typeof body.unread).toBe('number');
  });

  test('8. SSE connects without reconnecting banner (outreach)', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('outreach@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // Outreach lands at /outreach with SSE notifications active
    await expect(page).toHaveURL(/outreach/, { timeout: 15_000 });
    await page.waitForTimeout(5000);

    // The reconnecting banner should NOT be visible
    const reconnecting = page.getByTestId('connection-status-reconnecting');
    await expect(reconnecting).not.toBeVisible();
  });

  test('9. Admin can see assigned shelters on user edit drawer', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('admin@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // Navigate to Users tab
    await page.locator('[data-tab-key="users"]').waitFor({ timeout: 15_000 });
    await page.locator('[data-tab-key="users"]').click();
    await page.waitForTimeout(1000);

    // Click the Edit button on the dv-coordinator row to open the drawer
    const dvCoordRow = page.locator('tr', { hasText: 'dv-coordinator@dev.fabt.org' });
    await dvCoordRow.locator('button', { hasText: 'Edit' }).click();
    await page.waitForTimeout(1000);

    // Should see "Assigned Shelters" section with chips OR "No shelters assigned"
    const shelterChips = page.getByTestId('user-assigned-shelters');
    const noShelters = page.getByTestId('user-no-shelters');
    const either = await shelterChips.isVisible() || await noShelters.isVisible();
    expect(either).toBeTruthy();
  });

  test('10. Admin can see coordinator combobox on shelter edit', async ({ page }) => {
    await page.goto(LOGIN);
    await page.getByTestId('login-tenant-slug').fill('dev-coc');
    await page.getByTestId('login-email').fill('admin@dev.fabt.org');
    await page.getByTestId('login-password').fill('admin123');
    await page.getByTestId('login-submit').click();

    // Navigate to Shelters tab
    await page.locator('[data-tab-key="shelters"]').waitFor({ timeout: 15_000 });
    await page.locator('[data-tab-key="shelters"]').click();
    await page.waitForTimeout(1000);

    // Click Edit on first shelter to navigate to shelter edit page
    const editLink = page.locator('table tbody tr').first().locator('a', { hasText: 'Edit' });
    await editLink.click();
    await page.waitForURL(/\/edit/, { timeout: 10_000 });

    // The coordinator combobox input should be visible in edit mode
    const combobox = page.getByTestId('coordinator-combobox-input');
    await expect(combobox).toBeVisible({ timeout: 5_000 });
  });

  test('11. Forgot password page renders', async ({ page }) => {
    await page.goto(`${BASE}/login/forgot-password`);

    // Form fields should be visible
    await expect(page.getByTestId('forgot-password-tenant')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('forgot-password-email')).toBeVisible();
    await expect(page.getByTestId('forgot-password-submit')).toBeVisible();
  });
});
