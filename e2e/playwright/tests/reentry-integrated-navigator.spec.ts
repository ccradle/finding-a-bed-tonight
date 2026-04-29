import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';
import {
  createReentryShelter,
  createTestCoordinator,
  loginAndGetToken,
  TENANT,
} from './_helpers/reentry-fixtures';

/**
 * transitional-reentry-support slice 4 §14.12 — the Demetrius scenario:
 * the full chain from outreach worker filtering on the three slice 4
 * advanced filters → opening the hold dialog with attribution →
 * coordinator dashboard verification of the client name.
 *
 * <p>§14.12 explicitly says "completes the full three-filter →
 * hold-with-attribution → coordinator-view chain" — this spec is the
 * end-to-end integration that the per-feature specs slice apart.
 *
 * <p>§14.12a (M4 warroom 2026-04-28) suggested adding a static seed
 * fixture to {@code infra/scripts/seed-data.sql}. Per §14.11 we
 * intentionally do NOT take that path — every test creates its own
 * data via the public REST API. This is also more honest: the seed
 * file would need conditional-flag-gated rows so the 24h PII purge
 * doesn't delete them, and the API-driven setup proves the operator
 * flow for real.
 */
test.describe('transitional-reentry-support §14.12 — integrated navigator end-to-end', () => {
  // Cleanup BEFORE so accumulated leftover E2E shelters from prior
  // failed runs don't push our just-created shelter outside the
  // BedSearchService default 20-result window.
  test.beforeAll(async () => { await cleanupTestData(); });
  test.afterAll(async () => { await cleanupTestData(); });

  test('Demetrius scenario: outreach filters by Johnston + REENTRY_TRANSITIONAL + acceptsFelonies, places hold with attribution, coordinator sees client name', async ({ outreachPage, browser }) => {
    const adminToken = await loginAndGetToken();
    const ts = Date.now();

    // 1) Create the integrated-navigator shelter:
    //    - county=Johnston (in NC default counties — no tenant config needed)
    //    - shelterType=REENTRY_TRANSITIONAL
    //    - eligibility_criteria.criminal_record_policy.accepts_felonies=true
    //    - YOUTH_18_24 population so the test shelter dominates the
    //      filtered result set (sidesteps BedSearchService default
    //      limit of 20 — most seed shelters serve SINGLE_ADULT or
    //      FAMILY_WITH_CHILDREN).
    const shelter = await createReentryShelter(adminToken, {
      name: `E2E Test Reentry NavScenario ${ts}`,
      shelterType: 'REENTRY_TRANSITIONAL',
      county: 'Johnston',
      requiresVerificationCall: false,
      populationType: 'YOUTH_18_24',
      bedsTotal: 5,
      bedsOccupied: 0,
      eligibilityCriteria: {
        criminal_record_policy: {
          accepts_felonies: true,
          vawa_protections_apply: false,
        },
        program_requirements: ['sobriety'],
      },
    });

    // Coordinator who will see the hold on their dashboard.
    const coord = await createTestCoordinator(adminToken, shelter.id);

    // 2) Outreach worker applies the THREE slice 4 advanced filters:
    //    county=Johnston, shelterType=REENTRY_TRANSITIONAL, acceptsFelonies=true.
    await outreachPage.goto('/outreach');
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .waitFor({ state: 'visible', timeout: 15000 });
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .selectOption(shelter.populationType);

    // Open advanced filters if collapsed (mobile-default is open per the
    // current shipped UI; the toggle is a no-op when already open
    // because we click summary which toggles. Use a more defensive
    // approach: ensure open by reading the `open` attr first.)
    const detailsEl = outreachPage.locator('[data-testid="advanced-filters"]');
    const isOpen = await detailsEl.evaluate((el) => (el as HTMLDetailsElement).open);
    if (!isOpen) {
      await outreachPage.locator('[data-testid="advanced-filters-toggle"]').click();
    }

    await outreachPage.locator('[data-testid="shelter-type-filter-REENTRY_TRANSITIONAL"]').click();
    await outreachPage.locator('[data-testid="county-filter"]').selectOption('Johnston');
    await outreachPage.locator('[data-testid="accepts-felonies-filter"]').click();

    // The active-count badge should read 3 once all three filters land.
    await expect(outreachPage.locator('[data-testid="advanced-filters-active-count"]')).toContainText('3');

    // The shelter's type display + county display + hold button must all be visible.
    await expect(outreachPage.locator(`[data-testid="shelter-type-display-${shelter.id}"]`)).toBeVisible();
    await expect(outreachPage.locator(`[data-testid="county-display-${shelter.id}"]`)).toBeVisible();

    const holdButton = outreachPage.locator(`[data-testid="hold-bed-${shelter.id}-${shelter.populationType}"]`);
    await holdButton.waitFor({ state: 'visible', timeout: 15000 });

    // 3) Place a hold WITH attribution (name + DOB + notes).
    await holdButton.click();
    const dialog = outreachPage.locator('[data-testid="hold-dialog"]');
    await expect(dialog).toBeVisible();

    // Open attribution <details>
    await outreachPage.locator('[data-testid="hold-attribution-toggle"] summary').click();
    await expect(outreachPage.locator('[data-testid="hold-attribution-privacy-note"]')).toBeVisible();

    const clientName = `E2E Demetrius Carter ${ts}`;
    await outreachPage.locator('[data-testid="hold-client-name-input"]').fill(clientName);
    await outreachPage.locator('[data-testid="hold-client-dob-input"]').fill('1985-04-12');
    await outreachPage.locator('[data-testid="hold-notes-input"]').fill('Discharge intake from Johnston County Detention Center.');

    await outreachPage.locator('[data-testid="hold-dialog-confirm-button"]').click();
    await expect(dialog).toHaveCount(0, { timeout: 10000 });

    // 4) Coordinator view: sign in as the assigned coordinator, expand
    //    the shelter, verify the client-attribution sub-row carries
    //    the entered client name.
    const coordContext = await browser.newContext();
    const coordPage = await coordContext.newPage();
    await coordPage.goto('/login');
    await coordPage.locator('[data-testid="login-tenant-slug"]').fill(TENANT);
    await coordPage.locator('[data-testid="login-email"]').fill(coord.email);
    await coordPage.locator('[data-testid="login-password"]').fill(coord.password);
    await coordPage.locator('[data-testid="login-submit"]').click();
    await coordPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    await coordPage.goto('/coordinator');
    const card = coordPage.locator('[data-testid^="shelter-card-"]').filter({ hasText: shelter.name });
    await card.waitFor({ state: 'visible', timeout: 15000 });
    await card.click();

    await expect(coordPage.locator('[data-testid="coordinator-hold-list"]')).toBeVisible({ timeout: 10000 });
    const clientRows = coordPage.locator('[data-testid^="coordinator-hold-client-"]');
    await expect(clientRows.first()).toContainText(clientName, { timeout: 10000 });

    await coordContext.close();
  });
});
