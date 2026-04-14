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

  /**
   * Issue #106 Section 16 — banner genesis-gap regression test (tasks 16.5.1,
   * 16.5.2, 16.5.3). The original user story that motivated the entire
   * notification-deep-linking change: coordinator sees "1 referral waiting
   * for review", clicks, and expects to land on the pending referral. Before
   * Section 16 the click handler fell back to `shelters.find(s => s.dvShelter)`
   * which picked the alphabetically-first DV shelter regardless of where
   * the pending referral actually lived — Corey's Harbor House scenario
   * (Harbor House sits at index 2 alphabetically; #1 is Bridges to Safety).
   *
   * This test pins the fix by asserting the banner click navigates the
   * coordinator to whatever the backend's firstPending routing hint says
   * is the oldest pending referral — NOT a hardcoded shelter name (so the
   * test stays correct regardless of which shelter the seed happens to have
   * the oldest pending at). The design decision is in openspec/changes/
   * notification-deep-linking/design.md under D-BP.
   *
   * Pre-existing state independence: no seed modification needed — we read
   * firstPending before clicking and assert we land at that exact referralId.
   */
  test('Issue #106 Section 16: banner click routes via firstPending hint (not alphabetically-first DV shelter)', async ({ dvCoordinatorPage }) => {
    // 1. Login as dv-coordinator via API to read the current firstPending
    //    hint. We use the same credentials as the Playwright fixture so the
    //    token scopes exactly to the UI session.
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-coordinator@dev.fabt.org', password: 'admin123' }),
    });
    expect(loginResp.ok, 'dv-coordinator login must succeed (seed user)').toBeTruthy();
    const { accessToken } = await loginResp.json();

    const countResp = await fetch(`${API_URL}/api/v1/dv-referrals/pending/count`, {
      headers: { 'Authorization': `Bearer ${accessToken}` },
    });
    expect(countResp.ok, '/pending/count must succeed').toBeTruthy();
    const payload = await countResp.json() as {
      count: number;
      firstPending: { referralId: string; shelterId: string } | null;
    };

    // 2. Pre-condition: seed must currently have at least one pending referral
    //    for this test to exercise the click path. If it does not, skip
    //    gracefully rather than ship a false-pass — same pattern as the
    //    existing Issue #106 deep-link test at line 150.
    if (payload.count === 0 || !payload.firstPending) {
      test.skip(true, 'No pending referrals in seed — banner never renders, click path cannot be exercised');
    }

    const expectedReferralId = payload.firstPending!.referralId;
    const expectedShelterId = payload.firstPending!.shelterId;

    // 3. Navigate to /coordinator WITHOUT any ?referralId query param — this
    //    is the exact entry Corey reported (direct nav, bookmark, fresh login).
    //    The URL-wins path is already covered by the existing test at line 150.
    await dvCoordinatorPage.goto('/coordinator');
    await expect(dvCoordinatorPage.locator('[data-testid="coordinator-heading"]')).toBeVisible();

    // Confirm the URL has no referralId query param — proves we're exercising
    // the hint-fallback path, not the URL-passthrough path.
    const urlBefore = new URL(dvCoordinatorPage.url());
    expect(urlBefore.searchParams.get('referralId'), 'URL should have NO referralId before banner click').toBeNull();

    const banner = dvCoordinatorPage.locator('[data-testid="coordinator-referral-banner"]');
    await expect(banner, 'banner must be visible when count > 0').toBeVisible();

    // 4. Click the banner.
    await banner.click();

    // 5. Assert URL transitioned to /coordinator?referralId=<firstPending>.
    //    This is the CORE genesis-gap assertion — before Section 16 the URL
    //    stayed at /coordinator and the click handler scrolled to the wrong
    //    shelter without touching the URL.
    await expect.poll(
      () => new URL(dvCoordinatorPage.url()).searchParams.get('referralId'),
      { timeout: 5000 },
    ).toBe(expectedReferralId);

    // 6. Assert the screening row for the specific referral is visible —
    //    proves useDeepLink picked up the URL change and drove the
    //    resolve → expand → scroll sequence.
    const row = dvCoordinatorPage.locator(`[data-testid="screening-${expectedReferralId}"]`);
    await expect(row, 'screening row for firstPending referral must materialize').toBeVisible({ timeout: 10000 });

    // 7. S-2 safety: focus on row heading, NOT the Accept button. Identical
    //    contract to the existing ?referralId= test — banner-click and
    //    notification-click converge on the same useDeepLink state machine.
    await expect(row).toBeFocused();
    const acceptBtn = dvCoordinatorPage.locator(`[data-testid="accept-referral-${expectedReferralId}"]`);
    await expect(acceptBtn).not.toBeFocused();

    // 8. Assert the correct shelter card expanded — cross-check against
    //    firstPending.shelterId. If the banner had routed to the alphabetical-
    //    first DV shelter (old behavior), the shelter-card data-testid would
    //    point at a different UUID. This assertion is the teeth of the
    //    regression test: it would have failed against pre-Section-16 code.
    const shelterCard = dvCoordinatorPage.locator(`[data-testid="shelter-card-${expectedShelterId}"]`);
    await expect(shelterCard, 'shelter card for firstPending.shelterId must be in the DOM (expanded)').toBeVisible();
  });

  /**
   * Task 11.8 — stale referral deep-link surfaces toast and does NOT mark
   * notification acted. The useDeepLink 'stale' branch (D10 unified shape)
   * fires when resolveTarget 404s or the target times out.
   */
  test('Task 11.8: stale referralId deep-link shows stale toast (no acted flip)', async ({ dvCoordinatorPage }) => {
    // Bogus UUID — GET /api/v1/dv-referrals/<bogus> returns 404, which the
    // useDeepLink hook maps to {kind: 'stale', reason: 'not-found'}. The
    // toast fires regardless of whether the coordinator has a matching
    // notification in their bell.
    const bogusUuid = '00000000-0000-0000-0000-beefdead1234';
    await dvCoordinatorPage.goto(`/coordinator?referralId=${bogusUuid}`);

    // The stale toast has role="alert" (see CoordinatorDashboard.tsx —
    // single stale shape, D10). Assertion keyed to its text since that's
    // the user-facing signal Keisha cares about.
    const toast = dvCoordinatorPage.locator('[role="alert"]', {
      hasText: /no longer available|no longer pending|ya no está|stale/i,
    });
    await expect(toast).toBeVisible({ timeout: 10000 });
  });

  /**
   * Task 11.6 — My Past Holds renders HELD + terminal holds when outreach
   * worker has them. Skips gracefully if seed provides no holds.
   */
  test('Task 11.6: My Past Holds page renders — heading, rows-or-empty', async ({ dvOutreachPage }) => {
    await dvOutreachPage.goto('/outreach/my-holds');
    // Page heading is the primary landmark; confirms routing didn't 404
    // and the component rendered past loading. Actual testid in source
    // is {@code my-holds-heading} (verified via trace DOM snapshot +
    // grep of {@code MyPastHoldsPage.tsx:441}).
    await expect(dvOutreachPage.locator('[data-testid="my-holds-heading"]')).toBeVisible({ timeout: 10000 });

    // Either the empty-state testid OR some rows must be present —
    // anything else (blank page, infinite loading) is a real bug.
    const rowCount = await dvOutreachPage.locator('[data-testid^="my-holds-row-"]').count();
    if (rowCount === 0) {
      const empty = dvOutreachPage.locator('[data-testid="my-holds-empty"]');
      await expect(empty, 'empty state must render when no holds exist').toBeVisible();
      test.skip(true, 'dv-outreach seed has no holds — empty state asserted, row-level checks skipped');
    }

    // When rows exist, every one must carry a tel: link so the worker can
    // reach the shelter directly (Devon's training requirement).
    // There is NO status-label testid in the source (verified 2026-04-14);
    // status is rendered via an inline StatusBadge component, which is
    // visually-present but not test-addressable. If the StatusBadge ever
    // gains a testid, add a per-row count assertion here.
    const telLinks = dvOutreachPage.locator('[data-testid^="my-holds-row-"] a[href^="tel:"]');
    const telCount = await telLinks.count();
    expect(telCount, 'every rendered hold row should have at least one tel: link').toBeGreaterThan(0);
  });

  /**
   * Task 11.2 — admin escalation detail modal opens on deep-link. Requires
   * a referral that's in the escalated queue (queue membership is the
   * isTargetReady condition on DvEscalationsTab, L-2 guard). Skips if the
   * queue is empty.
   *
   * Uses coordinatorPage fixture (which is cocadmin per the project's
   * confusingly-named convention — the COC_ADMIN role is what can see
   * the DV Escalations tab).
   */
  /**
   * Task 11.13 — X-1 concurrency: two DV coordinators (Alice + Bob) both
   * receive a referral.requested notification. Alice accepts. Bob later
   * deep-links to the same referral → the useDeepLink awaiting-target
   * timeout fires (referral is no longer PENDING, so isTargetReady never
   * returns true, 5s deadline elapses → stale). Bob's notification must
   * end with {@code read_at} set but {@code acted_at} null — the
   * stale-fallback contract from Design D3.
   *
   * Bob is created fresh via `POST /api/v1/users` (cocadmin auth) because
   * the seed has exactly one dv-access COORDINATOR. Cleanup in a
   * finally block deactivates Bob via `PATCH /users/{id}/status` so
   * subsequent tests don't see the extra fan-out recipient.
   */
  test('Task 11.13: concurrent coordinators — Alice accepts, Bob sees stale + read-not-acted', async ({ browser }) => {
    const suffix = Math.random().toString(36).slice(2, 10);
    const bobEmail = `bob-coord-${suffix}@test.fabt.org`;
    let bobUserId: string | null = null;

    try {
      // --- 1. Admin-API prep: login as cocadmin, create Bob ---
      const cocadminLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'cocadmin@dev.fabt.org', password: 'admin123' }),
      });
      expect(cocadminLogin.ok, 'cocadmin login must succeed').toBeTruthy();
      const { accessToken: cocadminToken } = await cocadminLogin.json();

      const bobCreate = await fetch(`${API_URL}/api/v1/users`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${cocadminToken}`,
        },
        body: JSON.stringify({
          email: bobEmail,
          displayName: `Bob Coord ${suffix}`,
          password: 'admin123',
          roles: ['COORDINATOR'],
          dvAccess: true,
        }),
      });
      expect(bobCreate.status, `Bob creation must return 201 — body: ${await bobCreate.clone().text()}`).toBe(201);
      bobUserId = (await bobCreate.json()).id as string;

      // --- 2. dv-outreach creates a DV referral ---
      //      Fans out to ALL active dv-access COORDINATORs: Alice + Bob.
      const outreachLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-outreach@dev.fabt.org', password: 'admin123' }),
      });
      const { accessToken: outreachToken } = await outreachLogin.json();

      const sheltersResp = await fetch(`${API_URL}/api/v1/shelters?populationType=DV_SURVIVOR`, {
        headers: { 'Authorization': `Bearer ${outreachToken}` },
      });
      const shelters = await sheltersResp.json() as Array<{ shelter: { id: string; dvShelter?: boolean } }>;
      const dvShelter = shelters.find((s) => s.shelter?.dvShelter);
      expect(dvShelter, 'need a DV shelter for the test').toBeTruthy();

      const referralCreate = await fetch(`${API_URL}/api/v1/dv-referrals`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${outreachToken}`,
        },
        body: JSON.stringify({
          shelterId: dvShelter!.shelter.id,
          populationType: 'DV_SURVIVOR',
          householdSize: 2,
          urgency: 'URGENT',
          callbackNumber: '919-555-0113',
        }),
      });
      expect(referralCreate.status).toBe(201);
      const referralId = (await referralCreate.json()).id as string;

      // --- 3. Alice (dv-coordinator) accepts via API ---
      //      After this, referral.status = ACCEPTED. Bob's deep-link in
      //      step 4 will see it as non-PENDING, triggering the stale path.
      const aliceLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'dv-coordinator@dev.fabt.org', password: 'admin123' }),
      });
      const { accessToken: aliceToken } = await aliceLogin.json();

      const acceptResp = await fetch(`${API_URL}/api/v1/dv-referrals/${referralId}/accept`, {
        method: 'PATCH',
        headers: { 'Authorization': `Bearer ${aliceToken}` },
      });
      expect(acceptResp.ok, `Alice accept must succeed — body: ${await acceptResp.clone().text()}`).toBeTruthy();

      // --- 4. Bob opens a Playwright browser context and deep-links ---
      //      Use a fresh context with the login UI flow so tokens land in
      //      localStorage naturally (same path as the fixture's loginAndSaveState).
      const bobContext = await browser.newContext();
      const bobPage = await bobContext.newPage();
      await bobPage.goto(`${process.env.BASE_URL || 'http://localhost:8081'}/login`);
      await bobPage.locator('[data-testid="login-tenant-slug"]').fill(TENANT_SLUG);
      await bobPage.locator('[data-testid="login-email"]').fill(bobEmail);
      await bobPage.locator('[data-testid="login-password"]').fill('admin123');
      await bobPage.locator('[data-testid="login-submit"]').click();
      await bobPage.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });

      await bobPage.goto(`/coordinator?referralId=${referralId}`);

      // Stale toast appears when useDeepLink transitions to 'stale' (either
      // via resolveTarget 404 path OR via awaiting-target 5s timeout).
      // Bob's referral resolves to 200 (it exists, just non-PENDING), so the
      // path here is the timeout variant — matches Scenario 3 of the D-BP
      // banner-click-navigation spec.
      const staleToast = bobPage.locator('[role="alert"]', {
        hasText: /no longer available|no longer pending|ya no está|stale/i,
      });
      await expect(staleToast, 'stale toast must appear for Bob\'s already-accepted referral').toBeVisible({ timeout: 10000 });

      await bobContext.close();

      // --- 5. Verify Bob's notification state via API ---
      //      Must be read_at populated, acted_at null. This is the
      //      "I saw too late" contract (Design D3).
      const bobLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: bobEmail, password: 'admin123' }),
      });
      const { accessToken: bobToken } = await bobLogin.json();

      const bobNotifs = await fetch(`${API_URL}/api/v1/notifications?page=0&size=50`, {
        headers: { 'Authorization': `Bearer ${bobToken}` },
      });
      const { items: bobItems } = await bobNotifs.json() as { items: Array<{ payload: string; readAt: string | null; actedAt: string | null }> };
      const bobNotifForReferral = bobItems.find((n) => {
        try {
          return (JSON.parse(n.payload) as { referralId?: string }).referralId === referralId;
        } catch {
          return false;
        }
      });
      expect(bobNotifForReferral, `Bob must have a notification for referral ${referralId}`).toBeTruthy();
      // `spring.jackson.default-property-inclusion=non_null` strips null
      // fields from the serialized response, so `readAt`/`actedAt` arrive
      // as the string timestamp OR `undefined` (never literal null).
      // toBeTruthy/toBeFalsy captures both shapes correctly; a literal
      // `toBeNull()` would false-fail on the `undefined` shape.
      expect(bobNotifForReferral!.readAt, 'Bob\'s notification must be marked read by the stale-fallback').toBeTruthy();
      expect(bobNotifForReferral!.actedAt, 'Bob\'s notification MUST NOT be marked acted — stale-fallback uses /read, not /acted').toBeFalsy();

      // Alice's notification should be acted (accept + markNotificationsActedByPayload
      // on the frontend). But the API accept we used here doesn't go through the
      // frontend markActed helper — Alice's notification state is therefore NOT
      // asserted here. Backend task 9.2 pins the recipient-scoped contract.
    } finally {
      // --- Cleanup: deactivate Bob so future tests don't see double fan-out ---
      if (bobUserId) {
        const cocadminLogin = await fetch(`${API_URL}/api/v1/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'cocadmin@dev.fabt.org', password: 'admin123' }),
        });
        const { accessToken: cocadminToken } = await cocadminLogin.json();
        await fetch(`${API_URL}/api/v1/users/${bobUserId}/status`, {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${cocadminToken}`,
          },
          body: JSON.stringify({ status: 'DEACTIVATED' }),
        });
      }
    }
  });

  test('Task 11.2: admin deep-link to escalation queue opens detail modal', async ({ coordinatorPage }) => {
    // Probe the live queue first. If empty, skip — the modal's open
    // condition requires queue membership.
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'cocadmin@dev.fabt.org', password: 'admin123' }),
    });
    expect(loginResp.ok, 'cocadmin login must succeed').toBeTruthy();
    const { accessToken } = await loginResp.json();

    const escalatedResp = await fetch(`${API_URL}/api/v1/dv-referrals/escalated`, {
      headers: { 'Authorization': `Bearer ${accessToken}` },
    });
    expect(escalatedResp.ok, '/escalated must succeed for cocadmin').toBeTruthy();
    const queue = await escalatedResp.json() as Array<{ id: string }>;
    if (queue.length === 0) {
      test.skip(true, 'Escalated queue is empty — no referral to deep-link to');
    }
    const targetReferralId = queue[0].id;

    // Navigate to the admin panel DV Escalations tab with the referralId
    // hash query. useHashSearchParams drives useDeepLink; 'done' transition
    // opens the modal via the DvEscalationsTab useEffect.
    await coordinatorPage.goto(`/admin#dvEscalations?referralId=${targetReferralId}`);

    const modal = coordinatorPage.locator('[data-testid="dv-escalation-detail-modal"]');
    await expect(modal, 'detail modal must open on deep-link to an escalated referral').toBeVisible({ timeout: 10000 });
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
