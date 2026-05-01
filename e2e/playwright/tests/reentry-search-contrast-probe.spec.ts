import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

/**
 * Targeted color-contrast probe on the post-§10 outreach search surface.
 *
 * Diagnostic spec — confirms that the §10 expansion (7 shelter-type chips,
 * 101-county dropdown, advanced-filters group, accepts-felonies toggle)
 * introduced ZERO color-contrast violations. wcag-vpat-verification.spec.ts:187
 * fails reproducibly under a 30s budget because the cumulative 4-page axe
 * scan + 4 fresh contexts no longer fit; this spec rules out the real-
 * regression interpretation by scoping to the single page §10 changed.
 */

test('post-§10 outreach search: zero color-contrast violations', async ({ outreachPage }) => {
  test.setTimeout(60_000);

  await outreachPage.goto('/outreach');
  await outreachPage.waitForTimeout(2000);

  const results = await new AxeBuilder({ page: outreachPage })
    .withRules(['color-contrast'])
    .analyze();

  const violations = results.violations.map(v =>
    `[${v.id}] ${v.description} — ${v.nodes.length} instance(s)\n`
    + v.nodes.slice(0, 3).map((n: any) => `  → ${n.html.substring(0, 200)}`).join('\n')
  ).join('\n\n');

  expect(results.violations, violations).toEqual([]);
});
