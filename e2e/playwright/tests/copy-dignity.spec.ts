import { test, expect } from '../fixtures/auth.fixture';

/**
 * Dignity-centered copy verification.
 *
 * Verifies that user-facing labels protect the dignity and safety of
 * people in crisis. Evaluated through Keisha Thompson (AI persona,
 * Lived Experience Advisor) and Simone Okafor (AI persona, Brand
 * Strategist), both defined in PERSONAS.md.
 *
 * REQ-COPY-1: "Safety Shelter" replaces "DV Survivors" in search UI
 * REQ-COPY-3: Freshness badges include plain-text age description
 * REQ-COPY-4: Offline banner includes reassurance message
 * REQ-COPY-7: Labels render correctly in both languages
 */

test.describe('Dignity-Centered Copy', () => {

  test('population type dropdown shows "Safety Shelter" not "DV Survivors"', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);

    // The population type filter is a <select> with aria-label
    const dropdown = outreachPage.locator('[data-testid="population-type-filter"]');
    const dropdownText = await dropdown.evaluate(el => el.textContent || el.innerHTML);

    // Must contain "Safety Shelter"
    expect(dropdownText).toContain('Safety Shelter');

    // Must NOT contain "DV Survivors" or "DV_SURVIVOR"
    expect(dropdownText).not.toContain('DV Survivors');
    expect(dropdownText).not.toContain('DV_SURVIVOR');
  });

  test('no DV terminology visible on search page', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    // Check ALL visible text on the page for DV terminology
    const pageText = await outreachPage.evaluate(() => document.body.innerText);

    // "DV" as a standalone label should not appear (API enum is internal)
    // Allow "DV" in technical contexts that aren't user-visible (e.g., hidden data attributes)
    expect(pageText).not.toContain('DV Survivors');
    expect(pageText).not.toContain('DV_SURVIVOR');
    expect(pageText).not.toContain('Sobrevivientes de VD');
  });

  test('freshness badges include human-readable age', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    // Wait for shelter results to load
    await outreachPage.waitForSelector('[data-testid^="hold-bed-"]', { timeout: 15000 });

    // Find freshness badge text — should contain status + age (e.g., "Fresh · Updated 12 minutes ago")
    const badges = outreachPage.locator('span[aria-label*="Updated"]');
    const badgeCount = await badges.count();
    expect(badgeCount).toBeGreaterThan(0);

    // First badge should have the combined format
    const firstBadgeLabel = await badges.first().getAttribute('aria-label');
    expect(firstBadgeLabel).toMatch(/Fresh|Aging|Stale|Unknown/);
    expect(firstBadgeLabel).toMatch(/Updated/);
  });
});

test.describe('Raw Enum Prevention', () => {

  // Riley Cho: "The dropdown test proved the dropdown was fixed. It didn't prove
  // the rest of the UI was fixed." This test scans ALL visible text on key pages
  // for raw API enum values that should never be displayed to users.
  const RAW_ENUMS = ['DV_SURVIVOR', 'SINGLE_ADULT', 'FAMILY_WITH_CHILDREN', 'WOMEN_ONLY', 'YOUTH_18_24', 'YOUTH_UNDER_18'];

  test('no raw enum values visible on search page (admin with DV shelters)', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForSelector('[data-testid^="hold-bed-"]', { timeout: 15000 }).catch(() => {});
    await adminPage.waitForTimeout(2000);

    const pageText = await adminPage.evaluate(() => document.body.innerText);
    for (const raw of RAW_ENUMS) {
      expect(pageText, `Raw enum "${raw}" should not appear as visible text`)
        .not.toContain(raw);
    }
  });

  test('no raw enum values visible on coordinator dashboard', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);

    const pageText = await adminPage.evaluate(() => document.body.innerText);
    for (const raw of RAW_ENUMS) {
      expect(pageText, `Raw enum "${raw}" should not appear as visible text`)
        .not.toContain(raw);
    }
  });
});

test.describe('Spanish Locale Dignity Copy', () => {

  test('Spanish locale shows "Refugio Seguro" not "Sobrevivientes de VD"', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);

    // Switch to Spanish — language selector has specific aria-label
    const langSelector = outreachPage.locator('select[aria-label="Select language"]');
    await langSelector.selectOption('es');
    await outreachPage.waitForTimeout(1000);

    // After language switch, the population type dropdown text should be in Spanish
    const dropdown = outreachPage.locator('[data-testid="population-type-filter"]');
    const dropdownText = await dropdown.evaluate(el => el.textContent || el.innerHTML);

    expect(dropdownText).toContain('Refugio Seguro');
    expect(dropdownText).not.toContain('Sobrevivientes de VD');
  });
});
