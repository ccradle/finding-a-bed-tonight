/**
 * Reentry on release day — dedicated screenshot capture for the
 * `demo/reentry-story.html` capability deep-dive page.
 *
 * Six PNGs, one per <img> on the story page (alt text in the page itself):
 *   reentry-01-advanced-search-filters     — outreach search with the new
 *                                              filters open + REENTRY_TRANSITIONAL
 *                                              chip active + county dropdown set
 *   reentry-02-search-results-filtered     — results list after the filter
 *                                              applies; one or more reentry shelters
 *   reentry-03-shelter-detail-eligibility  — shelter detail page showing the
 *                                              eligibility criteria section
 *                                              (criminal record policy + sobriety + intake hours)
 *   reentry-04-hold-dialog-attribution     — hold dialog with the §11 always-
 *                                              visible privacy note above the
 *                                              attribution disclosure + the per-input
 *                                              help text inside it
 *   reentry-05-admin-reservation-settings  — CoC admin ReservationSettings panel
 *                                              showing hold-duration = 180 minutes
 *   reentry-06-no-match-failure-path       — search where the only county-eligible
 *                                              reentry shelter excludes the relevant
 *                                              offense type (the Demetrius "honest
 *                                              about no" moment)
 *
 * Output: ../../../../demo/screenshots/reentry-* (findABed docs repo)
 *
 * Tenant: dev-coc-east (slug). Has features.reentryMode=true seeded (V95 §16.E).
 * Has REENTRY_TRANSITIONAL shelters in Onslow + Henderson counties — Onslow
 * Womens Reentry has excluded_offense_types=[SEX_OFFENSE, ARSON] which powers
 * reentry-06 and aligns with the story page's reentry-03 alt text.
 *
 * Auth: outreach@pamlico.fabt.org for the navigator-perspective shots (1-4, 6),
 * cocadmin@pamlico.fabt.org for the admin shot (5). The default auth.fixture
 * is hard-coded to dev-coc; this spec uses inline tenant-scoped login + storage
 * state injection per §16.E feedback.
 *
 * Requires: dev-start.sh --nginx running with V95 seed loaded.
 */
import { test } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');
const API_URL = process.env.API_URL || 'http://localhost:8080';
const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';
const TENANT_SLUG = 'dev-coc-east';
const NAVIGATOR_USER = { email: 'outreach@pamlico.fabt.org', password: 'admin123' };
const COC_ADMIN_USER = { email: 'cocadmin@pamlico.fabt.org', password: 'admin123' };

test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
});

/**
 * Log in via direct API call + inject the access token into localStorage on a
 * page navigated to the configured BASE_URL. Mirrors the auth.fixture pattern
 * but uses an explicit tenant slug so this spec can target dev-coc-east
 * without depending on the default-fixture single-tenant assumption.
 */
async function loginAndInject(
  page: import('@playwright/test').Page,
  user: { email: string; password: string },
): Promise<void> {
  const resp = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tenantSlug: TENANT_SLUG,
      email: user.email,
      password: user.password,
    }),
  });
  if (!resp.ok) {
    throw new Error(`login failed for ${user.email}: ${resp.status} ${await resp.text()}`);
  }
  const body = await resp.json() as { accessToken: string; refreshToken?: string };

  // Navigate to BASE_URL first so localStorage has an origin to write into.
  await page.goto(BASE_URL);
  await page.evaluate(({ access, refresh }) => {
    localStorage.setItem('fabt_access_token', access);
    if (refresh) localStorage.setItem('fabt_refresh_token', refresh);
  }, { access: body.accessToken, refresh: body.refreshToken });
}

