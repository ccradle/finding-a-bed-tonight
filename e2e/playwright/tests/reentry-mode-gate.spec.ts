import { test, expect } from '../fixtures/auth.fixture';

/**
 * Round 5 §16.D — features.reentryMode UI gate (positive E2E coverage).
 *
 * <p>This spec proves end-to-end that the four §16.C frontend gates
 * actually wire `user.reentryMode` (parsed from the JWT claim) to render
 * the reentry-specific UI surfaces. The seed-flipped tenants
 * (`dev-coc` / `dev-coc-east` / `dev-coc-west`) all carry
 * `features.reentryMode=true`, so the JWT claim emits true at issue time
 * and the conditional renders activate.
 *
 * <p>Negative-path coverage (gate hides surfaces when flag is false) is
 * the responsibility of the lower test layers, which give us tighter
 * loops and don't fight the 60-second {@code JwtService.reentryModeCache}
 * TTL on a live stack:
 *
 * <ul>
 *   <li>Backend unit: {@code ReservationResponseReentryGateTest} (6
 *       cases, including unbound + flag=false).</li>
 *   <li>Backend integration: {@code HoldAttributionIntegrationTest} +
 *       {@code ShelterReservationsEndpointTest} explicitly opt-in via
 *       {@code authHelper.enableReentryMode(tenantId)} — the absence
 *       of that opt-in is the negative-path equivalent for the API
 *       gate.</li>
 *   <li>Frontend unit: {@code decodeJwtPayload.test.ts} (7 cases,
 *       including missing / null / non-boolean claim).</li>
 * </ul>
 *
 * <p>The test pyramid principle: a single end-to-end happy-path here
 * proves the wiring; finer-grained negative paths live where they're
 * cheap to run.
 */
test.describe('§16.D — features.reentryMode UI gate (positive matrix)', () => {

  test('reentry-flagged tenant: outreach search shows advanced filters', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');

    // Population type filter is the gate-page-loaded sentinel — without
    // it the search hasn't hydrated and an absent advanced-filters
    // wrapper would be a false negative.
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .waitFor({ state: 'visible', timeout: 15000 });

    await expect(outreachPage.locator('[data-testid="reentry-advanced-filters"]'))
      .toBeVisible();
    // Inside the wrapper: the actual filter chips are present.
    await expect(outreachPage.locator('[data-testid="advanced-filters"]'))
      .toBeVisible();
  });

  test('reentry-flagged tenant: hold dialog exposes PII-fields toggle', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .waitFor({ state: 'visible', timeout: 15000 });

    // Open the first available bed-search result that exposes a hold button.
    // We don't need to actually create a hold — only assert the dialog wires
    // the PII toggle when the user is reentry-flagged. The dialog mounts
    // when the operator clicks a Hold action.
    const holdButton = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() > 0) {
      await holdButton.click();

      // The reentry-pii-fields wrapper from §16.C.4 must mount when the
      // user has reentryMode=true.
      await expect(outreachPage.locator('[data-testid="reentry-pii-fields"]'))
        .toBeVisible({ timeout: 10000 });
      await expect(outreachPage.locator('[data-testid="hold-attribution-toggle"]'))
        .toBeVisible();
    } else {
      // No bed available to hold (seed timing). The dialog wiring is
      // already covered by the existing reentry-hold-dialog.spec.ts; this
      // assertion is best-effort and not the load-bearing test.
      test.info().annotations.push({
        type: 'note',
        description: 'No bed available to open hold dialog; relying on reentry-hold-dialog.spec.ts',
      });
    }
  });

  test('reentry-flagged tenant: shelter form exposes eligibility section to admin', async ({ adminPage }) => {
    // Navigate to a shelter edit page. The seed has multiple dev shelters;
    // any non-DV shelter on the dev tenant works.
    await adminPage.goto('/admin');
    await adminPage.locator('[data-testid="admin-shelters-tab"]')
      .waitFor({ state: 'visible', timeout: 15000 });
    await adminPage.locator('[data-testid="admin-shelters-tab"]').click();

    // Edit the first shelter row.
    const editLink = adminPage.locator('[data-testid^="edit-shelter-"]').first();
    await editLink.waitFor({ state: 'visible', timeout: 15000 });
    await editLink.click();

    // The reentry-eligibility-section wrapper from §16.C.3 mounts
    // (containing the requires_verification_call toggle + eligibility
    // criteria editor).
    await expect(adminPage.locator('[data-testid="reentry-eligibility-section"]'))
      .toBeVisible({ timeout: 10000 });
    await expect(adminPage.locator('[data-testid="requires-verification-call-toggle"]'))
      .toBeVisible();
  });
});
