import { test, expect } from '../fixtures/auth.fixture';

test.describe('OAuth2 Providers Admin Tab', () => {
  test('admin sees OAuth2 Providers tab and provider list', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);

    // Click OAuth2 Providers tab
    const tab = adminPage.locator('main button', { hasText: /^OAuth2 Providers$/ }).first();
    await expect(tab).toBeVisible();
    await tab.click();
    await adminPage.waitForTimeout(1000);

    // Title should be visible
    await expect(adminPage.locator('main h3', { hasText: /identity provider configuration/i })).toBeVisible();

    // Add Provider button should be visible
    await expect(adminPage.locator('main button', { hasText: /add provider/i })).toBeVisible();
  });

  test('admin can open add provider form with type presets', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^OAuth2 Providers$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    // Click Add Provider
    await adminPage.locator('main button', { hasText: /add provider/i }).click();
    await adminPage.waitForTimeout(500);

    // Form should be visible with provider type dropdown
    await expect(adminPage.locator('main select')).toBeVisible();

    // Client ID and Client Secret fields should be visible
    await expect(adminPage.locator('main', { hasText: /client id/i })).toBeVisible();
    await expect(adminPage.locator('main', { hasText: /client secret/i })).toBeVisible();

    // Issuer URI field should be visible
    const issuerInput = adminPage.locator('main input[placeholder*="accounts.google.com"]');
    await expect(issuerInput).toBeVisible();

    // Test Connection button should be visible
    await expect(adminPage.locator('main button', { hasText: /test connection/i })).toBeVisible();

    // Secret note should be visible
    await expect(adminPage.locator('main', { hasText: /stored securely/i })).toBeVisible();
  });
});
