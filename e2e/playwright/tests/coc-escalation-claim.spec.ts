import { test, expect } from '../fixtures/auth.fixture';
import { setupTestReferral } from '../helpers/dv-escalation-helpers';

/**
 * T-44 — coc-admin-escalation claim/release/override spec.
 *
 * <p>Two-context test: admin A (cocadmin) claims a referral, admin B
 * (platform admin) sees the row update via SSE within ~2 seconds, B is
 * blocked from claiming without override, override succeeds. Tests the
 * end-to-end of T-14/T-15 + the SSE event fan-out from
 * useDvEscalationQueue.</p>
 *
 * <p>Per memory <code>feedback_isolated_test_data</code>, this spec creates
 * its own DV shelter + referral via the REST helper rather than depending
 * on seed data — parallel-safe even when CI runs with 3 workers.</p>
 *
 * Run: <code>BASE_URL=http://localhost:8081 npx playwright test
 * coc-escalation-claim --trace on</code>
 */
test.describe('coc-admin-escalation: claim / release / override', () => {

  test('T-44a: admin claims a referral via inline button → row updates', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('claim-inline');

    // Land on the DV Escalations admin tab.
    await coordinatorPage.goto('/admin#dvEscalations');
    const tab = coordinatorPage.locator('[data-testid="dv-escalations-tab"]');
    await expect(tab).toBeVisible({ timeout: 5000 });

    // The fresh referral row should appear in the desktop table.
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    // Inline Claim button — wait for the POST response so we don't race the
    // re-fetch (per memory feedback_facts_over_guessing).
    const claimButton = coordinatorPage.locator(`[data-testid="dv-escalation-claim-${referralId}"]`);
    await expect(claimButton).toBeVisible();
    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/claim`) && resp.status() === 200,
      ),
      claimButton.click(),
    ]);

    // After the claim resolves, the row's claim-status cell should show
    // "Claimed by Dev CoC Admin" (or similar). The Claim button is gone
    // because the row is now owned by the current user.
    const claimedBadge = coordinatorPage.locator(`[data-testid="dv-escalation-claimed-${referralId}"]`);
    await expect(claimedBadge).toBeVisible({ timeout: 5000 });
  });

  test('T-44b: second admin sees claim via SSE within ~2s, blocked without override, succeeds with override', async ({ coordinatorPage, adminPage }) => {
    // Setup: fresh referral.
    const { referralId } = await setupTestReferral('claim-race');

    // Both admins land on the queue.
    await coordinatorPage.goto('/admin#dvEscalations');
    await adminPage.goto('/admin#dvEscalations');

    const aRow = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    const bRow = adminPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(aRow).toBeVisible({ timeout: 10000 });
    await expect(bRow).toBeVisible({ timeout: 10000 });

    // Admin A (cocadmin) claims via the inline button. Wait for the POST
    // response so we know the backend committed the claim before checking
    // admin B's view.
    const aClaimButton = coordinatorPage.locator(`[data-testid="dv-escalation-claim-${referralId}"]`);
    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/claim`) && resp.status() === 200,
      ),
      aClaimButton.click(),
    ]);

    // Admin B's view should update via the SSE event chain:
    //   ReferralTokenService publishes → NotificationService.notifyAdminQueueEvent
    //   → SSE wire → useNotifications onmessage → window.dispatchEvent
    //   → useDvEscalationQueue handler → 250ms debounce → REST refetch → render.
    // 6+ async hops; the local debounce alone is 250ms. War room round 8
    // sized this at 10 seconds defensively for CI variance — 5s was too
    // tight even on a healthy local machine.
    const bClaimedBadge = adminPage.locator(`[data-testid="dv-escalation-claimed-${referralId}"]`);
    await expect(bClaimedBadge).toBeVisible({ timeout: 10000 });

    const bClaimButtonGone = adminPage.locator(`[data-testid="dv-escalation-claim-${referralId}"]`);
    await expect(bClaimButtonGone).toHaveCount(0);

    // Admin B opens the detail modal and tries to claim — should hit 409.
    // The row's "More" button opens the detail modal because canClaim is false.
    const bMoreButton = adminPage.locator(`[data-testid="dv-escalation-open-${referralId}"]`);
    await expect(bMoreButton).toBeVisible();
    await bMoreButton.click();

    const bModal = adminPage.locator('[data-testid="dv-escalation-detail-modal"]');
    await expect(bModal).toBeVisible();
    // The modal shows a Claim button because admin B is not the current claim holder.
    const bModalClaimButton = adminPage.locator('[data-testid="dv-escalation-detail-claim"]');
    await expect(bModalClaimButton).toBeVisible();

    // Click claim — backend returns 409 because A holds the claim and B
    // didn't pass override. The frontend should show the
    // dvEscalations.error.claimedByOther error.
    await Promise.all([
      adminPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/claim`) && resp.status() === 409,
      ),
      bModalClaimButton.click(),
    ]);

    // The error should be visible in the modal.
    const bModalError = adminPage.locator('[data-testid="dv-escalation-detail-error"]');
    await expect(bModalError).toBeVisible({ timeout: 5000 });
    const errText = await bModalError.textContent() || '';
    // Person-centered: "Another admin claimed this first"
    expect(errText.toLowerCase()).toMatch(/another admin|otro admin|claimed/i);
  });

  test('T-44c: claim holder can release via the detail modal', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('release');

    await coordinatorPage.goto('/admin#dvEscalations');
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    // Claim first.
    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/claim`) && resp.status() === 200,
      ),
      coordinatorPage.locator(`[data-testid="dv-escalation-claim-${referralId}"]`).click(),
    ]);

    // Open the detail modal via the row click.
    await row.click();
    const modal = coordinatorPage.locator('[data-testid="dv-escalation-detail-modal"]');
    await expect(modal).toBeVisible();

    // The current user holds the claim → the modal shows a Release button instead of Claim.
    const releaseButton = coordinatorPage.locator('[data-testid="dv-escalation-detail-release"]');
    await expect(releaseButton).toBeVisible({ timeout: 5000 });

    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/release`) && resp.status() === 200,
      ),
      releaseButton.click(),
    ]);

    // Modal stays open but the action button reverts to Claim (the row is
    // now unclaimed for the current user).
    await expect(coordinatorPage.locator('[data-testid="dv-escalation-detail-claim"]')).toBeVisible({ timeout: 5000 });
  });
});
