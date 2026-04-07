import { test as base, expect } from '@playwright/test';
import { test as authTest } from '../fixtures/auth.fixture';

/**
 * App Version Display — Playwright E2E tests.
 *
 * Verifies version is visible on the login page (unauthenticated)
 * and in the authenticated layout footer.
 *
 * NOTE: Version endpoint requires BuildProperties (spring-boot:build-info goal).
 * The E2E CI workflow must include this goal — if not, these tests FAIL
 * (not skip) to expose the configuration gap.
 */

base.describe('Version on Login Page', () => {
  base('version is visible in login page footer', async ({ page }) => {
    await page.goto('/login');

    const version = page.locator('[data-testid="app-version"]');
    await expect(version).toBeVisible({ timeout: 10000 });
    const text = await version.textContent();
    expect(text).toMatch(/v\d+\.\d+/);
  });
});

authTest.describe('Version in Authenticated Layout', () => {
  authTest('version is visible in admin panel footer', async ({ adminPage }) => {
    await adminPage.goto('/admin');

    // Version footer renders only after /api/v1/version returns (conditional on appVersion state).
    // Wait for it to appear in the DOM first, then scroll into view.
    const version = adminPage.locator('[data-testid="app-version"]');
    await expect(version).toBeAttached({ timeout: 15000 });
    await version.scrollIntoViewIfNeeded();
    await expect(version).toBeVisible({ timeout: 10000 });
    const text = await version.textContent();
    expect(text).toMatch(/Finding A Bed Tonight v\d+\.\d+/);
  });
});
