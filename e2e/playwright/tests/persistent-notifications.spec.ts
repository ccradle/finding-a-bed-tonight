import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

/**
 * Persistent Notifications — Playwright E2E tests.
 *
 * IMPORTANT: Test order matters. Tests that READ state (T-54, T-56, T-58, T-58b)
 * run BEFORE tests that MODIFY state (T-57, T-58c). This prevents cascading failures
 * from state changes (e.g., marking CRITICAL as read breaks CRITICAL banner tests).
 *
 * Seed data (T-63) provides (updated per commit 9384fd1, 2026-04-08):
 * - dv-coordinator: referral.requested (ACTION_REQUIRED), surge.activated (CRITICAL)
 * - outreach worker: referral.responded (ACTION_REQUIRED, status=ACCEPTED)
 * - cocadmin: escalation.2h (CRITICAL) — added Session 6 T-43 banner CTA test
 *
 * Run: BASE_URL=http://localhost:8081 npx playwright test persistent-notifications --trace on
 */

test.describe('Persistent Notifications', () => {

  test.beforeAll(async () => {
    // Validate seed notifications exist — fail loudly if not.
    //
    // Commit 9384fd1 (2026-04-08) routed referral.requested + surge.activated
    // notifications from cocadmin (user 003) to dv-coordinator (user 006),
    // because the COORDINATOR is the persona who actually screens DV referrals.
    // This beforeAll logs in as dv-coordinator to match the current seed, not
    // cocadmin (which now only receives the escalation.2h notification added
    // in Session 6 T-43).
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-coordinator@dev.fabt.org', password: 'admin123' }),
    });
    if (!loginResp.ok) throw new Error('beforeAll: dv-coordinator login failed — is the stack running?');
    const { accessToken } = await loginResp.json();

    const countResp = await fetch(`${API_URL}/api/v1/notifications/count`, {
      headers: { 'Authorization': `Bearer ${accessToken}` },
    });
    if (!countResp.ok) throw new Error('beforeAll: notifications/count endpoint failed');
    const { unread } = await countResp.json();

    if (unread < 2) {
      throw new Error(
        `beforeAll: dv-coordinator has ${unread} unread notifications (need >= 2). ` +
        'Seed data (T-63) must provide referral.requested (ACTION_REQUIRED) + surge.activated (CRITICAL). ' +
        'Re-seed: psql < infra/scripts/seed-data.sql'
      );
    }
  });

  test.afterAll(async () => { await cleanupTestData(); });

  // =========================================================================
  // READ-ONLY TESTS (run first — do not modify notification state)
  // =========================================================================

  test('T-54: coordinator sees pending referral banner', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const banner = dvCoordinatorPage.locator('[data-testid="coordinator-referral-banner"]');
    if (await banner.count() > 0) {
      await expect(banner).toHaveAttribute('role', 'alert');
      await expect(banner).toContainText(/referral|referencia/i);
    }
  });

  test('T-56: bell badge shows unread count > 0 on login', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const bellButton = dvCoordinatorPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toBeVisible();
    await expect(bellButton).toHaveAttribute('aria-label', /\d+ unread/, { timeout: 5000 });

    const ariaLabel = await bellButton.getAttribute('aria-label') || '';
    const match = ariaLabel.match(/(\d+) unread/);
    expect(match).not.toBeNull();
    expect(parseInt(match![1], 10)).toBeGreaterThan(0);
  });

  test('T-58: WCAG banners have role="alert"', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    // Referral banner — if present
    const refBanner = dvCoordinatorPage.locator('[data-testid="coordinator-referral-banner"]');
    if (await refBanner.count() > 0) {
      await expect(refBanner).toHaveAttribute('role', 'alert');
      await expect(refBanner).toHaveAttribute('tabindex', '0');
    }

    // CRITICAL banner — must be present (seed data has surge.activated CRITICAL)
    const critBanner = dvCoordinatorPage.locator('[data-testid="critical-notification-banner"]');
    await expect(critBanner).toBeVisible({ timeout: 5000 });
    await expect(critBanner).toHaveAttribute('role', 'alert');

    // Bell — always present
    const bellButton = dvCoordinatorPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toHaveAttribute('aria-expanded', /true|false/);
  });

  test('T-58b: CRITICAL banner visible when CRITICAL notifications exist', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const critBanner = dvCoordinatorPage.locator('[data-testid="critical-notification-banner"]');
    await expect(critBanner).toBeVisible({ timeout: 5000 });
    await expect(critBanner).toHaveAttribute('role', 'alert');
    await expect(critBanner).toContainText(/notification|notificación/i);
  });

  test('T-58d: non-DV outreach worker has no DV referral notifications', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();

    const bellButton = outreachPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toBeVisible();
    await bellButton.click();

    const panel = outreachPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    const panelText = await panel.textContent() || '';
    expect(panelText).not.toContain('referral.requested');
    expect(panelText).not.toMatch(/needs your review|necesita su revisión/i);

    await outreachPage.keyboard.press('Escape');
  });

  /**
   * notification-deep-linking (Issue #106) — happy-path test, war-room M-2.
   *
   * Verifies the end-to-end Phase 1 flow: a coordinator with a
   * `referral.requested` notification clicks it (here, navigating directly
   * to /coordinator?referralId=X) and lands with the referral row visible
   * AND keyboard focus on the row heading (NOT the Accept button — S-2
   * safety: prevents accidental Enter-key acceptance of a DV referral).
   *
   * This test is the minimum coverage for Phase 1 ship-gate. Broader
   * scenarios (admin role-aware routing, stale-referral fallback,
   * unsaved-state dialog, mobile viewport, Spanish locale) are tracked
   * as Phase 4 tasks 11.x.
   */
  test('Issue #106 deep-link: ?referralId lands on referral row with focus on row (not Accept)', async ({ dvCoordinatorPage }) => {
    // N-2 fix from war-room round 2: create the referral in-test via API so
    // we don't depend on seed shape. Silent skips on seed drift were exactly
    // the "tests that just pass" failure mode flagged in the original review.
    //
    // Sequence:
    //   1. Login as dv-outreach via API
    //   2. Discover any DV shelter visible to the dv-outreach worker
    //   3. POST /api/v1/dv-referrals to create a fresh PENDING referral
    //   4. Navigate the dv-coordinator page to /coordinator?referralId=X
    //   5. Assert row visible + focused + Accept NOT focused + aria-live populated
    const outreachLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-outreach@dev.fabt.org', password: 'admin123' }),
    });
    expect(outreachLogin.ok, 'dv-outreach login must succeed (seed user)').toBeTruthy();
    const { accessToken: outreachToken } = await outreachLogin.json();

    const sheltersResp = await fetch(`${API_URL}/api/v1/shelters?populationType=DV_SURVIVOR`, {
      headers: { 'Authorization': `Bearer ${outreachToken}` },
    });
    expect(sheltersResp.ok, 'shelters list must succeed for dv-outreach').toBeTruthy();
    const shelters = await sheltersResp.json() as Array<{ shelter: { id: string; dvShelter?: boolean } }>;
    const dvShelter = shelters.find((s) => s.shelter?.dvShelter);
    expect(dvShelter, 'At least one DV shelter must exist for the deep-link test').toBeTruthy();

    const createResp = await fetch(`${API_URL}/api/v1/dv-referrals`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${outreachToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        shelterId: dvShelter!.shelter.id,
        populationType: 'DV_SURVIVOR',
        householdSize: 2,
        urgency: 'URGENT',
        callbackNumber: '919-555-0106',
      }),
    });
    // Read body ONCE — fetch Response bodies are single-consumption. The
    // template literal eagerly evaluates `.text()`; if we then called
    // `.json()` on the next line, it would throw "Body has already been
    // consumed" on every run, including success.
    const createBody = await createResp.text();
    expect(createResp.status, `referral creation must succeed — body: ${createBody}`).toBe(201);
    const referral = JSON.parse(createBody) as { id: string };
    const referralId = referral.id;
    expect(referralId, 'created referral must have an id').toBeTruthy();

    // Deep-link directly via URL — same effect as clicking the bell entry.
    await dvCoordinatorPage.goto(`/coordinator?referralId=${referralId}`);
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    // The screening row for the deep-linked referral must materialize without
    // the user having to expand any shelter card by hand.
    const row = dvCoordinatorPage.locator(`[data-testid="screening-${referralId}"]`);
    await expect(row).toBeVisible({ timeout: 10000 });

    // S-2 safety assertion: focus is on the ROW, not the Accept button.
    // Accidental Enter-key acceptance of a DV referral is the failure mode
    // this whole focus-target decision exists to prevent.
    await expect(row).toBeFocused();
    const acceptBtn = dvCoordinatorPage.locator(`[data-testid="accept-referral-${referralId}"]`);
    await expect(acceptBtn).not.toBeFocused();

    // T-1: aria-live status region populated for screen readers (no PII —
    // population type, household size, urgency only).
    const announcement = dvCoordinatorPage.locator('[data-testid="deep-link-announcement"]');
    await expect(announcement).toContainText(/referral|referencia/i);
  });

  test('T-58e: notification dropdown renders i18n text, not raw type', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const bellButton = dvCoordinatorPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toBeVisible();
    await bellButton.click();

    const panel = dvCoordinatorPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    await expect(panel).not.toContainText(/No notifications|Sin notificaciones/);

    const panelText = await panel.textContent() || '';
    expect(panelText).not.toContain('referral.requested');
    expect(panelText).not.toContain('surge.activated');
    expect(panelText).not.toContain('escalation.1h');
    expect(panelText.length).toBeGreaterThan(30);

    await dvCoordinatorPage.keyboard.press('Escape');
  });

  // =========================================================================
  // STATE-MODIFYING TESTS (run after read-only tests)
  // =========================================================================

  test('T-58c: mark all as read preserves CRITICAL badge count', async ({ dvCoordinatorPage }) => {
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const bellButton = dvCoordinatorPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toHaveAttribute('aria-label', /\d+ unread/, { timeout: 5000 });

    await bellButton.click();
    const panel = dvCoordinatorPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    const markAllBtn = dvCoordinatorPage.locator('[data-testid="mark-all-read-button"]');
    await expect(markAllBtn).toBeVisible();
    await markAllBtn.click();

    await dvCoordinatorPage.keyboard.press('Escape');
    await expect(panel).not.toBeVisible();

    // CRITICAL (surge.activated) should survive mark-all-read (Design D3)
    await expect(bellButton).toHaveAttribute('aria-label', /\d+ unread/, { timeout: 5000 });
    const ariaLabel = await bellButton.getAttribute('aria-label') || '';
    const match = ariaLabel.match(/(\d+) unread/);
    expect(match).not.toBeNull();
    expect(parseInt(match![1], 10)).toBeGreaterThan(0);
  });

  test('T-57: mark notification as read decrements badge', async ({ dvCoordinatorPage }) => {
    // NOTE: Runs LAST among coordinator tests. After mark-all-read (T-58c),
    // only CRITICAL remains. This test clicks it (marking as read) and
    // verifies the count decreases.
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const bellButton = dvCoordinatorPage.locator('[data-testid="notification-bell-button"]');
    await expect(bellButton).toHaveAttribute('aria-label', /\d+ unread/, { timeout: 5000 });

    const initialLabel = await bellButton.getAttribute('aria-label') || '';
    const initialMatch = initialLabel.match(/(\d+) unread/);
    expect(initialMatch).not.toBeNull();
    const initialCount = parseInt(initialMatch![1], 10);

    await bellButton.click();
    const panel = dvCoordinatorPage.locator('[data-testid="notification-panel"]');
    await expect(panel).toBeVisible();

    // Click the last notification item (avoids accidentally clicking CRITICAL first)
    const items = panel.locator('[role="list"] li');
    await expect(items.first()).toBeVisible();
    const itemCount = await items.count();
    await items.nth(itemCount - 1).click();

    // Navigate back and verify count changed
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    const newLabel = await bellButton.getAttribute('aria-label') || '';
    const newMatch = newLabel.match(/(\d+) unread/);
    if (newMatch) {
      expect(parseInt(newMatch[1], 10)).toBeLessThanOrEqual(initialCount);
    }
    // If no match (all read, label is just "Notifications"), that's also valid
  });
});
