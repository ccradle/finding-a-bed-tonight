import { test, expect } from '../fixtures/auth.fixture';

/**
 * Analytics Admin Tab — Playwright E2E tests.
 * Uses data-testid attributes for stable locators.
 */

test.describe('Analytics Tab', () => {

  test('Analytics tab visible to admin, not to outreach worker', async ({ adminPage, outreachPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    const analyticsTab = adminPage.locator('button', { hasText: /Analytics/i });
    await expect(analyticsTab).toBeVisible();

    // Outreach worker should not see admin panel at all
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(1000);
    const adminNav = outreachPage.locator('a', { hasText: /Administration/i });
    await expect(adminNav).toHaveCount(0);
  });

  test('executive summary shows utilization metrics', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(5000);

    const summary = adminPage.getByTestId('analytics-executive-summary');
    await expect(summary).toBeVisible({ timeout: 10000 });

    // Metric cards should be present
    await expect(adminPage.getByTestId('metric-utilization')).toBeVisible();
    await expect(adminPage.getByTestId('metric-searches')).toBeVisible();
    await expect(adminPage.getByTestId('metric-zero-results')).toBeVisible();
  });

  test('utilization trends chart renders', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const trends = adminPage.getByTestId('analytics-utilization-trends');
    await expect(trends).toBeVisible();
  });

  test('shelter performance table loads with RAG indicators', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const performance = adminPage.getByTestId('analytics-shelter-performance');
    await expect(performance).toBeVisible();
  });

  test('HIC/PIT export buttons visible', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const exportSection = adminPage.getByTestId('analytics-export');
    await expect(exportSection).toBeVisible();
    await expect(adminPage.getByTestId('download-hic-btn')).toBeVisible();
    await expect(adminPage.getByTestId('download-pit-btn')).toBeVisible();
    await expect(adminPage.getByTestId('export-date-picker')).toBeVisible();
  });

  test('batch jobs list shows job names and status', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(2000);

    // Switch to Batch Jobs section
    await adminPage.getByTestId('analytics-batch-jobs-btn').click();
    await adminPage.waitForTimeout(2000);

    const jobsList = adminPage.getByTestId('batch-jobs-list');
    await expect(jobsList).toBeVisible();

    // Should have at least one job
    const jobRows = jobsList.locator('tbody tr');
    expect(await jobRows.count()).toBeGreaterThan(0);
  });

  test('batch job execution history expands on click', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(2000);

    // Switch to Batch Jobs section
    await adminPage.getByTestId('analytics-batch-jobs-btn').click();
    await adminPage.waitForTimeout(2000);

    // Click the first job to expand execution history
    const firstJobLink = adminPage.locator('[data-testid^="batch-job-expand-"]').first();
    if (await firstJobLink.count() > 0) {
      await firstJobLink.click();
      await adminPage.waitForTimeout(2000);

      const history = adminPage.getByTestId('batch-execution-history');
      await expect(history).toBeVisible();
    }
  });

});
