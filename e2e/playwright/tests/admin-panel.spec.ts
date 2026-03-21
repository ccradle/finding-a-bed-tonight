import { test, expect } from '../fixtures/auth.fixture';

test.describe('Admin Panel', () => {
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
    // Fill form
    const uniqueEmail = `e2e-test-${Date.now()}@dev.fabt.org`;
    const inputs = adminPage.locator('main input');
    // Find email input, display name, password by type/order
    await adminPage.locator('main input[type="email"]').last().fill(uniqueEmail);
    await adminPage.locator('main input').nth(1).fill('E2E Test User');
    await adminPage.locator('main input[type="password"]').last().fill('TestPassword123!');
    // Select OUTREACH_WORKER role
    await adminPage.locator('main button', { hasText: 'OUTREACH_WORKER' }).click();
    // Submit — the last "Create User" button in the form
    await adminPage.locator('main button', { hasText: /create user/i }).last().click();
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
    const revealBox = adminPage.locator('main div[style*="monospace"]');
    await expect(revealBox).toBeVisible();
    const keyText = await revealBox.textContent();
    expect(keyText).toBeTruthy();
    expect(keyText).not.toBe('undefined');
    expect(keyText!.length).toBeGreaterThanOrEqual(16);
  });
});
