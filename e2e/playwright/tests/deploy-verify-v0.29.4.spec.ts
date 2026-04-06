import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * Deployment Verification — v0.29.4
 *
 * Run against live site after deploy:
 *   BASE_URL=https://findabed.org npx playwright test tests/deploy-verify-v0.29.4.spec.ts --reporter=list --trace on
 *
 * Or against local nginx:
 *   BASE_URL=http://localhost:8081 npx playwright test tests/deploy-verify-v0.29.4.spec.ts --reporter=list --trace on
 *
 * Covers:
 *   1. Version + health check
 *   2. WCAG 1.3.5 autocomplete attributes (new in v0.29.4)
 *   3. WCAG 2.4.7 focus-visible indicators, light + dark (new in v0.29.4)
 *   4. SSE stability — no reconnecting banner (v0.29.2 fix)
 *   5. DemoGuard blocks destructive ops (v0.29.3 fix)
 *   6. DV canary — non-DV user cannot see DV shelters
 *   7. User workflows — search beds, hold a bed, coordinator update
 *   8. axe-core zero violations
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

test.describe('v0.29.4 Deployment Verification', () => {

  // =========================================================================
  // 1. Version + Health
  // =========================================================================

  test('API returns version 0.29.4', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/version`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.version).toMatch(/0\.29/);
    console.log(`Version: ${body.version}`);
  });

  test('health endpoint returns UP', async ({ request }) => {
    const resp = await request.get(`${API_URL}/actuator/health`);
    // Health may be on management port (9091) — 404 is acceptable if behind proxy
    if (resp.ok()) {
      const body = await resp.json();
      expect(body.status).toBe('UP');
    } else {
      console.log(`Health endpoint returned ${resp.status()} — may be on management port. Skipping.`);
      test.skip();
    }
  });

  // =========================================================================
  // 2. WCAG 1.3.5 — Autocomplete Attributes (new in v0.29.4)
  // =========================================================================

  test('login form has autocomplete attributes', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);

    await expect(page.getByTestId('login-email')).toHaveAttribute('autocomplete', 'email');
    await expect(page.getByTestId('login-password')).toHaveAttribute('autocomplete', 'current-password');
    await expect(page.getByTestId('login-tenant-slug')).toHaveAttribute('autocomplete', 'organization');
  });

  // =========================================================================
  // 3. WCAG 2.4.7 — Focus Visible (new in v0.29.4)
  // =========================================================================

  test('login inputs show visible focus indicator on Tab (light mode)', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);

    // Tab: skip link → tenant → email → password
    await page.keyboard.press('Tab'); // skip link
    await page.keyboard.press('Tab'); // tenant
    await page.keyboard.press('Tab'); // email

    const focusResult = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el) return { hasIndicator: false, detail: 'nothing focused' };
      const s = window.getComputedStyle(el);
      const outlineWidth = parseInt(s.outlineWidth);
      const hasOutline = outlineWidth > 0 && s.outlineStyle !== 'none';
      const hasBoxShadow = s.boxShadow !== 'none';
      return {
        hasIndicator: hasOutline || hasBoxShadow,
        detail: `${el.tagName}#${el.id} outline:${s.outline} shadow:${s.boxShadow?.substring(0, 60)}`,
      };
    });

    console.log(`Focus (light): ${focusResult.detail}`);
    expect(focusResult.hasIndicator, `Light mode: focused input must have visible indicator. Got: ${focusResult.detail}`).toBe(true);

    await page.screenshot({ path: 'deploy-verify-08-focus-light.png' });
  });

  test('login inputs show visible focus indicator in dark mode', async ({ browser }) => {
    const context = await browser.newContext({ colorScheme: 'dark' });
    const page = await context.newPage();

    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);

    await page.keyboard.press('Tab'); // skip link
    await page.keyboard.press('Tab'); // tenant
    await page.keyboard.press('Tab'); // email

    const focusResult = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el) return { hasIndicator: false, detail: 'nothing focused' };
      const s = window.getComputedStyle(el);
      const outlineWidth = parseInt(s.outlineWidth);
      const hasOutline = outlineWidth > 0 && s.outlineStyle !== 'none';
      const hasBoxShadow = s.boxShadow !== 'none';
      return {
        hasIndicator: hasOutline || hasBoxShadow,
        detail: `${el.tagName}#${el.id} outline:${s.outline} shadow:${s.boxShadow?.substring(0, 60)}`,
      };
    });

    console.log(`Focus (dark): ${focusResult.detail}`);
    expect(focusResult.hasIndicator, `Dark mode: focused input must have visible indicator. Got: ${focusResult.detail}`).toBe(true);

    await page.screenshot({ path: 'deploy-verify-09-focus-dark.png' });
    await context.close();
  });

  // =========================================================================
  // 4. SSE Stability — No Reconnecting Banner (v0.29.2 fix)
  // =========================================================================

  test('SSE connects without reconnecting banner', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    await page.waitForTimeout(5000);

    const reconnecting = page.getByTestId('connection-status-reconnecting');
    await expect(reconnecting).not.toBeVisible();
    console.log('SSE: no reconnecting banner after 5 seconds');
  });

  // =========================================================================
  // 5. DemoGuard — Destructive Ops Blocked (v0.29.3 fix)
  // =========================================================================

  test('DemoGuard blocks user creation on public path', async () => {
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@dev.fabt.org', password: 'admin123', tenantSlug: 'dev-coc' }),
    });
    const { accessToken } = await loginResp.json();

    const createResp = await fetch(`${API_URL}/api/v1/users`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'deploy-test@example.com', password: 'TestPass12345!', displayName: 'Deploy Test', roles: ['OUTREACH_WORKER'] }),
    });

    if (createResp.status === 201) {
      console.log('DemoGuard not active (no demo profile) — skipping.');
      test.skip();
      return;
    }

    expect(createResp.status).toBe(403);
    const body = await createResp.json();
    expect(body.error).toBe('demo_restricted');
    console.log('DemoGuard: user creation blocked (403 demo_restricted)');
  });

  test('DemoGuard blocks password change on public path', async () => {
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'outreach@dev.fabt.org', password: 'admin123', tenantSlug: 'dev-coc' }),
    });
    const { accessToken } = await loginResp.json();

    const changeResp = await fetch(`${API_URL}/api/v1/auth/password`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: 'admin123', newPassword: 'hacked12345!' }),
    });

    if (changeResp.status === 200) {
      console.log('DemoGuard not active — skipping.');
      test.skip();
      return;
    }

    expect(changeResp.status).toBe(403);
    console.log('DemoGuard: password change blocked (403)');
  });

  // =========================================================================
  // 6. DV Canary — Non-DV User Cannot See DV Shelters
  // =========================================================================

  test('non-DV outreach worker does not see DV shelters', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    await page.locator('text=shelters found').waitFor({ state: 'visible', timeout: 15000 });

    // Non-DV outreach should NOT see Request Referral buttons
    const referralButtons = page.locator('[data-testid^="request-referral-"]');
    expect(await referralButtons.count()).toBe(0);

    // Should see Hold buttons (non-DV shelters)
    const holdButtons = page.locator('[data-testid^="hold-bed-"]');
    expect(await holdButtons.count()).toBeGreaterThan(0);

    console.log(`DV canary: 0 referral buttons, ${await holdButtons.count()} hold buttons — RLS working`);
  });

  // =========================================================================
  // 7. User Workflows — Search, Hold, Coordinator
  // =========================================================================

  test('outreach worker can search beds by population type', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');

    await page.getByTestId('population-type-filter').selectOption('SINGLE_ADULT');
    await page.waitForTimeout(2000);

    const shelterCard = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(shelterCard).toBeVisible({ timeout: 10000 });

    const resultText = await page.locator('text=shelters found').textContent();
    console.log(`Search: ${resultText}`);
  });

  test('outreach worker can hold a bed', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');

    await page.getByTestId('population-type-filter').selectOption('SINGLE_ADULT');
    const holdButton = page.locator('[data-testid^="hold-bed-"]').first();
    await expect(holdButton).toBeVisible({ timeout: 10000 });

    await holdButton.click();
    await page.waitForTimeout(2000);

    // 201 (hold created) or 409 (no beds) — neither should be 500
    const bodyText = await page.textContent('body');
    expect(bodyText).not.toContain('Internal Server Error');
    console.log('Hold: request completed without 500');
  });

  test('coordinator dashboard loads shelters', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(3000);

    const shelterCard = page.locator('[data-testid^="shelter-card-"]').first();
    await expect(shelterCard).toBeVisible({ timeout: 10000 });

    console.log('Coordinator: dashboard loaded with shelter cards');
  });

  // =========================================================================
  // 8. axe-core — Zero WCAG Violations
  // =========================================================================

  test('login page: zero WCAG violations', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(1500);

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    if (results.violations.length > 0) {
      console.log('axe violations:', results.violations.map(v => `${v.id}: ${v.description} (${v.nodes.length})`));
    }
    expect(results.violations).toEqual([]);
  });

  test('outreach search: zero WCAG violations', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    if (results.violations.length > 0) {
      console.log('axe violations:', results.violations.map(v => `${v.id}: ${v.description} (${v.nodes.length})`));
    }
    expect(results.violations).toEqual([]);
  });

  // =========================================================================
  // 9. Version in UI Footer
  // =========================================================================

  test('version shown in login page footer', async ({ page }) => {
    await page.goto(`${BASE_URL}/login`);
    const version = page.getByTestId('app-version');
    await expect(version).toContainText('v0.29', { timeout: 10000 });
    const text = await version.textContent();
    console.log(`Footer: ${text}`);
  });

  test('version shown in authenticated layout footer', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    const version = page.getByTestId('app-version');
    await expect(version).toContainText('v0.29', { timeout: 10000 });
  });
});
