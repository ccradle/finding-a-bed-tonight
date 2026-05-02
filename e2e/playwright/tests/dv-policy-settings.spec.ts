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
});
