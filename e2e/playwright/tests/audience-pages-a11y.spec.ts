import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import fs from 'fs';
import path from 'path';

/**
 * Audience Pages — axe-core accessibility scan.
 *
 * Scans the 3 new audience HTML pages (static, served from the docs repo).
 *
 * ## Docs-repo path resolution (task #169)
 *
 * The `demo/for-*.html` pages live in the SEPARATE `findABed` docs repo,
 * not the `finding-a-bed-tonight` code repo that hosts this spec. Locally
 * the docs repo is the parent directory of the code checkout (typical
 * sibling layout — user clones both repos, one nested inside the other or
 * both under a common parent).
 *
 * CI checks out only the code repo, so the default path resolution
 * returns a directory with no `demo/for-*.html` files → tests skip with a
 * clear reason message rather than failing with ERR_FILE_NOT_FOUND.
 *
 * To enable these tests in CI (future weekly-nginx-mode job), add a
 * checkout step for the docs repo and set `FABT_DOCS_ROOT` to its path.
 *
 * Zero Critical/Serious violations allowed per spec.
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

const DOCS_ROOT = process.env.FABT_DOCS_ROOT
  ?? path.resolve(__dirname, '..', '..', '..', '..');

const PAGES = [
  { name: 'for-coordinators', file: 'demo/for-coordinators.html' },
  { name: 'for-coc-admins', file: 'demo/for-coc-admins.html' },
  { name: 'for-funders', file: 'demo/for-funders.html' },
];

const SKIP_REASON = (filePath: string) =>
  `Audience page not found at ${filePath}. This spec requires the separate `
  + `'findABed' docs repo checked out alongside. Set FABT_DOCS_ROOT to its `
  + `path, or run this spec only on developer workstations with both repos cloned.`;

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
    v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 120)}`).join('\n')
  ).join('\n\n');
}

for (const pg of PAGES) {
  const filePath = path.join(DOCS_ROOT, pg.file);
  const fileExists = fs.existsSync(filePath);

  test(`${pg.name} has no Critical/Serious accessibility violations`, async ({ page }) => {
    test.skip(!fileExists, SKIP_REASON(filePath));
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);
    await page.waitForTimeout(500);

    const results = await new AxeBuilder({ page })
      .withTags(AXE_TAGS)
      .analyze();

    const serious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious');
    expect(serious, formatViolations(serious)).toEqual([]);
  });

  test(`${pg.name} has skip-to-content link`, async ({ page }) => {
    test.skip(!fileExists, SKIP_REASON(filePath));
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);

    const skipLinks = page.locator('a[href="#main"]');
    expect(await skipLinks.count()).toBeGreaterThanOrEqual(1);
  });

  test(`${pg.name} has FAQ structured data`, async ({ page }) => {
    test.skip(!fileExists, SKIP_REASON(filePath));
    await page.goto(`file://${filePath.replace(/\\/g, '/')}`);

    const schema = page.locator('script[type="application/ld+json"]');
    await expect(schema).toHaveCount(1);
    const text = await schema.textContent();
    expect(text).toContain('FAQPage');
  });
}
