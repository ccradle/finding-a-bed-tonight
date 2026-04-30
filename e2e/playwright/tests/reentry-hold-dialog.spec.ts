import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';
import {
  createReentryShelter,
  createTestCoordinator,
  loginAndGetToken,
  API_BASE,
  TENANT,
} from './_helpers/reentry-fixtures';

/**
 * transitional-reentry-support slice 4 §14 — hold dialog + admin
 * hold-duration + role-gated eligibility section.
 *
 * Covers:
 *  - §14.5 Hold dialog: opening the optional "Add client details"
 *    section, filling name/DOB/notes, submitting, then signing in as
 *    the assigned coordinator and verifying the dashboard shows the
 *    client name on the hold row.
 *  - §14.6 Admin hold-duration: set to 180 min, save, verify success
 *    toast message.
 *  - §14.9 Eligibility criteria section visible for COC_ADMIN, NOT
 *    visible for COORDINATOR in shelter edit form.
 *
 * Per §14.11 every test creates its own shelter / user via API. Per
 * memory feedback_isolated_test_users.md the hold-duration test
 * doesn't permanently mutate seed config — it restores the prior
 * value at the end of the test.
 */

const TOAST_DISMISS_MS = 4000;

test.describe('transitional-reentry-support §14 — hold dialog + admin hold-duration + role gating', () => {
  // Run cleanup BEFORE each test (in addition to afterAll) so accumulated
  // E2E shelters from prior runs don't push our just-created shelter past
  // the BedSearchService default limit (20). The test reset endpoint
  // matches `name LIKE 'E2E Test%'` and is dev/test-profile-only; safe to
  // call repeatedly.
  test.beforeAll(async () => { await cleanupTestData(); });
  test.afterAll(async () => { await cleanupTestData(); });

  test('§14.5 hold dialog with attribution: outreach worker submits, coordinator sees client name on dashboard', async ({ outreachPage, browser }) => {
    const adminToken = await loginAndGetToken();
    const ts = Date.now();

    // 1) Create a shelter the outreach worker can hold against (non-DV;
    //    DV shelters use the referral flow, not the hold flow). Use the
    //    YOUTH_18_24 population type so the test shelter is the ONLY
    //    shelter in the result set when we filter by it — sidesteps the
    //    BedSearchService 20-result limit. Most seed shelters serve
    //    SINGLE_ADULT or FAMILY_WITH_CHILDREN.
    const shelter = await createReentryShelter(adminToken, {
      name: `E2E Test Reentry H5-${ts}`,
      shelterType: 'EMERGENCY',
      county: null,
      populationType: 'YOUTH_18_24',
      bedsTotal: 5,
      bedsOccupied: 0,
    });

    // 2) Create a dedicated COORDINATOR user assigned to the new
    //    shelter. The seeded `dv-coordinator` user is assigned to
    //    DV shelters only — for a non-DV E2E shelter we need a fresh
    //    coordinator with the right assignment.
    const coord = await createTestCoordinator(adminToken, shelter.id);

    // 3) Outreach worker opens search, narrows by population type to
    //    bring the test shelter inside the 20-result window, opens
    //    the hold dialog.
    await outreachPage.goto('/outreach');
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .waitFor({ state: 'visible', timeout: 10000 });
    await outreachPage.locator('[data-testid="population-type-filter"]')
      .selectOption(shelter.populationType);
    const holdButton = outreachPage.locator(`[data-testid="hold-bed-${shelter.id}-${shelter.populationType}"]`);
    await holdButton.waitFor({ state: 'visible', timeout: 15000 });
    await holdButton.click();

    const dialog = outreachPage.locator('[data-testid="hold-dialog"]');
    await expect(dialog).toBeVisible();

    // Open the optional "Add client details" <details> and fill it.
    // The details element wraps the name/DOB/notes inputs and is
    // collapsed by default per HoldDialog.tsx §11.3.
    const attributionToggle = outreachPage.locator('[data-testid="hold-attribution-toggle"]');
    await attributionToggle.locator('summary').click();

    // Privacy note must be visible inside the open <details> per
    // warroom M5 — it's the load-bearing legal-review surface.
    await expect(outreachPage.locator('[data-testid="hold-attribution-privacy-note"]')).toBeVisible();

    const clientName = `E2E Demetrius ${ts}`;
    await outreachPage.locator('[data-testid="hold-client-name-input"]').fill(clientName);
    await outreachPage.locator('[data-testid="hold-client-dob-input"]').fill('1985-04-12');
    await outreachPage.locator('[data-testid="hold-notes-input"]').fill('Discharge-from-jail intake; needs Tuesday counseling check-in.');

    // Submit
    await outreachPage.locator('[data-testid="hold-dialog-confirm-button"]').click();

    // Dialog dismissed on success
    await expect(dialog).toHaveCount(0, { timeout: 10000 });

    // 4) Sign in as the assigned coordinator in a fresh browser context
    //    and verify the dashboard hold row carries the client name.
    const coordContext = await browser.newContext();
    const coordPage = await coordContext.newPage();
    await coordPage.goto('/login');
    await coordPage.locator('[data-testid="login-tenant-slug"]').fill(TENANT);
    await coordPage.locator('[data-testid="login-email"]').fill(coord.email);
    await coordPage.locator('[data-testid="login-password"]').fill(coord.password);
    await coordPage.locator('[data-testid="login-submit"]').click();
    // First-login flow may force a password change — bypass by
    // navigating directly to coordinator after auth.
    await coordPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    await coordPage.goto('/coordinator');
    // Find our shelter card and expand
    const shelterCard = coordPage.locator(`[data-testid^="shelter-card-"]`).filter({ hasText: shelter.name });
    await shelterCard.waitFor({ state: 'visible', timeout: 15000 });
    await shelterCard.click();

    // The per-hold list mounts when activeHolds > 0. The row for
    // *our* hold has the client-attribution sub-row visible.
    const holdList = coordPage.locator('[data-testid="coordinator-hold-list"]');
    await expect(holdList).toBeVisible({ timeout: 10000 });

    // Find the client-attribution sub-row by its prefix testid; the
    // text inside must contain the client name we entered.
    const clientRows = coordPage.locator('[data-testid^="coordinator-hold-client-"]');
    await expect(clientRows.first()).toContainText(clientName, { timeout: 10000 });

    await coordContext.close();
  });

  test('§14.6 admin hold-duration: set to 180 min, save, verify success toast; restore prior value', async ({ adminPage }) => {
    await adminPage.goto('/admin');

    const panel = adminPage.locator('[data-testid="reservation-settings"]');
    await expect(panel).toBeVisible({ timeout: 10000 });

    const input = adminPage.locator('[data-testid="hold-duration-input"]');
    const saveButton = adminPage.locator('[data-testid="hold-duration-save"]');

    // Capture the prior value so we can restore at the end (cleanup
    // discipline per feedback_isolated_test_data.md — don't leave
    // tenant config mutated for later tests).
    const priorValue = await input.inputValue();

    // Set to 180 and save.
    await input.fill('');
    await input.fill('180');
    await saveButton.click();

    // Success toast renders with the saved minutes value via
    // `admin.holdDuration.savedWithValue` (e.g. "Hold duration saved:
    // 180 minutes"). The testid is `hold-duration-success`.
    const successToast = adminPage.locator('[data-testid="hold-duration-success"]');
    await expect(successToast).toBeVisible({ timeout: 5000 });
    await expect(successToast).toContainText('180');

    // Restore prior value so the dev-coc tenant config remains stable
    // across other test runs. Toast auto-dismisses after 4s (handled
    // by ReservationSettings useEffect) — wait then save again.
    await expect(successToast).toHaveCount(0, { timeout: TOAST_DISMISS_MS + 2000 });
    await input.fill('');
    await input.fill(priorValue);
    await saveButton.click();
    await expect(adminPage.locator('[data-testid="hold-duration-success"]')).toBeVisible({ timeout: 5000 });
  });

  test('§14.9 eligibility criteria section visible for COC_ADMIN; not visible for COORDINATOR', async ({ adminPage, browser }) => {
    const adminToken = await loginAndGetToken();
    const ts = Date.now();

    // 1) Admin creates a shelter to edit.
    const shelter = await createReentryShelter(adminToken, {
      name: `E2E Test Reentry H9-${ts}`,
      shelterType: 'EMERGENCY',
    });

    // 2) Admin opens the edit form — the eligibility section must
    //    render (visible={canEditEligibility} which is true for
    //    COC_ADMIN/PLATFORM_ADMIN).
    await adminPage.goto(`/coordinator/shelters/${shelter.id}/edit`);
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible({ timeout: 10000 });
    await expect(adminPage.locator('[data-testid="eligibility-criteria-section"]')).toBeVisible();
    // The disclaimer is rendered as the FIRST child of the section
    // per Casey-reviewed legal posture; verify it is co-rendered.
    await expect(adminPage.locator('[data-testid="criminal-record-policy-disclaimer"]')).toBeVisible();

    // 3) Create a COORDINATOR (non-COC_ADMIN) user assigned to the
    //    shelter and sign in as that user. The eligibility section
    //    must NOT render (component returns null when visible=false).
    const coord = await createTestCoordinator(adminToken, shelter.id);

    const coordContext = await browser.newContext();
    const coordPage = await coordContext.newPage();
    await coordPage.goto('/login');
    await coordPage.locator('[data-testid="login-tenant-slug"]').fill(TENANT);
    await coordPage.locator('[data-testid="login-email"]').fill(coord.email);
    await coordPage.locator('[data-testid="login-password"]').fill(coord.password);
    await coordPage.locator('[data-testid="login-submit"]').click();
    await coordPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    // Coordinator opens the same shelter's edit form via direct URL
    // (the coordinator dashboard's "Edit Details" link goes to the
    // same route).
    await coordPage.goto(`/coordinator/shelters/${shelter.id}/edit`);
    await expect(coordPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible({ timeout: 10000 });
    // Wait for the form's first field so the role-gated render has
    // settled before asserting absence of the eligibility section.
    await expect(coordPage.locator('[data-testid="shelter-name"]')).toBeVisible();

    // The eligibility section must be absent for COORDINATOR.
    await expect(coordPage.locator('[data-testid="eligibility-criteria-section"]')).toHaveCount(0);

    await coordContext.close();
  });
});

// Re-exported so other reentry specs can share the API base if needed.
export { API_BASE };
