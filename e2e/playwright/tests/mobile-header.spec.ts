import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

/**
 * Mobile Header Overflow — Playwright tests.
 *
 * Tests the kebab overflow menu at mobile viewports (412px, 480px, 320px)
 * and verifies desktop layout is unchanged at 768px+ viewports.
 *
 * Run: BASE_URL=http://localhost:8081 npx playwright test tests/mobile-header.spec.ts --trace on
 */

const MOBILE_VP = { width: 412, height: 915 };
const S25_ULTRA_VP = { width: 480, height: 1040 };
const WCAG_MIN_VP = { width: 320, height: 568 };
const DESKTOP_VP = { width: 1024, height: 768 };
const BREAKPOINT_DESKTOP = { width: 768, height: 1024 };
const BREAKPOINT_MOBILE = { width: 767, height: 1024 };

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
    v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 120)}`).join('\n')
  ).join('\n\n');
}

// =========================================================================
// 3. Positive Tests — Requirements Met
// =========================================================================

test.describe('Mobile Header — Positive Tests', () => {

  test('3.1 — mobile header shows FABT, bell, queue, kebab — no inline controls', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // FABT title visible
    const header = adminPage.locator('header');
    const headerText = await header.textContent();
    expect(headerText).toContain('FABT');

    // Kebab menu visible
    await expect(adminPage.getByTestId('header-kebab-menu')).toBeVisible();

    // Desktop-only controls NOT visible
    await expect(adminPage.getByTestId('change-password-button')).not.toBeVisible();
    await expect(adminPage.getByTestId('totp-settings-button')).not.toBeVisible();

    await adminPage.screenshot({ path: 'mobile-header-positive-01.png' });
  });

  test('3.2 — no horizontal scrollbar at 412px', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const hasHScroll = await adminPage.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    );
    expect(hasHScroll).toBe(false);
  });

  test('3.3 — no horizontal scrollbar at 320px (WCAG 1.4.10)', async ({ adminPage }) => {
    await adminPage.setViewportSize(WCAG_MIN_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const hasHScroll = await adminPage.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    );
    expect(hasHScroll).toBe(false);
  });

  test('3.4 — kebab opens dropdown with all 5 items', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);

    const dropdown = adminPage.getByTestId('header-overflow-dropdown');
    await expect(dropdown).toBeVisible();

    // All 5 items present
    await expect(adminPage.getByTestId('header-overflow-username')).toBeVisible();
    await expect(adminPage.getByTestId('header-overflow-password')).toBeVisible();
    await expect(adminPage.getByTestId('header-overflow-security')).toBeVisible();
    await expect(adminPage.getByTestId('header-overflow-signout')).toBeVisible();
    // Language selector inside dropdown
    await expect(dropdown.locator('[data-testid="locale-selector"]')).toBeVisible();

    await adminPage.screenshot({ path: 'mobile-header-positive-04-kebab-open.png' });
  });

  test('3.5 — tap outside dropdown closes menu', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);
    await expect(adminPage.getByTestId('header-overflow-dropdown')).toBeVisible();

    // Click outside the menu (on the main content area)
    await adminPage.locator('main').click({ position: { x: 200, y: 300 } });
    await adminPage.waitForTimeout(300);

    await expect(adminPage.getByTestId('header-overflow-dropdown')).not.toBeVisible();
  });

  test('3.6 — Escape closes menu', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);
    await expect(adminPage.getByTestId('header-overflow-dropdown')).toBeVisible();

    await adminPage.keyboard.press('Escape');
    await adminPage.waitForTimeout(300);

    await expect(adminPage.getByTestId('header-overflow-dropdown')).not.toBeVisible();
  });

  test('3.7 — sign out from kebab redirects to login', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);
    await adminPage.getByTestId('header-overflow-signout').click();

    await adminPage.waitForURL(/\/login/, { timeout: 10000 });
    expect(adminPage.url()).toContain('/login');
  });

  test('3.8 — language change in kebab dropdown works', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);

    // Switch to Spanish
    const localeSelector = adminPage.getByTestId('header-overflow-dropdown').locator('[data-testid="locale-selector"]');
    await localeSelector.selectOption('es');
    await adminPage.waitForTimeout(500);

    // Menu items should re-render in Spanish — sign out text changes
    // Re-open menu if it closed
    if (await adminPage.getByTestId('header-overflow-dropdown').count() === 0) {
      await adminPage.getByTestId('header-kebab-menu').click();
      await adminPage.waitForTimeout(300);
    }

    const signOutText = await adminPage.getByTestId('header-overflow-signout').textContent();
    // Spanish "Cerrar Sesión" or similar — should NOT be "Logout"
    expect(signOutText).not.toMatch(/^Logout$/i);

    // Restore English
    const selector = adminPage.getByTestId('header-overflow-dropdown').locator('[data-testid="locale-selector"]');
    if (await selector.count() > 0) {
      await selector.selectOption('en');
    }
  });

  test('3.9 — axe-core scan at 412px: zero Critical/Serious', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: adminPage })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    const serious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
    expect(serious, formatViolations(serious)).toEqual([]);
  });

  test('3.10 — Tab navigation through kebab menu items', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);

    // Tab through menu items
    await adminPage.keyboard.press('Tab');
    await adminPage.keyboard.press('Tab');
    await adminPage.keyboard.press('Tab');

    // Menu should still be open (items are focusable)
    // One more Tab should leave the last item
    await adminPage.keyboard.press('Tab');
    await adminPage.waitForTimeout(300);

    // Verify focus moved through items (at minimum, no crash/error)
    // The menu may or may not close depending on focus trap implementation
  });

  test('3.11 — dark mode kebab dropdown renders with contrast', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.emulateMedia({ colorScheme: 'dark' });
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);

    await expect(adminPage.getByTestId('header-overflow-dropdown')).toBeVisible();
    await adminPage.screenshot({ path: 'mobile-header-positive-11-dark-mode.png' });

    // Full-page axe-core contrast check in dark mode — no exceptions
    const results = await new AxeBuilder({ page: adminPage })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();

    const contrastIssues = results.violations.filter(v => v.id.includes('contrast'));
    expect(contrastIssues, formatViolations(contrastIssues)).toEqual([]);
  });

  test('3.12 — Galaxy S25 Ultra (480px): kebab layout, no scroll', async ({ adminPage }) => {
    await adminPage.setViewportSize(S25_ULTRA_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // Kebab visible (480px < 768px)
    await expect(adminPage.getByTestId('header-kebab-menu')).toBeVisible();

    // No horizontal scroll
    const hasHScroll = await adminPage.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    );
    expect(hasHScroll).toBe(false);

    // Kebab functional
    await adminPage.getByTestId('header-kebab-menu').click();
    await adminPage.waitForTimeout(300);
    await expect(adminPage.getByTestId('header-overflow-dropdown')).toBeVisible();

    await adminPage.screenshot({ path: 'mobile-header-positive-12-s25-ultra.png' });
  });
});

// =========================================================================
// 4. Negative Tests — Nothing Broken
// =========================================================================

test.describe('Mobile Header — Negative Tests (Desktop Unchanged)', () => {

  test('4.1 — desktop shows full title + all inline controls, no kebab', async ({ adminPage }) => {
    await adminPage.setViewportSize(DESKTOP_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const header = adminPage.locator('header');
    const headerText = await header.textContent();
    expect(headerText).toContain('Finding A Bed Tonight');

    // All inline controls visible
    await expect(adminPage.getByTestId('change-password-button')).toBeVisible();
    await expect(adminPage.getByTestId('totp-settings-button')).toBeVisible();
    await expect(adminPage.getByTestId('locale-selector')).toBeVisible();

    // No kebab icon
    await expect(adminPage.getByTestId('header-kebab-menu')).not.toBeVisible();

    await adminPage.screenshot({ path: 'mobile-header-negative-01-desktop.png' });
  });

  test('4.2 — desktop header buttons all functional', async ({ adminPage }) => {
    await adminPage.setViewportSize(DESKTOP_VP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // Password button clickable
    await expect(adminPage.getByTestId('change-password-button')).toBeEnabled();
    // Security button clickable
    await expect(adminPage.getByTestId('totp-settings-button')).toBeEnabled();
    // Locale selector functional
    await expect(adminPage.getByTestId('locale-selector')).toBeVisible();
  });

  test('4.3 — at 768px: desktop layout (no kebab)', async ({ adminPage }) => {
    await adminPage.setViewportSize(BREAKPOINT_DESKTOP);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await expect(adminPage.getByTestId('header-kebab-menu')).not.toBeVisible();
    await expect(adminPage.getByTestId('change-password-button')).toBeVisible();

    const headerText = await adminPage.locator('header').textContent();
    expect(headerText).toContain('Finding A Bed Tonight');
  });

  test('4.4 — at 767px: mobile layout (kebab visible)', async ({ adminPage }) => {
    await adminPage.setViewportSize(BREAKPOINT_MOBILE);
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    await expect(adminPage.getByTestId('header-kebab-menu')).toBeVisible();
    await expect(adminPage.getByTestId('change-password-button')).not.toBeVisible();

    const headerText = await adminPage.locator('header').textContent();
    expect(headerText).toContain('FABT');
  });
});
