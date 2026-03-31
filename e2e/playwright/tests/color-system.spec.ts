import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

/**
 * Color system and dark mode tests.
 *
 * Written BEFORE component migration (TDD approach):
 * - T-24 (no hardcoded hex) will FAIL until all 18 components are migrated
 * - T-20 (dark mode rendering) will FAIL until migration provides var() references
 * - T-21 (dark mode axe contrast) will FAIL until dark palette is applied
 * - T-22 (light mode regression) should PASS now and continue passing after migration
 *
 * Riley: "If you migrate 18 files without a test that catches regressions,
 * you're trusting yourself to get 593 replacements right. You won't."
 */

test.describe('Light Mode Regression Guard (T-22)', () => {

  test('light mode: no contrast violations on search page', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: outreachPage })
      .withRules(['color-contrast'])
      .analyze();

    expect(results.violations).toEqual([]);
  });

  test('light mode: no contrast violations on admin panel', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: adminPage })
      .withRules(['color-contrast'])
      .analyze();

    expect(results.violations).toEqual([]);
  });
});

test.describe('Dark Mode Rendering (T-20)', () => {

  test('dark mode: app renders with dark background on all key views', async ({ browser }) => {
    // Create a context with dark color scheme emulation
    const context = await browser.newContext({
      colorScheme: 'dark',
      storageState: await getAuthState(browser),
    });
    const page = await context.newPage();

    // Check search page
    await page.goto('/outreach');
    await page.waitForTimeout(2000);

    const searchBg = await page.evaluate(() => {
      return window.getComputedStyle(document.body).backgroundColor;
    });
    // Dark mode bg should NOT be white
    expect(searchBg).not.toBe('rgb(255, 255, 255)');

    // Check admin page
    await page.goto('/admin');
    await page.waitForTimeout(2000);

    const adminBg = await page.evaluate(() => {
      const main = document.querySelector('main');
      return main ? window.getComputedStyle(main).backgroundColor : 'unknown';
    });
    expect(adminBg).not.toBe('rgb(255, 255, 255)');

    // Check coordinator dashboard
    await page.goto('/coordinator');
    await page.waitForTimeout(2000);

    const coordBg = await page.evaluate(() => {
      return window.getComputedStyle(document.body).backgroundColor;
    });
    expect(coordBg).not.toBe('rgb(255, 255, 255)');

    await context.close();
  });

  test('dark mode: capture screenshots for visual comparison', async ({ browser }) => {
    const path = await import('path');
    const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');

    const context = await browser.newContext({
      colorScheme: 'dark',
      storageState: await getAuthState(browser),
    });
    const page = await context.newPage();

    // Search page
    await page.goto('/outreach');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(DEMO_DIR, 'dark-search.png'), fullPage: true });

    // Coordinator dashboard
    await page.goto('/coordinator');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(DEMO_DIR, 'dark-coordinator.png'), fullPage: true });

    // Admin panel
    await page.goto('/admin');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(DEMO_DIR, 'dark-admin.png'), fullPage: true });

    // Login page
    await page.goto('/login');
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(DEMO_DIR, 'dark-login.png'), fullPage: true });

    await context.close();
  });
});

test.describe('Dark Mode Accessibility (T-21)', () => {

  test('dark mode: axe-core reports zero contrast violations on search page', async ({ browser }) => {
    const context = await browser.newContext({
      colorScheme: 'dark',
      storageState: await getAuthState(browser),
    });
    const page = await context.newPage();

    await page.goto('/outreach');
    await page.waitForTimeout(2000);

    const results = await new AxeBuilder({ page })
      .withRules(['color-contrast'])
      .analyze();

    if (results.violations.length > 0) {
      console.log(`Dark mode contrast violations (${results.violations.length}):`);
      for (const v of results.violations) {
        console.log(`  ${v.nodes.length}x: ${v.description}`);
        for (const n of v.nodes.slice(0, 5)) {
          console.log(`  NODE: ${n.html?.substring(0, 120)}`);
        }
      }
    }

    expect(results.violations).toEqual([]);

    await context.close();
  });
});

test.describe('No Hardcoded Hex Colors (T-24)', () => {

  test('no hardcoded hex colors in component source files', async () => {
    // Source-level check: grep .tsx files for inline hex color patterns.
    // Browser normalizes hex to rgb() so DOM-level checks don't work.
    const fs = await import('fs');
    const path = await import('path');
    const glob = await import('glob' as any).catch(() => null);

    const srcDir = path.resolve(__dirname, '../../../frontend/src');
    const violations: string[] = [];

    // Read all .tsx files in pages/ and components/
    const dirs = ['pages', 'components'];
    for (const dir of dirs) {
      const dirPath = path.join(srcDir, dir);
      if (!fs.existsSync(dirPath)) continue;

      const files = fs.readdirSync(dirPath).filter((f: string) => f.endsWith('.tsx'));
      for (const file of files) {
        const content = fs.readFileSync(path.join(dirPath, file), 'utf-8');
        const lines = content.split('\n');

        for (let i = 0; i < lines.length; i++) {
          const line = lines[i];
          // Skip comments and imports
          if (line.trim().startsWith('//') || line.trim().startsWith('*') || line.trim().startsWith('import')) continue;

          // Match hex colors in style contexts (color:, backgroundColor:, border:, etc.)
          const hexMatches = line.match(/'#[0-9a-fA-F]{3,8}'|"#[0-9a-fA-F]{3,8}"/g);
          if (hexMatches) {
            // Exclude OAuth provider brand colors (external brand guidelines, not our design system)
            // OAuth provider buttons use external brand colors (Google, Microsoft, Keycloak)
            const oauthBrandColors = ['#2f2f2f', '#3c4043', '#dadce0', '#4285f4', '#ffffff', '#374151'];
            const nonOauth = hexMatches.filter(h => {
              const hex = h.replace(/['"]/g, '').toLowerCase();
              return !oauthBrandColors.includes(hex);
            });
            if (nonOauth.length > 0) {
              violations.push(`${file}:${i + 1} — ${nonOauth.join(', ')}`);
            }
          }
        }
      }
    }

    // After migration, this should be empty.
    // Before migration, this will produce hundreds of violations.
    if (violations.length > 0) {
      console.log(`Found ${violations.length} hardcoded hex colors:\n${violations.slice(0, 20).join('\n')}${violations.length > 20 ? `\n... and ${violations.length - 20} more` : ''}`);
    }
    expect(violations.length).toBe(0);
  });
});

/**
 * Helper: get an authenticated storage state for dark mode tests.
 * Dark mode tests need their own browser context (for colorScheme: 'dark'),
 * so they can't use the fixture-provided pages directly.
 */
async function getAuthState(browser: any) {
  const fs = await import('fs');
  const path = await import('path');
  const stateFile = path.join(__dirname, '..', 'auth', 'admin.json');

  // If auth state exists and is valid, use it
  if (fs.existsSync(stateFile)) {
    return stateFile;
  }

  // Otherwise, log in and save state
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/login');
  await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
  await page.locator('[data-testid="login-email"]').fill('admin@dev.fabt.org');
  await page.locator('[data-testid="login-password"]').fill('admin123');
  await page.locator('[data-testid="login-submit"]').click();
  await page.waitForURL((url: URL) => !url.pathname.includes('/login'), { timeout: 10000 });
  const authDir = path.dirname(stateFile);
  if (!fs.existsSync(authDir)) fs.mkdirSync(authDir, { recursive: true });
  await context.storageState({ path: stateFile });
  await context.close();
  return stateFile;
}
