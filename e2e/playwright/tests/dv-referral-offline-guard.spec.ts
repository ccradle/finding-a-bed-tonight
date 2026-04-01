import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';
import type { Page } from '@playwright/test';

/**
 * DV Referral Offline Guard — Playwright E2E tests.
 *
 * Verifies that DV referral requests are blocked when offline with
 * actionable feedback, and that online referral flow + hold buttons
 * are not regressed.
 *
 * Uses adminPage (PLATFORM_ADMIN with dvAccess=true) since DV shelter
 * availability may be consumed in full suite — admin always has access.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';

async function goOffline(page: Page) {
  await page.context().setOffline(true);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'onLine', { value: false, writable: true, configurable: true });
    window.dispatchEvent(new Event('offline'));
  });
  await page.waitForTimeout(300);
}

async function goOnline(page: Page, waitMs = 2000) {
  await page.context().setOffline(false);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'onLine', { value: true, writable: true, configurable: true });
    window.dispatchEvent(new Event('online'));
  });
  await page.waitForTimeout(waitMs);
}

test.describe('DV Referral Offline Guard', () => {
  test.beforeAll(async () => { await cleanupTestData(); });
  test.afterAll(async () => { await cleanupTestData(); });

  // =========================================================================
  // Positive tests — new behavior works
  // =========================================================================

  test('Request Referral button has aria-disabled when offline', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    // Verify button is NOT aria-disabled while online
    await expect(referralBtn.first()).not.toHaveAttribute('aria-disabled', 'true');

    // Go offline
    await goOffline(adminPage);

    // Button should now be aria-disabled (NOT the disabled attribute)
    await expect(referralBtn.first()).toHaveAttribute('aria-disabled', 'true');
    // Should NOT have the disabled attribute (preserves keyboard focus per WCAG)
    await expect(referralBtn.first()).not.toHaveAttribute('disabled', '');
  });

  test('tapping offline referral button shows inline message with tel link, modal does NOT open', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    await goOffline(adminPage);

    // Click the aria-disabled button (force: true because Playwright respects aria-disabled)
    await referralBtn.first().click({ force: true });
    await adminPage.waitForTimeout(500);

    // Modal should NOT open
    const modal = adminPage.getByTestId('referral-modal');
    expect(await modal.count()).toBe(0);

    // Inline offline message should appear with tel: link
    const offlineMsg = adminPage.locator('[data-testid^="offline-referral-msg-"]');
    await expect(offlineMsg.first()).toBeVisible();

    // Should contain a phone link
    const phoneLink = offlineMsg.first().locator('a[href^="tel:"]');
    await expect(phoneLink).toBeVisible();
  });

  test('offline banner includes DV referral language', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    await goOffline(adminPage);

    // Banner should mention DV referral connection requirement
    const banner = adminPage.locator('text=/DV referral requests require a connection/i');
    await expect(banner).toBeVisible({ timeout: 3000 });
  });

  test('connectivity restored clears offline state on referral buttons', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    // Go offline → click button → inline message appears
    await goOffline(adminPage);
    await referralBtn.first().click({ force: true });
    await adminPage.waitForTimeout(300);

    const offlineMsg = adminPage.locator('[data-testid^="offline-referral-msg-"]');
    await expect(offlineMsg.first()).toBeVisible();

    // Go back online
    await goOnline(adminPage);

    // aria-disabled should be removed
    await expect(referralBtn.first()).not.toHaveAttribute('aria-disabled', 'true');

    // Inline message should be dismissed
    expect(await offlineMsg.count()).toBe(0);
  });

  test('captive portal: network error during submit shows error inside modal', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    // Intercept the referral API to simulate network failure while "online"
    await adminPage.route('**/api/v1/dv-referrals', route => route.abort('connectionfailed'));

    // Open modal (we're "online" so the guard doesn't trigger)
    await referralBtn.first().click();
    await adminPage.waitForTimeout(500);

    const modal = adminPage.getByTestId('referral-modal');
    await expect(modal).toBeVisible();

    // Fill form and submit
    await adminPage.getByTestId('referral-household-size').fill('2');
    await adminPage.getByTestId('referral-callback').fill('919-555-0042');
    await adminPage.getByTestId('referral-submit').click();
    await adminPage.waitForTimeout(1500);

    // Error should appear INSIDE the modal (not behind it)
    const errorInModal = adminPage.getByTestId('referral-error');
    await expect(errorInModal).toBeVisible();

    // Modal should still be open (user can retry or cancel)
    await expect(modal).toBeVisible();

    // Clean up route interception
    await adminPage.unroute('**/api/v1/dv-referrals');
  });

  // =========================================================================
  // Regression tests — existing behavior preserved
  // =========================================================================

  test('online referral flow still works end-to-end after guard added', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    // Button should NOT be aria-disabled online
    await expect(referralBtn.first()).not.toHaveAttribute('aria-disabled', 'true');

    // Click to open modal
    await referralBtn.first().click();
    await adminPage.waitForTimeout(500);

    const modal = adminPage.getByTestId('referral-modal');
    await expect(modal).toBeVisible();

    // Fill and submit
    await adminPage.getByTestId('referral-household-size').fill('2');
    await adminPage.getByTestId('referral-callback').fill('919-555-9876');
    await adminPage.getByTestId('referral-submit').click();
    await adminPage.waitForTimeout(1500);

    // Modal should close on success
    await expect(modal).not.toBeVisible({ timeout: 5000 });
  });

  test('Hold This Bed buttons NOT affected by offline guard', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const holdBtn = adminPage.locator('[data-testid^="hold-bed-"]');
    if (await holdBtn.count() === 0) {
      test.skip(true, 'No shelters with available beds');
      return;
    }

    // Go offline
    await goOffline(adminPage);

    // Hold buttons should NOT have aria-disabled (they queue offline)
    await expect(holdBtn.first()).not.toHaveAttribute('aria-disabled', 'true');

    // Hold button should still be fully opaque (no opacity change)
    const opacity = await holdBtn.first().evaluate(el => window.getComputedStyle(el).opacity);
    expect(opacity).toBe('1');

    await goOnline(adminPage);
  });

  test('rapid online/offline toggle stabilizes', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds');
      return;
    }

    // Rapidly toggle 3 times
    await goOffline(adminPage);
    await goOnline(adminPage, 200);
    await goOffline(adminPage);
    await goOnline(adminPage, 200);
    await goOffline(adminPage);
    await goOnline(adminPage, 500);

    // After settling online, button should be fully enabled
    await expect(referralBtn.first()).not.toHaveAttribute('aria-disabled', 'true');

    // No stale inline messages
    const offlineMsg = adminPage.locator('[data-testid^="offline-referral-msg-"]');
    expect(await offlineMsg.count()).toBe(0);
  });
});
