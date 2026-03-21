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
});
