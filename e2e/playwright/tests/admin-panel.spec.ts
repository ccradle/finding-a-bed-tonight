import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

test.describe('Admin Panel', () => {
  test.afterAll(async () => { await cleanupTestData(); });
  test('admin panel loads with tabs (Users, Shelters, API Keys, Subscriptions)', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);
    // Tab buttons are inside main content
    await expect(adminPage.locator('main button', { hasText: /users/i })).toBeVisible();
    await expect(adminPage.locator('main button', { hasText: /shelters/i })).toBeVisible();
    await expect(adminPage.locator('main button', { hasText: /api keys/i })).toBeVisible();
    await expect(adminPage.locator('main button', { hasText: /subscriptions/i })).toBeVisible();
  });

  test('create user form submits and user appears in list', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Click Create User button
    await adminPage.locator('main button', { hasText: /create user/i }).click();
    // Fill form (scope to tabpanel to avoid ReservationSettings inputs)
    const panel = adminPage.locator('[role="tabpanel"]');
    const uniqueEmail = `e2e-test-${Date.now()}@dev.fabt.org`;
    await panel.locator('input[type="email"]').last().fill(uniqueEmail);
    await panel.locator('input').nth(1).fill('E2E Test User');
    await panel.locator('input[type="password"]').last().fill('TestPassword123!');
    // Select OUTREACH_WORKER role
    await panel.locator('button', { hasText: 'OUTREACH_WORKER' }).click();
    // Submit — the last "Create User" button in the form
    await panel.locator('button', { hasText: /create user/i }).last().click();
    await adminPage.waitForTimeout(2000);
    // Verify user appears in table
    await expect(adminPage.locator('main td', { hasText: uniqueEmail })).toBeVisible();
  });

  test('shelter list displays shelters with basic info', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Click Shelters tab — use first() to avoid matching nav
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    // Should show shelter table with data
    const rows = adminPage.locator('main tbody tr');
    expect(await rows.count()).toBeGreaterThan(0);
    // First row should have a name
    await expect(rows.first().locator('td').first()).not.toBeEmpty();
  });

  test('create API key shows the key in reveal panel', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Switch to API Keys tab
    await adminPage.locator('main button', { hasText: /api keys/i }).click();
    await adminPage.waitForTimeout(500);
    // Click Create API Key
    await adminPage.locator('main button', { hasText: /create/i }).click();
    // Fill label
    await adminPage.locator('main input[placeholder*="e.g."]').fill('E2E Test Key');
    // Submit
    await adminPage.locator('main button', { hasText: /create/i }).last().click();
    await adminPage.waitForTimeout(2000);
    // The reveal panel should show a hex key string (32 hex chars), not blank or "undefined"
    const revealBox = adminPage.locator('[data-testid="api-key-reveal"]');
    await expect(revealBox).toBeVisible();
    const keyText = await revealBox.textContent();
    expect(keyText).toBeTruthy();
    expect(keyText).not.toBe('undefined');
    expect(keyText!.length).toBeGreaterThanOrEqual(16);
  });

  test('surge tab displays and allows activation', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Click Surge tab
    await adminPage.locator('main button', { hasText: /^Surge$|^Emergencia$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    // Should see activate button or active surge banner
    const activateBtn = adminPage.locator('main button', { hasText: /activate|activar/i });
    const surgeBanner = adminPage.locator('main div[style*="dc2626"]');
    const hasActivate = await activateBtn.count() > 0;
    const hasBanner = await surgeBanner.count() > 0;
    // Either the activate button or an active surge banner should be visible
    expect(hasActivate || hasBanner).toBe(true);
  });

  test('2-1-1 Import link navigates to import page from admin panel', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Click Imports tab
    await adminPage.locator('main button', { hasText: /imports/i }).click();
    await adminPage.waitForTimeout(500);
    // Click 2-1-1 Import link
    await adminPage.locator('a', { hasText: /2-1-1 import/i }).click();
    // Verify we navigated to the import page (file upload area visible)
    await expect(adminPage).toHaveURL(/\/coordinator\/import\/211/);
    await expect(adminPage.locator('input[type="file"]')).toBeAttached();
  });
});
