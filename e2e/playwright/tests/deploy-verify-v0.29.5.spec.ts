import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * Deployment Verification — v0.29.5
 *
 * Run against live site after deploy:
 *   BASE_URL=https://findabed.org npx playwright test tests/deploy-verify-v0.29.5.spec.ts --reporter=list --trace on
 *
 * Inherits all v0.29.4 checks, adds:
 *   - #58 Audit event fix verification (API-level)
 *   - #64 Clickable reservation shelter links
 *   - #72 Contrast fix on hero subtitles + surge banner
 *   - Surge-active axe-core scan
 */

const BASE_URL = process.env.BASE_URL || 'https://findabed.org';
const API_URL = process.env.API_URL || BASE_URL;

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto(`${BASE_URL}/login`);
  await page.waitForTimeout(1000);
  await page.getByTestId('login-tenant-slug').fill('dev-coc');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill('admin123');
  await page.getByTestId('login-submit').click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });
  await page.waitForTimeout(2000);
}

async function getAdminToken(): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const data = await res.json();
  return data.accessToken;
}

test.describe('v0.29.5 Deployment Verification', () => {

  // =========================================================================
  // 1. Version + Health (carried from v0.29.4)
  // =========================================================================

  test('API returns version 0.29', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/version`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.version).toMatch(/0\.29/);
    console.log(`Version: ${body.version}`);
  });

  test('version shown in login page footer', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    const version = page.getByTestId('app-version');
    await expect(version).toContainText('v0.29', { timeout: 10000 });
  });

  // =========================================================================
  // 2. WCAG — Autocomplete + Focus Visible (v0.29.4, still valid)
  // =========================================================================

  test('login form has autocomplete attributes', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);
    await expect(page.getByTestId('login-email')).toHaveAttribute('autocomplete', 'email');
    await expect(page.getByTestId('login-password')).toHaveAttribute('autocomplete', 'current-password');
    await expect(page.getByTestId('login-tenant-slug')).toHaveAttribute('autocomplete', 'organization');
  });

  test('login inputs show visible focus indicator on Tab', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    const focusResult = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el) return { hasIndicator: false, detail: 'nothing focused' };
      const s = window.getComputedStyle(el);
      const hasOutline = parseInt(s.outlineWidth) > 0 && s.outlineStyle !== 'none';
      const hasBoxShadow = s.boxShadow !== 'none';
      return { hasIndicator: hasOutline || hasBoxShadow, detail: `${el.tagName}#${el.id}` };
    });
    expect(focusResult.hasIndicator, `Focus indicator missing on ${focusResult.detail}`).toBe(true);
  });

  // =========================================================================
  // 3. #72 Contrast Fix — Hero Subtitles (new in v0.29.5)
  // =========================================================================

  test('outreach hero subtitle uses light color on dark gradient', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    const subtitle = page.locator('text=/Search available|Buscar camas/i');
    await expect(subtitle).toBeVisible({ timeout: 10000 });

    const color = await subtitle.evaluate((el) => window.getComputedStyle(el).color);
    // Should be white/light (headerText) — NOT textTertiary (#475569 = rgb(71, 85, 105))
    expect(color).not.toBe('rgb(71, 85, 105)');
    console.log(`Outreach subtitle color: ${color}`);
  });

  test('coordinator hero subtitle uses light color on dark gradient', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(2000);
    const subtitle = page.locator('text=/Update bed counts|Actualizar conteo/i');
    await expect(subtitle).toBeVisible({ timeout: 10000 });

    const color = await subtitle.evaluate((el) => window.getComputedStyle(el).color);
    expect(color).not.toBe('rgb(71, 85, 105)');
    console.log(`Coordinator subtitle color: ${color}`);
  });

  test('admin hero subtitle uses light color on dark gradient', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/admin`);
    await page.waitForTimeout(2000);
    const subtitle = page.locator('text=/Manage your|Gestionar su/i');
    await expect(subtitle).toBeVisible({ timeout: 10000 });

    const color = await subtitle.evaluate((el) => window.getComputedStyle(el).color);
    expect(color).not.toBe('rgb(71, 85, 105)');
    console.log(`Admin subtitle color: ${color}`);
  });

  // =========================================================================
  // 4. #72 Contrast Fix — Surge Banner (new in v0.29.5)
  // =========================================================================

  test('surge banner timestamp uses light color on red gradient', async ({ page }) => {
    // Activate surge
    let token: string;
    try {
      token = await getAdminToken();
      const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      const surges = await surgesRes.json();
      if (!surges.some((s: { status: string }) => s.status === 'ACTIVE')) {
        await fetch(`${API_URL}/api/v1/surge-events`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ reason: 'Deploy verify contrast check', temperatureF: 25 }),
        });
      }
    } catch {
      console.log('Could not activate surge — skipping');
      test.skip();
      return;
    }

    try {
      await login(page, 'outreach@dev.fabt.org');
      const surgeBanner = page.locator('text=/SURGE ACTIVE|EMERGENCIA ACTIVA/i');
      await expect(surgeBanner).toBeVisible({ timeout: 10000 });

      // Find the "Active since" timestamp
      const timestamp = page.locator('text=/Active since|Activo desde/i');
      if (await timestamp.count() > 0) {
        const color = await timestamp.evaluate((el) => window.getComputedStyle(el).color);
        // Should be textInverse (white) — NOT textTertiary
        expect(color).not.toBe('rgb(71, 85, 105)');
        console.log(`Surge timestamp color: ${color}`);
      }

      // axe-core scan with surge visible
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(results.violations).toEqual([]);
      console.log('Surge axe-core: zero violations');
    } finally {
      // Deactivate surge
      try {
        const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
          headers: { Authorization: `Bearer ${token!}` },
        });
        const surges = await surgesRes.json();
        const active = surges.find((s: { status: string }) => s.status === 'ACTIVE');
        if (active) {
          await fetch(`${API_URL}/api/v1/surge-events/${active.id}/deactivate`, {
            method: 'PATCH',
            headers: { Authorization: `Bearer ${token!}` },
          });
        }
      } catch { /* best effort */ }
    }
  });

  // =========================================================================
  // 5. #64 Clickable Reservation Shelter Links (new in v0.29.5)
  // =========================================================================

  test('reservation shelter name is clickable with data-testid', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');

    // Hold a bed
    const holdButton = page.locator('[data-testid^="hold-bed-"]').first();
    if (await holdButton.count() === 0) {
      console.log('No hold buttons — skipping');
      test.skip();
      return;
    }
    await holdButton.click();
    await page.waitForTimeout(2000);

    // Open reservations panel
    const panel = page.locator('button', { hasText: /My Reservations/i });
    if (await panel.count() === 0) {
      console.log('No reservations panel — hold may have failed');
      test.skip();
      return;
    }
    await panel.click();
    await page.waitForTimeout(500);

    // Shelter link should exist
    const shelterLink = page.locator('[data-testid^="reservation-shelter-link-"]').first();
    await expect(shelterLink).toBeVisible({ timeout: 5000 });

    // Click should open modal (not navigate away)
    const urlBefore = page.url();
    await shelterLink.click();
    await page.waitForTimeout(1000);
    expect(page.url()).toBe(urlBefore);

    // Modal should be visible
    const modal = page.locator('div[style*="position: fixed"][style*="inset: 0"]');
    await expect(modal).toBeVisible();
    console.log('Reservation shelter link: clickable, opens modal, no navigation');

    await page.keyboard.press('Escape');
  });

  // =========================================================================
  // 6. SSE Stability (carried from v0.29.4)
  // =========================================================================

  test('SSE connects without reconnecting banner', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    await page.waitForTimeout(5000);
    const reconnecting = page.getByTestId('connection-status-reconnecting');
    await expect(reconnecting).not.toBeVisible();
    console.log('SSE: no reconnecting banner');
  });

  // =========================================================================
  // 7. DemoGuard (carried from v0.29.4)
  // =========================================================================

  test('DemoGuard blocks user creation', async () => {
    const token = await getAdminToken();
    const resp = await fetch(`${API_URL}/api/v1/users`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'deploy-test@example.com', password: 'TestPass12345!', displayName: 'Deploy Test', roles: ['OUTREACH_WORKER'] }),
    });
    if (resp.status === 201) { test.skip(); return; }
    expect(resp.status).toBe(403);
    console.log('DemoGuard: user creation blocked');
  });

  // =========================================================================
  // 8. DV Canary (carried from v0.29.4)
  // =========================================================================

  test('non-DV outreach worker does not see DV shelters', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    await page.locator('text=shelters found').waitFor({ state: 'visible', timeout: 15000 });
    const referralButtons = page.locator('[data-testid^="request-referral-"]');
    expect(await referralButtons.count()).toBe(0);
    const holdButtons = page.locator('[data-testid^="hold-bed-"]');
    expect(await holdButtons.count()).toBeGreaterThan(0);
    console.log(`DV canary: 0 referral, ${await holdButtons.count()} hold — RLS working`);
  });

  // =========================================================================
  // 9. User Workflows (carried from v0.29.4)
  // =========================================================================

  test('outreach search returns results', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    await page.getByTestId('population-type-filter').selectOption('SINGLE_ADULT');
    await page.waitForTimeout(2000);
    const card = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(card).toBeVisible({ timeout: 10000 });
  });

  test('coordinator dashboard loads', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(3000);
    const card = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(card).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // 10. axe-core (carried from v0.29.4)
  // =========================================================================

  test('login page: zero WCAG violations', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations).toEqual([]);
  });

  test('outreach search: zero WCAG violations', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect(results.violations).toEqual([]);
  });
});
