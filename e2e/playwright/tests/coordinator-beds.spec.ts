import { test, expect } from '../fixtures/auth.fixture';

/**
 * Coordinator Dashboard — bed calculation verification tests.
 *
 * Uses data-testid attributes for stable, layout-independent locators.
 * Tests expand a shelter, manipulate total/occupied via steppers,
 * and verify: available = total - occupied - onHold
 */

test.describe('Coordinator Bed Calculations', () => {

  /** Navigate to coordinator dashboard and expand first shelter */
  async function expandFirstShelter(page: import('@playwright/test').Page) {
    await page.goto('/coordinator');
    await page.waitForTimeout(1500);
    const cards = page.locator('main button').filter({ hasText: /raleigh|shelter|center|haven/i });
    if (await cards.count() > 0) {
      await cards.first().click();
      await page.waitForTimeout(1500);
    }
  }

  /** Get the first visible population type from expanded availability rows */
  async function getFirstPopType(page: import('@playwright/test').Page): Promise<string> {
    const row = page.locator('[data-testid^="avail-row-"]').first();
    const testId = await row.getAttribute('data-testid');
    return testId?.replace('avail-row-', '') || '';
  }

  /** Read the displayed available number for a population type */
  async function readAvailable(page: import('@playwright/test').Page, popType: string): Promise<number> {
    const text = await page.getByTestId(`available-value-${popType}`).textContent();
    const match = text?.match(/(\d+)/);
    return match ? parseInt(match[1]) : NaN;
  }

  test('available decreases when occupied increases', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);
    expect(initialAvail).not.toBeNaN();

    await coordinatorPage.getByTestId(`occupied-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);

    const newAvail = await readAvailable(coordinatorPage, popType);
    expect(newAvail).toBe(initialAvail - 1);

    // Restore
    await coordinatorPage.getByTestId(`occupied-minus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    const restored = await readAvailable(coordinatorPage, popType);
    expect(restored).toBe(initialAvail);
  });

  test('on-hold is read-only (no stepper buttons)', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    // On-hold value should be visible
    const holdValue = coordinatorPage.getByTestId(`onhold-value-${popType}`);
    await expect(holdValue).toBeVisible();

    // There should be NO on-hold stepper buttons (no data-testid for onhold-plus/minus)
    const holdPlus = coordinatorPage.locator(`[data-testid="onhold-plus-${popType}"]`);
    const holdMinus = coordinatorPage.locator(`[data-testid="onhold-minus-${popType}"]`);
    expect(await holdPlus.count()).toBe(0);
    expect(await holdMinus.count()).toBe(0);
  });

  test('available updates when total beds changed via stepper', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    await coordinatorPage.getByTestId(`total-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(500);

    const newAvail = await readAvailable(coordinatorPage, popType);
    expect(newAvail).toBe(initialAvail + 1);

    // Restore
    await coordinatorPage.getByTestId(`total-minus-${popType}`).click();
    await coordinatorPage.waitForTimeout(500);

    const restored = await readAvailable(coordinatorPage, popType);
    expect(restored).toBe(initialAvail);
  });

  test('occupied stepper cannot produce negative available', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    // Click occupied + several times rapidly
    for (let i = 0; i < 15; i++) {
      const plusBtn = coordinatorPage.getByTestId(`occupied-plus-${popType}`);
      if (await plusBtn.isDisabled()) break;
      await plusBtn.click();
      await coordinatorPage.waitForTimeout(100);
    }

    const finalAvail = await readAvailable(coordinatorPage, popType);
    expect(finalAvail).toBeGreaterThanOrEqual(0);

    // Restore
    for (let i = 0; i < 15; i++) {
      const minusBtn = coordinatorPage.getByTestId(`occupied-minus-${popType}`);
      if (await minusBtn.isDisabled()) break;
      await minusBtn.click();
      await coordinatorPage.waitForTimeout(100);
    }
  });

  test('save availability persists after page reload', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    // Change occupied +1
    await coordinatorPage.getByTestId(`occupied-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    const expectedAvail = initialAvail - 1;

    // Save
    await coordinatorPage.getByTestId(`save-avail-${popType}`).click();
    await coordinatorPage.waitForTimeout(2000);

    // Reload and re-expand
    await expandFirstShelter(coordinatorPage);

    const afterReload = await readAvailable(coordinatorPage, popType);
    expect(afterReload).toBe(expectedAvail);

    // Restore
    await coordinatorPage.getByTestId(`occupied-minus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    await coordinatorPage.getByTestId(`save-avail-${popType}`).click();
    await coordinatorPage.waitForTimeout(1000);
  });
});
