import { test, expect } from '../fixtures/auth.fixture';
import { setupTestReferral } from '../helpers/dv-escalation-helpers';

/**
 * T-45 — coc-admin-escalation reassign spec.
 *
 * <p>Verifies the COORDINATOR_GROUP reassign happy path AND the disclosure
 * pattern (Advanced expander) for SPECIFIC_USER. The actual chain-broken
 * + chain-resume semantics are exercised exhaustively in the backend
 * `ReassignTest`; this spec verifies the UI wires the request correctly.</p>
 *
 * Run: <code>BASE_URL=http://localhost:8081 npx playwright test
 * coc-escalation-reassign --trace on</code>
 */
test.describe('coc-admin-escalation: reassign sub-modal', () => {

  test('T-45a: COORDINATOR_GROUP reassign with reason completes successfully', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('reassign-coord-group');

    await coordinatorPage.goto('/admin#dvEscalations');
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    // Open detail modal → Reassign button
    await row.click();
    const modal = coordinatorPage.locator('[data-testid="dv-escalation-detail-modal"]');
    await expect(modal).toBeVisible();

    const reassignButton = coordinatorPage.locator('[data-testid="dv-escalation-detail-reassign"]');
    await reassignButton.click();

    // Reassign sub-modal opens at z-index 1100
    const subModal = coordinatorPage.locator('[data-testid="dv-escalation-reassign-modal"]');
    await expect(subModal).toBeVisible();

    // Default selection is COORDINATOR_GROUP — verify the radio is checked.
    const coordRadio = coordinatorPage.locator('[data-testid="dv-escalation-reassign-coordinator-group"]');
    await expect(coordRadio).toBeChecked();

    // Type a reason. Use a recognizable string so we can later assert
    // (in the backend tests) that it appears in the audit row but NOT in
    // the broadcast notification payload — the Keisha Thompson PII
    // reduction lock-in.
    const reasonInput = coordinatorPage.locator('[data-testid="dv-escalation-reassign-reason"]');
    await reasonInput.fill('Coordinator group should pick this up');

    // Submit and wait for the POST 200.
    const submitButton = coordinatorPage.locator('[data-testid="dv-escalation-reassign-submit"]');
    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes(`/api/v1/dv-referrals/${referralId}/reassign`) && resp.status() === 200,
      ),
      submitButton.click(),
    ]);

    // The sub-modal should auto-close after onSuccess. The detail modal
    // stays open and reflects the refreshed referral state.
    await expect(subModal).not.toBeVisible({ timeout: 5000 });
  });

  test('T-45b: SPECIFIC_USER option is gated behind an Advanced disclosure (NOT visible by default)', async ({ coordinatorPage }) => {
    const { referralId } = await setupTestReferral('reassign-specific-disclosure');

    await coordinatorPage.goto('/admin#dvEscalations');
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    await row.click();
    await coordinatorPage.locator('[data-testid="dv-escalation-detail-reassign"]').click();
    const subModal = coordinatorPage.locator('[data-testid="dv-escalation-reassign-modal"]');
    await expect(subModal).toBeVisible();

    // The SPECIFIC_USER radio is INSIDE a <details> element that defaults
    // to closed. The radio exists in the DOM but is hidden until the user
    // expands the disclosure — D20 conformance with archived
    // sse-notifications spec (disclosure pattern, not menu pattern).
    const specificUserRadio = coordinatorPage.locator('[data-testid="dv-escalation-reassign-specific-user"]');

    // Verify the disclosure exists and is collapsed by default.
    const advancedDetails = coordinatorPage.locator('[data-testid="dv-escalation-reassign-advanced"]');
    await expect(advancedDetails).toBeVisible();
    // <details> has an `open` attribute when expanded — should not be present.
    await expect(advancedDetails).not.toHaveAttribute('open', '');

    // Click the summary to expand. The radio becomes interactable.
    const summary = advancedDetails.locator('summary');
    await summary.click();

    // Now the SPECIFIC_USER radio should be visible (hit-testable).
    await expect(specificUserRadio).toBeVisible({ timeout: 2000 });
  });

  test('T-45c: PII warning is visible above the reason field', async ({ coordinatorPage }) => {
    // Marcus Webb + Keisha Thompson PII discipline: the reason field MUST
    // display the warning text BEFORE the user types. Backend stores
    // reason verbatim in the audit row but DELIBERATELY omits it from
    // the broadcast notification payload — the modal is the only PII
    // checkpoint.
    const { referralId } = await setupTestReferral('reassign-pii-warning');

    await coordinatorPage.goto('/admin#dvEscalations');
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    await row.click();
    await coordinatorPage.locator('[data-testid="dv-escalation-detail-reassign"]').click();
    const subModal = coordinatorPage.locator('[data-testid="dv-escalation-reassign-modal"]');
    await expect(subModal).toBeVisible();

    // The PII warning text from the i18n key dvEscalations.modal.piiWarning
    // should appear above the reason textarea.
    const submodalText = await subModal.textContent() || '';
    expect(submodalText).toMatch(/do not include client names|no incluya nombres/i);
    expect(submodalText).toMatch(/audit trail|rastro de auditor/i);
  });
});
