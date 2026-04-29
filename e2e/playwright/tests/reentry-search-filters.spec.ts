import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';
import { createReentryShelter, loginAndGetToken } from './_helpers/reentry-fixtures';

/**
 * transitional-reentry-support slice 4 §14 — outreach search filter coverage.
 *
 * Covers §14.1 (shelter type filter), §14.2 (county filter), §14.7
 * (requires_verification_call badge), and §14.8 (mobile collapsed
 * advanced filters). All tests create their own data via API per
 * §14.11 + memory feedback_isolated_test_data.md — no seed dependency.
 *
 * Per memory feedback_data_testid.md every assertion uses a
 * data-testid selector (no CSS / text matching for the
 * load-bearing assertions). Per feedback_check_ports_before_assuming.md
 * the runner must set BASE_URL=http://localhost:8081 (nginx, not
 * bare Vite).
 */
test.describe('transitional-reentry-support §14 — outreach search filters', () => {
  // Cleanup BEFORE so accumulated leftover E2E shelters from prior runs
  // (or earlier specs in the same run) don't push the test shelters
  // outside the BedSearchService default 20-result window. The dev
  // test/reset endpoint is dev-profile-only and only deletes
  // `name LIKE 'E2E Test%'` rows — safe to call.
  test.beforeAll(async () => { await cleanupTestData(); });
  test.afterAll(async () => { await cleanupTestData(); });

  /**
   * The seed data has ~87 shelters; BedSearchService caps results at 20.
   * Every test in this describe filters to a specific population type
   * (YOUTH_18_24) to keep the test shelters dominant in the result set
   * without depending on cleanup ordering across parallel spec files.
   */
  const TEST_POP_TYPE = 'YOUTH_18_24';

  async function gotoOutreachAndNarrow(page: import('@playwright/test').Page) {
    await page.goto('/outreach');
    await page.locator('[data-testid="population-type-filter"]')
      .waitFor({ state: 'visible', timeout: 15000 });
    await page.locator('[data-testid="population-type-filter"]').selectOption(TEST_POP_TYPE);
  }

  test('§14.1 shelter type filter: selecting TRANSITIONAL hides EMERGENCY shelters', async ({ outreachPage }) => {
    const token = await loginAndGetToken();
    const ts = Date.now();
    const transitional = await createReentryShelter(token, {
      name: `E2E Test Reentry F1-T-${ts}`,
      shelterType: 'TRANSITIONAL',
      county: null,
      populationType: TEST_POP_TYPE,
    });
    const emergency = await createReentryShelter(token, {
      name: `E2E Test Reentry F1-E-${ts}`,
      shelterType: 'EMERGENCY',
      county: null,
      populationType: TEST_POP_TYPE,
    });

    await gotoOutreachAndNarrow(outreachPage);
    // Wait for results — at least the emergency one with our prefix renders by default
    await outreachPage.locator(`[data-testid="shelter-type-display-${emergency.id}"]`)
      .waitFor({ state: 'visible', timeout: 15000 });

    // Both shelter-type displays should be visible before filtering
    await expect(outreachPage.locator(`[data-testid="shelter-type-display-${transitional.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="shelter-type-display-${emergency.id}"]`)).toBeVisible();

    // Activate the TRANSITIONAL chip in advanced filters (open by default on desktop)
    const transChip = outreachPage.locator('[data-testid="shelter-type-filter-TRANSITIONAL"]');
    await expect(transChip).toBeVisible();
    await transChip.click();

    // Wait for the active-count badge to confirm the filter applied (re-fetch happens)
    await expect(outreachPage.locator('[data-testid="advanced-filters-active-count"]')).toBeVisible();

    // The transitional shelter must remain visible; the emergency one must disappear
    await expect(outreachPage.locator(`[data-testid="shelter-type-display-${transitional.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="shelter-type-display-${emergency.id}"]`)).toHaveCount(0);
  });

  test('§14.2 county filter: selecting filters; deselecting returns all', async ({ outreachPage }) => {
    const token = await loginAndGetToken();
    const ts = Date.now();
    const johnston = await createReentryShelter(token, {
      name: `E2E Test Reentry F2-J-${ts}`,
      shelterType: 'EMERGENCY',
      county: 'Johnston',
      populationType: TEST_POP_TYPE,
    });
    const wake = await createReentryShelter(token, {
      name: `E2E Test Reentry F2-W-${ts}`,
      shelterType: 'EMERGENCY',
      county: 'Wake',
      populationType: TEST_POP_TYPE,
    });

    await gotoOutreachAndNarrow(outreachPage);
    await outreachPage.locator(`[data-testid="county-display-${johnston.id}"]`)
      .waitFor({ state: 'visible', timeout: 15000 });

    // Both visible by default
    await expect(outreachPage.locator(`[data-testid="county-display-${johnston.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="county-display-${wake.id}"]`)).toBeVisible();

    // Apply county=Johnston
    const countyFilter = outreachPage.locator('[data-testid="county-filter"]');
    await expect(countyFilter).toBeVisible();
    await countyFilter.selectOption('Johnston');

    // Active-count badge confirms filter applied
    await expect(outreachPage.locator('[data-testid="advanced-filters-active-count"]')).toBeVisible();

    await expect(outreachPage.locator(`[data-testid="county-display-${johnston.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="county-display-${wake.id}"]`)).toHaveCount(0);

    // Deselect (empty option = "Any county") and verify both come back
    await countyFilter.selectOption('');
    await expect(outreachPage.locator(`[data-testid="county-display-${johnston.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="county-display-${wake.id}"]`)).toBeVisible();
  });

  test('§14.7 requires_verification_call badge: renders when true; absent when false', async ({ outreachPage }) => {
    const token = await loginAndGetToken();
    const ts = Date.now();
    const verifies = await createReentryShelter(token, {
      name: `E2E Test Reentry F7-V-${ts}`,
      shelterType: 'EMERGENCY',
      requiresVerificationCall: true,
      populationType: TEST_POP_TYPE,
    });
    const noVerify = await createReentryShelter(token, {
      name: `E2E Test Reentry F7-N-${ts}`,
      shelterType: 'EMERGENCY',
      requiresVerificationCall: false,
      populationType: TEST_POP_TYPE,
    });

    await gotoOutreachAndNarrow(outreachPage);
    await outreachPage.locator(`[data-testid="shelter-type-display-${verifies.id}"]`)
      .waitFor({ state: 'visible', timeout: 15000 });

    await expect(outreachPage.locator(`[data-testid="requires-verification-call-badge-${verifies.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="requires-verification-call-badge-${noVerify.id}"]`)).toHaveCount(0);
  });

  test.describe('§14.8 mobile viewport (412px)', () => {
    test.use({ viewport: { width: 412, height: 800 } });

    test('advanced filters: collapsing then expanding reveals shelter-type/county/accepts-felonies controls', async ({ outreachPage }) => {
      // §14.8 — at the time this spec was written, `OutreachSearch.tsx`
      // hard-codes `<details ... open>` so the filters are *always* open
      // by default on every viewport (desktop AND mobile). The slice-4
      // warroom M4 comment in the source explicitly says
      // "Open by default... mobile users can collapse with one tap."
      // The §14.8 task description ("collapsed by default on mobile")
      // contradicts the shipped behavior — flagged for follow-up.
      //
      // To still cover the load-bearing assertion (the filter content
      // is truly hidden when the <details> is collapsed and revealed
      // when expanded — i.e. the collapse mechanism actually works),
      // this test toggles collapsed → expanded and verifies all three
      // filter controls become visible after the expand step.
      const token = await loginAndGetToken();
      const ts = Date.now();
      await createReentryShelter(token, {
        name: `E2E Test Reentry F8-${ts}`,
        shelterType: 'EMERGENCY',
        county: 'Wake',
      });

      await outreachPage.goto('/outreach');
      await outreachPage.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

      const detailsEl = outreachPage.locator('[data-testid="advanced-filters"]');
      const toggle = outreachPage.locator('[data-testid="advanced-filters-toggle"]');
      await expect(detailsEl).toBeVisible();

      // Collapse first (defensive: works regardless of whether default is open or closed).
      const isOpen = await detailsEl.evaluate((el) => (el as HTMLDetailsElement).open);
      if (isOpen) {
        await toggle.click();
        await expect(detailsEl).not.toHaveAttribute('open', '');
      }

      // Confirm filter contents are hidden while collapsed.
      await expect(outreachPage.locator('[data-testid="shelter-type-filter"]')).toBeHidden();
      await expect(outreachPage.locator('[data-testid="county-filter"]')).toBeHidden();
      await expect(outreachPage.locator('[data-testid="accepts-felonies-filter"]')).toBeHidden();

      // Expand and confirm all three advanced filters become visible.
      await toggle.click();
      await expect(detailsEl).toHaveAttribute('open', '');
      await expect(outreachPage.locator('[data-testid="shelter-type-filter"]')).toBeVisible();
      await expect(outreachPage.locator('[data-testid="county-filter"]')).toBeVisible();
      await expect(outreachPage.locator('[data-testid="accepts-felonies-filter"]')).toBeVisible();
    });
  });
});
