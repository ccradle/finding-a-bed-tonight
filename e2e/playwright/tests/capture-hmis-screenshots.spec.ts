/**
 * HMIS Export — dedicated screenshot capture for the HMIS bridge walkthrough.
 *
 * Captures: Admin HMIS Export tab (status, preview, history), Grafana HMIS Bridge dashboard.
 * Output: ../../demo/screenshots/hmis-* (findABed docs repo)
 *
 * Requires: dev-start.sh running (optionally with --observability for Grafana shots)
 */
import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');

test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
});

test.describe('HMIS Export Screenshot Capture', () => {

  test('hmis-01 - HMIS Export tab overview', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(2000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'hmis-01-export-tab.png'), fullPage: true });
  });

  test('hmis-02 - Data preview with DV aggregation', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(2000);
    // Click DV filter to show aggregated view
    const dvFilter = adminPage.locator('button', { hasText: /DV.*Aggregated/i });
    if (await dvFilter.count() > 0) {
      await dvFilter.click();
      await adminPage.waitForTimeout(500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'hmis-02-dv-aggregation.png'), fullPage: false });
  });

  test('hmis-03 - Push Now confirmation', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /HMIS Export/i }).click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'hmis-03-push-controls.png'), fullPage: false });
  });

  test('hmis-04 - Grafana HMIS Bridge dashboard', async ({ browser }) => {
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:3000/d/fabt-hmis-bridge/fabt-hmis-bridge?orgId=1&from=now-1h&to=now', { timeout: 10000 });
      const loginForm = page.locator('input[name="user"]');
      if (await loginForm.count() > 0) {
        await loginForm.fill('admin');
        await page.locator('input[name="password"]').fill('admin');
        await page.locator('button[type="submit"]').click();
        await page.waitForTimeout(2000);
        const skipButton = page.locator('a', { hasText: /skip/i });
        if (await skipButton.count() > 0) await skipButton.click();
        await page.goto('http://localhost:3000/d/fabt-hmis-bridge/fabt-hmis-bridge?orgId=1&from=now-1h&to=now');
      }
      await page.waitForTimeout(3000);
      await page.screenshot({ path: path.join(DEMO_DIR, 'hmis-04-grafana-dashboard.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Grafana not available — skipping HMIS dashboard screenshot');
    }
  });
});
