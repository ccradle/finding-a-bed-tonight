/**
 * Screenshot capture for offline demo walkthrough.
 * Navigates all key views and saves full-page screenshots.
 *
 * Order: Login → Search/Hold → Coordinator → i18n → Admin → Observability
 *
 * Usage: npx playwright test tests/capture-screenshots.spec.ts
 * Output: ../../demo/screenshots/ (findABed docs repo)
 *
 * Requires: dev-start.sh --observability running (for Grafana/Jaeger shots)
 */
import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');

test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
});

test.describe('Demo Screenshot Capture', () => {

  // === LOGIN ===

  test('01 - Login page', async ({ page }) => {
    await page.goto('/login');
    await page.locator('[data-testid="login-tenant-slug"]').waitFor({ state: 'visible', timeout: 10000 });
    await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(DEMO_DIR, '01-login.png'), fullPage: true });
  });

  // === OUTREACH WORKER: BED SEARCH ===

  test('02 - Bed search', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1000);
    // Select FAMILY_WITH_CHILDREN filter — Darius is searching for a family of five
    await outreachPage.locator('[data-testid="population-type-filter"]').selectOption('FAMILY_WITH_CHILDREN');
    await outreachPage.waitForTimeout(2000);
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '02-bed-search.png'), fullPage: true });
  });

  test('03 - Search results', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);
    // Select FAMILY_WITH_CHILDREN filter — results should show 3 family shelters
    await outreachPage.locator('[data-testid="population-type-filter"]').selectOption('FAMILY_WITH_CHILDREN');
    await outreachPage.waitForTimeout(2000);
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '03-search-results.png'), fullPage: true });
  });

  test('04 - Shelter detail from search', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);
    await outreachPage.locator('[data-testid="population-type-filter"]').selectOption('FAMILY_WITH_CHILDREN');
    await outreachPage.waitForTimeout(2000);
    // Click Crabtree Valley by data-testid — caption: "pets allowed, wheelchair accessible, updated 12 minutes ago"
    await outreachPage.locator('[data-testid="shelter-card-crabtree-valley-family-haven"]').click();
    await outreachPage.waitForTimeout(1500);
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '04-shelter-detail-search.png'), fullPage: true });
  });

  test('05 - Reservation hold', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);
    await outreachPage.locator('[data-testid="population-type-filter"]').selectOption('FAMILY_WITH_CHILDREN');
    await outreachPage.waitForTimeout(2000);
    // Hold a bed at Crabtree Valley — caption: "One tap holds the bed for 90 minutes"
    const holdButton = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() > 0) {
      await holdButton.click();
      await outreachPage.waitForTimeout(2000);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '05-reservation-hold.png'), fullPage: true });
  });

  // === COORDINATOR: BED MANAGEMENT ===

  test('06 - Coordinator dashboard', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await expect(coordinatorPage.locator('main')).toBeVisible();
    await coordinatorPage.waitForTimeout(1500);
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '06-coordinator-dashboard.png'), fullPage: true });
  });

  test('07 - Coordinator bed update', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(1500);
    // Expand first shelter to show unified Total Beds / Occupied / On Hold steppers
    const shelterCard = coordinatorPage.locator('main button[style*="text-align: left"]').first();
    if (await shelterCard.count() > 0) {
      await shelterCard.click();
      await coordinatorPage.waitForTimeout(1500);
    }
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '07-coordinator-bed-update.png'), fullPage: true });
  });

  // === INTERNATIONALIZATION ===

  test('08 - Spanish language', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(500);
    const localeSelect = outreachPage.locator('select');
    if (await localeSelect.count() > 0) {
      await localeSelect.first().selectOption('es');
      await outreachPage.waitForTimeout(1500);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '08-spanish.png'), fullPage: true });
    if (await localeSelect.count() > 0) {
      await localeSelect.first().selectOption('en');
    }
  });

  // === ADMIN: USER & SHELTER MANAGEMENT ===

  test('09 - Admin users tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '09-admin-users.png'), fullPage: true });
  });

  test('10 - Create user form', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(500);
    await adminPage.locator('main button', { hasText: /create user/i }).click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '10-admin-create-user.png'), fullPage: true });
  });

  test('11 - Admin shelters tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '11-admin-shelters.png'), fullPage: true });
  });

  test('12 - Add shelter form', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator/shelters/new');
    await coordinatorPage.waitForTimeout(1500);
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '12-add-shelter.png'), fullPage: true });
  });

  test('13 - Shelter detail from admin', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    const firstShelterLink = adminPage.locator('main tbody tr td').first();
    if (await firstShelterLink.count() > 0) {
      await firstShelterLink.click();
      await adminPage.waitForTimeout(1500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '13-admin-shelter-detail.png'), fullPage: true });
  });

  test('14 - Admin surge tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Surge$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '14-admin-surge.png'), fullPage: true });
  });

  test('15 - Admin observability tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Observability$/ }).first().click();
    await adminPage.waitForTimeout(1500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '15-admin-observability.png'), fullPage: true });
  });

  test('16 - Admin OAuth2 providers tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    const provTab = adminPage.locator('main button', { hasText: /OAuth2|Providers/i }).first();
    if (await provTab.count() > 0) {
      await provTab.click();
      await adminPage.waitForTimeout(1500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '16-admin-oauth2-providers.png'), fullPage: true });
  });

  // === PASSWORD MANAGEMENT ===

  test('19 - Change Password modal', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);
    await outreachPage.locator('[data-testid="change-password-button"]').click();
    await outreachPage.waitForTimeout(500);
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '19-change-password.png'), fullPage: true });
    // Close modal without submitting
    await outreachPage.locator('button', { hasText: /cancel/i }).click();
  });

  // === SHELTER EDIT: 211 IMPORT → EDIT LIFECYCLE ===
  // One test, multiple screenshots — the import must persist across captures
  // so each subsequent screenshot sees the imported shelters. This mirrors
  // the real user flow: Marcus stays in one browser session.

  test('20-25 - Import → Edit → DV shelter flow', async ({ adminPage }) => {
    const fixturePath = path.join(__dirname, '..', '..', 'fixtures', 'nc-211-sample.csv');

    // --- Screenshot 20: Import 211 preview ---
    // "Marcus is onboarding three new partner shelters from the region's 211 database —
    //  every hour a shelter isn't in the system is an hour a family can't find it."
    await adminPage.goto('/coordinator/import/211');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('input[type="file"]').setInputFiles(fixturePath);
    await adminPage.waitForTimeout(500);
    // Step 1: Click "Preview Column Mapping" to see the mapping
    await adminPage.locator('button', { hasText: /preview column mapping/i }).click();
    await adminPage.waitForTimeout(2000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '20-import-211-preview.png'), fullPage: true });

    // --- Screenshot 21: Import success ---
    // Step 2: Click "Confirm & Import" to execute
    await adminPage.locator('button', { hasText: /confirm.*import/i }).click();
    await adminPage.waitForTimeout(3000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '21-import-211-success.png'), fullPage: true });

    // --- Screenshot 22: Shelters tab with Edit links ---
    // "Every shelter in the community is here — including the three that just arrived."
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '22-admin-shelters-edit.png'), fullPage: true });

    // --- Screenshot 23: Edit shelter phone ---
    // "A phone number came through wrong. Sandra will need this number tonight."
    const sunriseRow = adminPage.locator('tr', { hasText: 'Sunrise Family Center' });
    await sunriseRow.locator('a', { hasText: /^Edit$/ }).click();
    await adminPage.waitForTimeout(1500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '23-shelter-edit-phone.png'), fullPage: true });

    // Back to shelters tab for DV screenshots
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);

    // --- Screenshot 24: DV toggle on Safe Passage House ---
    // "This shelter protects survivors."
    const safePassageRow = adminPage.locator('tr', { hasText: 'Safe Passage House' });
    await safePassageRow.locator('a', { hasText: /^Edit$/ }).click();
    await adminPage.waitForTimeout(1500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '24-shelter-edit-dv-toggle.png'), fullPage: true });

    // --- Screenshot 25: DV confirmation dialog ---
    // Enable DV first (false→true, no dialog needed)
    const dvToggle = adminPage.locator('[data-testid="dv-shelter-toggle"]');
    const isCurrentlyDv = (await dvToggle.getAttribute('aria-checked')) === 'true';
    if (!isCurrentlyDv) {
      await dvToggle.click();
      await adminPage.waitForTimeout(500);
    }
    // Now disable DV (true→false) — this triggers the confirmation dialog
    await dvToggle.click();
    await adminPage.waitForTimeout(500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '25-dv-confirm-dialog.png'), fullPage: true });
    // Cancel — don't actually remove protection
    await adminPage.locator('[data-testid="dv-confirm-cancel"]').click();
  });

  test('26 - Coordinator edit form', async ({ coordinatorPage }) => {
    // "Sandra can update phone and hours — the things that change week to week.
    //  But name, address, and DV status are read-only for her role."
    // NOTE: coordinatorPage is COC_ADMIN — all fields editable. A true COORDINATOR
    // would see structural fields grayed out. Caption notes this distinction.
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(1000);

    const shelterCard = coordinatorPage.locator('[data-testid^="shelter-card-"]').first();
    await shelterCard.click();
    await coordinatorPage.waitForTimeout(500);
    const editBtn = coordinatorPage.locator('a', { hasText: /edit details/i }).first();
    await editBtn.click();
    await coordinatorPage.waitForTimeout(1500);

    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '26-coordinator-edit-form.png'), fullPage: true });
  });

  // === OBSERVABILITY STACK ===

  test('17 - Grafana dashboard', async ({ browser }) => {
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:3000/d/fabt-operations/fabt-operations?orgId=1&from=now-1h&to=now', { timeout: 10000 });
      const loginForm = page.locator('input[name="user"]');
      if (await loginForm.count() > 0) {
        await loginForm.fill('admin');
        await page.locator('input[name="password"]').fill('admin');
        await page.locator('button[type="submit"]').click();
        await page.waitForTimeout(2000);
        const skipButton = page.locator('a', { hasText: /skip/i });
        if (await skipButton.count() > 0) await skipButton.click();
        await page.goto('http://localhost:3000/d/fabt-operations/fabt-operations?orgId=1&from=now-1h&to=now');
      }
      await page.waitForTimeout(3000);
      await page.screenshot({ path: path.join(DEMO_DIR, '17-grafana-dashboard.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Grafana not available — skipping screenshot');
    }
  });

  test('18 - Jaeger traces', async ({ browser }) => {
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:16686/search?service=finding-a-bed-tonight&limit=20', { timeout: 10000 });
      await page.waitForTimeout(3000);
      const findButton = page.locator('button', { hasText: /find traces/i });
      if (await findButton.count() > 0) {
        await findButton.click();
        await page.waitForTimeout(3000);
      }
      await page.screenshot({ path: path.join(DEMO_DIR, '18-jaeger-traces.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Jaeger not available — skipping screenshot');
    }
  });
});
