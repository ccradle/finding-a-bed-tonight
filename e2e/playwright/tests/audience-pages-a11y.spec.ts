import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import path from 'path';

/**
 * Audience Pages — axe-core accessibility scan.
 *
 * Scans the 3 new audience HTML pages (static, served from docs repo).
 * Zero Critical/Serious violations allowed per spec.
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

const DOCS_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const PAGES = [
  { name: 'for-coordinators', file: 'demo/for-coordinators.html' },
  { name: 'for-coc-admins', file: 'demo/for-coc-admins.html' },
  { name: 'for-funders', file: 'demo/for-funders.html' },
];

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
    v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 120)}`).join('\n')
  ).join('\n\n');
}

for (const pg of PAGES) {
  test(`${pg.name} has no Critical/Serious accessibility violations`, async ({ page }) => {
    const filePath = path.join(DOCS_ROOT, pg.file);
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);
    await page.waitForTimeout(500);

    const results = await new AxeBuilder({ page })
      .withTags(AXE_TAGS)
      .analyze();

    const serious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
    expect(serious, formatViolations(serious)).toEqual([]);
  });

  test(`${pg.name} has skip-to-content link`, async ({ page }) => {
    const filePath = path.join(DOCS_ROOT, pg.file);
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);

    const skipLinks = page.locator('a[href="#main"]');
    expect(await skipLinks.count()).toBeGreaterThanOrEqual(1);
  });

  test(`${pg.name} has FAQ structured data`, async ({ page }) => {
    const filePath = path.join(DOCS_ROOT, pg.file);
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);

    const schema = page.locator('script[type="application/ld+json"]');
    await expect(schema).toHaveCount(1);
    const text = await schema.textContent();
    expect(text).toContain('FAQPage');
  });
}
