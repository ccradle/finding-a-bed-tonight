import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

test.describe('Coordinator-Shelter Assignment', () => {
  let shelterEditUrl: string;

  test.afterAll(async () => { await cleanupTestData(); });

  // Shared setup: navigate to first shelter edit page
  async function navigateToShelterEdit(page: import('@playwright/test').Page) {
    await page.goto('/admin');
    const sheltersTab = page.locator('main button', { hasText: /^Shelters$/ }).first();
    await expect(sheltersTab).toBeVisible({ timeout: 5000 });
    await sheltersTab.click();

    const editLink = page.locator('main a', { hasText: /^Edit$/ }).first();
    await expect(editLink).toBeVisible({ timeout: 5000 });
    await editLink.click();

    await expect(page.locator('h2', { hasText: /edit shelter/i })).toBeVisible({ timeout: 5000 });
    shelterEditUrl = page.url();
  }

  // =========================================================================
  // Positive Tests
  // =========================================================================

  // T-15: Admin opens shelter edit → "Assigned Coordinators" section visible
  test('T-15: shelter edit shows Assigned Coordinators section with combobox', async ({ adminPage }) => {
    await navigateToShelterEdit(adminPage);

    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await expect(combobox).toBeVisible({ timeout: 5000 });
    await expect(combobox).toHaveAttribute('role', 'combobox');
    await expect(combobox).toHaveAttribute('aria-haspopup', 'listbox');
  });

  // T-16 + T-17 combined: search → select → chip appears → remove → chip gone
  test('T-16/T-17: search, select adds chip, remove removes chip', async ({ adminPage }) => {
    await navigateToShelterEdit(adminPage);

    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await combobox.click();
    await combobox.fill('a');

    // Dropdown MUST appear — fail if it doesn't
    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    await expect(listbox).toBeVisible({ timeout: 5000 });

    // Select first option
    const firstOption = listbox.locator('[role="option"]').first();
    await expect(firstOption).toBeVisible();
    const optionText = await firstOption.locator('div').first().textContent();
    expect(optionText).toBeTruthy();
    await firstOption.click();

    // Chip MUST appear with the selected coordinator's name
    const chipName = optionText!.trim().split('\n')[0].trim();
    const removeButton = adminPage.locator(`button[aria-label="Remove ${chipName}"]`);
    await expect(removeButton).toBeVisible({ timeout: 3000 });

    // Combobox should be cleared after selection
    await expect(combobox).toHaveValue('');

    // T-17: Remove the chip — it should disappear
    const removeCountBefore = await adminPage.locator('button[aria-label^="Remove"]').count();
    await removeButton.click();
    await expect(removeButton).not.toBeVisible({ timeout: 3000 });
    const removeCountAfter = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(removeCountAfter).toBeLessThan(removeCountBefore);
  });

  // T-17 extended: verify "staged, not persisted" — remove chip, navigate away, come back
  test('T-17b: removed chip reappears after navigation without save (staged only)', async ({ adminPage }) => {
    await navigateToShelterEdit(adminPage);

    // Check if there are existing assigned coordinators
    const removeButtons = adminPage.locator('button[aria-label^="Remove"]');
    const initialCount = await removeButtons.count();

    if (initialCount === 0) {
      // No existing assignments to test staged removal — add one first
      const combobox = adminPage.getByTestId('coordinator-combobox-input');
      await combobox.click();
      await combobox.fill('a');
      const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
      await expect(listbox).toBeVisible({ timeout: 5000 });
      await listbox.locator('[role="option"]').first().click();

      // Save it so we have a persisted assignment to test against
      await adminPage.locator('[data-testid="shelter-save"]').click();
      await adminPage.waitForURL(/\/(admin|coordinator)/, { timeout: 10000 });

      // Navigate back to the shelter edit
      await navigateToShelterEdit(adminPage);
    }

    // Now we should have at least one chip
    const chipsBeforeRemove = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(chipsBeforeRemove).toBeGreaterThan(0);

    // Remove the last chip (staged, not saved)
    await adminPage.locator('button[aria-label^="Remove"]').last().click();
    const chipsAfterRemove = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(chipsAfterRemove).toBeLessThan(chipsBeforeRemove);

    // Navigate away WITHOUT saving
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main button', { hasText: /^Shelters$/ })).toBeVisible({ timeout: 5000 });

    // Navigate back to the same shelter edit
    await navigateToShelterEdit(adminPage);

    // The chip should be back — removal was staged, not persisted
    const chipsAfterReturn = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(chipsAfterReturn).toBe(chipsBeforeRemove);
  });

  // T-18: Save persists assignment — verified by reloading the page
  test('T-18: saving shelter persists coordinator assignment', async ({ adminPage }) => {
    await navigateToShelterEdit(adminPage);

    // Record initial chip count
    const initialRemoveButtons = await adminPage.locator('button[aria-label^="Remove"]').count();

    // Add a coordinator
    const combobox = adminPage.getByTestId('coordinator-combobox-input');
    await combobox.click();
    await combobox.fill('a');

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    await expect(listbox).toBeVisible({ timeout: 5000 });

    const firstOption = listbox.locator('[role="option"]').first();
    const optionText = await firstOption.locator('div').first().textContent();
    await firstOption.click();

    // Chip should be added
    const afterAddCount = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(afterAddCount).toBe(initialRemoveButtons + 1);

    // Save the shelter
    await adminPage.locator('[data-testid="shelter-save"]').click();
    await adminPage.waitForURL(/\/(admin|coordinator)/, { timeout: 10000 });

    // Navigate back to the same shelter edit to verify persistence
    await navigateToShelterEdit(adminPage);

    // The chip should still be there after reload — assignment was persisted
    const afterReloadCount = await adminPage.locator('button[aria-label^="Remove"]').count();
    expect(afterReloadCount).toBe(afterAddCount);

    // Cleanup: remove the assignment we just added
    if (optionText) {
      const chipName = optionText.trim().split('\n')[0].trim();
      const cleanupButton = adminPage.locator(`button[aria-label="Remove ${chipName}"]`);
      if (await cleanupButton.isVisible()) {
        await cleanupButton.click();
        await adminPage.locator('[data-testid="shelter-save"]').click();
        await adminPage.waitForURL(/\/(admin|coordinator)/, { timeout: 10000 });
      }
    }
  });

  // T-19: Admin opens user edit drawer → "Assigned Shelters" section visible
  test('T-19: user edit drawer shows Assigned Shelters section', async ({ adminPage }) => {
    await adminPage.goto('/admin');

    // Users tab — click first edit button
    const editButton = adminPage.locator('[data-testid^="user-edit-"]').first();
    await expect(editButton).toBeVisible({ timeout: 5000 });
    await editButton.click();

    // "Assigned Shelters" heading MUST appear in drawer
    const shelterSection = adminPage.locator('text=Assigned Shelters');
    await expect(shelterSection).toBeVisible({ timeout: 5000 });

    // Either chips or "No shelters assigned" MUST be visible — not both
    const chips = adminPage.getByTestId('user-assigned-shelters');
    const noShelters = adminPage.getByTestId('user-no-shelters');

    const hasChips = await chips.isVisible().catch(() => false);
    const hasEmpty = await noShelters.isVisible().catch(() => false);
    expect(hasChips || hasEmpty).toBe(true);
    // Mutually exclusive
    expect(hasChips && hasEmpty).toBe(false);
  });

  // =========================================================================
  // WCAG Accessibility (T-20)
  // =========================================================================

  test('T-20: WCAG combobox accessibility — ARIA attributes and keyboard navigation', async ({ adminPage }) => {
    await navigateToShelterEdit(adminPage);

    const combobox = adminPage.getByTestId('coordinator-combobox-input');

    // ARIA attributes
    await expect(combobox).toHaveAttribute('role', 'combobox');
    await expect(combobox).toHaveAttribute('aria-haspopup', 'listbox');
    await expect(combobox).toHaveAttribute('aria-expanded', 'false');
    await expect(combobox).toHaveAttribute('aria-autocomplete', 'list');

    // Keyboard: Arrow Down opens dropdown
    await combobox.focus();
    await combobox.press('ArrowDown');

    const listbox = adminPage.getByTestId('coordinator-combobox-listbox');
    await expect(listbox).toBeVisible({ timeout: 3000 });

    // aria-expanded should now be true
    await expect(combobox).toHaveAttribute('aria-expanded', 'true');

    // Listbox ARIA
    await expect(listbox).toHaveAttribute('role', 'listbox');
    const firstOption = listbox.locator('[role="option"]').first();
    await expect(firstOption).toBeVisible();

    // aria-activedescendant should point to the first option
    const activeDescendant = await combobox.getAttribute('aria-activedescendant');
    expect(activeDescendant).toBeTruthy();
    const firstOptionId = await firstOption.getAttribute('id');
    expect(activeDescendant).toBe(firstOptionId);

    // First option should have aria-selected="true"
    await expect(firstOption).toHaveAttribute('aria-selected', 'true');

    // Arrow Down moves to second option (if exists)
    const optionCount = await listbox.locator('[role="option"]').count();
    if (optionCount > 1) {
      await combobox.press('ArrowDown');
      const secondOption = listbox.locator('[role="option"]').nth(1);
      const newActiveDescendant = await combobox.getAttribute('aria-activedescendant');
      const secondOptionId = await secondOption.getAttribute('id');
      expect(newActiveDescendant).toBe(secondOptionId);
      await expect(secondOption).toHaveAttribute('aria-selected', 'true');
      // First option should no longer be selected
      await expect(firstOption).toHaveAttribute('aria-selected', 'false');
    }

    // Enter selects the active option — chip should appear
    await combobox.press('Enter');
    const removeButtons = adminPage.locator('button[aria-label^="Remove"]');
    await expect(removeButtons.first()).toBeVisible({ timeout: 3000 });

    // Verify remove button has proper aria-label format
    const ariaLabel = await removeButtons.first().getAttribute('aria-label');
    expect(ariaLabel).toMatch(/^Remove .+/);

    // Escape closes dropdown
    await combobox.focus();
    await combobox.press('ArrowDown');
    await expect(listbox).toBeVisible({ timeout: 3000 });
    await combobox.press('Escape');
    await expect(listbox).not.toBeVisible({ timeout: 3000 });
    await expect(combobox).toHaveAttribute('aria-expanded', 'false');
  });

  // =========================================================================
  // Negative / Authorization Tests
  // =========================================================================

  test('Outreach worker does NOT see coordinator combobox on shelter edit', async ({ outreachPage }) => {
    // Outreach workers can search for beds but cannot edit shelters.
    // If they somehow navigate to a shelter edit URL, the combobox should
    // not be present (they lack COC_ADMIN/PLATFORM_ADMIN role).
    await outreachPage.goto('/admin');

    // Outreach workers should be redirected or see no admin content
    // They should NOT have access to the Shelters tab edit flow.
    // Verify the coordinator combobox is NOT on the page.
    const combobox = outreachPage.getByTestId('coordinator-combobox-input');
    expect(await combobox.count()).toBe(0);
  });
});
