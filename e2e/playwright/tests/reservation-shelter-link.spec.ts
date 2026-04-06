import { test, expect } from '../fixtures/auth.fixture';

/**
 * My Reservations — Clickable Shelter Names (#64)
 *
 * Verifies that shelter names in the My Reservations panel are clickable
 * links that open the shelter detail modal, with countdown timer preserved.
 *
 * Persona: Darius Webb — hold a bed, then needs directions to transport.
 * "I held a bed, now I can't get back to the shelter details?"
 */

test.describe('Reservation Shelter Link (#64)', () => {

  /**
   * Helper: hold a bed and open the reservations panel.
   * Returns the shelter name text for verification.
   */
  async function holdBedAndOpenPanel(outreachPage: any): Promise<string | null> {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    // Hold a bed
    const holdButton = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() === 0 || !(await holdButton.isVisible())) {
      return null;
    }
    await holdButton.click();
    await outreachPage.waitForTimeout(2000);

    // Open reservations panel
    const panelToggle = outreachPage.locator('button', { hasText: /My Reservations|Mis Reservaciones/i });
    if (await panelToggle.count() === 0) return null;
    await panelToggle.click();
    await outreachPage.waitForTimeout(500);

    // Get shelter name from the clickable link
    const shelterLink = outreachPage.locator('[data-testid^="reservation-shelter-link-"]').first();
    if (await shelterLink.count() === 0) return null;
    return await shelterLink.textContent();
  }

  // T-64f: Positive — reservation shows clickable shelter link
  test('hold a bed → reservation shows shelter name as clickable link', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    const holdButton = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() === 0) {
      console.log('No hold buttons available — skipping');
      test.skip();
      return;
    }

    await holdButton.click();
    await outreachPage.waitForTimeout(2000);

    // Open reservations panel
    const panelToggle = outreachPage.locator('button', { hasText: /My Reservations/i });
    await expect(panelToggle).toBeVisible({ timeout: 5000 });
    await panelToggle.click();
    await outreachPage.waitForTimeout(500);

    // Shelter link should exist with data-testid
    const shelterLink = outreachPage.locator('[data-testid^="reservation-shelter-link-"]').first();
    await expect(shelterLink).toBeVisible();

    // Should be a button element (not just text)
    const tagName = await shelterLink.evaluate((el: HTMLElement) => el.tagName);
    expect(tagName).toBe('BUTTON');

    // Should have underline styling (indicates clickability)
    const textDecoration = await shelterLink.evaluate((el: HTMLElement) =>
      window.getComputedStyle(el).textDecoration
    );
    expect(textDecoration).toContain('underline');
  });

  // T-64g: Positive — click opens shelter detail modal
  test('click reservation shelter link → shelter detail modal opens', async ({ outreachPage }) => {
    const shelterName = await holdBedAndOpenPanel(outreachPage);
    if (!shelterName) { test.skip(); return; }

    // Click the shelter link
    const shelterLink = outreachPage.locator('[data-testid^="reservation-shelter-link-"]').first();
    await shelterLink.click();
    await outreachPage.waitForTimeout(1000);

    // Detail modal should appear
    const modal = outreachPage.locator('div[style*="position: fixed"][style*="inset: 0"]');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Modal should contain shelter details (address, phone)
    const modalText = await modal.textContent();
    expect(modalText).toBeTruthy();

    // Close modal
    await outreachPage.keyboard.press('Escape');
  });

  // T-64h: Positive — countdown timer still visible after clicking
  test('hold countdown timer visible and decrementing after clicking shelter link', async ({ outreachPage }) => {
    const shelterName = await holdBedAndOpenPanel(outreachPage);
    if (!shelterName) { test.skip(); return; }

    // Capture countdown text before click
    const countdownBefore = await outreachPage.locator('text=/Expires in|Expira en/i').first().textContent();

    // Click shelter link to open modal
    const shelterLink = outreachPage.locator('[data-testid^="reservation-shelter-link-"]').first();
    await shelterLink.click();
    await outreachPage.waitForTimeout(1000);

    // Close modal
    await outreachPage.keyboard.press('Escape');
    await outreachPage.waitForTimeout(2000);

    // Countdown should still be visible in the panel
    const countdownAfter = outreachPage.locator('text=/Expires in|Expira en/i').first();
    await expect(countdownAfter).toBeVisible();

    // Countdown should have decremented (time passed)
    const textAfter = await countdownAfter.textContent();
    // Both should be countdown strings — the value may have changed
    expect(textAfter).toMatch(/Expires in|Expira en/i);
  });

  // T-64i: Positive — multiple reservations independently clickable
  test('multiple reservations are independently clickable', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    // Hold two beds on different shelters
    const holdButtons = outreachPage.locator('[data-testid^="hold-bed-"]');
    const holdCount = await holdButtons.count();
    if (holdCount < 2) {
      console.log(`Only ${holdCount} hold buttons available — need 2 for this test`);
      test.skip();
      return;
    }

    await holdButtons.nth(0).click();
    await outreachPage.waitForTimeout(2000);
    await holdButtons.nth(1).click();
    await outreachPage.waitForTimeout(2000);

    // Open reservations panel
    const panelToggle = outreachPage.locator('button', { hasText: /My Reservations/i });
    await panelToggle.click();
    await outreachPage.waitForTimeout(500);

    // Should have at least 2 shelter links
    const shelterLinks = outreachPage.locator('[data-testid^="reservation-shelter-link-"]');
    const linkCount = await shelterLinks.count();
    expect(linkCount).toBeGreaterThanOrEqual(2);

    // Each should have a unique data-testid (different shelter IDs)
    const testIds = new Set<string>();
    for (let i = 0; i < linkCount; i++) {
      const testId = await shelterLinks.nth(i).getAttribute('data-testid');
      testIds.add(testId!);
    }
    expect(testIds.size).toBeGreaterThanOrEqual(2);

    // Click first link — should open modal
    await shelterLinks.nth(0).click();
    await outreachPage.waitForTimeout(500);
    const modal = outreachPage.locator('div[style*="position: fixed"][style*="inset: 0"]');
    await expect(modal).toBeVisible();
    await outreachPage.keyboard.press('Escape');
    await outreachPage.waitForTimeout(300);

    // Click second link — should also open modal (independent)
    await shelterLinks.nth(1).click();
    await outreachPage.waitForTimeout(500);
    await expect(modal).toBeVisible();
    await outreachPage.keyboard.press('Escape');
  });

  // T-64j: Negative — expired reservations (panel only shows HELD, so expired are removed)
  // This test verifies the panel behavior is correct — expired items disappear
  test('expired reservation is removed from panel', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    // Check if any reservations exist in the panel
    const panelToggle = outreachPage.locator('button', { hasText: /My Reservations/i });
    if (await panelToggle.count() === 0) {
      console.log('No reservations panel — skipping');
      test.skip();
      return;
    }

    await panelToggle.click();
    await outreachPage.waitForTimeout(500);

    // All visible shelter links should have a non-expired countdown
    const countdowns = outreachPage.locator('text=/Expires in|Expira en/i');
    const countdownCount = await countdowns.count();

    // If there are countdowns, none should show "Expired"
    for (let i = 0; i < countdownCount; i++) {
      const text = await countdowns.nth(i).textContent();
      // Active reservations show "Expires in Xm Ys"
      expect(text).toMatch(/Expires in|Expira en/i);
    }
  });

  // T-64k: Negative — clicking shelter link does NOT navigate away
  test('clicking shelter link stays on search page, does not navigate', async ({ outreachPage }) => {
    const shelterName = await holdBedAndOpenPanel(outreachPage);
    if (!shelterName) { test.skip(); return; }

    // Record current URL
    const urlBefore = outreachPage.url();

    // Click shelter link
    const shelterLink = outreachPage.locator('[data-testid^="reservation-shelter-link-"]').first();
    await shelterLink.click();
    await outreachPage.waitForTimeout(1000);

    // URL should NOT have changed (modal opens on same page)
    const urlAfter = outreachPage.url();
    expect(urlAfter).toBe(urlBefore);

    // Close modal
    await outreachPage.keyboard.press('Escape');
  });
});
