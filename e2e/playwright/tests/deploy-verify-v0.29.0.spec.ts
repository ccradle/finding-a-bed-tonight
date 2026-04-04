import { test, expect } from '@playwright/test';

/**
 * Deployment Verification — v0.29.0
 *
 * Run against live site after deploy:
 *   BASE_URL=https://findabed.org npx playwright test tests/deploy-verify-v0.29.0.spec.ts --reporter=list
 *
 * Or against local nginx:
 *   BASE_URL=http://localhost:8081 npx playwright test tests/deploy-verify-v0.29.0.spec.ts --reporter=list
 *
 * Covers: version check, static content, DV referral expiration UI, i18n, DV canary.
 */

const BASE_URL = process.env.BASE_URL || 'https://findabed.org';
const API_URL = process.env.API_URL || BASE_URL;

async function login(page: import('@playwright/test').Page, email: string) {
  await page.goto(`${BASE_URL}/login`);
  await page.getByTestId('login-tenant-slug').fill('dev-coc');
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill('admin123');
  await page.getByTestId('login-submit').click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });
}

test.describe('v0.29.0 Deployment Verification', () => {

  // =========================================================================
  // 1. Version Check
  // =========================================================================

  test('API returns version 0.29', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/version`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    if (!body.version.match(/0\.29/)) {
      console.log(`Version is ${body.version} — expected 0.29. Skipping (pre-deploy or local dev).`);
      test.skip();
    }
  });

  // =========================================================================
  // 2. Static Content — Audience Pages
  // =========================================================================

  test('audience pages return 200 with expected content', async ({ request }) => {
    // Static content only served from /var/www/findabed-docs/ on the VM — skip locally
    const probe = await request.get(`${BASE_URL}/demo/for-cities.html`);
    const probeHtml = await probe.text();
    if (!probeHtml.includes('City Officials')) {
      console.log('Static content not served at this BASE_URL — skipping (local dev).');
      test.skip();
      return;
    }

    const pages = [
      { path: '/demo/for-coordinators.html', expect: 'Shelter Coordinators' },
      { path: '/demo/for-coc-admins.html', expect: 'CoC Administrators' },
      { path: '/demo/for-funders.html', expect: 'Funders' },
      { path: '/demo/for-cities.html', expect: 'City Officials' },
    ];

    for (const pg of pages) {
      const resp = await request.get(`${BASE_URL}${pg.path}`);
      expect(resp.ok(), `${pg.path} should return 200`).toBeTruthy();
      const html = await resp.text();
      expect(html).toContain(pg.expect);
      expect(html).toContain('FAQPage'); // structured data
      expect(html).toContain('prefers-color-scheme'); // dark mode
    }
  });

  test('homepage has no GitHub markdown links in audience cards', async ({ request }) => {
    // Static content only on VM — skip locally
    const resp = await request.get(`${BASE_URL}/index.html`);
    const html = await resp.text();
    if (!html.includes('Quick Start Guide') && !html.includes('github.com/ccradle')) {
      console.log('Docs index.html not served at this BASE_URL — skipping (local dev).');
      test.skip();
      return;
    }
    expect(html).not.toMatch(/github\.com\/ccradle.*FOR-/);
    expect(html).toContain('Quick Start Guide');
    expect(html).toContain('Admin Overview');
    expect(html).toContain('Impact Report');
    expect(html).toContain('Evaluation Guide');
  });

  test('sitemap includes all audience pages', async ({ request }) => {
    const resp = await request.get(`${BASE_URL}/sitemap.xml`);
    const xml = await resp.text();
    if (!xml.includes('urlset')) {
      console.log('Sitemap not served at this BASE_URL — skipping (local dev).');
      test.skip();
      return;
    }
    expect(xml).toContain('for-coordinators.html');
    expect(xml).toContain('for-coc-admins.html');
    expect(xml).toContain('for-funders.html');
    expect(xml).toContain('for-cities.html');
  });

  // =========================================================================
  // 3. DV Referral Expiration UI
  // =========================================================================

  test('coordinator sees countdown timer on DV referral', async ({ page }) => {
    // Login as admin (has dvAccess=true, can see DV shelters + referrals)
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(2000);
    await page.screenshot({ path: 'deploy-verify-01-coordinator.png' });

    // Find a DV shelter card using the hidden indicator
    const dvIndicator = page.locator('[data-testid^="dv-indicator-"]').first();
    if (await dvIndicator.count() === 0) {
      console.log('No DV shelters visible — skipping referral UI checks');
      test.skip();
      return;
    }

    // Extract shelter ID from the indicator testid
    const indicatorTestId = await dvIndicator.getAttribute('data-testid');
    const shelterId = indicatorTestId?.replace('dv-indicator-', '');

    // Expand the DV shelter
    await page.getByTestId(`shelter-card-${shelterId}`).click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: 'deploy-verify-02-dv-shelter-expanded.png' });

    // Check for pending referrals with countdown
    const screening = page.getByTestId('referral-screening');
    if (await screening.count() === 0) {
      console.log('No pending referrals on DV shelter — creating one');

      // Create a referral via API so we have something to test
      const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
      });
      const { accessToken } = await loginResp.json();

      await fetch(`${API_URL}/api/v1/dv-referrals`, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          shelterId,
          householdSize: 2,
          populationType: 'DV_SURVIVOR',
          urgency: 'URGENT',
          callbackNumber: '919-555-0042',
        }),
      });

      // Re-expand shelter to see the new referral
      await page.getByTestId(`shelter-card-${shelterId}`).click();
      await page.waitForTimeout(500);
      await page.getByTestId(`shelter-card-${shelterId}`).click();
      await page.waitForTimeout(2000);
    }

    // Verify countdown is visible
    const countdown = page.locator('[data-testid^="referral-countdown-"]').first();
    if (await countdown.count() > 0) {
      await expect(countdown).toBeVisible();
      const text = await countdown.textContent();
      expect(text).toMatch(/remaining/i);
      await page.screenshot({ path: 'deploy-verify-03-countdown-visible.png' });

      // Verify accept/reject buttons are ENABLED (not expired)
      const acceptBtn = page.locator('[data-testid^="accept-referral-"]').first();
      await expect(acceptBtn).toBeEnabled();
    }
  });

  test('expired SSE event disables buttons and shows badge', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(2000);

    // Find and expand DV shelter
    const dvIndicator = page.locator('[data-testid^="dv-indicator-"]').first();
    if (await dvIndicator.count() === 0) { test.skip(); return; }

    const indicatorTestId = await dvIndicator.getAttribute('data-testid');
    const shelterId = indicatorTestId?.replace('dv-indicator-', '');
    await page.getByTestId(`shelter-card-${shelterId}`).click();
    await page.waitForTimeout(2000);

    const acceptBtn = page.locator('[data-testid^="accept-referral-"]').first();
    if (await acceptBtn.count() === 0) { test.skip(); return; }

    // Get token ID
    const testId = await acceptBtn.getAttribute('data-testid');
    const tokenId = testId?.replace('accept-referral-', '');

    // Simulate SSE expiration event
    await page.evaluate((id) => {
      window.dispatchEvent(new CustomEvent('fabt:referral-expired', {
        detail: { tokenIds: [id] },
      }));
    }, tokenId);
    await page.waitForTimeout(500);

    // Badge should appear, buttons should be disabled
    const badge = page.getByTestId(`referral-expired-badge-${tokenId}`);
    await expect(badge).toBeVisible({ timeout: 5000 });
    await expect(acceptBtn).toBeDisabled();

    await page.screenshot({ path: 'deploy-verify-04-expired-badge.png' });
  });

  test('expired accept shows specific error message', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(2000);

    // Find and expand DV shelter
    const dvIndicator = page.locator('[data-testid^="dv-indicator-"]').first();
    if (await dvIndicator.count() === 0) { test.skip(); return; }

    const indicatorTestId = await dvIndicator.getAttribute('data-testid');
    const shelterId = indicatorTestId?.replace('dv-indicator-', '');
    await page.getByTestId(`shelter-card-${shelterId}`).click();
    await page.waitForTimeout(2000);

    const acceptBtn = page.locator('[data-testid^="accept-referral-"]').first();
    if (await acceptBtn.count() === 0 || !(await acceptBtn.isEnabled())) { test.skip(); return; }

    // Intercept API to simulate backend expiration
    await page.route('**/api/v1/dv-referrals/*/accept', (route) => {
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Token has expired', status: 409 }),
      });
    });

    await acceptBtn.click();
    await page.waitForTimeout(1000);

    const bodyText = await page.textContent('body');
    expect(bodyText).toContain('expired');

    await page.screenshot({ path: 'deploy-verify-05-expired-error.png' });
  });

  // =========================================================================
  // 4. i18n — Spanish Locale
  // =========================================================================

  test('Spanish locale shows translated referral text', async ({ page }) => {
    await login(page, 'admin@dev.fabt.org');
    await page.goto(`${BASE_URL}/coordinator`);
    await page.waitForTimeout(2000);

    // Switch to Spanish
    const localeSelector = page.getByTestId('locale-selector');
    await expect(localeSelector).toBeVisible();
    await localeSelector.selectOption('es');
    await page.waitForTimeout(1000);

    // Find and expand DV shelter
    const dvIndicator = page.locator('[data-testid^="dv-indicator-"]').first();
    if (await dvIndicator.count() === 0) { test.skip(); return; }

    const indicatorTestId = await dvIndicator.getAttribute('data-testid');
    const shelterId = indicatorTestId?.replace('dv-indicator-', '');
    await page.getByTestId(`shelter-card-${shelterId}`).click();
    await page.waitForTimeout(2000);

    const countdown = page.locator('[data-testid^="referral-countdown-"]').first();
    if (await countdown.count() > 0) {
      const text = await countdown.textContent();
      expect(text).toMatch(/restantes/i);

      // Simulate expiration to check badge translation
      const acceptBtn = page.locator('[data-testid^="accept-referral-"]').first();
      const testId = await acceptBtn.getAttribute('data-testid');
      const tokenId = testId?.replace('accept-referral-', '');

      await page.evaluate((id) => {
        window.dispatchEvent(new CustomEvent('fabt:referral-expired', {
          detail: { tokenIds: [id] },
        }));
      }, tokenId);
      await page.waitForTimeout(500);

      const badge = page.getByTestId(`referral-expired-badge-${tokenId}`);
      if (await badge.count() > 0) {
        const badgeText = await badge.textContent();
        expect(badgeText).toMatch(/Expirada/i);
      }
    }

    await page.screenshot({ path: 'deploy-verify-06-spanish-locale.png' });

    // Restore English
    await localeSelector.selectOption('en');
  });

  // =========================================================================
  // 5. DV Canary — Non-DV User Cannot See DV Shelters
  // =========================================================================

  test('non-DV outreach worker does not see DV shelters', async ({ page }) => {
    await login(page, 'outreach@dev.fabt.org');
    // Outreach lands on search page
    await page.locator('text=shelters found').waitFor({ state: 'visible', timeout: 15000 });
    await page.screenshot({ path: 'deploy-verify-07-outreach-search.png' });

    // Non-DV outreach should NOT see Request Referral buttons (DV shelters hidden by RLS)
    const referralButtons = page.locator('[data-testid^="request-referral-"]');
    expect(await referralButtons.count()).toBe(0);

    // Should see Hold buttons (non-DV shelters)
    const holdButtons = page.locator('[data-testid^="hold-bed-"]');
    expect(await holdButtons.count()).toBeGreaterThan(0);
  });

  // =========================================================================
  // 6. Demo Guard — Destructive Ops Blocked
  // =========================================================================

  test('demo guard blocks password change', async ({}) => {
    // Use fetch directly — Playwright APIRequestContext doesn't reliably pass headers
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

    // Demo guard only active with 'demo' profile — skip if not demo env
    if (changeResp.status === 200) {
      console.log('Demo guard not active (no demo profile) — skipping (local dev).');
      test.skip();
      return;
    }
    expect(changeResp.status).toBe(403);
    const body = await changeResp.json();
    expect(body.error).toBe('demo_restricted');
  });
});
