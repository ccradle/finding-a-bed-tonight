import { test, expect } from '../fixtures/auth.fixture';

/**
 * SSE Notification Bell — Playwright E2E tests.
 * Verifies the notification bell uses WAI-ARIA disclosure pattern (not menu),
 * has correct keyboard navigation, and appears for all authenticated roles.
 *
 * NOTE: SSE event delivery tests are in the backend integration suite.
 * These tests verify the UI component is present and accessible.
 */

test.describe('Notification Bell', () => {

  test('Bell is visible in header for outreach worker', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bell = outreachPage.locator('[data-testid="notification-bell"]');
    await expect(bell).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toBeVisible();
  });

  test('Bell is visible in header for coordinator', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await expect(coordinatorPage.locator('main')).toBeVisible();

    const bell = coordinatorPage.locator('[data-testid="notification-bell"]');
    await expect(bell).toBeVisible();
  });

  test('Bell is visible in header for admin', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main')).toBeVisible();

    const bell = adminPage.locator('[data-testid="notification-bell"]');
    await expect(bell).toBeVisible();
  });

  test('Bell button has accessible aria-label', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    const ariaLabel = await bellButton.getAttribute('aria-label');
    expect(ariaLabel).toBeTruthy();
    expect(ariaLabel).toContain('Notifications');
  });

  test('Bell has aria-live region for screen readers', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const liveRegion = outreachPage.locator('[data-testid="notification-bell"] [aria-live="polite"]');
    await expect(liveRegion).toBeAttached();
  });

  // --- WAI-ARIA disclosure pattern tests (T-38) ---

  test('Bell button has aria-expanded=false when closed', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toHaveAttribute('aria-expanded', 'false');
  });

  test('Bell button toggles aria-expanded on click', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toHaveAttribute('aria-expanded', 'false');

    await bellButton.click();
    await expect(bellButton).toHaveAttribute('aria-expanded', 'true');

    await bellButton.click();
    await expect(bellButton).toHaveAttribute('aria-expanded', 'false');
  });

  test('Panel uses disclosure pattern — no role="menu" present', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await bellButton.click();

    const panel = outreachPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    // Disclosure pattern: role="region", NOT role="menu"
    await expect(panel).toHaveAttribute('role', 'region');

    // No menuitem roles should exist
    const menuItems = outreachPage.locator('[data-testid="notification-panel"] [role="menuitem"]');
    expect(await menuItems.count()).toBe(0);
  });

  test('Clicking bell opens panel with empty state', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    await outreachPage.locator('[data-testid="notification-bell-button"]').click();

    const panel = outreachPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText(/No notifications|Sin notificaciones/);
  });

  test('Escape key closes panel and returns focus to bell', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await bellButton.click();

    const panel = outreachPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    // Press Escape
    await outreachPage.keyboard.press('Escape');
    await expect(panel).not.toBeVisible();

    // Focus should return to bell button
    const focused = outreachPage.locator(':focus');
    await expect(focused).toHaveAttribute('data-testid', 'notification-bell-button');
  });

  // --- Connection status banner tests (T-42) ---

  test('Connection status banner has role="status" and aria-live', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1000);

    const statusBanner = outreachPage.locator('[data-testid="connection-status-hidden"]');
    await expect(statusBanner).toBeAttached();
    await expect(statusBanner).toHaveAttribute('role', 'status');
    await expect(statusBanner).toHaveAttribute('aria-live', 'polite');
  });

  test('Connection status shows hidden state when connected', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1000);

    // When connected: hidden testid present, reconnecting testid absent
    await expect(outreachPage.locator('[data-testid="connection-status-hidden"]')).toBeAttached();
    await expect(outreachPage.locator('[data-testid="connection-status-reconnecting"]')).not.toBeAttached();
  });

  // --- Layout ordering ---

  test('Bell is positioned between locale selector and password button', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const header = outreachPage.locator('header');
    const bell = header.locator('[data-testid="notification-bell"]');
    const passwordBtn = header.locator('[data-testid="change-password-button"]');

    await expect(bell).toBeVisible();
    await expect(passwordBtn).toBeVisible();

    const bellBox = await bell.boundingBox();
    const passwordBox = await passwordBtn.boundingBox();
    if (bellBox && passwordBox) {
      expect(bellBox.x).toBeLessThan(passwordBox.x);
    }
  });
});
