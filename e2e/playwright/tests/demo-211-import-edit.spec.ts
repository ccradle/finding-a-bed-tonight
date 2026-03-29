import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';
import * as path from 'path';

/**
 * Demo flow: 211 Import → Shelter Edit lifecycle
 *
 * Story (Simone's lens): Marcus is onboarding three new partner shelters from
 * the county's 211 database. Every hour a shelter isn't in the system is an
 * hour a family can't find it.
 *
 * The CSV uses iCarol-style headers (agency_name, street_address, etc.) to
 * demonstrate fuzzy column matching — the same format NC 211 exports.
 *
 * Three shelters, each with a purpose:
 *   - Hope Harbor Emergency Shelter: imports cleanly, no edits needed
 *   - Sunrise Family Center: phone is 919-555-0000 (wrong) — motivates the edit
 *   - Safe Passage House: needs DV flag set — motivates the DV configuration story
 *
 * Riley's lens: "What happens to the person in crisis if this test is missing?"
 *   - If import fails silently, shelters never appear in search — families can't find beds
 *   - If phone is wrong and nobody catches it, Sandra calls a dead number at midnight
 *   - If DV flag isn't set, a survivor's address is visible to everyone
 */

const FIXTURE_PATH = path.resolve(__dirname, '../../fixtures/nc-211-sample.csv');

// Demo shelter names from the CSV fixture
const HOPE_HARBOR = 'Hope Harbor Emergency Shelter';
const SUNRISE_FAMILY = 'Sunrise Family Center';
const SAFE_PASSAGE = 'Safe Passage House';

test.describe('Demo: 211 Import → Edit Lifecycle', () => {
  test.afterAll(async () => { await cleanupTestData(); });

  test('admin imports 211 CSV, preview shows mapping, shelters appear', async ({ adminPage }) => {
    // Step 1: Navigate directly to Import 211 page
    await adminPage.goto('/coordinator/import/211');
    await adminPage.waitForTimeout(1000);

    // Step 2: Upload the demo CSV
    const fileInput = adminPage.locator('input[type="file"]');
    await fileInput.setInputFiles(FIXTURE_PATH);
    await adminPage.waitForTimeout(500);

    // Step 3: Click "Preview Column Mapping" to see the mapping
    await adminPage.locator('button', { hasText: /preview column mapping/i }).click();
    await adminPage.waitForTimeout(2000);

    // Step 4: Preview should show column mapping — iCarol headers mapped to FABT fields
    await expect(adminPage.locator('text=agency_name')).toBeVisible();

    // Step 5: Confirm import
    await adminPage.locator('button', { hasText: /confirm.*import/i }).click();
    await adminPage.waitForTimeout(3000);

    // Step 6: Verify success — "Import Complete" with created or updated count
    // (re-importing the same file produces updates instead of creates due to upsert)
    await expect(adminPage.locator('text=Import Complete')).toBeVisible();

    // Step 6: Navigate to Shelters tab and verify all 3 appear
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    await expect(adminPage.locator('main td', { hasText: HOPE_HARBOR })).toBeVisible();
    await expect(adminPage.locator('main td', { hasText: SUNRISE_FAMILY })).toBeVisible();
    await expect(adminPage.locator('main td', { hasText: SAFE_PASSAGE })).toBeVisible();
  });

  test('admin edits imported shelter phone number', async ({ adminPage }) => {
    // Story: "A phone number came through wrong in the import.
    //         Sandra will need this number tonight — Marcus fixes it in seconds."
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    // Find Sunrise Family Center's row and click Edit
    const sunriseRow = adminPage.locator('tr', { hasText: SUNRISE_FAMILY });
    await expect(sunriseRow).toBeVisible();
    await sunriseRow.locator('a', { hasText: /^Edit$/ }).click();

    // Verify edit form loads with the imported data
    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();
    const nameInput = adminPage.locator('[data-testid="shelter-name"]');
    await expect(nameInput).toHaveValue(SUNRISE_FAMILY);

    // Verify the wrong phone number imported correctly
    const phoneInput = adminPage.locator('[data-testid="shelter-phone"]');
    await expect(phoneInput).toHaveValue('919-555-0000');

    // Fix the phone number
    await phoneInput.fill('919-555-0142');

    // Save
    await adminPage.locator('[data-testid="shelter-save"]').click();
    await adminPage.waitForURL(/\/admin/);
    await adminPage.waitForTimeout(1000);

    // Verify: re-edit to confirm phone persisted
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    const sunriseRowAfter = adminPage.locator('tr', { hasText: SUNRISE_FAMILY });
    await sunriseRowAfter.locator('a', { hasText: /^Edit$/ }).click();
    await expect(adminPage.locator('[data-testid="shelter-phone"]')).toHaveValue('919-555-0142');
  });

  test('admin sets DV flag on imported shelter with confirmation', async ({ adminPage }) => {
    // Story: "This shelter protects survivors. Marcus enables DV safeguards —
    //         the address disappears from public view immediately.
    //         Safety isn't a setting, it's a commitment."
    //
    // MUST use adminPage (PLATFORM_ADMIN, dvAccess=true).
    // RLS policy requires dvAccess to write dv_shelter=true.
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    // Find Safe Passage House and click Edit
    const safePassageRow = adminPage.locator('tr', { hasText: SAFE_PASSAGE });
    await expect(safePassageRow).toBeVisible();
    await safePassageRow.locator('a', { hasText: /^Edit$/ }).click();

    await expect(adminPage.locator('h2', { hasText: /edit shelter/i })).toBeVisible();

    // DV toggle should be visible — may be ON or OFF depending on prior test state
    const dvToggle = adminPage.locator('[data-testid="dv-shelter-toggle"]');
    await expect(dvToggle).toBeVisible();

    const isAlreadyDv = (await dvToggle.getAttribute('aria-checked')) === 'true';

    if (!isAlreadyDv) {
      // Enable DV protection — no confirmation needed for false→true
      await dvToggle.click();
      expect(await dvToggle.getAttribute('aria-checked')).toBe('true');
    }

    // Save the DV-protected shelter
    await adminPage.locator('[data-testid="shelter-save"]').click();
    await adminPage.waitForURL(/\/admin/);
    await adminPage.waitForTimeout(1000);

    // Verify: re-edit to confirm DV flag persisted
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    const safePassageRowAfter = adminPage.locator('tr', { hasText: SAFE_PASSAGE });
    await safePassageRowAfter.locator('a', { hasText: /^Edit$/ }).click();

    const dvToggleAfter = adminPage.locator('[data-testid="dv-shelter-toggle"]');
    expect(await dvToggleAfter.getAttribute('aria-checked')).toBe('true');
  });
});
