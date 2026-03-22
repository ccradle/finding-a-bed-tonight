/**
 * Screenshot capture for offline demo walkthrough.
 * Navigates all key views and saves full-page screenshots.
 *
 * Usage: npx playwright test tests/capture-screenshots.spec.ts
 * Output: ../../demo/screenshots/ (findABed docs repo)
 *
 * Requires: dev-start.sh running (optionally with --observability for Grafana/Jaeger shots)
 */
import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');

// Ensure output directory exists
test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
});

test.describe('Demo Screenshot Capture', () => {

  test('01 - Login page', async ({ page }) => {
    await page.goto('/login');
    await page.waitForSelector('input[type="email"]', { timeout: 10000 });
    await page.screenshot({ path: path.join(DEMO_DIR, '01-login.png'), fullPage: true });
  });

  test('02 - Outreach search (empty)', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1000);
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '02-outreach-search.png'), fullPage: true });
  });

  test('03 - Bed search results', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1000);
    // Trigger a search — look for a search/filter button or form
    const searchButton = outreachPage.locator('button', { hasText: /search|find/i });
    if (await searchButton.count() > 0) {
      await searchButton.first().click();
      await outreachPage.waitForTimeout(2000);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '03-bed-results.png'), fullPage: true });
  });

  test('04 - Reservation hold', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1500);
    // Try to click a hold button if results are visible
    const holdButton = outreachPage.locator('button', { hasText: /hold/i });
    if (await holdButton.count() > 0) {
      await holdButton.first().click();
      await outreachPage.waitForTimeout(2000);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '04-reservation-hold.png'), fullPage: true });
  });

  test('05 - Coordinator dashboard', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await expect(coordinatorPage.locator('main')).toBeVisible();
    await coordinatorPage.waitForTimeout(1500);
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '05-coordinator-dashboard.png'), fullPage: true });
  });

  test('05b - Coordinator availability update', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(1500);
    // Click first shelter card to expand the availability update form
    const shelterCard = coordinatorPage.locator('main [style*="cursor"]').first();
    if (await shelterCard.count() > 0) {
      await shelterCard.click();
      await coordinatorPage.waitForTimeout(1500);
    }
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '05b-coordinator-update.png'), fullPage: true });
  });

  test('06 - Admin Users tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '06-admin-users.png'), fullPage: true });
  });

  test('07 - Admin Shelters tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '07-admin-shelters.png'), fullPage: true });
  });

  test('08 - Admin Surge tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Surge$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '08-admin-surge.png'), fullPage: true });
  });

  test('09 - Admin Observability tab', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Observability$/ }).first().click();
    await adminPage.waitForTimeout(1500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '09-admin-observability.png'), fullPage: true });
  });

  test('10 - Create User form', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(500);
    await adminPage.locator('main button', { hasText: /create user/i }).click();
    await adminPage.waitForTimeout(1000);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '10-create-user.png'), fullPage: true });
  });

  test('11 - Add Shelter form', async ({ coordinatorPage }) => {
    // ShelterForm is at /coordinator/shelters/new
    await coordinatorPage.goto('/coordinator/shelters/new');
    await coordinatorPage.waitForTimeout(1500);
    await coordinatorPage.screenshot({ path: path.join(DEMO_DIR, '11-add-shelter.png'), fullPage: true });
  });

  test('12 - Shelter detail from admin', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('main button', { hasText: /^Shelters$/ }).first().click();
    await adminPage.waitForTimeout(1000);
    // Click the first shelter name in the table
    const firstShelterLink = adminPage.locator('main tbody tr td').first();
    if (await firstShelterLink.count() > 0) {
      await firstShelterLink.click();
      await adminPage.waitForTimeout(1500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, '12-shelter-detail.png'), fullPage: true });
  });

  test('13 - Shelter detail from search', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(1500);
    // Click on first shelter card/result if visible
    const shelterCard = outreachPage.locator('main [style*="cursor: pointer"], main button', { hasText: /shelter|haven|hope/i });
    if (await shelterCard.count() > 0) {
      await shelterCard.first().click();
      await outreachPage.waitForTimeout(1500);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '13-search-shelter-detail.png'), fullPage: true });
  });

  test('14 - Spanish language', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(500);
    // Switch to Spanish via the locale selector
    const localeSelect = outreachPage.locator('select');
    if (await localeSelect.count() > 0) {
      await localeSelect.first().selectOption('es');
      await outreachPage.waitForTimeout(1500);
    }
    await outreachPage.screenshot({ path: path.join(DEMO_DIR, '14-spanish.png'), fullPage: true });
    // Switch back to English
    if (await localeSelect.count() > 0) {
      await localeSelect.first().selectOption('en');
    }
  });

  test('15 - Grafana dashboard', async ({ browser }) => {
    // Grafana doesn't need app auth — uses its own admin/admin
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:3000/d/fabt-operations/fabt-operations?orgId=1&from=now-1h&to=now', { timeout: 10000 });
      // Login to Grafana
      const loginForm = page.locator('input[name="user"]');
      if (await loginForm.count() > 0) {
        await loginForm.fill('admin');
        await page.locator('input[name="password"]').fill('admin');
        await page.locator('button[type="submit"]').click();
        await page.waitForTimeout(2000);
        // Skip password change if prompted
        const skipButton = page.locator('a', { hasText: /skip/i });
        if (await skipButton.count() > 0) await skipButton.click();
        await page.goto('http://localhost:3000/d/fabt-operations/fabt-operations?orgId=1&from=now-1h&to=now');
      }
      await page.waitForTimeout(3000);
      await page.screenshot({ path: path.join(DEMO_DIR, '15-grafana-dashboard.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Grafana not available — skipping screenshot 10');
    }
  });

  test('16 - Jaeger traces', async ({ browser }) => {
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('http://localhost:16686/search?service=finding-a-bed-tonight&limit=20', { timeout: 10000 });
      await page.waitForTimeout(3000);
      // Click Find Traces if button exists
      const findButton = page.locator('button', { hasText: /find traces/i });
      if (await findButton.count() > 0) {
        await findButton.click();
        await page.waitForTimeout(3000);
      }
      await page.screenshot({ path: path.join(DEMO_DIR, '16-jaeger-traces.png'), fullPage: true });
      await context.close();
    } catch {
      console.log('Jaeger not available — skipping screenshot 11');
    }
  });
});
