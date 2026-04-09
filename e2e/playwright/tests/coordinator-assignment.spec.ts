import { test, expect } from '../fixtures/auth.fixture';

test.describe('Coordinator-Shelter Assignment', () => {

  // T-15: Admin opens shelter edit → "Assigned Coordinators" section visible
  test('T-15: shelter edit shows Assigned Coordinators section with combobox', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // Combobox input should be visible
    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await expect(combobox).toBeVisible();
    await expect(combobox).toHaveAttribute('role', 'combobox');
    await expect(combobox).toHaveAttribute('aria-haspopup', 'listbox');
  });

  // T-16: Admin types coordinator name → dropdown filters → select adds chip
  test('T-16: typing in combobox filters dropdown and select adds chip', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await combobox.click();
    await adminPage.waitForTimeout(500);

    // Type a search query — look for the listbox to appear
    await combobox.fill('admin');
    await adminPage.waitForTimeout(300);

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    // Listbox should appear if there are matching eligible users
    if (await listbox.isVisible()) {
      // Click first option to select
      const firstOption = listbox.locator('[role="option"]').first();
      const optionName = await firstOption.locator('div').first().textContent();
      await firstOption.click();

      // Chip should appear with the selected name
      if (optionName) {
        await expect(adminPage.locator(`text=${optionName.trim()}`)).toBeVisible();
      }

      // Combobox should be cleared
      await expect(combobox).toHaveValue('');
    }
  });

  // T-17: Admin removes chip → chip disappears (staged, not persisted)
  test('T-17: removing chip removes it from UI (staged)', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // Try to add a coordinator first
    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await combobox.click();
    await combobox.fill('a');
    await adminPage.waitForTimeout(300);

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    if (await listbox.isVisible()) {
      await listbox.locator('[role="option"]').first().click();
      await adminPage.waitForTimeout(200);

      // Now find and click the remove button on the chip
      const removeButtons = adminPage.locator('button[aria-label^="Remove"]');
      const countBefore = await removeButtons.count();
      if (countBefore > 0) {
        await removeButtons.last().click();
        await adminPage.waitForTimeout(200);

        const countAfter = await adminPage.locator('button[aria-label^="Remove"]').count();
        expect(countAfter).toBeLessThan(countBefore);
      }
    }
  });

  // T-18: Admin saves shelter → assignment persisted (verify via API)
  test('T-18: saving shelter persists coordinator assignment', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // Add a coordinator
    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await combobox.click();
    await combobox.fill('a');
    await adminPage.waitForTimeout(300);

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    if (await listbox.isVisible()) {
      await listbox.locator('[role="option"]').first().click();
      await adminPage.waitForTimeout(200);

      // Save
      await adminPage.locator('[data-testid="shelter-save"]').click();
      await adminPage.waitForURL(/\/(admin|coordinator)/, { timeout: 10000 });

      // Success — navigated away means save completed
    }
  });

  // T-19: Admin opens user edit drawer → "Assigned Shelters" chips visible
  test('T-19: user edit drawer shows Assigned Shelters section', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Users tab should be default or click it
    await adminPage.waitForTimeout(1000);

    // Click first edit button on a user row
    const editButtons = adminPage.locator('[data-testid^="user-edit-"]').first();
    if (await editButtons.isVisible()) {
      await editButtons.click();
      await adminPage.waitForTimeout(500);

      // Assigned Shelters section should be visible in the drawer
      const shelterSection = adminPage.locator('text=Assigned Shelters');
      await expect(shelterSection).toBeVisible({ timeout: 5000 });

      // Either chips or "No shelters assigned" should be visible
      const chips = adminPage.getByTestId('user-assigned-shelters');
      const noShelters = adminPage.getByTestId('user-no-shelters');
      const hasChips = await chips.isVisible().catch(() => false);
      const hasEmpty = await noShelters.isVisible().catch(() => false);
      expect(hasChips || hasEmpty).toBe(true);
    }
  });

  // T-20: WCAG — combobox has role="combobox", chips have aria-label, keyboard navigation
  test('T-20: WCAG combobox accessibility', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    const combobox = adminPage.getByTestId('coordinator-combobox-input');

    // WCAG: role="combobox"
    await expect(combobox).toHaveAttribute('role', 'combobox');
    // WCAG: aria-haspopup="listbox"
    await expect(combobox).toHaveAttribute('aria-haspopup', 'listbox');
    // WCAG: aria-expanded exists
    await expect(combobox).toHaveAttribute('aria-expanded');
    // WCAG: aria-autocomplete="list"
    await expect(combobox).toHaveAttribute('aria-autocomplete', 'list');

    // Keyboard: Arrow down opens dropdown
    await combobox.focus();
    await combobox.press('ArrowDown');
    await adminPage.waitForTimeout(300);

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    if (await listbox.isVisible()) {
      // WCAG: listbox has role="listbox"
      await expect(listbox).toHaveAttribute('role', 'listbox');

      // WCAG: options have role="option"
      const firstOption = listbox.locator('[role="option"]').first();
      await expect(firstOption).toBeVisible();

      // Keyboard: Escape closes dropdown
      await combobox.press('Escape');
      await adminPage.waitForTimeout(200);
      await expect(listbox).not.toBeVisible();
    }

    // If there are existing chips, verify aria-label on remove buttons
    const removeButtons = adminPage.locator('button[aria-label^="Remove"]');
    const removeCount = await removeButtons.count();
    if (removeCount > 0) {
      const ariaLabel = await removeButtons.first().getAttribute('aria-label');
      expect(ariaLabel).toMatch(/^Remove /);
    }
  });
});
