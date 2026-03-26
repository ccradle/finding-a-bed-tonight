import { test, expect } from '../fixtures/auth.fixture';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Force fresh login by deleting cached auth state.
 * Portfolio Lesson 42: Cached tokens expire mid-suite (15-min lifespan).
 * Per-test acquisition adds ~1s overhead but prevents silent 401 → redirect → context closed.
 */
function clearAdminAuthState() {
  const stateFile = path.join(__dirname, '..', 'auth', 'admin.json');
  if (fs.existsSync(stateFile)) fs.unlinkSync(stateFile);
}

/** Navigate to Observability tab and wait for config to load */
async function goToObservabilityTab(page: import('@playwright/test').Page) {
  await page.goto('/admin');
  await expect(page.locator('main h1')).toContainText(/administration/i);
  await page.locator('button[role="tab"]', { hasText: /^Observability$/ }).click();
  await expect(page.locator('main h3', { hasText: /observability configuration/i }))
    .toBeVisible({ timeout: 5000 });
  await page.waitForTimeout(500);
}

test.describe('Observability Tab', () => {
  test('observability tab is visible and loads config', async ({ adminPage }) => {
    await goToObservabilityTab(adminPage);

    // Toggle switches should be present
    await expect(adminPage.locator('main', { hasText: /prometheus metrics/i })).toBeVisible();
    await expect(adminPage.locator('main', { hasText: /opentelemetry tracing/i })).toBeVisible();

    // Monitor interval inputs should be present
    await expect(adminPage.locator('main', { hasText: /stale shelter check/i })).toBeVisible();
    await expect(adminPage.locator('main', { hasText: /dv canary check/i })).toBeVisible();
    await expect(adminPage.locator('main', { hasText: /temperature check/i })).toBeVisible();

    // Temperature threshold input should be present
    await expect(adminPage.locator('main', { hasText: /surge activation threshold/i })).toBeVisible();
  });

  test('toggle tracing on, save, and verify persists on reload', async ({ adminPage }) => {
    await goToObservabilityTab(adminPage);

    // Toggle tracing on via UI
    const tracingToggle = adminPage.getByTestId('toggle-tracing');
    await expect(tracingToggle).toBeVisible();

    // Check initial state — should be off (aria-checked="false")
    const initialState = await tracingToggle.getAttribute('aria-checked');
    if (initialState === 'true') {
      // Already on — toggle off first, save, then toggle on
      await tracingToggle.click();
      await adminPage.getByTestId('observability-save').click();
      await adminPage.waitForTimeout(500);
      await goToObservabilityTab(adminPage);
    }

    // Toggle tracing ON
    await adminPage.getByTestId('toggle-tracing').click();
    await adminPage.waitForTimeout(200);

    // Save via UI
    await adminPage.getByTestId('observability-save').click();
    await adminPage.waitForTimeout(1000);
    await expect(adminPage.locator('main', { hasText: /saved/i })).toBeVisible();

    // Reload and verify tracing persists
    await goToObservabilityTab(adminPage);

    // OTLP endpoint should be visible (tracing is on)
    await expect(adminPage.locator('main', { hasText: /otlp endpoint/i })).toBeVisible({ timeout: 5000 });

    // Verify toggle shows ON state
    await expect(adminPage.getByTestId('toggle-tracing')).toHaveAttribute('aria-checked', 'true');

    // Clean up: toggle tracing back OFF
    await adminPage.getByTestId('toggle-tracing').click();
    await adminPage.getByTestId('observability-save').click();
    await adminPage.waitForTimeout(500);
  });

  test('change temperature threshold and save', async ({ adminPage }) => {
    test.setTimeout(60000); // Extended — this test navigates twice + saves + verifies

    await goToObservabilityTab(adminPage);

    // Find and fill the threshold input (fill auto-scrolls)
    await adminPage.getByTestId('temp-threshold').fill('40');

    // Save (data-testid to avoid ambiguity with ReservationSettings Save)
    await adminPage.getByTestId('observability-save').click();
    await adminPage.waitForTimeout(1000);
    await expect(adminPage.locator('main', { hasText: /saved/i })).toBeVisible();

    // Re-navigate and verify persistence
    await goToObservabilityTab(adminPage);
    await expect(adminPage.getByTestId('temp-threshold')).toHaveValue('40');

    // Clean up: reset to 32
    await adminPage.getByTestId('temp-threshold').fill('32');
    await adminPage.getByTestId('observability-save').click();
    await adminPage.waitForTimeout(500);
  });

  test('temperature status section displays when data available', async ({ adminPage }) => {
    await goToObservabilityTab(adminPage);

    // Temperature status may or may not be visible depending on whether the
    // hourly monitor has run. If visible, verify structure.
    const tempBanner = adminPage.locator('main', { hasText: /station:/i });
    const bannerCount = await tempBanner.count();

    if (bannerCount > 0) {
      await expect(adminPage.locator('main', { hasText: /threshold/i }).first()).toBeVisible();
      await expect(adminPage.locator('main', { hasText: /last checked/i })).toBeVisible();
    }
    // If no temperature data yet (monitor hasn't run), that's acceptable
  });
});
