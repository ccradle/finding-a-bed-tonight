import { test, expect } from '../fixtures/auth.fixture';
import {
  loginPlatformOperator,
  loginAsSmokeUser,
  platformAdminFetch,
  type PlatformOperatorSession,
} from '../helpers/auth/platform-operator';

test.describe('Observability — Platform Dashboard + Surge Tab', () => {
  let session: PlatformOperatorSession;

  // Use beforeAll: single MFA login for all tests in this describe block.
  // Avoids TOTP replay collision (89s window) that occurs with beforeEach
  // when the smoke user's last_totp_code is already set.
  test.beforeAll(async ({ request }) => {
    session = await loginAsSmokeUser(request);
  });

  // platform-observability-split (openspec, 2026-05-02):
  // Tracing + Prometheus + monitor intervals are now on the Platform Dashboard
  // (PUT /api/v1/platform/observability, PLATFORM_OPERATOR + @PlatformAdminOnly).
  // Temperature threshold is now on the Surge tab (COC_ADMIN scope).
  test('toggle tracing on, save, and verify persists on reload', async ({ request }) => {
    // GET current config (API returns camelCase fields)
    let resp = await platformAdminFetch(request, session, 'GET', '/api/v1/platform/observability', 'toggle tracing test');
    expect(resp.ok()).toBeTruthy();
    type ObservabilityConfig = {
      tracingEnabled: boolean;
      [key: string]: unknown;
    };
    const config = (await resp.json()) as ObservabilityConfig;
    const wasEnabled = config.tracingEnabled;

    // Toggle tracing
    resp = await platformAdminFetch(
      request, session, 'PUT', '/api/v1/platform/observability',
      'toggle tracing test',
      { tracing_enabled: !wasEnabled }
    );
    expect(resp.ok(), `PUT failed: ${await resp.text()}`).toBeTruthy();

    // Verify GET reflects new state
    resp = await platformAdminFetch(request, session, 'GET', '/api/v1/platform/observability', 'verify tracing toggle');
    const updated = (await resp.json()) as ObservabilityConfig;
    expect(updated.tracingEnabled).toBe(!wasEnabled);

    // Clean up: restore original state. Warroom round 5 fix — the prior
    // version did `if (wasEnabled) PUT { tracing_enabled: true }`, which
    // (a) skipped restore entirely when wasEnabled was false (the default
    // post-V98), leaving the platform_config row with `tracing_enabled:
    // true` and polluting subsequent tests / dev-stack state, and (b)
    // wrote the wrong value (`true`) when restoring a previously-true
    // state — only correct by accident. Always PUT the captured baseline.
    resp = await platformAdminFetch(
      request, session, 'PUT', '/api/v1/platform/observability',
      'restore tracing state',
      { tracing_enabled: wasEnabled }
    );
    expect(resp.ok(), `Restore failed: ${await resp.text()}`).toBeTruthy();
  });

  test('change temperature threshold — modal flow + persistence + Escape', async ({ adminPage }) => {
    test.setTimeout(60000); // Extended — this test navigates twice + saves + verifies

    // Navigate to Surge tab (temperature threshold moved here)
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);
    await adminPage.locator('button[role="tab"]', { hasText: /^Surge$/ }).click();

    // Spec §6.7 data-testid contract.
    const settings = adminPage.getByTestId('surge-temperature-settings');
    await expect(settings).toBeVisible({ timeout: 5000 });

    const input = adminPage.getByTestId('temperature-threshold-input');
    const save = adminPage.getByTestId('temperature-save-button');
    await expect(input).toBeVisible();

    // 1) Modal-flow happy path.
    await input.fill('40');
    await save.click();

    // Confirm modal should open with focus on cancel (warroom round 3 H2).
    const modal = adminPage.getByTestId('temperature-confirm-modal');
    await expect(modal).toBeVisible({ timeout: 3000 });
    const cancelBtn = adminPage.getByTestId('temperature-cancel-button');
    const confirmBtn = adminPage.getByTestId('temperature-confirm-button');
    await expect(cancelBtn).toBeFocused();

    // 2) Escape closes the modal (warroom round 3 H1) without persisting.
    await adminPage.keyboard.press('Escape');
    await expect(modal).not.toBeVisible();

    // 3) Re-open and confirm. After confirmation, saved-toast surfaces and
    //    a reload reflects the new persisted value.
    await input.fill('40');
    await save.click();
    await expect(modal).toBeVisible({ timeout: 3000 });
    await confirmBtn.click();
    await expect(adminPage.getByTestId('temperature-saved-toast')).toBeVisible({ timeout: 5000 });

    // Re-navigate and verify persistence
    await adminPage.goto('/admin');
    await adminPage.locator('button[role="tab"]', { hasText: /^Surge$/ }).click();
    await expect(adminPage.getByTestId('temperature-threshold-input')).toHaveValue('40');

    // Clean up: reset to 32
    await adminPage.getByTestId('temperature-threshold-input').fill('32');
    await adminPage.getByTestId('temperature-save-button').click();
    await adminPage.getByTestId('temperature-confirm-button').click();
    await expect(adminPage.getByTestId('temperature-saved-toast')).toBeVisible({ timeout: 5000 });
  });

  test('SurgeTab structure — temperature settings always render (warroom round 4)', async ({ adminPage }) => {
    // Round 4 fix: this test was previously a conditional no-op — if the
    // monitor banner wasn't visible (which it usually isn't in CI before
    // the 90s warmup), the test passed with zero assertions. Now we assert
    // structural elements that are ALWAYS present (the threshold input
    // settings) plus a tolerant assertion on the optional banner.
    await adminPage.goto('/admin');
    await expect(adminPage.locator('main h1')).toContainText(/administration/i);
    await adminPage.locator('button[role="tab"]', { hasText: /^Surge$/ }).click();

    // Hard requirement: SurgeTemperatureSettings panel + the threshold input
    // are always rendered when the operator opens the Surge tab.
    await expect(adminPage.getByTestId('surge-temperature-settings')).toBeVisible({ timeout: 5000 });
    await expect(adminPage.getByTestId('temperature-threshold-input')).toBeVisible();
    await expect(adminPage.getByTestId('temperature-save-button')).toBeVisible();

    // Optional: temperature monitor banner. If it's rendered (monitor has
    // already run at least once), verify it has the structure we expect;
    // if it isn't rendered yet (cold start), no assertion fires here.
    // This is intentional — the banner is a runtime-driven surface, but
    // unlike the prior version of this test, the test is NOT a no-op:
    // the assertions above always fire.
    const banner = adminPage.locator('main', { hasText: /station:/i });
    if (await banner.count() > 0) {
      await expect(adminPage.locator('main', { hasText: /threshold/i }).first()).toBeVisible();
    }
  });
});
