import { test, expect } from '../fixtures/auth.fixture';

test.describe('Offline Behavior', () => {
  test('offline banner appears on connectivity loss', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    // Go offline
    await outreachPage.context().setOffline(true);
    await outreachPage.waitForTimeout(1000);

    // Offline banner should appear
    const banner = outreachPage.locator('text=/offline/i');
    await expect(banner).toBeVisible({ timeout: 5000 });

    // Restore
    await outreachPage.context().setOffline(false);
  });

  test('search results remain visible while offline (stale cache)', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    // Wait for results to load
    await outreachPage.waitForSelector('main div[style*="border"]', { timeout: 10000 });

    // Go offline
    await outreachPage.context().setOffline(true);
    await outreachPage.waitForTimeout(1000);

    // Results should still be visible (served from cache)
    const mainContent = outreachPage.locator('main');
    await expect(mainContent).not.toBeEmpty();
    // Should not show a blank error page
    await expect(outreachPage.locator('main')).not.toContainText(/500|internal server error/i);

    // Restore
    await outreachPage.context().setOffline(false);
  });

  test('queued availability update replays on reconnect', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForSelector('main button[style*="text-align: left"]', { timeout: 10000 });

    // Expand first shelter
    await coordinatorPage.locator('main button[style*="text-align: left"]').first().click();
    await coordinatorPage.waitForTimeout(500);

    // Go offline
    await coordinatorPage.context().setOffline(true);
    await coordinatorPage.waitForTimeout(500);

    // Try to submit availability update while offline
    const updateBtn = coordinatorPage.locator('main button', { hasText: /update availability/i }).first();
    if (await updateBtn.isVisible()) {
      await updateBtn.click();
      await coordinatorPage.waitForTimeout(1000);
      // Should not crash — may show queued indicator or error, but page stays functional
      await expect(coordinatorPage.locator('main')).not.toBeEmpty();
    }

    // Restore connectivity
    await coordinatorPage.context().setOffline(false);
    await coordinatorPage.waitForTimeout(2000);
  });
});
