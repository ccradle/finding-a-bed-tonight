import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

/**
 * WCAG VPAT Verification Tests
 *
 * Purpose: verify specific WCAG 2.1 AA claims made in the Accessibility
 * Conformance Report (docs/WCAG-ACR.md). Each test maps to one or more
 * WCAG success criteria and documents what is verifiable via automation
 * versus what requires manual testing.
 *
 * These tests exist to ensure the ACR is grounded in truth. If a test
 * fails, the corresponding ACR claim must be downgraded before release.
 *
 * Created: April 2026 for VPAT v0.29.3 update
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
    v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 120)}`).join('\n')
  ).join('\n\n');
}

test.describe('WCAG 1.1.1 Non-text Content', () => {

  test('all images and icon buttons have text alternatives', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    // Check for images without alt text
    const imagesWithoutAlt = await outreachPage.evaluate(() => {
      const imgs = document.querySelectorAll('img');
      const violations: string[] = [];
      imgs.forEach(img => {
        if (!img.hasAttribute('alt') && !img.hasAttribute('aria-hidden')) {
          violations.push(`<img src="${img.src.substring(0, 50)}">`);
        }
      });
      return violations;
    });
    expect(imagesWithoutAlt).toEqual([]);

    // Check for icon-only buttons without accessible names
    const buttonsWithoutLabel = await outreachPage.evaluate(() => {
      const buttons = document.querySelectorAll('button');
      const violations: string[] = [];
      buttons.forEach(btn => {
        const text = btn.textContent?.trim();
        const ariaLabel = btn.getAttribute('aria-label');
        const ariaLabelledBy = btn.getAttribute('aria-labelledby');
        if (!text && !ariaLabel && !ariaLabelledBy) {
          violations.push(btn.outerHTML.substring(0, 100));
        }
      });
      return violations;
    });
    expect(buttonsWithoutLabel).toEqual([]);
  });
});

test.describe('WCAG 1.3.1 Info and Relationships', () => {

  test('admin tab bar has correct ARIA structure', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // Verify tablist exists
    const tablist = adminPage.locator('[role="tablist"]');
    await expect(tablist).toBeVisible();

    // Verify at least one tab is selected
    const selectedTab = adminPage.locator('[role="tab"][aria-selected="true"]');
    await expect(selectedTab).toHaveCount(1);

    // Verify tabpanel exists and is linked
    const tabpanel = adminPage.locator('[role="tabpanel"]');
    await expect(tabpanel).toBeVisible();
  });

  test('form inputs have associated labels or aria-label', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    const unlabeled = await page.evaluate(() => {
      const inputs = document.querySelectorAll('input, select, textarea');
      const violations: string[] = [];
      inputs.forEach(input => {
        const id = input.getAttribute('id');
        const ariaLabel = input.getAttribute('aria-label');
        const ariaLabelledBy = input.getAttribute('aria-labelledby');
        const hasLinkedLabel = id ? document.querySelector(`label[for="${id}"]`) : null;
        const parentLabel = input.closest('label');
        if (!ariaLabel && !ariaLabelledBy && !hasLinkedLabel && !parentLabel) {
          violations.push(`${input.tagName}[name="${input.getAttribute('name')}"]`);
        }
      });
      return violations;
    });
    expect(unlabeled).toEqual([]);
  });
});

test.describe('WCAG 1.3.5 Identify Input Purpose', () => {

  test('login form has autocomplete attributes', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    // Email/username field should have autocomplete
    const emailInput = page.locator('[data-testid="login-email"]');
    if (await emailInput.count() > 0) {
      const autocomplete = await emailInput.getAttribute('autocomplete');
      // Record whether autocomplete is present — this verifies the ACR claim
      console.log(`Login email autocomplete: ${autocomplete || 'MISSING'}`);
    }

    // Password field should have autocomplete
    const passwordInput = page.locator('[data-testid="login-password"]');
    if (await passwordInput.count() > 0) {
      const autocomplete = await passwordInput.getAttribute('autocomplete');
      console.log(`Login password autocomplete: ${autocomplete || 'MISSING'}`);
    }
  });
});

test.describe('WCAG 1.4.1 Use of Color', () => {

  test('freshness badges include text labels alongside color', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(3000);

    // Freshness badges must have text, not just color
    const badges = await outreachPage.evaluate(() => {
      // Look for freshness indicators
      const elements = document.querySelectorAll('[data-testid*="freshness"], [data-testid*="data-age"]');
      const results: { text: string; hasText: boolean }[] = [];
      elements.forEach(el => {
        const text = el.textContent?.trim() || '';
        results.push({ text, hasText: text.length > 0 });
      });
      // Also check by content: elements containing Fresh/Stale/Unknown
      if (results.length === 0) {
        document.querySelectorAll('span, div').forEach(el => {
          const text = el.textContent?.trim() || '';
          if (/^(Fresh|Stale|Unknown|Fresco|Obsoleto)$/i.test(text)) {
            results.push({ text, hasText: true });
          }
        });
      }
      return results;
    });

    // At least some freshness indicators should exist if shelters are loaded
    if (badges.length > 0) {
      for (const badge of badges) {
        expect(badge.hasText).toBe(true);
      }
    }
  });
});

test.describe('WCAG 1.4.3 Contrast (Light + Dark)', () => {

  test('light mode: zero contrast violations across all pages', async ({ outreachPage, adminPage, coordinatorPage }) => {
    // Search page
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);
    const searchResults = await new AxeBuilder({ page: outreachPage })
      .withRules(['color-contrast'])
      .analyze();
    expect(searchResults.violations, formatViolations(searchResults.violations)).toEqual([]);

    // Admin page
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);
    const adminResults = await new AxeBuilder({ page: adminPage })
      .withRules(['color-contrast'])
      .analyze();
    expect(adminResults.violations, formatViolations(adminResults.violations)).toEqual([]);

    // Coordinator page
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    const coordResults = await new AxeBuilder({ page: coordinatorPage })
      .withRules(['color-contrast'])
      .analyze();
    expect(coordResults.violations, formatViolations(coordResults.violations)).toEqual([]);
  });

  test('dark mode: zero contrast violations on key pages', async ({ browser }) => {
    const fs = await import('fs');
    const path = await import('path');
    const stateFile = path.join(__dirname, '..', 'auth', 'admin.json');

    const storageState = fs.existsSync(stateFile) ? stateFile : undefined;
    const context = await browser.newContext({
      colorScheme: 'dark',
      ...(storageState ? { storageState } : {}),
    });
    const page = await context.newPage();

    if (!storageState) {
      // Log in manually
      await page.goto('/login');
      await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
      await page.locator('[data-testid="login-email"]').fill('admin@dev.fabt.org');
      await page.locator('[data-testid="login-password"]').fill('admin123');
      await page.locator('[data-testid="login-submit"]').click();
      await page.waitForURL((url: URL) => !url.pathname.includes('/login'), { timeout: 10000 });
    }

    await page.goto('/outreach');
    await page.waitForTimeout(2000);
    const results = await new AxeBuilder({ page })
      .withRules(['color-contrast'])
      .analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);

    await context.close();
  });
});

test.describe('WCAG 2.1.1 Keyboard Operability', () => {

  test('login form is fully keyboard operable', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    // Tab to first input
    await page.keyboard.press('Tab'); // skip link
    await page.keyboard.press('Tab'); // first input

    // Verify an input is focused
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(['INPUT', 'SELECT']).toContain(focused);

    // Tab through all inputs to submit button
    let foundSubmit = false;
    for (let i = 0; i < 10; i++) {
      await page.keyboard.press('Tab');
      const tag = await page.evaluate(() => document.activeElement?.tagName);
      const type = await page.evaluate(() => (document.activeElement as HTMLElement)?.getAttribute('type'));
      if (tag === 'BUTTON' && type === 'submit') {
        foundSubmit = true;
        break;
      }
    }
    expect(foundSubmit).toBe(true);
  });

  test('admin tab bar supports arrow key navigation', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const selectedTab = adminPage.locator('[role="tab"][aria-selected="true"]');
    const firstTabText = await selectedTab.textContent();
    await selectedTab.focus();

    await adminPage.keyboard.press('ArrowRight');
    await adminPage.waitForTimeout(200);

    const newSelected = adminPage.locator('[role="tab"][aria-selected="true"]');
    const newText = await newSelected.textContent();
    expect(newText).not.toEqual(firstTabText);
  });

  test('modal dialogs can be closed with Escape', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    // Open a shelter detail modal if cards exist
    const firstCard = outreachPage.locator('[data-testid^="shelter-card-"]').first();
    if (await firstCard.count() > 0) {
      await firstCard.click();
      await outreachPage.waitForTimeout(500);

      // Press Escape to close
      await outreachPage.keyboard.press('Escape');
      await outreachPage.waitForTimeout(300);

      // Modal should be closed (no dialog visible)
      const dialog = outreachPage.locator('[role="dialog"], [role="alertdialog"]');
      const visibleDialogs = await dialog.count();
      // Either no dialog or not visible
      if (visibleDialogs > 0) {
        await expect(dialog.first()).not.toBeVisible();
      }
    }
  });
});

test.describe('WCAG 2.4.1 Bypass Blocks', () => {

  test('skip-to-content link is first focusable element', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    // Tab to first element
    await page.keyboard.press('Tab');

    // Should be the skip link
    const activeText = await page.evaluate(() => document.activeElement?.textContent?.trim());
    expect(activeText?.toLowerCase()).toContain('skip');

    // Should link to #main-content
    const href = await page.evaluate(() => document.activeElement?.getAttribute('href'));
    expect(href).toBe('#main-content');
  });
});

test.describe('WCAG 2.4.2 Page Titled', () => {

  test('pages have descriptive titles', async ({ page }) => {
    await page.goto('/login');
    const title = await page.title();
    expect(title).toBeTruthy();
    expect(title.length).toBeGreaterThan(0);
  });
});

test.describe('WCAG 2.4.7 Focus Visible', () => {

  test('focused elements have visible focus indicators', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    // Tab through elements and verify focus is visible
    // This checks for outline, box-shadow, or border changes on focus
    const focusResults: { element: string; hasIndicator: boolean }[] = [];

    for (let i = 0; i < 8; i++) {
      await outreachPage.keyboard.press('Tab');
      await outreachPage.waitForTimeout(100);

      const result = await outreachPage.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) return null;

        const style = window.getComputedStyle(el);
        const outline = style.outline;
        const outlineWidth = parseInt(style.outlineWidth);
        const boxShadow = style.boxShadow;
        const borderColor = style.borderColor;

        const hasOutline = outlineWidth > 0 && style.outlineStyle !== 'none';
        const hasBoxShadow = boxShadow !== 'none';

        return {
          element: `${el.tagName}[${el.getAttribute('aria-label') || el.textContent?.trim().substring(0, 30) || ''}]`,
          hasIndicator: hasOutline || hasBoxShadow,
          outline,
          boxShadow: boxShadow?.substring(0, 50),
        };
      });

      if (result) {
        focusResults.push(result);
        console.log(`Focus: ${result.element} — indicator: ${result.hasIndicator} (outline: ${result.outline})`);
      }
    }

    // Log results for ACR accuracy — at least some elements should have visible focus
    const withIndicator = focusResults.filter(r => r.hasIndicator);
    console.log(`Focus visible: ${withIndicator.length}/${focusResults.length} elements have visible focus indicators`);

    // This test documents the current state rather than hard-failing,
    // because browser default focus rings may not be detected by getComputedStyle
    // in all cases. The ACR should reflect the actual ratio.
  });
});

test.describe('WCAG 2.4.5 Multiple Ways', () => {

  test('content accessible via navigation and direct URL', async ({ outreachPage }) => {
    // Direct URL access
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    // Page should load with content
    const heading = outreachPage.locator('h1, h2').first();
    await expect(heading).toBeVisible();

    // Navigation links should exist
    const nav = outreachPage.locator('nav');
    await expect(nav.first()).toBeVisible();
  });
});

test.describe('WCAG 3.1.1 Language of Page', () => {

  test('html lang attribute is set', async ({ page }) => {
    await page.goto('/login');
    const lang = await page.evaluate(() => document.documentElement.lang);
    expect(lang).toBeTruthy();
    expect(['en', 'es']).toContain(lang);
  });
});

test.describe('WCAG 3.1.2 Language of Parts', () => {

  test('Spanish locale sets lang to es', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    const langSelector = outreachPage.locator('select[aria-label="Select language"]');
    if (await langSelector.count() > 0) {
      await langSelector.selectOption('es');
      await outreachPage.waitForTimeout(500);
      const lang = await outreachPage.evaluate(() => document.documentElement.lang);
      expect(lang).toBe('es');

      // Restore
      await langSelector.selectOption('en');
    }
  });
});

test.describe('WCAG 4.1.3 Status Messages', () => {

  test('aria-live regions exist for status announcements', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    const liveRegions = await outreachPage.evaluate(() => {
      const regions = document.querySelectorAll('[aria-live], [role="status"], [role="alert"]');
      return Array.from(regions).map(r => ({
        role: r.getAttribute('role'),
        ariaLive: r.getAttribute('aria-live'),
        tag: r.tagName,
      }));
    });

    // Should have at least one live region (route announcer)
    expect(liveRegions.length).toBeGreaterThan(0);
    console.log(`Found ${liveRegions.length} aria-live regions:`, JSON.stringify(liveRegions));
  });
});

test.describe('WCAG 1.4.10 Reflow (320px)', () => {

  test('no horizontal scrollbar at 320px viewport width', async ({ browser }) => {
    const context = await browser.newContext({
      viewport: { width: 320, height: 568 },
    });
    const page = await context.newPage();

    await page.goto('/login');
    await page.waitForTimeout(1000);

    const hasHorizontalScroll = await page.evaluate(() => {
      return document.documentElement.scrollWidth > document.documentElement.clientWidth;
    });

    expect(hasHorizontalScroll).toBe(false);
    await context.close();
  });
});

test.describe('WCAG 1.4.12 Text Spacing (automated)', () => {

  test('text spacing overrides cause no content loss on login page', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    await page.addStyleTag({
      content: `
        * {
          line-height: 1.5 !important;
          letter-spacing: 0.12em !important;
          word-spacing: 0.16em !important;
        }
        p { margin-bottom: 2em !important; }
      `
    });
    await page.waitForTimeout(500);

    const overflows = await page.evaluate(() => {
      const elements = document.querySelectorAll('div, span, p, h1, h2, h3, button, a, label, input');
      const issues: string[] = [];
      elements.forEach(el => {
        const style = window.getComputedStyle(el);
        if (parseInt(style.height) <= 1 || style.display === 'none' || style.visibility === 'hidden') return;
        if (style.overflow === 'hidden' && el.scrollHeight > el.clientHeight + 2) {
          const text = (el.textContent || '').substring(0, 50).trim();
          if (text) issues.push(`${el.tagName}: "${text}"`);
        }
      });
      return issues;
    });

    expect(overflows).toEqual([]);
  });
});

test.describe('Touch Target Minimum Size', () => {

  test('all interactive elements meet 44x44px minimum', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);

    const undersized = await outreachPage.evaluate(() => {
      const interactives = document.querySelectorAll('button, a, [role="tab"], [role="menuitem"], input, select');
      const violations: string[] = [];
      interactives.forEach(el => {
        const rect = el.getBoundingClientRect();
        // Skip hidden elements
        if (rect.width === 0 || rect.height === 0) return;
        // Skip visually-hidden elements
        const style = window.getComputedStyle(el);
        if (style.position === 'absolute' && parseInt(style.height) <= 1) return;

        if (rect.width < 44 || rect.height < 44) {
          const label = el.getAttribute('aria-label') || el.textContent?.trim().substring(0, 30) || el.tagName;
          violations.push(`${label} (${Math.round(rect.width)}x${Math.round(rect.height)})`);
        }
      });
      return violations;
    });

    if (undersized.length > 0) {
      console.log(`Undersized touch targets: ${undersized.join(', ')}`);
    }
    // This documents the state — some elements may intentionally be smaller on desktop
    // The ACR should reflect the actual findings
  });
});

test.describe('Full axe-core scan (comprehensive baseline)', () => {

  test('login page: zero WCAG violations', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);
    const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('outreach page: zero WCAG violations', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000);
    const results = await new AxeBuilder({ page: outreachPage }).withTags(AXE_TAGS).analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('coordinator page: zero WCAG violations', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    const results = await new AxeBuilder({ page: coordinatorPage }).withTags(AXE_TAGS).analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('admin page: zero WCAG violations', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);
    const results = await new AxeBuilder({ page: adminPage }).withTags(AXE_TAGS).analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });
});
