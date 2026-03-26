import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

/**
 * WCAG 2.1 AA Accessibility Scan — axe-core automated audit.
 *
 * Scans every major route in multiple states. Zero violations allowed.
 * This is a CI-blocking gate per Design D1.
 *
 * Tags tested: wcag2a, wcag2aa, wcag21a, wcag21aa
 * Coverage: ~57% of WCAG issues (axe-core automated). Manual testing
 * (screen reader walkthroughs) covers the remaining ~43%.
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
    v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 120)}`).join('\n')
  ).join('\n\n');
}

test.describe('WCAG 2.1 AA Accessibility Scan', () => {

  test('login page has no accessibility violations', async ({ page }) => {
    await page.goto('/login');
    await page.waitForTimeout(1000);

    const results = await new AxeBuilder({ page })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('outreach search page has no accessibility violations', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: outreachPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('outreach search with modal open has no violations', async ({ outreachPage }) => {
    await outreachPage.goto('/');
    await outreachPage.waitForTimeout(2000);

    // Click first shelter card to open detail modal
    const firstCard = outreachPage.locator('[data-testid^="shelter-card-"]').first();
    if (await firstCard.count() > 0) {
      await firstCard.click();
      await outreachPage.waitForTimeout(1000);
    }

    const results = await new AxeBuilder({ page: outreachPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('coordinator dashboard has no accessibility violations', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: coordinatorPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('admin panel — users tab has no violations', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: adminPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('admin panel — shelters tab has no violations', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button[role="tab"]', { hasText: /^Shelters$/i }).click();
    await adminPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: adminPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('admin panel — analytics tab has no violations', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button[role="tab"]', { hasText: /Analytics/i }).click();
    await adminPage.waitForTimeout(3000);

    const results = await new AxeBuilder({ page: adminPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('admin panel — observability tab has no violations', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.waitForTimeout(1000);
    await adminPage.locator('button[role="tab"]', { hasText: /Observability/i }).click();
    await adminPage.waitForTimeout(2000);

    const results = await new AxeBuilder({ page: adminPage })
      .withTags(AXE_TAGS)
      .analyze();

    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

});
