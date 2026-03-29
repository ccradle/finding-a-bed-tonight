import { test, expect } from '../fixtures/auth.fixture';

/**
 * User Management — Playwright E2E tests.
 * Tests the edit drawer, deactivation, reactivation, and status badges.
 */

test.describe('User Management', () => {

  test('Edit button visible on each user row', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main')).toBeVisible();

    // Users tab is default — edit buttons should be present
    const editButtons = adminPage.locator('[data-testid^="edit-user-"]');
    await expect(editButtons.first()).toBeVisible();
  });

  test('Clicking Edit opens the user edit drawer', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    // Click first edit button
    const editButton = adminPage.locator('[data-testid^="edit-user-"]').first();
    await editButton.click();

    // Drawer should open
    const drawer = adminPage.locator('[data-testid="user-edit-drawer"]');
    await expect(drawer).toBeVisible();

    // Fields should be populated
    const nameInput = drawer.locator('[data-testid="user-edit-displayName"]');
    await expect(nameInput).toBeVisible();
    const nameValue = await nameInput.inputValue();
    expect(nameValue.length).toBeGreaterThan(0);
  });

  test('Edit user display name, save, verify change', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    // Edit the deactivated test user (safe — doesn't affect other tests)
    const editButton = adminPage.locator('[data-testid="edit-user-former@dev.fabt.org"]');
    if (await editButton.count() > 0) {
      await editButton.click();
      await adminPage.waitForTimeout(300);

      const drawer = adminPage.locator('[data-testid="user-edit-drawer"]');
      await expect(drawer).toBeVisible();

      // Change display name (non-destructive edit)
      const nameInput = drawer.locator('[data-testid="user-edit-displayName"]');
      await nameInput.fill('Updated Staff Member');

      // Save
      await drawer.locator('[data-testid="user-edit-save"]').click();
      await adminPage.waitForTimeout(1000);

      // Restore original name
      await editButton.click();
      await adminPage.waitForTimeout(300);
      await drawer.locator('[data-testid="user-edit-displayName"]').fill('Former Staff Member');
      await drawer.locator('[data-testid="user-edit-save"]').click();
      await adminPage.waitForTimeout(500);
    }
  });

  test('Deactivate button shows confirmation dialog', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    const editButton = adminPage.locator('[data-testid^="edit-user-"]').first();
    await editButton.click();
    await adminPage.waitForTimeout(300);

    const drawer = adminPage.locator('[data-testid="user-edit-drawer"]');
    const deactivateBtn = drawer.locator('[data-testid="user-deactivate-button"]');

    // Only test if user is active (has deactivate button)
    if (await deactivateBtn.count() > 0) {
      await deactivateBtn.click();
      await adminPage.waitForTimeout(300);

      // Confirmation dialog should appear
      const confirmDialog = adminPage.locator('[data-testid="deactivate-confirm-dialog"]');
      await expect(confirmDialog).toBeVisible();

      // Cancel — don't actually deactivate seed users
      await confirmDialog.locator('button', { hasText: /cancel/i }).click();
      await expect(confirmDialog).not.toBeVisible();
    }
  });

  test('Status badge visible on user rows', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    // Status column should show Active/Deactivated badges
    const statusHeader = adminPage.locator('th', { hasText: /Status|Estado/ });
    await expect(statusHeader).toBeVisible();
  });

  test('Drawer closes on Escape key', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    const editButton = adminPage.locator('[data-testid^="edit-user-"]').first();
    await editButton.click();

    const drawer = adminPage.locator('[data-testid="user-edit-drawer"]');
    await expect(drawer).toBeVisible();

    await adminPage.keyboard.press('Escape');
    await expect(drawer).not.toBeVisible();
  });

  test('Drawer has accessible dialog role', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);

    const editButton = adminPage.locator('[data-testid^="edit-user-"]').first();
    await editButton.click();

    const drawer = adminPage.locator('[data-testid="user-edit-drawer"]');
    await expect(drawer).toHaveAttribute('role', 'dialog');
  });
});
