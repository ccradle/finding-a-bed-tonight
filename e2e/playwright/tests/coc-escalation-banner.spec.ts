import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

/**
 * T-43 — coc-admin-escalation banner CTA spec.
 *
 * <p>Verifies the round-trip from the CRITICAL banner's "Review N pending
 * escalations →" CTA button (Session 6 T-40) to the DV Escalations admin
 * tab being pre-selected via the {@code #dvEscalations} hash anchor (T-41).</p>
 *
 * <p>Depends on the seed data including an {@code escalation.2h CRITICAL}
 * notification for cocadmin (added to {@code seed-data.sql} as part of
 * Session 6). Without that notification the CTA gating logic correctly
 * suppresses the button — and this spec would correctly fail.</p>
 *
 * Run: <code>BASE_URL=http://localhost:8081 npx playwright test
 * coc-escalation-banner --trace on</code>
 */
test.describe('coc-admin-escalation: CriticalNotificationBanner CTA', () => {

  // Drop any test-created shelters + referral tokens so this spec doesn't
  // crowd later specs' search results (dv-outreach-worker depends on seed
  // DV shelters being findable by testid).
  test.afterAll(async () => { await cleanupTestData(); });

  test('T-43a: CTA appears when an escalation.* CRITICAL notification exists', async ({ coordinatorPage }) => {
    // Note: the auth fixture's "coordinatorPage" is actually logged in as
    // cocadmin@dev.fabt.org (the file is named cocadmin.json) — confusingly
    // named, but that's the project convention. The seed includes an
    // escalation.2h CRITICAL notification for this exact user.
    await coordinatorPage.goto('/coordinator');

    const banner = coordinatorPage.locator('[data-testid="critical-notification-banner"]');
    await expect(banner).toBeVisible({ timeout: 5000 });
    await expect(banner).toHaveAttribute('role', 'alert');

    const cta = coordinatorPage.locator('[data-testid="critical-banner-escalation-cta"]');
    await expect(cta).toBeVisible({ timeout: 5000 });

    // Person-centered copy: "Review N pending escalations →" — no anxiety verbs
    const ctaText = await cta.textContent() || '';
    expect(ctaText).toMatch(/review|revisar/i);
    expect(ctaText).toMatch(/escalation|escalación/i);
    // Right-arrow indicating navigation
    expect(ctaText).toContain('→');

    // 44x44px touch target (WCAG D5)
    const ctaBox = await cta.boundingBox();
    expect(ctaBox).not.toBeNull();
    expect(ctaBox!.height).toBeGreaterThanOrEqual(44);
  });

  test('T-43b: CTA click navigates to /admin#dvEscalations and pre-selects the tab', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');

    const cta = coordinatorPage.locator('[data-testid="critical-banner-escalation-cta"]');
    await expect(cta).toBeVisible({ timeout: 5000 });

    // Click the CTA and wait for the admin route to load.
    await cta.click();
    await coordinatorPage.waitForURL(/\/admin/, { timeout: 5000 });

    // The hash should be #dvEscalations and the AdminPanel should pre-select that tab.
    expect(coordinatorPage.url()).toContain('#dvEscalations');

    // The DvEscalationsTab should be the active tab content. The tab itself
    // sets data-testid="dv-escalations-tab" on its root.
    const tabContent = coordinatorPage.locator('[data-testid="dv-escalations-tab"]');
    await expect(tabContent).toBeVisible({ timeout: 5000 });

    // The DV Escalations tab button in the tab bar should have aria-selected=true.
    const dvEscalationsTabButton = coordinatorPage.locator('[data-tab-key="dvEscalations"]');
    await expect(dvEscalationsTabButton).toHaveAttribute('aria-selected', 'true');
  });

  test('T-43c: navigating to a different tab and clicking the CTA again switches back', async ({ coordinatorPage }) => {
    // Verifies the hashchange listener (T-41): if the user is already on
    // /admin but on a different tab (e.g. Users), clicking the banner CTA
    // a second time must switch them back to dvEscalations. Without the
    // hashchange listener, BrowserRouter only re-renders on pathname
    // changes, not fragment changes, so the second click would be a no-op.
    await coordinatorPage.goto('/admin');

    // Switch to the Users tab first.
    const usersTabButton = coordinatorPage.locator('[data-tab-key="users"]');
    await usersTabButton.click();
    await expect(usersTabButton).toHaveAttribute('aria-selected', 'true');

    // Now click the CTA in the banner (which is still rendered above the admin panel).
    const cta = coordinatorPage.locator('[data-testid="critical-banner-escalation-cta"]');
    await expect(cta).toBeVisible({ timeout: 5000 });
    await cta.click();

    // The DV Escalations tab should now be active.
    const dvEscalationsTabButton = coordinatorPage.locator('[data-tab-key="dvEscalations"]');
    await expect(dvEscalationsTabButton).toHaveAttribute('aria-selected', 'true', { timeout: 5000 });
  });
});
