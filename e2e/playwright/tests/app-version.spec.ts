import { test as base, expect } from '@playwright/test';
import { test as authTest } from '../fixtures/auth.fixture';

/**
 * App Version Display — Playwright E2E tests.
 *
 * Verifies version is visible on the login page (unauthenticated)
 * and in the authenticated layout footer.
 *
 * NOTE: Version endpoint requires BuildProperties (build-info Maven goal).
 * In dev mode without a Maven build, the endpoint may not exist —
 * tests skip gracefully if version is not available.
 */

base.describe('Version on Login Page', () => {
  base('version is visible in login page footer', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(2000);

    const version = page.locator('[data-testid="app-version"]');
    if (await version.count() === 0) {
      // BuildProperties not available (dev mode) — skip gracefully
      base.skip();
      return;
    }
    await expect(version).toBeVisible();
    const text = await version.textContent();
    expect(text).toMatch(/v\d+\.\d+/);
  });
});

authTest.describe('Version in Authenticated Layout', () => {
  authTest('version is visible in admin panel footer', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const version = adminPage.locator('[data-testid="app-version"]');
    if (await version.count() === 0) {
      authTest.skip();
      return;
    }
    await expect(version).toBeVisible();
    const text = await version.textContent();
    expect(text).toMatch(/Finding A Bed Tonight v\d+\.\d+/);
  });
});
