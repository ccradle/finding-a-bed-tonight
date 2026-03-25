/**
 * CoC Analytics — dedicated screenshot capture for the analytics walkthrough.
 *
 * Captures: Analytics tab (executive summary, utilization trends, shelter performance,
 * HIC/PIT export, batch jobs management).
 * Output: ../../demo/screenshots/analytics-* (findABed docs repo)
 *
 * Requires: dev-start.sh running
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

test.describe('Analytics Screenshot Capture', () => {

  test('analytics-01 - Executive summary', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'analytics-01-executive-summary.png'), fullPage: true });
  });

  test('analytics-02 - Utilization trends chart', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const trends = adminPage.getByTestId('analytics-utilization-trends');
    await trends.scrollIntoViewIfNeeded();
    await adminPage.waitForTimeout(500);
    await trends.screenshot({ path: path.join(DEMO_DIR, 'analytics-02-utilization-trends.png') });
  });

  test('analytics-03 - Demand signals', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const summary = adminPage.getByTestId('analytics-executive-summary');
    await summary.screenshot({ path: path.join(DEMO_DIR, 'analytics-03-demand-signals.png') });
  });

  test('analytics-04 - Batch jobs management', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('analytics-batch-jobs-btn').click();
    await adminPage.waitForTimeout(2000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'analytics-04-batch-jobs.png'), fullPage: true });
  });

  test('analytics-05 - HIC/PIT export section', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const exportSection = adminPage.getByTestId('analytics-export');
    await exportSection.scrollIntoViewIfNeeded();
    await exportSection.screenshot({ path: path.join(DEMO_DIR, 'analytics-05-hic-pit-export.png') });
  });

  test('analytics-06 - Grafana CoC Analytics dashboard', async ({ browser }) => {
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:3000/d/fabt-coc-analytics/fabt-coc-analytics?orgId=1&from=now-1h&to=now', { timeout: 10000 });
      const loginForm = page.locator('input[name="user"]');
      if (await loginForm.count() > 0) {
        await loginForm.fill('admin');
        await page.locator('input[name="password"]').fill('admin');
        await page.locator('button[type="submit"]').click();
        await page.waitForTimeout(2000);
        const skipButton = page.locator('a', { hasText: /skip/i });
        if (await skipButton.count() > 0) await skipButton.click();
        await page.goto('http://localhost:3000/d/fabt-coc-analytics/fabt-coc-analytics?orgId=1&from=now-1h&to=now');
      }
      await page.waitForTimeout(5000);
      await page.screenshot({ path: path.join(DEMO_DIR, 'analytics-06-grafana-dashboard.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Grafana not available — skipping CoC Analytics dashboard screenshot');
    }
  });

});
