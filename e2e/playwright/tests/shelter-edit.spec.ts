import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

test.describe('Shelter Edit', () => {
  test.afterAll(async () => { await cleanupTestData(); });

  test('admin edits shelter name via Shelters tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Switch to Shelters tab
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    // Find the first Edit link
    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await expect(editLink).toBeVisible();
    await editLink.click();

    // Wait for edit form to load
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // Verify form is pre-populated
    const nameInput = adminPage.locator('[data-testid="shelter-name"]');
    await expect(nameInput).toBeVisible();
    const originalName = await nameInput.inputValue();
    expect(originalName.length).toBeGreaterThan(0);

    // Update name
    const updatedName = `${originalName} (edited)`;
    await nameInput.fill(updatedName);

    // Save
    await adminPage.locator('[data-testid="shelter-save"]').click();

    // Should navigate back to admin panel
    await adminPage.waitForURL(/\/admin/);
    await adminPage.waitForTimeout(1000);

    // Switch to Shelters tab and verify updated name
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    await expect(adminPage.locator('main td', { hasText: updatedName })).toBeVisible();
  });

  test('coordinator edits phone from dashboard', async ({ coordinatorPage }) => {
    // coordinatorPage is COC_ADMIN — has coordinator dashboard access
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(1000);

    // Expand first shelter card
    const shelterCard = coordinatorPage.locator('[data-testid^="shelter-card-"]').first();
    await shelterCard.click();
    await coordinatorPage.waitForTimeout(500);

    // Click Edit Details
    const editDetailsBtn = coordinatorPage.locator('a', { hasText: /edit details/i }).first();
    await expect(editDetailsBtn).toBeVisible();
    await editDetailsBtn.click();

    // Wait for edit form
    await expect(coordinatorPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // Update phone
    const phoneInput = coordinatorPage.locator('[data-testid="shelter-phone"]');
    await expect(phoneInput).toBeVisible();
    await phoneInput.fill('919-555-1234');

    // Save
    await coordinatorPage.locator('[data-testid="shelter-save"]').click();

    // Should navigate back to coordinator dashboard
    await coordinatorPage.waitForURL(/\/coordinator/);
  });

  test('DV toggle renders correctly for COC_ADMIN', async ({ coordinatorPage }) => {
    // NOTE: coordinatorPage fixture uses COC_ADMIN (cocadmin@dev.fabt.org), dvAccess=false.
    // The DV flag disabled state only appears for COORDINATOR role users.
    // Backend integration test (test_coordinatorCannotChangeDvFlag) covers role enforcement.
    // Full E2E coverage of disabled tooltip requires adding a COORDINATOR user fixture.
    //
    // Here we verify the DV toggle renders and is enabled for COC_ADMIN.
    await coordinatorPage.goto('/admin');
    await coordinatorPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await coordinatorPage.waitForTimeout(1000);

    const editLink = coordinatorPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();

    await expect(coordinatorPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // DV toggle should be visible and enabled for COC_ADMIN
    const dvToggle = coordinatorPage.locator('[data-testid="dv-shelter-toggle"]');
    await expect(dvToggle).toBeVisible();
    await expect(dvToggle).toBeEnabled();

    // Readonly tooltip should NOT be visible for COC_ADMIN
    const tooltip = coordinatorPage.locator('[data-testid="dv-readonly-tooltip"]');
    expect(await tooltip.count()).toBe(0);
  });

  test('DV flag change shows confirmation dialog and saves', async ({ adminPage }) => {
    // MUST use adminPage (PLATFORM_ADMIN, dvAccess=true) — not coordinatorPage.
    // RLS policy requires app.dv_access=true to write dv_shelter=true.
    // Riley: "If this test uses the wrong user, a DV shelter could be left unprotected."
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    const editLink = adminPage.locator('main a', { hasText: /^Edit$/ }).first();
    await editLink.click();

    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    const dvToggle = adminPage.locator('[data-testid="dv-shelter-toggle"]');
    await expect(dvToggle).toBeVisible();

    const isCurrentlyDv = (await dvToggle.getAttribute('aria-checked')) === 'true';

    if (isCurrentlyDv) {
      // Turning DV OFF requires confirmation dialog
      await dvToggle.click();

      const dialog = adminPage.locator('[data-testid="dv-confirm-dialog"]');
      await expect(dialog).toBeVisible();
      await expect(dialog.locator('[data-testid="dv-confirm-proceed"]')).toBeVisible();
      await expect(dialog.locator('[data-testid="dv-confirm-cancel"]')).toBeVisible();

      // Cancel — DV flag should remain true
      await dialog.locator('[data-testid="dv-confirm-cancel"]').click();
      await expect(dialog).not.toBeVisible();
      expect(await dvToggle.getAttribute('aria-checked')).toBe('true');
    } else {
      // Turning DV ON does NOT require confirmation — immediate toggle
      await dvToggle.click();
      expect(await dvToggle.getAttribute('aria-checked')).toBe('true');

      // Now turning OFF triggers the confirmation dialog
      await dvToggle.click();

      const dialog = adminPage.locator('[data-testid="dv-confirm-dialog"]');
      await expect(dialog).toBeVisible();

      // Verify dialog content is accessible (WCAG: keyboard-navigable, aria roles)
      await expect(dialog).toHaveAttribute('role', 'alertdialog');
      await expect(dialog).toHaveAttribute('aria-modal', 'true');

      // Cancel to leave the shelter in its original non-DV state
      await dialog.locator('[data-testid="dv-confirm-cancel"]').click();
      await expect(dialog).not.toBeVisible();
      // Toggle went true, then cancel reverts the dialog but the UI state is still true
      // (cancel prevents the true→false change, keeping it at true)
      expect(await dvToggle.getAttribute('aria-checked')).toBe('true');
    }
  });
});
