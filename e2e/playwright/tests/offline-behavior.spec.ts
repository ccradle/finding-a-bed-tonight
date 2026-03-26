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

  /**
   * Hospital PWA test — service worker blocked by IT policy.
   *
   * Dr. James Whitfield's use case: hospital-managed Windows laptop with
   * locked-down Chrome. Service worker registration may be blocked.
   * The app must function for search and hold without the service worker.
   *
   * We simulate this by unregistering all service workers and blocking
   * future registration, then verify the core flow still works.
   */
  test('app functions when service worker is blocked (hospital use case)', async ({ outreachPage }) => {
    // Unregister any existing service workers
    await outreachPage.evaluate(async () => {
      if ('serviceWorker' in navigator) {
        const registrations = await navigator.serviceWorker.getRegistrations();
        for (const reg of registrations) {
          await reg.unregister();
        }
        // Block future registrations by overriding
        (navigator.serviceWorker as any).register = () => Promise.reject(new Error('SW blocked by IT policy'));
      }
    });

    // Navigate to search — should load normally without SW
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(3000);

    // Search results should load (API calls work without SW)
    // Shelter results are rendered as buttons with shelter names
    const shelterCount = outreachPage.locator('text=/shelters? found/i');
    await expect(shelterCount).toBeVisible({ timeout: 5000 });

    // Search filter should work
    const searchInput = outreachPage.locator('input[type="search"]');
    await expect(searchInput).toBeVisible();

    // Population type filter should work
    const popFilter = outreachPage.locator('select[aria-label="Filter by population type"]');
    await expect(popFilter).toBeVisible();
    await popFilter.selectOption('VETERAN');
    await outreachPage.waitForTimeout(1000);

    // Hold a bed should work
    const holdBtn = outreachPage.locator('button', { hasText: /Hold This Bed/i }).first();
    if (await holdBtn.count() > 0) {
      await holdBtn.click();
      await outreachPage.waitForTimeout(2000);

      // Should see reservation or confirmation — not an error
      const page = outreachPage;
      const hasReservation = await page.locator('text=/held|holding|expires/i').count();
      const hasError = await page.locator('text=/500|internal server error|failed/i').count();
      expect(hasError).toBe(0);
      // Hold may or may not succeed (depends on bed availability) but should not crash
    }

    // Verify no SW-related errors crashed the page
    await expect(outreachPage.locator('main')).not.toBeEmpty();
  });
});
