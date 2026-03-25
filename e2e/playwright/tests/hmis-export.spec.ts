import { test, expect } from '../fixtures/auth.fixture';

/**
 * HMIS Export Admin Tab — Playwright E2E tests.
 * Uses data-testid attributes for stable locators.
 */

test.describe('HMIS Export', () => {

  test('HMIS Export tab visible to admin', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    const hmisTab = adminPage.locator('button', { hasText: /HMIS Export/i });
    await expect(hmisTab).toBeVisible();
  });

  test('data preview shows shelter inventory', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(2000);

    const preview = adminPage.getByTestId('hmis-preview');
    await expect(preview).toBeVisible();
    // Should have at least one row in the preview table
    const rows = preview.locator('tbody tr');
    expect(await rows.count()).toBeGreaterThan(0);
  });

  test('export history section loads', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(2000);

    const history = adminPage.getByTestId('hmis-history');
    await expect(history).toBeVisible();
  });

  test('Push Now button visible to admin', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(1000);

    const pushBtn = adminPage.getByTestId('hmis-push-now');
    await expect(pushBtn).toBeVisible();
  });

  test('export status section loads', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(2000);

    const status = adminPage.getByTestId('hmis-status');
    await expect(status).toBeVisible();
  });
});
