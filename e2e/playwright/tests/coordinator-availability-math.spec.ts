import { test, expect } from '../fixtures/auth.fixture';

/**
 * Coordinator Dashboard — Availability Math Verification
 *
 * These tests verify the fundamental invariant:
 *   displayed_available == displayed_total - displayed_occupied - displayed_onHold
 *
 * Uses data-testid attributes for stable, layout-independent locators.
 */

test.describe('Coordinator Availability Math', () => {

  /** Read all availability values from expanded shelter using data-testid attributes */
  async function readAvailabilityValues(page: import('@playwright/test').Page) {
    const results: Array<{
      popType: string;
      available: number;
      occupied: number;
      onHold: number;
      total: number;
    }> = [];

    // Find all availability rows by data-testid pattern
    const rows = page.locator('[data-testid^="avail-row-"]');
    const count = await rows.count();

    for (let i = 0; i < count; i++) {
      const row = rows.nth(i);
      const testId = await row.getAttribute('data-testid');
      if (!testId) continue;
      const popType = testId.replace('avail-row-', '');

      const totalText = await page.getByTestId(`total-value-${popType}`).textContent();
      const occupiedText = await page.getByTestId(`occupied-value-${popType}`).textContent();
      const onHoldText = await page.getByTestId(`onhold-value-${popType}`).textContent();
      const availText = await page.getByTestId(`available-value-${popType}`).textContent();

      const total = totalText ? parseInt(totalText.trim()) : -999;
      const occupied = occupiedText ? parseInt(occupiedText.trim()) : -999;
      const onHold = onHoldText ? parseInt(onHoldText.trim()) : 0;
      const availMatch = availText?.match(/(\d+)/);
      const available = availMatch ? parseInt(availMatch[1]) : -999;

      results.push({ popType, available, occupied, onHold, total });
    }

    return results;
  }

  test('INVARIANT CHECK: available == total - occupied - onHold on page load', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    const cards = coordinatorPage.locator('main button[style*="text-align: left"]');
    const cardCount = await cards.count();
    expect(cardCount).toBeGreaterThan(0);

    for (let c = 0; c < Math.min(cardCount, 5); c++) {
      const card = cards.nth(c);
      const cardText = await card.textContent();
      if (!cardText) continue;
      const shelterName = cardText.substring(0, 30).trim();

      await card.click();
      await coordinatorPage.waitForTimeout(2000);

      const values = await readAvailabilityValues(coordinatorPage);

      for (const v of values) {
        if (v.total < 0 || v.occupied < 0 || v.available < 0) continue;
        const expected = v.total - v.occupied - v.onHold;
        expect(v.available).toBe(expected,
          `MATH WRONG for "${shelterName}" / ${v.popType}: ` +
          `total=${v.total} - occupied=${v.occupied} - onHold=${v.onHold} = ${expected}, ` +
          `but UI shows available=${v.available}`
        );
      }

      await card.click();
      await coordinatorPage.waitForTimeout(500);
    }
  });

  test('INVARIANT CHECK: after changing total beds', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    const targetCard = coordinatorPage.locator('main button', { hasText: 'Downtown Warming Station' });
    await targetCard.click();
    await coordinatorPage.waitForTimeout(2000);

    const before = await readAvailabilityValues(coordinatorPage);
    expect(before.length).toBeGreaterThan(0);
    const pop = before[0];

    // Click total beds + using data-testid
    const plusBtn = coordinatorPage.getByTestId(`total-plus-${pop.popType}`);
    if (await plusBtn.count() > 0) {
      await plusBtn.click();
      await coordinatorPage.waitForTimeout(500);

      const after = await readAvailabilityValues(coordinatorPage);
      const popAfter = after[0];
      const expected = popAfter.total - popAfter.occupied - popAfter.onHold;
      expect(popAfter.available).toBe(expected,
        `After total+1: total=${popAfter.total}, occupied=${popAfter.occupied}, ` +
        `onHold=${popAfter.onHold}, expected=${expected}, got=${popAfter.available}`
      );

      // Restore
      await coordinatorPage.getByTestId(`total-minus-${pop.popType}`).click();
      await coordinatorPage.waitForTimeout(300);
    }
  });

  test('INVARIANT CHECK: after changing occupied beds', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    const targetCard = coordinatorPage.locator('main button', { hasText: 'Downtown Warming Station' });
    await targetCard.click();
    await coordinatorPage.waitForTimeout(2000);

    const before = await readAvailabilityValues(coordinatorPage);
    expect(before.length).toBeGreaterThan(0);
    const pop = before[0];

    // Click occupied + using data-testid
    const plusBtn = coordinatorPage.getByTestId(`occupied-plus-${pop.popType}`);
    if (await plusBtn.count() > 0 && !(await plusBtn.isDisabled())) {
      await plusBtn.click();
      await coordinatorPage.waitForTimeout(500);

      const after = await readAvailabilityValues(coordinatorPage);
      const popAfter = after[0];
      const expected = popAfter.total - popAfter.occupied - popAfter.onHold;
      expect(popAfter.available).toBe(expected,
        `After occupied+1: total=${popAfter.total}, occupied=${popAfter.occupied}, ` +
        `onHold=${popAfter.onHold}, expected=${expected}, got=${popAfter.available}`
      );

      // Restore
      await coordinatorPage.getByTestId(`occupied-minus-${pop.popType}`).click();
      await coordinatorPage.waitForTimeout(300);
    }
  });

  test('INVARIANT CHECK: after save and reload', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    const targetCard = coordinatorPage.locator('main button', { hasText: 'Downtown Warming Station' });
    const shelterName = 'Downtown Warming Station';
    await targetCard.click();
    await coordinatorPage.waitForTimeout(2000);

    const before = await readAvailabilityValues(coordinatorPage);
    expect(before.length).toBeGreaterThan(0);
    const pop = before[0];

    // Click occupied + using data-testid
    const plusBtn = coordinatorPage.getByTestId(`occupied-plus-${pop.popType}`);
    if (await plusBtn.count() > 0 && !(await plusBtn.isDisabled())) {
      await plusBtn.click();
      await coordinatorPage.waitForTimeout(300);

      // Save using data-testid
      await coordinatorPage.getByTestId(`save-avail-${pop.popType}`).click();
      await coordinatorPage.waitForTimeout(2000);

      // Reload and re-expand
      await coordinatorPage.goto('/coordinator');
      await coordinatorPage.waitForTimeout(2000);
      const card = coordinatorPage.locator('main button', { hasText: new RegExp(shelterName.substring(0, 15), 'i') }).first();
      await card.click();
      await coordinatorPage.waitForTimeout(2000);

      const after = await readAvailabilityValues(coordinatorPage);
      for (const v of after) {
        if (v.total < 0 || v.occupied < 0 || v.available < 0) continue;
        const expected = v.total - v.occupied - v.onHold;
        expect(v.available).toBe(expected,
          `After save+reload "${shelterName}" / ${v.popType}: ` +
          `total=${v.total}, occupied=${v.occupied}, onHold=${v.onHold}, ` +
          `expected=${expected}, got=${v.available}`
        );
      }

      // Restore
      const minusBtn = coordinatorPage.getByTestId(`occupied-minus-${pop.popType}`);
      if (await minusBtn.count() > 0 && !(await minusBtn.isDisabled())) {
        await minusBtn.click();
        await coordinatorPage.waitForTimeout(300);
        await coordinatorPage.getByTestId(`save-avail-${pop.popType}`).click();
        await coordinatorPage.waitForTimeout(1000);
      }
    }
  });

  test('INVARIANT CHECK: collapsed badge matches expanded available sum', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    // Find first shelter with an avail badge via data-testid
    const badge = coordinatorPage.locator('[data-testid^="avail-badge-"]').first();
    if (await badge.count() === 0) return;

    const badgeText = await badge.textContent();
    const badgeAvail = parseInt(badgeText?.match(/(\d+)/)?.[1] || '-999');

    // Extract shelter ID from the badge's data-testid
    const badgeTestId = await badge.getAttribute('data-testid');
    const shelterId = badgeTestId?.replace('avail-badge-', '') || '';

    // Click the shelter card to expand
    await coordinatorPage.getByTestId(`shelter-card-${shelterId}`).click();
    await coordinatorPage.waitForTimeout(2000);

    const values = await readAvailabilityValues(coordinatorPage);
    const totalAvail = values.reduce((sum, v) => sum + Math.max(0, v.available), 0);

    expect(totalAvail).toBe(badgeAvail,
      `Badge shows ${badgeAvail} avail but expanded detail sums to ${totalAvail}. ` +
      `Values: ${JSON.stringify(values)}`
    );
  });
});
