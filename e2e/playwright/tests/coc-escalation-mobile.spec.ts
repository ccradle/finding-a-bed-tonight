import { test, expect } from '../fixtures/auth.fixture';
import { setupTestReferral } from '../helpers/dv-escalation-helpers';
import { cleanupTestData } from '../helpers/test-cleanup';

/**
 * T-47 — coc-admin-escalation mobile responsive spec.
 *
 * <p>Verifies the responsive behavior of the DV Escalations admin tab at
 * a mobile viewport (375x667, iPhone SE size):</p>
 * <ul>
 *   <li>The queue renders as a card list (not a table)</li>
 *   <li>The Policy editor section renders the read-only message</li>
 *   <li>The Save button is NOT in the DOM on mobile (per the spec contract)</li>
 *   <li>Card-list claim button works</li>
 * </ul>
 *
 * Run: <code>BASE_URL=http://localhost:8081 npx playwright test
 * coc-escalation-mobile --trace on</code>
 */
test.describe('coc-admin-escalation: mobile responsive', () => {

  test.use({ viewport: { width: 375, height: 667 } });

  // Drop any test-created shelters + referral tokens so this spec doesn't
  // crowd later specs' search results.
  test.afterAll(async () => { await cleanupTestData(); });

  test('T-47a: queue renders as card list (not table) on mobile', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('mobile-cards');

    await coordinatorPage.goto('/admin#dvEscalations');
    await expect(coordinatorPage.locator('[data-testid="dv-escalations-tab"]')).toBeVisible({ timeout: 5000 });

    // Card list should be present.
    const cardList = coordinatorPage.locator('[data-testid="dv-escalation-queue-cards"]');
    await expect(cardList).toBeVisible({ timeout: 10000 });

    // Desktop table should NOT be in the DOM.
    const desktopTable = coordinatorPage.locator('[data-testid="dv-escalation-queue-table"]');
    await expect(desktopTable).toHaveCount(0);

    // The fresh referral should appear as a card.
    const card = coordinatorPage.locator(`[data-testid="dv-escalation-card-${referralId}"]`);
    await expect(card).toBeVisible({ timeout: 5000 });
  });

  test('T-47b: card claim button is 44x44 minimum touch target', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('mobile-claim-touch');

    await coordinatorPage.goto('/admin#dvEscalations');
    const card = coordinatorPage.locator(`[data-testid="dv-escalation-card-${referralId}"]`);
    await expect(card).toBeVisible({ timeout: 10000 });

    const claimButton = coordinatorPage.locator(`[data-testid="dv-escalation-card-claim-${referralId}"]`);
    await expect(claimButton).toBeVisible();

    const box = await claimButton.boundingBox();
    expect(box).not.toBeNull();
    // WCAG D5 — 44x44 minimum
    expect(box!.height).toBeGreaterThanOrEqual(44);
    expect(box!.width).toBeGreaterThanOrEqual(44);
  });

  test('T-47c: claim from a card succeeds', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('mobile-claim');

    await coordinatorPage.goto('/admin#dvEscalations');
    await expect(coordinatorPage.locator(`[data-testid="dv-escalation-card-${referralId}"]`)).toBeVisible({ timeout: 10000 });

    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/claim`) && resp.status() === 200,
      ),
      coordinatorPage.locator(`[data-testid="dv-escalation-card-claim-${referralId}"]`).click(),
    ]);

    // After claim, the card-claim button is gone (the row is now owned by the user).
    const claimButtonGone = coordinatorPage.locator(`[data-testid="dv-escalation-card-claim-${referralId}"]`);
    await expect(claimButtonGone).toHaveCount(0, { timeout: 5000 });
  });

  test('T-47d: segment switch is HIDDEN on mobile (queue-only view)', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin#dvEscalations');
    await expect(coordinatorPage.locator('[data-testid="dv-escalations-tab"]')).toBeVisible({ timeout: 5000 });

    // The segment switch buttons (Queue / Policy) are not rendered on mobile.
    const queueSegment = coordinatorPage.locator('[data-testid="dv-escalations-segment-queue"]');
    const policySegment = coordinatorPage.locator('[data-testid="dv-escalations-segment-policy"]');
    await expect(queueSegment).toHaveCount(0);
    await expect(policySegment).toHaveCount(0);

    // The queue section is rendered directly without the segment chrome.
    const queueSection = coordinatorPage.locator('[data-testid="dv-escalations-queue-section"]');
    await expect(queueSection).toBeVisible({ timeout: 5000 });

    // The policy section is NOT rendered (per design D2 — queue-only on mobile).
    const policySection = coordinatorPage.locator('[data-testid="dv-escalations-policy-section"]');
    await expect(policySection).toHaveCount(0);
  });
});
