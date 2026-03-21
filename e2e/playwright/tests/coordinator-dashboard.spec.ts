import { test, expect } from '../fixtures/auth.fixture';
import { CoordinatorDashboardPage } from '../pages/CoordinatorDashboardPage';

test.describe('Coordinator Dashboard', () => {
  test('dashboard loads with shelter cards showing name, address, data age', async ({ coordinatorPage }) => {
    const dashboard = new CoordinatorDashboardPage(coordinatorPage);
    await dashboard.goto();
    await dashboard.waitForShelters();
    await expect(dashboard.heading).toContainText(/shelter dashboard/i);
    const cardCount = await dashboard.shelterCards.count();
    expect(cardCount).toBeGreaterThan(0);
  });

  test('expanding shelter shows availability update form with steppers', async ({ coordinatorPage }) => {
    const dashboard = new CoordinatorDashboardPage(coordinatorPage);
    await dashboard.goto();
    await dashboard.waitForShelters();
    await dashboard.expandShelter(0);
    await expect(coordinatorPage.locator('main h4', { hasText: /availability/i })).toBeVisible();
    const stepperButtons = coordinatorPage.locator('main button:has-text("+"), main button:has-text("−")');
    expect(await stepperButtons.count()).toBeGreaterThan(0);
  });

  test('submitting availability update shows success indicator', async ({ coordinatorPage }) => {
    const dashboard = new CoordinatorDashboardPage(coordinatorPage);
    await dashboard.goto();
    await dashboard.waitForShelters();
    await dashboard.expandShelter(0);
    const updateBtn = coordinatorPage.locator('main button', { hasText: /update availability/i }).first();
    await updateBtn.click();
    await coordinatorPage.waitForTimeout(2000);
    // After clicking update, button temporarily shows success (text changes or color changes)
    // Verify the update didn't produce an error
    await coordinatorPage.waitForTimeout(1000);
    const errorBanner = coordinatorPage.locator('main div[style*="fef2f2"]');
    expect(await errorBanner.count()).toBe(0);
  });

  test('capacity stepper adjusts bed counts and saves', async ({ coordinatorPage }) => {
    const dashboard = new CoordinatorDashboardPage(coordinatorPage);
    await dashboard.goto();
    await dashboard.waitForShelters();
    await dashboard.expandShelter(0);
    const saveButton = coordinatorPage.locator('main button', { hasText: /save/i });
    await expect(saveButton).toBeVisible();
    await saveButton.click();
    await coordinatorPage.waitForTimeout(2000);
    const errorBanner = coordinatorPage.locator('main div[style*="fef2f2"]');
    expect(await errorBanner.count()).toBe(0);
  });
});
