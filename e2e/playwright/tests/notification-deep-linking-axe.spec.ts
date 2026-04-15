import { test, expect } from '../fixtures/auth.fixture';
import AxeBuilder from '@axe-core/playwright';

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

/**
 * notification-deep-linking Phase 4 a11y scans (tasks 12.1–12.4).
 *
 * Runs axe-core against the three deep-link target states in BOTH light
 * and dark mode. Tags: WCAG 2.0 + 2.1, levels A and AA — matches the
 * project's existing deploy-verify a11y baseline.
 *
 * Coverage:
 *   - 12.1: /outreach/my-holds (light + dark)
 *   - 12.2: /coordinator?referralId=X with shelter expanded (light + dark)
 *   - 12.3: /admin#dvEscalations?referralId=X with detail modal open (light + dark — when queue has data)
 *   - 12.4: dark-mode variants are emitted as separate test cases for each scan
 *
 * Run against the local stack via nginx (matches the project's standard
 * Playwright invocation). Tee output to logs/issue-106-phase4-a11y.log.
 */

async function runAxe(page: import('@playwright/test').Page, label: string): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  if (results.violations.length > 0) {
    console.log(`\naxe violations on ${label}:`);
    for (const v of results.violations) {
      console.log(`  [${v.impact}] ${v.id}: ${v.help}`);
      console.log(`    help: ${v.helpUrl}`);
      for (let i = 0; i < v.nodes.length; i++) {
        const n = v.nodes[i];
        console.log(`    node ${i + 1}/${v.nodes.length}:`);
        console.log(`      target: ${JSON.stringify(n.target)}`);
        console.log(`      html: ${n.html.slice(0, 300)}${n.html.length > 300 ? ' …' : ''}`);
        if (n.failureSummary) {
          console.log(`      reason: ${n.failureSummary.replace(/\n/g, ' | ')}`);
        }
      }
    }
  }
  expect(results.violations, `axe-core violations on ${label}`).toEqual([]);
}

async function setDarkMode(page: import('@playwright/test').Page): Promise<void> {
  // The project uses prefers-color-scheme media query for dark mode (per
  // FOR-DEVELOPERS color system docs). Playwright's emulateMedia is the
  // direct way to flip it without UI navigation.
  await page.emulateMedia({ colorScheme: 'dark' });
}

