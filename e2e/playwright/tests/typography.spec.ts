import { test, expect } from '../fixtures/auth.fixture';

/**
 * Typography consistency and WCAG text spacing tests.
 *
 * Verifies:
 * - REQ-PW-TYP-1: Consistent font-family across all key views
 * - REQ-PW-TYP-2: No serif fonts anywhere
 * - REQ-PW-TYP-3: WCAG 1.4.12 text spacing override causes no clipping
 * - REQ-PW-TYP-4: Form elements inherit system font
 */

test.describe('Typography Consistency', () => {

  test('font-family is consistent across all key views', async ({ outreachPage, adminPage }) => {
    // Check outreach search page — use main content heading, not nav header
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main h1, [role="main"] h1, h1');
    const searchFont = await outreachPage.locator('h1').first().evaluate(
      el => window.getComputedStyle(el).fontFamily
    );

    // Check admin panel
    await adminPage.goto('/admin');
    await adminPage.waitForSelector('h1');
    const adminFont = await adminPage.locator('h1').first().evaluate(
      el => window.getComputedStyle(el).fontFamily
    );

    // Both should resolve to the same system font
    expect(searchFont).toBe(adminFont);

    // Neither should contain "serif" without "sans-" prefix
    expect(searchFont).not.toMatch(/(?<!sans-)serif/i);
    expect(adminFont).not.toMatch(/(?<!sans-)serif/i);
  });

  test('no element renders with a serif font', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('[data-testid="shelter-list"]', { timeout: 10000 }).catch(() => {});
    await outreachPage.waitForTimeout(2000);

    // Check all visible text elements for serif fonts
    const serifElements = await outreachPage.evaluate(() => {
      const elements = document.querySelectorAll('*');
      const serifs: string[] = [];
      elements.forEach(el => {
        const computed = window.getComputedStyle(el);
        const fontFamily = computed.fontFamily.toLowerCase();
        // Flag if fontFamily contains "serif" but not "sans-serif"
        if (fontFamily.includes('serif') && !fontFamily.includes('sans-serif')) {
          serifs.push(`${el.tagName}.${el.className}: ${fontFamily}`);
        }
      });
      return serifs;
    });

    expect(serifElements).toEqual([]);
  });

  test('form elements use system font, not browser defaults', async ({ page }) => {
    await page.goto('/login');
    await page.waitForSelector('input');

    // Get font-family of the email input
    const inputFont = await page.locator('input[type="text"], input[type="email"], input[placeholder]').first().evaluate(
      el => window.getComputedStyle(el).fontFamily
    );

    // Get font-family of the login button
    const buttonFont = await page.locator('button[type="submit"]').evaluate(
      el => window.getComputedStyle(el).fontFamily
    );

    // Get font-family of body text for comparison
    const bodyFont = await page.locator('body').evaluate(
      el => window.getComputedStyle(el).fontFamily
    );

    // All should match — form elements should NOT fall back to browser defaults
    expect(inputFont).toBe(bodyFont);
    expect(buttonFont).toBe(bodyFont);

    // Should be a system font, not a generic fallback
    expect(bodyFont.toLowerCase()).toMatch(/system-ui|segoe ui|roboto|helvetica|arial/);
  });
});

test.describe('WCAG 1.4.12 Text Spacing', () => {

  test('text spacing override causes no overflow on search results', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('[data-testid="shelter-list"]', { timeout: 10000 }).catch(() => {});
    await outreachPage.waitForTimeout(2000);

    // Inject WCAG 1.4.12 text spacing overrides
    await outreachPage.addStyleTag({
      content: `
        * {
          line-height: 1.5 !important;
          letter-spacing: 0.12em !important;
          word-spacing: 0.16em !important;
        }
        p { margin-bottom: 2em !important; }
      `
    });

    await outreachPage.waitForTimeout(500);

    // Check that no visible text element has overflowing content.
    // Exclude intentionally hidden a11y elements (skip links, route announcers)
    // which use overflow:hidden + 1px height by design.
    const overflowElements = await outreachPage.evaluate(() => {
      const elements = document.querySelectorAll('div, span, p, h1, h2, h3, h4, button, a, label');
      const overflows: string[] = [];
      elements.forEach(el => {
        const style = window.getComputedStyle(el);
        // Skip visually-hidden elements (a11y pattern: 1px height + overflow hidden)
        if (parseInt(style.height) <= 1 || style.position === 'absolute' && parseInt(style.height) <= 1) return;
        // Skip elements not visible on screen
        if (style.display === 'none' || style.visibility === 'hidden') return;
        if (style.overflow === 'hidden' && el.scrollHeight > el.clientHeight + 2) {
          const text = (el.textContent || '').substring(0, 50);
          if (text.trim()) {
            overflows.push(`${el.tagName}[${el.className}]: "${text}" (scrollH=${el.scrollHeight}, clientH=${el.clientHeight})`);
          }
        }
      });
      return overflows;
    });

    expect(overflowElements).toEqual([]);
  });
});
