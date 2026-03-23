import { test, expect } from '../fixtures/auth.fixture';

/**
 * Coordinator Dashboard — bed calculation verification tests.
 *
 * These tests expand a shelter, manipulate total/occupied/onHold via steppers,
 * and verify the displayed "available" count is always:
 *   available = total - occupied - onHold
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

  /** Get the availability row (the section with population name + "X Available" + steppers) */
  function getAvailRow(page: import('@playwright/test').Page) {
    // The availability row has "Available" text and stepper buttons
    return page.locator('h4', { hasText: /update availability/i }).locator('..').locator('div[style*="border-bottom"]').first();
  }

  /** Read the displayed available number */
  async function readAvailable(page: import('@playwright/test').Page): Promise<number> {
    const availSpan = page.locator('h4', { hasText: /update availability/i }).locator('..').locator('span', { hasText: /available/i }).first();
    const text = await availSpan.textContent();
    const match = text?.match(/(\d+)/);
    return match ? parseInt(match[1]) : NaN;
  }

  /** Read the displayed occupied number (first number in the stepper row after "Occupied" label) */
  async function readOccupied(page: import('@playwright/test').Page): Promise<number> {
    // The occupied stepper is inside a div containing "Occupied" label
    // Structure: [label "Occupied"] [button −] [span with number] [button +]
    // Get the span between the two buttons in the occupied row
    const occupiedSection = page.locator('span', { hasText: /^Occupied$/i }).locator('..');
    const numberSpan = occupiedSection.locator('span[style*="fontWeight"]').first();
    const text = await numberSpan.textContent();
    return text ? parseInt(text) : NaN;
  }

  /** Click the occupied + button */
  async function clickOccupiedPlus(page: import('@playwright/test').Page) {
    // Find "Occupied" label, go to parent container, find the + button
    const occupiedLabel = page.locator('span', { hasText: /^Occupied$/i }).first();
    const container = occupiedLabel.locator('..');
    await container.locator('button', { hasText: '+' }).click();
    await page.waitForTimeout(300);
  }

  /** Click the occupied - button */
  async function clickOccupiedMinus(page: import('@playwright/test').Page) {
    const occupiedLabel = page.locator('span', { hasText: /^Occupied$/i }).first();
    const container = occupiedLabel.locator('..');
    await container.locator('button', { hasText: '−' }).click();
    await page.waitForTimeout(300);
  }

  /** Click the on-hold + button */
  async function clickOnHoldPlus(page: import('@playwright/test').Page) {
    const holdLabel = page.locator('span', { hasText: /^On Hold$/i }).first();
    const container = holdLabel.locator('..');
    await container.locator('button', { hasText: '+' }).click();
    await page.waitForTimeout(300);
  }

  /** Click the on-hold - button */
  async function clickOnHoldMinus(page: import('@playwright/test').Page) {
    const holdLabel = page.locator('span', { hasText: /^On Hold$/i }).first();
    const container = holdLabel.locator('..');
    await container.locator('button', { hasText: '−' }).click();
    await page.waitForTimeout(300);
  }

  test('available decreases when occupied increases', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage);
    expect(initialAvail).not.toBeNaN();

    await clickOccupiedPlus(coordinatorPage);

    const newAvail = await readAvailable(coordinatorPage);
    expect(newAvail).toBe(initialAvail - 1);

    // Restore
    await clickOccupiedMinus(coordinatorPage);
    const restored = await readAvailable(coordinatorPage);
    expect(restored).toBe(initialAvail);
  });

  test('available decreases when on-hold increases', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage);

    await clickOnHoldPlus(coordinatorPage);

    const newAvail = await readAvailable(coordinatorPage);
    expect(newAvail).toBe(initialAvail - 1);

    // Restore
    await clickOnHoldMinus(coordinatorPage);
    const restored = await readAvailable(coordinatorPage);
    expect(restored).toBe(initialAvail);
  });

  test('available updates when total beds changed via capacity stepper', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage);

    // Find "Total Beds" section and the first population type row with a non-zero count
    const totalBedsSection = coordinatorPage.locator('h4', { hasText: /total beds/i });
    await expect(totalBedsSection).toBeVisible();

    // The capacity section has rows like: [label "family with children"] [- btn] [30] [+ btn]
    // Use getByText to find the label, then navigate to its parent row's + button
    const familyLabel = coordinatorPage.getByText('family with children').last();
    const familyRow = familyLabel.locator('..');
    await familyRow.locator('button', { hasText: '+' }).click();
    await coordinatorPage.waitForTimeout(500);

    const newAvail = await readAvailable(coordinatorPage);
    expect(newAvail).toBe(initialAvail + 1);

    // Restore
    await familyRow.locator('button', { hasText: '−' }).click();
    await coordinatorPage.waitForTimeout(500);

    const restored = await readAvailable(coordinatorPage);
    expect(restored).toBe(initialAvail);
  });

  test('multiple changes: +2 occupied +1 hold = -3 available', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage);

    await clickOccupiedPlus(coordinatorPage);
    await clickOccupiedPlus(coordinatorPage);
    await clickOnHoldPlus(coordinatorPage);

    const newAvail = await readAvailable(coordinatorPage);
    expect(newAvail).toBe(initialAvail - 3);

    // Restore
    await clickOccupiedMinus(coordinatorPage);
    await clickOccupiedMinus(coordinatorPage);
    await clickOnHoldMinus(coordinatorPage);

    const restored = await readAvailable(coordinatorPage);
    expect(restored).toBe(initialAvail);
  });

  test('save availability persists after page reload', async ({ coordinatorPage }) => {
    await expandFirstShelter(coordinatorPage);

    const initialAvail = await readAvailable(coordinatorPage);

    // Change occupied +1
    await clickOccupiedPlus(coordinatorPage);
    const expectedAvail = initialAvail - 1;

    // Save
    const updateBtn = coordinatorPage.locator('button', { hasText: /update availability/i }).first();
    await updateBtn.click();
    await coordinatorPage.waitForTimeout(2000);

    // Reload and re-expand
    await expandFirstShelter(coordinatorPage);

    const afterReload = await readAvailable(coordinatorPage);
    expect(afterReload).toBe(expectedAvail);

    // Restore
    await clickOccupiedMinus(coordinatorPage);
    await coordinatorPage.locator('button', { hasText: /update availability/i }).first().click();
    await coordinatorPage.waitForTimeout(1000);
  });
});
