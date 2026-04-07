import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';
import type { AxeResults } from 'axe-core';

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

const API_URL = process.env.BASE_URL || 'http://localhost:8080';

function logIncomplete(results: AxeResults): void {
  if (results.incomplete.length > 0) {
    console.log(`  ⚠ ${results.incomplete.length} incomplete (need manual review):`);
    for (const item of results.incomplete) {
      console.log(`    [${item.id}] ${item.description} — ${item.nodes.length} instance(s)`);
    }
  }
}

async function getAdminToken(): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const data = await res.json();
  return data.accessToken;
}

async function activateSurge(token: string): Promise<void> {
  const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const surges = await surgesRes.json();
  if (surges.some((s: { status: string }) => s.status === 'ACTIVE')) return;

  await fetch(`${API_URL}/api/v1/surge-events`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: 'WCAG scan — surge contrast check', temperatureF: 25 }),
  });
}

async function deactivateSurge(token: string): Promise<void> {
  const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const surges = await surgesRes.json();
  const active = surges.find((s: { status: string }) => s.status === 'ACTIVE');
  if (!active) return;

  await fetch(`${API_URL}/api/v1/surge-events/${active.id}/deactivate`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${token}` },
  });
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

    logIncomplete(results);
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

    logIncomplete(results);
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });

  test('outreach search with surge banner has no violations', async ({ outreachPage }) => {
    // Activate surge so the banner renders
    let token: string;
    try {
      token = await getAdminToken();
      await activateSurge(token);
    } catch {
      console.log('Could not activate surge (backend not reachable?) — skipping');
      test.skip();
      return;
    }

    try {
      await outreachPage.goto('/');
      await outreachPage.waitForTimeout(3000);

      // Verify surge banner is visible before scanning
      const surgeBanner = outreachPage.locator('text=/SURGE ACTIVE|EMERGENCIA ACTIVA/i');
      await expect(surgeBanner).toBeVisible({ timeout: 5000 });

      const results = await new AxeBuilder({ page: outreachPage })
        .withTags(AXE_TAGS)
        .analyze();

      logIncomplete(results);
      expect(results.violations, formatViolations(results.violations)).toEqual([]);
    } finally {
      // Always deactivate surge after test
      try { await deactivateSurge(token!); } catch { /* best effort */ }
    }
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
