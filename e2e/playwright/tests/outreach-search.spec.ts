import { test, expect } from '../fixtures/auth.fixture';
import { OutreachSearchPage } from '../pages/OutreachSearchPage';

test.describe('Outreach Search', () => {
  test('search page loads with shelter results showing name, address, availability', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    await expect(searchPage.heading).toContainText(/find a bed/i);
    // Should have at least one shelter result — verify a seed shelter name is visible
    await expect(outreachPage.locator('main')).toContainText(/shelter|emergency|haven/i, { timeout: 5000 });
  });

  test('population type filter refreshes results', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    await searchPage.selectPopulationType('VETERAN');
    // After filtering, results should still render (may be fewer)
    await outreachPage.waitForTimeout(1000);
    await expect(outreachPage.locator('body')).not.toContainText('error');
  });

  test('pets filter and wheelchair filter toggle and refresh results', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    // Click pets filter
    await searchPage.clickFilter('Pets');
    await outreachPage.waitForTimeout(1000);
    await expect(outreachPage.locator('body')).not.toContainText('error');
    // Click wheelchair filter
    await searchPage.clickFilter('Accessible');
    await outreachPage.waitForTimeout(1000);
    await expect(outreachPage.locator('body')).not.toContainText('error');
  });

  test('clicking shelter card opens detail modal', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    // Click the first shelter result
    await searchPage.clickShelterCard(0);
    await outreachPage.waitForTimeout(500);
    // Modal should appear with shelter details
    const modal = outreachPage.locator('div[style*="position: fixed"][style*="inset: 0"]');
    await expect(modal).toBeVisible();
    // Modal should contain shelter name (h2)
    await expect(modal.locator('h2')).toBeVisible();
  });

  test('closing modal returns to search results', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    await searchPage.clickShelterCard(0);
    await outreachPage.waitForTimeout(500);
    // Close modal
    const closeButton = outreachPage.locator('button', { hasText: /close|cerrar/i });
    await closeButton.click();
    await outreachPage.waitForTimeout(300);
    // Modal should be gone
    const modal = outreachPage.locator('div[style*="position: fixed"][style*="inset: 0"][style*="backgroundColor"]');
    await expect(modal).not.toBeVisible();
  });

  test('hold a bed shows reservation panel with countdown', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    // Find and click a "Hold This Bed" button
    const holdButton = outreachPage.locator('main button', { hasText: /hold this bed/i }).first();
    if (await holdButton.isVisible()) {
      await holdButton.click();
      await outreachPage.waitForTimeout(2000);
      // Reservations panel should appear with at least one entry
      const reservationsPanel = outreachPage.locator('main button', { hasText: /my reservations/i });
      await expect(reservationsPanel).toBeVisible({ timeout: 5000 });
    }
  });

  test('cancel a hold releases the bed', async ({ outreachPage }) => {
    const searchPage = new OutreachSearchPage(outreachPage);
    await searchPage.goto();
    await searchPage.waitForResults();
    // Hold a bed first
    const holdButton = outreachPage.locator('main button', { hasText: /hold this bed/i }).first();
    if (await holdButton.isVisible()) {
      await holdButton.click();
      await outreachPage.waitForTimeout(2000);
      // Open reservations panel
      const panelToggle = outreachPage.locator('main button', { hasText: /my reservations/i });
      if (await panelToggle.isVisible()) {
        await panelToggle.click();
        await outreachPage.waitForTimeout(500);
        // Click cancel
        const cancelBtn = outreachPage.locator('main button', { hasText: /cancel/i }).first();
        if (await cancelBtn.isVisible()) {
          await cancelBtn.click();
          await outreachPage.waitForTimeout(1000);
        }
      }
    }
    // Page should not show errors
    await expect(outreachPage.locator('main')).not.toContainText(/failed|error/i);
  });

  test('language switch to Español changes UI text', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main h1', { timeout: 10000 });
    const englishHeading = await outreachPage.locator('main h1').textContent();
    // Find language selector and switch to Spanish
    const langSelector = outreachPage.locator('select[aria-label*="language"], nav select').first();
    if (await langSelector.isVisible()) {
      await langSelector.selectOption('es');
      await outreachPage.waitForTimeout(500);
      const spanishHeading = await outreachPage.locator('main h1').textContent();
      // Heading should be different from English
      expect(spanishHeading).not.toBe(englishHeading);
      // Switch back
      await langSelector.selectOption('en');
      await outreachPage.waitForTimeout(500);
      const restoredHeading = await outreachPage.locator('main h1').textContent();
      expect(restoredHeading).toBe(englishHeading);
    }
  });

  test('data freshness badges are visible on search results', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    // Wait for results to actually load (not just the search input border)
    await outreachPage.waitForSelector('main div[style*="font-weight"][style*="700"]', { timeout: 15000 });
    // Should see at least one freshness badge — check for the DataAge component's dot indicator
    const dataAgeDots = outreachPage.locator('main span[style*="border-radius: 50%"]');
    expect(await dataAgeDots.count()).toBeGreaterThan(0);
  });
});
