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

  test('1. Version shows v0.28', async ({ page }) => {
    await page.goto(LOGIN);
    const version = page.getByTestId('app-version');
    await expect(version).toContainText('v0.28', { timeout: 10_000 });
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

  test('5. SSE connects without reconnecting banner', async ({ page }) => {
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
});
