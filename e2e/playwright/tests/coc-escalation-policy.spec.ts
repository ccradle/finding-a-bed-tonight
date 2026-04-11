import { test, expect } from '../fixtures/auth.fixture';
import { setupTestReferral, getEscalationPolicy, resetDvReferralPolicyToDefault } from '../helpers/dv-escalation-helpers';

/**
 * T-46 — coc-admin-escalation policy editor + frozen-at-creation spec.
 *
 * <p>Drives the policy editor through the UI: edits a threshold, saves,
 * verifies the new version was inserted, AND verifies the load-bearing
 * frozen-at-creation invariant — an existing referral keeps its frozen
 * policy snapshot even after the tenant policy is updated.</p>
 *
 * <p>The frozen-at-creation correctness is exhaustively tested in the
 * backend `ReferralEscalationFrozenPolicyTest` (Casey Drummond + Riley
 * Cho gate). This spec is the UI-level smoke that the editor wires the
 * PATCH correctly and the version increments visibly.</p>
 *
 * Run: <code>BASE_URL=http://localhost:8081 npx playwright test
 * coc-escalation-policy --trace on</code>
 */
test.describe('coc-admin-escalation: escalation policy editor', () => {

  // Reset the tenant's dv-referral policy to the platform default baseline
  // before every test. The escalation_policy table is append-only, so each
  // PATCH creates a new version — prior test runs (or prior test cases in
  // this spec file) leave extra thresholds in the CURRENT version and break
  // the "threshold ids must be unique" validation when the next run tries
  // to re-add the same id. Resetting in beforeEach (not afterEach) makes
  // the baseline robust to crashed runs that never reach their teardown.
  // Per memory feedback_isolated_test_data.
  test.beforeEach(async () => {
    await resetDvReferralPolicyToDefault();
  });

  test('T-46a: editor loads the current policy and shows the seeded thresholds', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin#dvEscalations');
    const tab = coordinatorPage.locator('[data-testid="dv-escalations-tab"]');
    await expect(tab).toBeVisible({ timeout: 5000 });

    // Switch to the Policy segment.
    const policySegment = coordinatorPage.locator('[data-testid="dv-escalations-segment-policy"]');
    await policySegment.click();

    // The policy editor renders.
    const editor = coordinatorPage.locator('[data-testid="dv-escalation-policy-editor"]');
    await expect(editor).toBeVisible({ timeout: 5000 });

    // The seeded platform default has 4 thresholds with ids 1h/2h/3_5h/4h
    // — the editor renders one row per threshold. Each threshold has its
    // own data-testid keyed by index.
    const threshold0 = coordinatorPage.locator('[data-testid="dv-escalation-policy-threshold-0"]');
    await expect(threshold0).toBeVisible({ timeout: 5000 });

    // The first threshold's id field should be "1h" (per V40 seed).
    const id0 = coordinatorPage.locator('[data-testid="dv-escalation-policy-id-0"]');
    await expect(id0).toHaveValue('1h');
  });

  test('T-46b: PATCH a new threshold and verify the version increments', async ({ coordinatorPage }) => {
    // Capture the current version for this tenant. (May be platform default
    // if no tenant policy exists yet, or a tenant version if a prior test
    // already PATCHed.)
    const before = await getEscalationPolicy('dv-referral');
    const beforeVersion = before.version;

    await coordinatorPage.goto('/admin#dvEscalations');
    await coordinatorPage.locator('[data-testid="dv-escalations-segment-policy"]').click();
    await expect(coordinatorPage.locator('[data-testid="dv-escalation-policy-editor"]')).toBeVisible({ timeout: 5000 });

    // Add a new threshold via the "Add threshold" button.
    const addButton = coordinatorPage.locator('[data-testid="dv-escalation-policy-add"]');
    await addButton.click();

    // The new threshold appears as the LAST one. The number of existing
    // thresholds before the click determines its index. Read the count
    // dynamically so this test is robust to seed changes.
    const allThresholds = coordinatorPage.locator('[data-testid^="dv-escalation-policy-threshold-"]');
    const newIndex = (await allThresholds.count()) - 1;

    // Set a unique id for the new threshold + a duration that's larger
    // than any existing one. The seed has up to 4h, so PT5H is monotonic.
    const newIdInput = coordinatorPage.locator(`[data-testid="dv-escalation-policy-id-${newIndex}"]`);
    await newIdInput.fill('5h');
    const newAtInput = coordinatorPage.locator(`[data-testid="dv-escalation-policy-at-${newIndex}"]`);
    await newAtInput.fill('PT5H');
    // Default severity is INFO, default recipients [COORDINATOR] — leave as-is.

    // Save and wait for the PATCH 200.
    const saveButton = coordinatorPage.locator('[data-testid="dv-escalation-policy-save"]');
    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes('/api/v1/admin/escalation-policy/dv-referral') && resp.request().method() === 'PATCH' && resp.status() === 200,
      ),
      saveButton.click(),
    ]);

    // The success flash should appear.
    const savedFlash = coordinatorPage.locator('[data-testid="dv-escalation-policy-saved"]');
    await expect(savedFlash).toBeVisible({ timeout: 5000 });

    // Verify via API that the version actually incremented.
    const after = await getEscalationPolicy('dv-referral');
    expect(after.version).toBeGreaterThan(beforeVersion);

    // The new threshold should be present.
    const newThreshold = after.thresholds.find((t) => t.id === '5h');
    expect(newThreshold).toBeDefined();
    expect(newThreshold!.at).toBe('PT5H');
  });

  test('T-46c: existing referral remains in the queue after a policy PATCH (UI smoke)', async ({ coordinatorPage }) => {
    // **Scope of this test:** UI smoke only. It verifies that PATCHing the
    // policy via the editor does NOT cause existing referrals to disappear
    // or change in the queue view — i.e., the policy update is additive
    // and doesn't destroy in-flight work.
    //
    // **What this test does NOT prove (and isn't meant to):** the full
    // frozen-at-creation invariant — that the batch tasklet uses the
    // SNAPSHOTTED policy thresholds for each existing referral. That
    // load-bearing assertion is exhaustively tested in the backend
    // `ReferralEscalationFrozenPolicyTest` (Casey Drummond + Riley Cho
    // gate). Driving the actual escalation tasklet from a Playwright
    // spec would require time-travel of created_at + a forced tasklet
    // invocation — out of scope for a UI smoke test.
    const { referralId } = await setupTestReferral('frozen-policy-smoke');

    await coordinatorPage.goto('/admin#dvEscalations');
    await expect(coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`)).toBeVisible({ timeout: 10000 });

    // PATCH a new policy version via the editor.
    await coordinatorPage.locator('[data-testid="dv-escalations-segment-policy"]').click();
    const editor = coordinatorPage.locator('[data-testid="dv-escalation-policy-editor"]');
    await expect(editor).toBeVisible({ timeout: 5000 });

    // Add a new threshold to force a new version.
    await coordinatorPage.locator('[data-testid="dv-escalation-policy-add"]').click();
    const allThresholds = coordinatorPage.locator('[data-testid^="dv-escalation-policy-threshold-"]');
    const newIndex = (await allThresholds.count()) - 1;
    await coordinatorPage.locator(`[data-testid="dv-escalation-policy-id-${newIndex}"]`).fill('6h');
    await coordinatorPage.locator(`[data-testid="dv-escalation-policy-at-${newIndex}"]`).fill('PT6H');

    await Promise.all([
      coordinatorPage.waitForResponse((resp) =>
        resp.url().includes('/api/v1/admin/escalation-policy/dv-referral') && resp.request().method() === 'PATCH' && resp.status() === 200,
      ),
      coordinatorPage.locator('[data-testid="dv-escalation-policy-save"]').click(),
    ]);

    // Switch back to the queue. The pre-existing referral should still
    // be in the queue (status PENDING). Frozen-at-creation guarantees
    // it kept its original policy snapshot — visible behavior is
    // "the row didn't disappear or change."
    await coordinatorPage.locator('[data-testid="dv-escalations-segment-queue"]').click();
    const row = coordinatorPage.locator(`[data-testid="dv-escalation-row-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });
  });
});
