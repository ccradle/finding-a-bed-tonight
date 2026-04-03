import { test, expect } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'https://findabed.org';

test.describe('Demo Guard Verification', () => {

  test('admin creates user → sees demo_restricted error message', async ({ page }) => {
    // Login as admin
    await page.goto(`${BASE_URL}/login`);
    await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
    await page.locator('[data-testid="login-email"]').fill('admin@dev.fabt.org');
    await page.locator('[data-testid="login-password"]').fill('admin123');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    // Admin lands on Administration page automatically
    // Wait for user list table to load (not just spinner)
    await page.locator('table').waitFor({ state: 'visible', timeout: 15000 });
    await page.screenshot({ path: 'demo-guard-01-admin-users-loaded.png' });

    // Click "Create User" to open form
    await page.locator('button:has-text("Create User")').click();
    // Wait for form: password input appears only when form is open
    await page.locator('input[type="password"]').waitFor({ state: 'visible', timeout: 5000 });

    // Fill form using type-based selectors (no data-testid on these inputs)
    await page.locator('input[type="email"]').fill('demo-test@test.fabt.org');
    await page.locator('input[type="password"]').fill('DemoTest12345!');
    // Display name is the only plain text input in the form (not email, not password, not number)
    await page.locator('input:not([type])').last().fill('Demo Test User');
    // Select a role
    await page.locator('button:has-text("OUTREACH_WORKER")').click();
    await page.screenshot({ path: 'demo-guard-02-form-filled.png' });

    // Submit — last "Create User" button (full-width in form)
    await page.locator('button:has-text("Create User")').last().click();

    // Wait for error to appear
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'demo-guard-03-after-submit.png' });

    // Assert: the demo_restricted message should be visible on the page
    const pageText = await page.textContent('body');
    expect(pageText).toContain('disabled in the demo environment');
    expect(pageText).toContain('full deployment');
  });

  test('outreach worker can search beds (safe mutation allowed)', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
    await page.locator('[data-testid="login-email"]').fill('outreach@dev.fabt.org');
    await page.locator('[data-testid="login-password"]').fill('admin123');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    // Wait for search results to load
    await page.locator('text=shelters found').waitFor({ state: 'visible', timeout: 10000 });
    await page.screenshot({ path: 'demo-guard-04-outreach-search.png' });

    // Should see shelter data
    const shelterText = await page.textContent('body');
    expect(shelterText).toContain('shelters found');
    expect(shelterText).toContain('Hold This Bed');
  });
});