test.describe('Reentry screenshot capture', () => {

  test('reentry-01 — advanced search filters with REENTRY_TRANSITIONAL active', async ({ page }) => {
    await loginAndInject(page, NAVIGATOR_USER);
    await page.goto('/outreach');
    // Wait for outreach to hydrate and the population-type filter to render
    // (sentinel that the search page is interactive).
    await page.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

    // §16.C wraps the advanced filters in a section; the <details> opens by default
    // when a tenant has reentryMode=true (verified in §16.D).
    const advancedFilters = page.locator('[data-testid="reentry-advanced-filters"]');
    await advancedFilters.waitFor({ state: 'visible', timeout: 15000 });

    // Activate the REENTRY_TRANSITIONAL chip so the screenshot shows a real filter
    // selection, not the empty unfiltered state.
    const reentryChip = page.locator('[data-testid="shelter-type-filter-REENTRY_TRANSITIONAL"]');
    if (await reentryChip.count() > 0) {
      await reentryChip.click();
      await page.waitForTimeout(300);
    }

    // Set the county filter to Onslow so the screenshot's filter state aligns
    // with the page's reentry-01 alt text and the V95 seed's Onslow Womens
    // Reentry shelter.
    const countySelect = page.locator('[data-testid="county-filter"]');
    if (await countySelect.count() > 0) {
      await countySelect.selectOption({ label: 'Onslow' }).catch(async () => {
        // Fall back to any non-empty option if the seed county labels differ.
        await countySelect.selectOption({ index: 1 });
      });
      await page.waitForTimeout(300);
    }

    await page.waitForTimeout(800);
    await page.screenshot({
      path: path.join(DEMO_DIR, 'reentry-01-advanced-search-filters.png'),
      fullPage: true,
    });
  });

  test('reentry-02 — filtered results list', async ({ page }) => {
    await loginAndInject(page, NAVIGATOR_USER);
    await page.goto('/outreach');
    await page.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

    // Filter to REENTRY_TRANSITIONAL so the results list is the navigator's
    // narrowed view (not the unfiltered 25-shelter dump).
    const reentryChip = page.locator('[data-testid="shelter-type-filter-REENTRY_TRANSITIONAL"]');
    if (await reentryChip.count() > 0) {
      await reentryChip.click();
      await page.waitForTimeout(500);
    }

    // Scroll the first reentry shelter into view so the screenshot is centered
    // on the relevant inventory rather than empty filter state.
    const firstResult = page.locator('[data-testid^="shelter-card-"]').first();
    if (await firstResult.count() > 0) {
      await firstResult.scrollIntoViewIfNeeded();
      await page.waitForTimeout(300);
    }

    await page.waitForTimeout(800);
    await page.screenshot({
      path: path.join(DEMO_DIR, 'reentry-02-search-results-filtered.png'),
      fullPage: true,
    });
  });

  test('reentry-03 — shelter detail with eligibility criteria', async ({ page }) => {
    await loginAndInject(page, NAVIGATOR_USER);
    await page.goto('/outreach');
    await page.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

    // Filter to REENTRY_TRANSITIONAL, then click into Onslow Womens Reentry —
    // its eligibility_criteria seed has accepts_felonies=true with
    // excluded_offense_types=[SEX_OFFENSE, ARSON], aligned with the
    // page's reentry-03 alt text ("accepts felonies excluding sex
    // offenses, accepts pending charges, requires sobriety").
    const reentryChip = page.locator('[data-testid="shelter-type-filter-REENTRY_TRANSITIONAL"]');
    if (await reentryChip.count() > 0) {
      await reentryChip.click();
      await page.waitForTimeout(500);
    }

    const firstCard = page.locator('[data-testid^="shelter-card-"]').first();
    await firstCard.click();
    await page.waitForTimeout(1200);

    // §7.4 (warroom Tomás-critical): the screenshot MUST contain the
    // CriminalRecordPolicyDisclaimer rendered ABOVE the eligibility data.
    // Capture the eligibility-criteria-display ELEMENT (not fullPage) so
    // the screenshot frames the disclaimer + eligibility together,
    // bypassing modal-scroll issues that the prior fullPage capture
    // hit at run time.
    const eligibilitySection = page.locator('[data-testid="eligibility-criteria-display"]').first();
    await eligibilitySection.waitFor({ state: 'visible', timeout: 10000 });
    await eligibilitySection.scrollIntoViewIfNeeded();
    await page.waitForTimeout(500);
    await eligibilitySection.screenshot({
      path: path.join(DEMO_DIR, 'reentry-03-shelter-detail-eligibility.png'),
    });
  });

  test('reentry-04 — hold dialog with optional client attribution', async ({ page }) => {
    await loginAndInject(page, NAVIGATOR_USER);
    await page.goto('/outreach');
    await page.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

    // Find a shelter with available beds and click Hold Bed. The seed has
    // beds available across most non-DV shelters; the first card is fine.
    const reentryChip = page.locator('[data-testid="shelter-type-filter-REENTRY_TRANSITIONAL"]');
    if (await reentryChip.count() > 0) {
      await reentryChip.click();
      await page.waitForTimeout(500);
    }

    const holdButton = page.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() > 0) {
      await holdButton.scrollIntoViewIfNeeded();
      await holdButton.click();
      await page.waitForTimeout(600);

      // §11 Round 2 (warroom Devon DK-RR-A12 + Tomás DK-RR-4) made the
      // attribution disclosure collapsed-by-default — privacy note renders
      // outside the <details>, inputs are inside. Click the summary to
      // expand so the screenshot captures the full inputs-with-help-text
      // shape the page describes. The summary's data-testid lives on the
      // parent <details>.
      const attributionToggle = page.locator('[data-testid="hold-attribution-toggle"] summary');
      if (await attributionToggle.count() > 0) {
        await attributionToggle.click();
        await page.waitForTimeout(300);
      }

      // Fill the attribution fields with synthetic placeholder values so the
      // screenshot looks like a real navigator's input, not an empty form.
      const nameInput = page.locator('[data-testid="hold-client-name-input"]');
      if (await nameInput.count() > 0) {
        await nameInput.fill('Andre Synthetic');
      }
      const dobInput = page.locator('[data-testid="hold-client-dob-input"]');
      if (await dobInput.count() > 0) {
        await dobInput.fill('1985-06-15');
      }
      const notesInput = page.locator('[data-testid="hold-notes-input"]');
      if (await notesInput.count() > 0) {
        await notesInput.fill('Releasing today; navigator will call shelter intake at 4:30.');
      }
      await page.waitForTimeout(300);
    }

    await page.screenshot({
      path: path.join(DEMO_DIR, 'reentry-04-hold-dialog-attribution.png'),
      fullPage: false,
    });
  });

  test('reentry-05 — CoC admin ReservationSettings (hold-duration = 180m)', async ({ page }) => {
    await loginAndInject(page, COC_ADMIN_USER);
    // AdminPanel uses URL-hash routing for tab selection — go directly to the
    // reservations tab where ReservationSettings renders.
    await page.goto('/admin#reservations');
    await page.waitForTimeout(2000);

    // Try to find the hold-duration setting by data-testid; if the testid
    // pattern differs in the live UI, fall back to a full-page admin shot
    // so capture still produces a non-empty image.
    const reservationSettings = page.locator('[data-testid="reservation-settings"]');
    if (await reservationSettings.count() > 0) {
      await reservationSettings.scrollIntoViewIfNeeded();
      await page.waitForTimeout(300);
    }

    await page.screenshot({
      path: path.join(DEMO_DIR, 'reentry-05-admin-reservation-settings.png'),
      fullPage: true,
    });
  });

  test('reentry-06 — no match: filter combination yields empty result + banner', async ({ page }) => {
    await loginAndInject(page, NAVIGATOR_USER);
    await page.goto('/outreach');
    await page.locator('[data-testid="population-type-filter"]').waitFor({ state: 'visible', timeout: 15000 });

    // Empty-state framing: filter TRANSITIONAL + Buncombe County + activate
    // the accepts-felonies toggle. Mountain View Transitional (the only
    // TRANSITIONAL shelter in Buncombe per V95 seed) has eligibility_criteria
    // unset, which the §8/§9 H4 AcceptsFeloniesEvaluator branch treats as
    // "unknown" → excluded from the accepts-felonies-only filter → zero
    // results + the empty-state warning banner described in the page's
    // §4 alt text ("the platform shows no match available").
    const transitionalChip = page.locator('[data-testid="shelter-type-filter-TRANSITIONAL"]');
    if (await transitionalChip.count() > 0) {
      await transitionalChip.click();
      await page.waitForTimeout(300);
    }
    const countySelect = page.locator('[data-testid="county-filter"]');
    if (await countySelect.count() > 0) {
      await countySelect.selectOption({ label: 'Buncombe' }).catch(async () => {
        await countySelect.selectOption({ index: 1 });
      });
      await page.waitForTimeout(300);
    }
    const acceptsFeloniesToggle = page.locator('[data-testid="accepts-felonies-filter"]');
    if (await acceptsFeloniesToggle.count() > 0) {
      await acceptsFeloniesToggle.click();
      await page.waitForTimeout(500);
    }

    // Wait for the empty-state banner to render. If the filter combination
    // does NOT yield empty (seed evolved), the screenshot still captures
    // whatever results render — at minimum it shows the activated filter
    // state, which is the page's intent.
    const emptyBanner = page.locator('[data-testid="accepts-felonies-empty-banner"]');
    if (await emptyBanner.count() > 0) {
      await emptyBanner.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
    }

    await page.waitForTimeout(800);
    await page.screenshot({
      path: path.join(DEMO_DIR, 'reentry-06-no-match-failure-path.png'),
      fullPage: true,
    });
  });
});
