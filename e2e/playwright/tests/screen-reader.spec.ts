import { test, expect } from '../fixtures/auth.fixture';
import { virtual } from '@guidepup/virtual-screen-reader';
import { JSDOM } from 'jsdom';

/**
 * Virtual Screen Reader Tests — automated screen reader simulation.
 *
 * Uses @guidepup/virtual-screen-reader to verify that screen reader
 * navigation produces correct announcements. Runs in JSDOM (no real
 * screen reader needed), works on all platforms including CI.
 *
 * This does NOT replace testing with real screen readers (NVDA/VoiceOver)
 * but catches ARIA labeling mistakes, missing roles, and broken
 * navigation order automatically.
 */

async function getPageDOM(page: any): Promise<Document> {
  const html = await page.content();
  const dom = new JSDOM(html, { pretendToBeVisual: true });
  // Polyfill CSS global for virtual screen reader
  if (!('CSS' in dom.window)) {
    (dom.window as any).CSS = { supports: () => false, escape: (s: string) => s };
  }
  // Set globalThis references for virtual-screen-reader
  (globalThis as any).document = dom.window.document;
  (globalThis as any).window = dom.window;
  (globalThis as any).Node = dom.window.Node;
  return dom.window.document;
}

test.describe('Virtual Screen Reader Tests', () => {

  test('search page: screen reader can navigate shelters', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    const doc = await getPageDOM(outreachPage);
    const main = doc.querySelector('main') || doc.body;

    await virtual.start({ container: main as unknown as HTMLElement });

    // Navigate through elements and collect spoken phrases
    const phrases: string[] = [];
    for (let i = 0; i < 20; i++) {
      await virtual.next();
      const log = await virtual.lastSpokenPhrase();
      if (log) phrases.push(log);
    }

    await virtual.stop();

    const allSpoken = phrases.join(' ');

    // Should announce headings
    expect(allSpoken).toContain('heading');

    // Should announce search input
    expect(allSpoken.toLowerCase()).toMatch(/search|textbox|combobox/);

    // Should announce shelter names or links
    expect(phrases.length).toBeGreaterThan(5);
  });

  test('search page: freshness badges announce status text', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(3000);

    const doc = await getPageDOM(outreachPage);
    const main = doc.querySelector('main') || doc.body;

    await virtual.start({ container: main as unknown as HTMLElement });

    // Navigate through all elements
    const phrases: string[] = [];
    for (let i = 0; i < 50; i++) {
      await virtual.next();
      const log = await virtual.lastSpokenPhrase();
      if (log) phrases.push(log);
    }

    await virtual.stop();

    const allSpoken = phrases.join(' ');

    // Freshness badges should announce "Fresh" or "Stale" text (WCAG 1.4.1)
    expect(allSpoken).toMatch(/Fresh|Stale|Unknown/);
  });

  test('admin panel: tab bar has correct ARIA roles', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    // Verify tablist role exists
    const tablist = adminPage.locator('[role="tablist"]');
    await expect(tablist).toBeVisible();

    // Verify tabs have correct roles and selection
    const selectedTab = adminPage.locator('[role="tab"][aria-selected="true"]');
    await expect(selectedTab).toBeVisible();
    expect(await selectedTab.textContent()).toBeTruthy();

    // Verify tabpanel exists
    const tabpanel = adminPage.locator('[role="tabpanel"]');
    await expect(tabpanel).toBeVisible();

    // Verify arrow key navigation works
    const firstTabText = await selectedTab.textContent();
    await selectedTab.focus();
    await adminPage.keyboard.press('ArrowRight');
    await adminPage.waitForTimeout(200);
    const newSelected = adminPage.locator('[role="tab"][aria-selected="true"]');
    const newText = await newSelected.textContent();
    expect(newText).not.toEqual(firstTabText);
  });

  test('coordinator dashboard: stepper buttons have accessible labels', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(3000);

    // Verify stepper buttons have aria-labels
    const increaseBtn = coordinatorPage.locator('button[aria-label="Increase"]').first();
    const decreaseBtn = coordinatorPage.locator('button[aria-label="Decrease"]').first();

    if (await increaseBtn.count() > 0) {
      await expect(increaseBtn).toBeVisible();
      await expect(decreaseBtn).toBeVisible();

      // Verify minimum touch target size
      const box = await increaseBtn.boundingBox();
      expect(box).toBeTruthy();
      if (box) {
        expect(box.width).toBeGreaterThanOrEqual(44);
        expect(box.height).toBeGreaterThanOrEqual(44);
      }
    }
  });

  test('skip-to-content link is announced', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(2000);

    const doc = await getPageDOM(outreachPage);
    const body = doc.body;

    await virtual.start({ container: body as unknown as HTMLElement });

    // First focusable element should be the skip link
    await virtual.next();
    const firstPhrase = await virtual.lastSpokenPhrase();

    await virtual.stop();

    expect(firstPhrase?.toLowerCase()).toContain('skip');
  });

  test('Spanish locale: lang attribute is es', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(2000);

    // Switch to Spanish
    await outreachPage.locator('select[aria-label="Select language"]').selectOption('es');
    await outreachPage.waitForTimeout(500);

    // Check lang attribute
    const lang = await outreachPage.evaluate(() => document.documentElement.lang);
    expect(lang).toBe('es');

    // Switch back
    await outreachPage.locator('select[aria-label="Select language"]').selectOption('en');
    await outreachPage.waitForTimeout(500);
    const langBack = await outreachPage.evaluate(() => document.documentElement.lang);
    expect(langBack).toBe('en');
  });

});
