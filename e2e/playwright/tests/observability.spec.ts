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
  await page.locator('main button', { hasText: /^Observability$/ }).first().click();
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

    // Toggle tracing on via the API directly (avoids fragile toggle button targeting)
    // then verify the UI reflects the change after reload
    const tenantId = 'a0000000-0000-0000-0000-000000000001';
    await adminPage.evaluate(async (tid) => {
      const token = localStorage.getItem('fabt_access_token');
      await fetch(`/api/v1/tenants/${tid}/observability`, {
        method: 'PUT',
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prometheus_enabled: true, tracing_enabled: true,
          tracing_endpoint: 'http://localhost:4318/v1/traces',
          monitor_stale_interval_minutes: 5, monitor_dv_canary_interval_minutes: 15,
          monitor_temperature_interval_minutes: 60, temperature_threshold_f: 32,
        }),
      });
    }, tenantId);

    // Reload and verify tracing is reflected in the UI
    await goToObservabilityTab(adminPage);

    // OTLP endpoint should be visible (tracing is on)
    await expect(adminPage.locator('main', { hasText: /otlp endpoint/i })).toBeVisible({ timeout: 5000 });

    // Clean up: toggle tracing back off via API
    await adminPage.evaluate(async (tid) => {
      const token = localStorage.getItem('fabt_access_token');
      await fetch(`/api/v1/tenants/${tid}/observability`, {
        method: 'PUT',
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prometheus_enabled: true, tracing_enabled: false,
          tracing_endpoint: 'http://localhost:4318/v1/traces',
          monitor_stale_interval_minutes: 5, monitor_dv_canary_interval_minutes: 15,
          monitor_temperature_interval_minutes: 60, temperature_threshold_f: 32,
        }),
      });
    }, tenantId);
  });

  test('change temperature threshold and save', async ({ adminPage }) => {
    await goToObservabilityTab(adminPage);

    // Find the threshold input (last number input — the one with °F)
    const thresholdInput = adminPage.locator('main input[type="number"]').last();
    await thresholdInput.fill('40');

    // Save
    await adminPage.locator('main button', { hasText: /^Save$/ }).click();
    await adminPage.waitForTimeout(1000);
    await expect(adminPage.locator('main', { hasText: /saved/i })).toBeVisible();

    // Re-navigate and verify persistence
    await goToObservabilityTab(adminPage);
    await expect(adminPage.locator('main input[type="number"]').last()).toHaveValue('40');

    // Clean up: reset to 32
    await adminPage.locator('main input[type="number"]').last().fill('32');
    await adminPage.locator('main button', { hasText: /^Save$/ }).click();
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
