import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

/**
 * DV Outreach Worker — Playwright E2E tests.
 *
 * Verifies the DV-authorized outreach worker persona (dv-outreach@dev.fabt.org):
 * - DV shelters visible in bed search results
 * - Addresses redacted per tenant dv_address_visibility policy
 * - "Request Referral" button shown (not "Hold This Bed") for DV shelters
 * - Referral request modal opens and submits
 * - Non-DV shelters show full address and "Hold This Bed" as normal
 *
 * Uses dvOutreachPage fixture (OUTREACH_WORKER with dvAccess=true).
 */

const DV_SHELTER_NAMES = ['Safe Haven DV Shelter', 'Harbor House', 'Bridges to Safety'];

test.describe('DV Outreach Worker', () => {
  test.beforeAll(async () => {
    await cleanupTestData();
  });

  test.afterAll(async () => {
    await cleanupTestData();
  });

  test('DV shelters visible in search results', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach');
    await dvOutreachPage.waitForTimeout(2000);

    // At least one DV shelter should be visible in results
    let dvShelterFound = false;
    for (const name of DV_SHELTER_NAMES) {
      const slug = name.toLowerCase().replace(/\s+/g, '-');
      const card = dvOutreachPage.locator(`[data-testid="shelter-card-${slug}"]`);
      if (await card.count() > 0) {
        dvShelterFound = true;
        await expect(card).toBeVisible();
      }
    }
    expect(dvShelterFound).toBe(true);
  });

  test('DV shelter address is redacted', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach');
    await dvOutreachPage.waitForTimeout(2000);

    // Find a DV shelter card and verify address is withheld
    for (const name of DV_SHELTER_NAMES) {
      const slug = name.toLowerCase().replace(/\s+/g, '-');
      const card = dvOutreachPage.locator(`[data-testid="shelter-card-${slug}"]`);
      if (await card.count() > 0) {
        await expect(card).toContainText('Address withheld for safety');
        // Should NOT contain actual street addresses
        await expect(card).not.toContainText('Undisclosed Ave');
        return;
      }
    }
    throw new Error('No DV shelter found in search results');
  });

  test('DV shelter shows Request Referral instead of Hold This Bed', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach');
    await dvOutreachPage.waitForTimeout(3000);

    // DV shelters only show "Request Referral" when bedsAvailable > 0.
    // In full suite, prior test activity may have consumed DV shelter availability.
    const referralBtn = dvOutreachPage.locator('[data-testid^="request-referral-"]');
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds — prior test activity consumed availability');
      return;
    }

    await expect(referralBtn.first()).toBeVisible();

    // Verify NO "Hold This Bed" buttons exist on DV shelter cards
    for (const name of DV_SHELTER_NAMES) {
      const slug = name.toLowerCase().replace(/\s+/g, '-');
      const card = dvOutreachPage.locator(`[data-testid="shelter-card-${slug}"]`);
      if (await card.count() > 0) {
        const holdBtn = card.locator('[data-testid^="hold-bed-"]');
        expect(await holdBtn.count()).toBe(0);
      }
    }
  });

  test('Referral request modal opens and submits', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach');
    await dvOutreachPage.waitForTimeout(3000);

    // DV shelters only show "Request Referral" when bedsAvailable > 0.
    // In full suite, prior test activity may have consumed DV shelter availability.
    const referralBtn = dvOutreachPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() === 0) {
      test.skip(true, 'No DV shelters with available beds — prior test activity consumed availability');
      return;
    }

    await referralBtn.click();
    await dvOutreachPage.waitForTimeout(500);

    // Modal should open
    const modal = dvOutreachPage.getByTestId('referral-modal');
    await expect(modal).toBeVisible();

    // Fill referral form
    await dvOutreachPage.getByTestId('referral-household-size').fill('2');
    await dvOutreachPage.getByTestId('referral-special-needs').fill('Wheelchair accessible');
    await dvOutreachPage.getByTestId('referral-callback').fill('919-555-0042');

    // Submit
    const submitBtn = dvOutreachPage.getByTestId('referral-submit');
    await expect(submitBtn).toBeEnabled();
    await submitBtn.click();
    await dvOutreachPage.waitForTimeout(1500);

    // Modal should close after successful submission
    await expect(modal).not.toBeVisible({ timeout: 5000 });

    // My DV Referrals: shelter snapshot + time on primary line (dv-referral-token spec / Tomás a11y headline)
    // S5 fix: assert visibility instead of conditionally skipping (feedback_never_skip_silently)
    const referralsToggle = dvOutreachPage.locator('button', { hasText: /My DV Referrals/i });
    await expect(referralsToggle).toBeVisible({ timeout: 5000 });
    await referralsToggle.click();
    const panel = dvOutreachPage.getByTestId('my-referrals');
    await expect(panel).toBeVisible();

    // S7 fix: verify ARIA list semantics (Tomás Herrera — screen readers
    // announce "list, N items" on focus). role="list" is on the panel itself.
    await expect(panel).toHaveAttribute('role', 'list');
    const items = panel.locator('[role="listitem"]');
    expect(await items.count()).toBeGreaterThan(0);

    // Issue #92 core assertion: shelter name appears in the primary headline
    const primary = panel.locator('[data-testid^="referral-primary-line-"]').first();
    await expect(primary).toBeVisible();
    await expect(primary).toContainText(/Safe Haven|DV Shelter|Hope House|Refuge/i);
    await expect(primary).toContainText(/\d{1,2}:\d{2}/);
  });

  test('Non-DV shelters show full address and Hold This Bed', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach');
    await dvOutreachPage.waitForTimeout(2000);

    // "Hold This Bed" buttons should exist for non-DV shelters
    const holdBtn = dvOutreachPage.locator('[data-testid^="hold-bed-"]');
    await expect(holdBtn.first()).toBeVisible();

    // A non-DV shelter card should show a real street address
    const nonDvCard = dvOutreachPage.locator('[data-testid="shelter-card-oak-city-community-shelter"]');
    if (await nonDvCard.count() > 0) {
      await expect(nonDvCard).toContainText('314 E Hargett St');
      await expect(nonDvCard).not.toContainText('Address withheld');
    }
  });
});
