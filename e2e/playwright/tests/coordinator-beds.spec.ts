import { test, expect } from '../fixtures/auth.fixture';

/**
 * Coordinator Dashboard — bed calculation verification tests.
 *
 * Uses a specific known seed shelter ("Downtown Warming Station") instead of
 * "first shelter" to avoid fragility when import tests add shelters.
 * Uses data-testid attributes for stable, layout-independent locators.
 */

const TARGET_SHELTER = 'Downtown Warming Station';

test.describe('Coordinator Bed Calculations', () => {

  /** Navigate to coordinator dashboard and expand the target shelter */
  async function expandTargetShelter(page: import('@playwright/test').Page) {
    await page.goto('/coordinator');
    await page.waitForTimeout(1500);
    const card = page.locator('main button', { hasText: TARGET_SHELTER });
    await expect(card).toBeVisible({ timeout: 10000 });
    await card.click();
    await page.waitForTimeout(1500);
  }

  /** Get the first visible population type from expanded availability rows */
  async function getFirstPopType(page: import('@playwright/test').Page): Promise<string> {
    const row = page.locator('[data-testid^="avail-row-"]').first();
    await expect(row).toBeVisible({ timeout: 5000 });
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
    await expandTargetShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    await coordinatorPage.getByTestId(`occupied-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    const newAvail = await readAvailable(coordinatorPage, popType);
    expect(newAvail).toBe(initialAvail - 1);
  });

  test('on-hold is read-only (no stepper buttons)', async ({ coordinatorPage }) => {
    await expandTargetShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const plusButton = coordinatorPage.getByTestId(`onhold-plus-${popType}`);
    const minusButton = coordinatorPage.getByTestId(`onhold-minus-${popType}`);
    expect(await plusButton.count()).toBe(0);
    expect(await minusButton.count()).toBe(0);
  });

  test('available updates when total beds changed via stepper', async ({ coordinatorPage }) => {
    await expandTargetShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    await coordinatorPage.getByTestId(`total-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    const newAvail = await readAvailable(coordinatorPage, popType);
    expect(newAvail).toBe(initialAvail + 1);

    // Restore
    await coordinatorPage.getByTestId(`total-minus-${popType}`).click();
  });

  test('occupied stepper cannot produce negative available', async ({ coordinatorPage }) => {
    await expandTargetShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    // Click occupied+ until the button becomes disabled or available reaches 0
    let clicks = 0;
    for (let i = 0; i <= initialAvail + 1; i++) {
      const plusBtn = coordinatorPage.getByTestId(`occupied-plus-${popType}`);
      if (await plusBtn.isDisabled()) break;
      await plusBtn.click();
      await coordinatorPage.waitForTimeout(100);
      clicks++;
    }
    const finalAvail = await readAvailable(coordinatorPage, popType);
    expect(finalAvail).toBeGreaterThanOrEqual(0);

    // Restore: click minus same number of times we clicked plus
    for (let i = 0; i < clicks; i++) {
      const minusBtn = coordinatorPage.getByTestId(`occupied-minus-${popType}`);
      if (await minusBtn.isDisabled()) break;
      await minusBtn.click();
      await coordinatorPage.waitForTimeout(100);
    }
  });

  test('save availability persists after page reload', async ({ coordinatorPage }) => {
    await expandTargetShelter(coordinatorPage);
    const popType = await getFirstPopType(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage, popType);

    // Change occupied +1
    await coordinatorPage.getByTestId(`occupied-plus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    const expectedAvail = initialAvail - 1;

    // Save and wait for confirmation
    await coordinatorPage.getByTestId(`save-avail-${popType}`).click();
    await coordinatorPage.waitForTimeout(3000);

    // Full page reload to verify persistence
    await coordinatorPage.reload({ waitUntil: 'domcontentloaded' });
    await coordinatorPage.waitForTimeout(1000);
    await expandTargetShelter(coordinatorPage);

    const afterReload = await readAvailable(coordinatorPage, popType);
    expect(afterReload).toBe(expectedAvail);

    // Restore
    await coordinatorPage.getByTestId(`occupied-minus-${popType}`).click();
    await coordinatorPage.waitForTimeout(300);
    await coordinatorPage.getByTestId(`save-avail-${popType}`).click();
  });
});
