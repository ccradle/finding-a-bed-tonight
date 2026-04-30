import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import fs from 'fs';
import path from 'path';

/**
 * Reentry Story Page — strict axe-core accessibility scan.
 *
 * v0.55 reentry-release-readiness §12.6.5 + Tomás TO-RR-2: ZERO violations
 * required (not just zero Critical/Serious). The reentry-story page is
 * narrative content surfaced from the front-door demo grid; it ships with
 * a synthetic protagonist (Andre) and operator-facing screenshots, and
 * v0.55 is its inaugural release. ADA Title II (April 2026 deadline) is
 * the load-bearing constraint behind the strict-zero bar.
 *
 * ## Docs-repo path resolution
 *
 * Same pattern as audience-pages-a11y.spec.ts: the page lives in the
 * separate `findABed` docs repo. Set FABT_DOCS_ROOT in CI; locally the
 * default sibling-layout works.
 */

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

const DOCS_ROOT = process.env.FABT_DOCS_ROOT
  ?? path.resolve(__dirname, '..', '..', '..', '..');

const REENTRY_STORY_PATH = path.join(DOCS_ROOT, 'demo', 'reentry-story.html');

const SKIP_REASON =
  `reentry-story.html not found at ${REENTRY_STORY_PATH}. This spec requires `
  + `the separate 'findABed' docs repo checked out alongside. Set `
  + `FABT_DOCS_ROOT to its path, or run on a developer workstation with both `
  + `repos cloned.`;

function formatViolations(violations: any[]): string {
  return violations.map(v =>
    `[${v.id}] ${v.description} (impact=${v.impact}, ${v.nodes.length} instance(s))\n`
    + v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 160)}`).join('\n')
    + (v.helpUrl ? `\n  ${v.helpUrl}` : '')
  ).join('\n\n');
}

const fileExists = fs.existsSync(REENTRY_STORY_PATH);

test('reentry-story.html has zero axe-core violations (strict — TO-RR-2)', async ({ page }) => {
  test.skip(!fileExists, SKIP_REASON);
  await page.goto(`file://${REENTRY_STORY_PATH.replace(/\\/g, '/')}`);
  await page.waitForTimeout(500);

  const results = await new AxeBuilder({ page })
    .withTags(AXE_TAGS)
    .analyze();

  expect(
    results.violations,
    `Tomás TO-RR-2 requires zero violations on reentry-story.html.\n\n${formatViolations(results.violations)}`,
  ).toEqual([]);
});

test('reentry-story.html has skip-to-content link', async ({ page }) => {
  test.skip(!fileExists, SKIP_REASON);
  await page.goto(`file://${REENTRY_STORY_PATH.replace(/\\/g, '/')}`);

  const skipLinks = page.locator('a[href="#main"]');
  expect(await skipLinks.count()).toBeGreaterThanOrEqual(1);
});

test('reentry-story.html has see-also nav landmark', async ({ page }) => {
  test.skip(!fileExists, SKIP_REASON);
  await page.goto(`file://${REENTRY_STORY_PATH.replace(/\\/g, '/')}`);

  // §6.4 Tomás-voice scrub asserted <nav aria-label="See also"> wraps the
  // footer cross-links. Zero-violations is necessary but a brittle proxy —
  // pin the landmark explicitly so a regression that strips the aria-label
  // surfaces a clear failure rather than a generic axe rule miss.
  const navByLabel = page.getByRole('navigation', { name: 'See also' });
  await expect(navByLabel).toBeVisible();
});
