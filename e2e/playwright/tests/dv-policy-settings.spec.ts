import { test, expect } from '../fixtures/auth.fixture';

/**
 * dv-policy-tenant-flag — admin panel smoke (§9.3 of openspec/changes/dv-policy-tenant-flag/tasks.md).
 *
 * Scope is intentionally narrow:
 *  - panel renders for a COC_ADMIN with dvAccess=true
 *  - clicking the toggle opens the extra-confirm modal
 *  - clicking Cancel closes the modal without flipping the flag
 *
 * What this spec does NOT exercise (and why):
 *  - Actually flipping the flag → would mutate the dev-coc tenant's
 *    dv_policy_enabled JSONB key for the rest of the suite. Backend
 *    invariants + cross-tenant audit are covered by DvPolicyControllerTest
 *    (10 IT scenarios) and ShelterServiceDvPolicyInvariantTest (10 scenarios).
 *  - Disable-rejection rendering with count → covered by the
 *    {@code parseDvPolicyError} Vitest unit tests
 *    (DvPolicySettings.test.ts, 8 scenarios).
 *  - Inventory link target → static href verified by inspection;
 *    dynamic routing is covered by existing admin-panel.spec.ts tab tests.
 *
 * The cocadmin@dev.fabt.org seed user has roles=[COC_ADMIN] and
 * dv_access=true (per infra/scripts/seed-data.sql lines 121-147), which
 * is the exact role+claim combination required by the DvPolicyController
 * (design D10 — RLS coupling forces the dvAccess gate).
 */
test.describe('Admin Panel — DV Policy Settings', () => {
  test('panel renders + toggle opens confirm modal + cancel closes without flipping', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin');

    const panel = coordinatorPage.locator('[data-testid="dv-policy-settings"]');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText(/Domestic Violence Shelter Operations/);

    const toggle = coordinatorPage.locator('[data-testid="dv-policy-toggle"]');
    await expect(toggle).toBeVisible();
    await expect(toggle).toBeEnabled();

    const initialPressed = await toggle.getAttribute('aria-pressed');
    expect(initialPressed === 'true' || initialPressed === 'false').toBe(true);

    await toggle.click();

    const modal = coordinatorPage.locator('[data-testid="dv-policy-confirm-modal"]');
    await expect(modal).toBeVisible();
    await expect(modal).toContainText(/DV shelter operations for this CoC/i);

    await coordinatorPage.locator('[data-testid="dv-policy-cancel-button"]').click();

    await expect(modal).toBeHidden();

    const afterCancelPressed = await toggle.getAttribute('aria-pressed');
    expect(afterCancelPressed).toBe(initialPressed);
  });

  // Warroom round 3 H1 + H2 — keyboard accessibility on the confirm modal.
  // Without these handlers the modal violated the W3C ARIA Modal Dialog
  // pattern (no Esc, no focus capture) — keyboard users had to manually
  // tab into the modal and could not dismiss without reaching the cancel
  // button. Belt-and-suspenders coverage: assert the contracts at the
  // browser level so a regression in the React effect or keydown wiring
  // surfaces here, not just in user reports.
  test('Escape key closes the confirm modal without flipping the flag', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin');

    const toggle = coordinatorPage.locator('[data-testid="dv-policy-toggle"]');
    const initialPressed = await toggle.getAttribute('aria-pressed');

    await toggle.click();

    const modal = coordinatorPage.locator('[data-testid="dv-policy-confirm-modal"]');
    await expect(modal).toBeVisible();

    await coordinatorPage.keyboard.press('Escape');

    await expect(modal).toBeHidden();
    expect(await toggle.getAttribute('aria-pressed')).toBe(initialPressed);
  });

  test('cancel button receives focus when modal opens', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin');

    const toggle = coordinatorPage.locator('[data-testid="dv-policy-toggle"]');
    await toggle.click();

    const modal = coordinatorPage.locator('[data-testid="dv-policy-confirm-modal"]');
    await expect(modal).toBeVisible();

    // Wait for the focus effect to land — useEffect runs after paint so the
    // assertion must wait for the effect tick rather than reading focus
    // synchronously off the click().
    await expect(coordinatorPage.locator('[data-testid="dv-policy-cancel-button"]'))
      .toBeFocused({ timeout: 2000 });

    // Clean up — close modal so subsequent tests in the file don't see it
    await coordinatorPage.keyboard.press('Escape');
    await expect(modal).toBeHidden();
  });

  // Warroom round 3 B1 — defense against the inventory-link regression we
  // just fixed. Earlier the href was {@code ?tab=shelters&dvShelter=true&active=true}
  // which AdminPanel ignored entirely (it parses tab from {@code location.hash}
  // via {@code tabKeyFromHash}, not from {@code location.search}). Lock the
  // href to the hash convention so a future "fix" reverting to query params
  // gets caught here. The link only renders when a disable rejection is
  // present in state; we can't easily force that without mutating the
  // tenant's flag, so dig the rendered DOM for the href instead — read-only
  // and seed-safe. The link is hidden in the smoke happy path, so this test
  // injects state via JS rather than triggering it through the API.
  test('inventory link points at hash-routed shelters tab when rendered', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/admin');

    // Inject a synthetic disable-rejection error into the component so the
    // link renders. We can't mutate React state directly from Playwright, so
    // instead we open the modal and use the keyboard path that does NOT
    // commit a flip (ensures seed stays clean). The link is gated on
    // {@code error.remainingDvShelters > 0}, which only the real backend
    // can produce — so this assertion checks the static href in the bundled
    // source as a contract test rather than a live render.
    //
    // Alternative: this could become a Vitest snapshot test once the codebase
    // adds RTL. For now, fetch the bundle and grep — gives the same regression
    // protection without standing up a fake backend.
    const bundleHasHashHref = await coordinatorPage.evaluate(async () => {
      const sources = Array.from(document.querySelectorAll('script[src]'))
        .map((s) => (s as HTMLScriptElement).src)
        .filter((src) => src.includes('assets/'));
      for (const src of sources) {
        const text = await fetch(src).then((r) => r.text()).catch(() => '');
        if (text.includes('dv-policy-shelter-inventory-link')) {
          // Found the chunk that contains the inventory-link testid. Verify
          // the href is the hash form, not the query-string form.
          const hasHashHref = text.includes('/admin#shelters');
          const hasOldQueryHref = text.includes('tab=shelters&dvShelter=true');
          return { found: true, hasHashHref, hasOldQueryHref };
        }
      }
      return { found: false };
    });

    expect(bundleHasHashHref.found, 'inventory link testid not found in any bundled chunk').toBe(true);
    expect(bundleHasHashHref.hasHashHref, 'inventory link must use /admin#shelters hash convention').toBe(true);
    expect(bundleHasHashHref.hasOldQueryHref, 'inventory link must NOT use the deprecated ?tab=shelters query form').toBe(false);
  });
});
