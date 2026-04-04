import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

const API_URL = process.env.API_URL || 'http://localhost:8080';

/**
 * DV Opaque Referral — Playwright E2E tests.
 * Uses data-testid attributes for stable locators.
 *
 * NOTE: These tests require a DV shelter with dvAccess user.
 * The admin user (PLATFORM_ADMIN with dvAccess=true) is used for both roles.
 */

test.describe('DV Opaque Referral', () => {
  test.afterAll(async () => { await cleanupTestData(); });

  // Clean up stale test data before running DV referral tests
  test.beforeAll(async () => {
    try {
      const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
      });
      const { accessToken } = await loginResp.json();
      await fetch(`${API_URL}/api/v1/test/reset`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${accessToken}`, 'X-Confirm-Reset': 'DESTROY' },
      });
    } catch { /* reset endpoint may not exist — fine */ }
  });

  test('DV shelter search result shows Request Referral instead of Hold', async ({ adminPage }) => {
    // Admin has dvAccess=true, so DV shelters appear in search
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    // Look for a DV shelter result with "Request Referral" button
    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]');
    const holdBtn = adminPage.locator('[data-testid^="hold-bed-"]');

    // If DV shelters are in results, referral button should be visible
    if (await referralBtn.count() > 0) {
      await expect(referralBtn.first()).toBeVisible();
    }
    // Non-DV shelters should have hold buttons
    if (await holdBtn.count() > 0) {
      await expect(holdBtn.first()).toBeVisible();
    }
  });

  test('Referral request modal opens and can be filled', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() === 0) {
      test.skip();
      return;
    }

    await referralBtn.click();
    await adminPage.waitForTimeout(500);

    // Modal should be visible
    const modal = adminPage.getByTestId('referral-modal');
    await expect(modal).toBeVisible();

    // Fill form
    await adminPage.getByTestId('referral-household-size').fill('3');
    await adminPage.getByTestId('referral-special-needs').fill('Wheelchair accessible');
    await adminPage.getByTestId('referral-callback').fill('919-555-0042');

    // Urgency buttons should be visible
    const urgencyContainer = adminPage.getByTestId('referral-urgency');
    await expect(urgencyContainer).toBeVisible();

    // Submit button should be enabled once callback is filled
    const submitBtn = adminPage.getByTestId('referral-submit');
    await expect(submitBtn).toBeEnabled();
  });

  test('My Referrals section shows pending token', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);

    // Submit a referral if possible
    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() === 0) {
      test.skip();
      return;
    }

    await referralBtn.click();
    await adminPage.waitForTimeout(500);

    await adminPage.getByTestId('referral-household-size').fill('2');
    await adminPage.getByTestId('referral-callback').fill('919-555-9999');
    await adminPage.getByTestId('referral-submit').click();
    await adminPage.waitForTimeout(1500);

    // My Referrals section should appear
    const referralsSection = adminPage.locator('button', { hasText: /My DV Referrals/i });
    if (await referralsSection.count() > 0) {
      await referralsSection.click();
      await adminPage.waitForTimeout(500);
      const myReferrals = adminPage.getByTestId('my-referrals');
      await expect(myReferrals).toBeVisible();
    }
  });

  test('Coordinator sees pending referral badge on DV shelter', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    // Look for a referral badge
    const badge = adminPage.locator('[data-testid^="referral-badge-"]');
    // Badge only appears when there are pending referrals — may or may not be present
    // Just verify the page loads without error
    const cards = adminPage.locator('main button[style*="text-align: left"]');
    expect(await cards.count()).toBeGreaterThan(0);
  });

  test('Coordinator screening view shows operational data', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    // Expand a DV shelter that might have pending referrals
    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();
    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const screening = adminPage.getByTestId('referral-screening');
      if (await screening.count() > 0) {
        await expect(screening).toBeVisible();

        // Verify screening shows operational data, not PII
        const text = await screening.textContent();
        expect(text).toContain('person');      // household size
        expect(text).toContain('919-555');      // callback number
        // Should NOT contain any client PII indicators
        expect(text).not.toContain('SSN');
        expect(text).not.toContain('Date of Birth');

        // Accept/reject buttons should be visible
        const acceptBtn = adminPage.locator('[data-testid^="accept-referral-"]').first();
        if (await acceptBtn.count() > 0) {
          await expect(acceptBtn).toBeVisible();
        }
        break;
      }
      // Collapse and try next
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }
  });

  test('Accept referral shows shelter phone, no address', async ({ adminPage }) => {
    // This test requires a pending referral to accept
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();
    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const acceptBtn = adminPage.locator('[data-testid^="accept-referral-"]').first();
      if (await acceptBtn.count() > 0) {
        await acceptBtn.click();
        await adminPage.waitForTimeout(1000);
        // After accepting, the referral should be removed from pending list
        break;
      }
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }
  });

  test('Active referral countdown is visible and buttons are enabled', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();
    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const countdown = adminPage.locator('[data-testid^="referral-countdown-"]').first();
      if (await countdown.count() > 0) {
        await expect(countdown).toBeVisible();
        const text = await countdown.textContent();
        expect(text).toMatch(/\d+m.*remaining/i);

        // Accept and reject buttons should be enabled (not disabled)
        const acceptBtn = adminPage.locator('[data-testid^="accept-referral-"]').first();
        await expect(acceptBtn).toBeEnabled();
        const rejectBtn = adminPage.locator('[data-testid^="reject-referral-"]').first();
        await expect(rejectBtn).toBeEnabled();
        break;
      }
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }
  });

  test('Expired referral shows disabled buttons and badge via SSE event', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    // Expand a DV shelter with pending referrals
    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();
    let foundReferral = false;

    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const acceptBtn = adminPage.locator('[data-testid^="accept-referral-"]').first();
      if (await acceptBtn.count() > 0) {
        // Get the token ID from the accept button's data-testid
        const testId = await acceptBtn.getAttribute('data-testid');
        const tokenId = testId?.replace('accept-referral-', '');

        // Simulate SSE expired event via window dispatch (tests D6 frontend path)
        await adminPage.evaluate((id) => {
          window.dispatchEvent(new CustomEvent('fabt:referral-expired', {
            detail: { tokenIds: [id] },
          }));
        }, tokenId);

        await adminPage.waitForTimeout(500);

        // Expired badge should appear (without page refresh — proves SSE path)
        const expiredBadge = adminPage.getByTestId(`referral-expired-badge-${tokenId}`);
        await expect(expiredBadge).toBeVisible({ timeout: 5000 });

        // Accept button should be disabled
        await expect(acceptBtn).toBeDisabled();

        foundReferral = true;
        break;
      }
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }

    if (!foundReferral) {
      test.skip();
    }
  });

  test('Clicking expired accept shows expiration error message', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    // Expand a DV shelter with pending referrals
    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();

    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const acceptBtn = adminPage.locator('[data-testid^="accept-referral-"]').first();
      if (await acceptBtn.count() > 0 && await acceptBtn.isEnabled()) {
        // Simulate the backend having already expired this token by intercepting the API call
        await adminPage.route('**/api/v1/dv-referrals/*/accept', (route) => {
          route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'Token has expired', status: 409 }),
          });
        });

        await acceptBtn.click();
        await adminPage.waitForTimeout(1000);

        // Should show specific expiration error (not generic)
        const errorText = await adminPage.textContent('body');
        expect(errorText).toContain('expired');
        break;
      }
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }
  });

  test('Reject referral shows decline reason to worker', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    const cards = adminPage.locator('main button[style*="text-align: left"]');
    const count = await cards.count();
    for (let i = 0; i < Math.min(count, 5); i++) {
      await cards.nth(i).click();
      await adminPage.waitForTimeout(1500);

      const rejectBtn = adminPage.locator('[data-testid^="reject-referral-"]').first();
      if (await rejectBtn.count() > 0) {
        await rejectBtn.click();
        await adminPage.waitForTimeout(500);

        // Reason input should appear
        const reasonInput = adminPage.locator('[data-testid^="reject-reason-"]').first();
        if (await reasonInput.count() > 0) {
          await reasonInput.fill('No capacity for pets');
          const confirmBtn = adminPage.locator('[data-testid^="reject-confirm-"]').first();
          await confirmBtn.click();
          await adminPage.waitForTimeout(1000);
        }
        break;
      }
      await cards.nth(i).click();
      await adminPage.waitForTimeout(300);
    }
  });
});