test.describe('notification-deep-linking — axe scans (Phase 4 tasks 12.1-12.4)', () => {

  // -------------------------------------------------------------------------
  // 12.1 — /outreach/my-holds (light + dark)
  // -------------------------------------------------------------------------

  test('12.1 light: /outreach/my-holds — zero axe violations', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach/my-holds');
    await expect(dvOutreachPage.locator('[data-testid="my-holds-heading"]')).toBeVisible({ timeout: 10000 });
    await runAxe(dvOutreachPage, '/outreach/my-holds (light)');
  });

  test('12.1 dark: /outreach/my-holds — zero axe violations', async ({ dvOutreachPage }) => {
    await setDarkMode(dvOutreachPage);
    await dvOutreachPage.goto('/outreach/my-holds');
    await expect(dvOutreachPage.locator('[data-testid="my-holds-heading"]')).toBeVisible({ timeout: 10000 });
    await runAxe(dvOutreachPage, '/outreach/my-holds (dark)');
  });

  // -------------------------------------------------------------------------
  // 12.2 — coordinator deep-linked with shelter expanded (light + dark)
  // -------------------------------------------------------------------------

  // Resolve a PENDING DV referral the dv-coordinator can deep-link to. Tries
  // the count endpoint's firstPending hint first (zero state mutation); only
  // falls back to creating a fresh referral when no pending exists. Avoids
  // the uq_referral_token_pending unique-constraint collision that bit the
  // initial draft of this test (same outreach + same shelter twice).
  async function resolveCoordinatorReferralId(): Promise<string> {
    const coordLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-coordinator@dev.fabt.org', password: 'admin123' }),
    });
    const { accessToken: coordToken } = await coordLogin.json();
    const countResp = await fetch(`${API_URL}/api/v1/dv-referrals/pending/count`, {
      headers: { Authorization: `Bearer ${coordToken}` },
    });
    const countPayload = await countResp.json() as {
      count: number;
      firstPending: { referralId: string } | null;
    };
    if (countPayload.firstPending) {
      return countPayload.firstPending.referralId;
    }
    // No pending exists — create one. (First-time-ish run, or all existing
    // pendings have been actioned.)
    const outreachLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-outreach@dev.fabt.org', password: 'admin123' }),
    });
    const { accessToken: outreachToken } = await outreachLogin.json();
    const sheltersResp = await fetch(`${API_URL}/api/v1/shelters?populationType=DV_SURVIVOR`, {
      headers: { Authorization: `Bearer ${outreachToken}` },
    });
    const shelters = await sheltersResp.json() as Array<{ shelter: { id: string; dvShelter?: boolean } }>;
    const dvShelter = shelters.find((s) => s.shelter?.dvShelter);
    expect(dvShelter, 'a DV shelter must exist').toBeTruthy();
    const createResp = await fetch(`${API_URL}/api/v1/dv-referrals`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${outreachToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        shelterId: dvShelter!.shelter.id,
        populationType: 'DV_SURVIVOR',
        householdSize: 2,
        urgency: 'URGENT',
        callbackNumber: '919-555-0121',
      }),
    });
    const body = await createResp.text();
    expect(createResp.status, `referral creation: ${body}`).toBe(201);
    return (JSON.parse(body) as { id: string }).id;
  }

  test('12.2 light: /coordinator?referralId=X with shelter expanded — zero axe violations', async ({ dvCoordinatorPage }) => {
    const referralId = await resolveCoordinatorReferralId();
    await dvCoordinatorPage.goto(`/coordinator?referralId=${referralId}`);
    await expect(dvCoordinatorPage.locator(`[data-testid="screening-${referralId}"]`)).toBeVisible({ timeout: 10000 });
    await runAxe(dvCoordinatorPage, `/coordinator?referralId=${referralId} (light)`);
  });

  test('12.2 dark: /coordinator?referralId=X with shelter expanded — zero axe violations', async ({ dvCoordinatorPage }) => {
    await setDarkMode(dvCoordinatorPage);
    const referralId = await resolveCoordinatorReferralId();
    await dvCoordinatorPage.goto(`/coordinator?referralId=${referralId}`);
    await expect(dvCoordinatorPage.locator(`[data-testid="screening-${referralId}"]`)).toBeVisible({ timeout: 10000 });
    await runAxe(dvCoordinatorPage, `/coordinator?referralId=${referralId} (dark)`);
  });

  // -------------------------------------------------------------------------
  // 12.3 — admin escalation modal opened via deep-link (light + dark)
  //        Skips when escalated queue is empty (modal won't open without
  //        queue membership — DvEscalationsTab L-2 isTargetReady guard).
  // -------------------------------------------------------------------------

  async function probeEscalatedQueue(): Promise<string | null> {
    const cocadminLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'cocadmin@dev.fabt.org', password: 'admin123' }),
    });
    if (!cocadminLogin.ok) return null;
    const { accessToken } = await cocadminLogin.json();
    const escalatedResp = await fetch(`${API_URL}/api/v1/dv-referrals/escalated`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!escalatedResp.ok) return null;
    const queue = await escalatedResp.json() as Array<{ id: string }>;
    return queue.length > 0 ? queue[0].id : null;
  }

  test('12.3 light: /admin#dvEscalations?referralId=X with modal — zero axe violations', async ({ coordinatorPage }) => {
    const targetReferralId = await probeEscalatedQueue();
    if (!targetReferralId) {
      test.skip(true, 'Escalated queue is empty — no referral for the modal to open against');
    }
    await coordinatorPage.goto(`/admin#dvEscalations?referralId=${targetReferralId}`);
    await expect(coordinatorPage.locator('[data-testid="dv-escalation-detail-modal"]')).toBeVisible({ timeout: 10000 });
    await runAxe(coordinatorPage, `/admin#dvEscalations?referralId=${targetReferralId} (light)`);
  });

  test('12.3 dark: /admin#dvEscalations?referralId=X with modal — zero axe violations', async ({ coordinatorPage }) => {
    await setDarkMode(coordinatorPage);
    const targetReferralId = await probeEscalatedQueue();
    if (!targetReferralId) {
      test.skip(true, 'Escalated queue is empty — no referral for the modal to open against');
    }
    await coordinatorPage.goto(`/admin#dvEscalations?referralId=${targetReferralId}`);
    await expect(coordinatorPage.locator('[data-testid="dv-escalation-detail-modal"]')).toBeVisible({ timeout: 10000 });
    await runAxe(coordinatorPage, `/admin#dvEscalations?referralId=${targetReferralId} (dark)`);
  });

});
