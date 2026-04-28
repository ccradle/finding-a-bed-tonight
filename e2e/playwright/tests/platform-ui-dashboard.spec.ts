import { test, expect } from '@playwright/test';

/**
 * F11 PlatformDashboard — action-card rendering, flag-gate disable,
 * operator-management placeholder, backup-codes urgency label
 * (warroom round 7 H5 — the earlier slice D specs covered banner +
 * routing only; the dashboard itself had no Playwright coverage).
 *
 * Mocks /me + the safe-action endpoints so the spec runs without a
 * backend. The dashboard's flag gate is hard-coded to false in v0.54
 * (TENANT_LIFECYCLE_ENABLED constant in PlatformDashboard.tsx) — this
 * spec asserts the user-visible consequences of that gate.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';

function fakeAccessJwt(opts: { expSeconds?: number } = {}): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + (opts.expSeconds ?? 900);
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify({
      iss: 'fabt-platform',
      sub: 'op-1',
      mfaVerified: true,
      exp,
    })),
    'sig',
  ].join('.');
}

interface MeOpts {
  backupCodesRemaining?: number;
}

async function setupAuthedPage(page: import('@playwright/test').Page, meOpts: MeOpts = {}) {
  const jwt = fakeAccessJwt();
  await page.addInitScript(([key, value]) => {
    window.sessionStorage.setItem(key, value);
  }, [PLATFORM_JWT_KEY, jwt]);

  await page.route('**/api/v1/auth/platform/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-0000-0000-000000000fab',
        email: 'test-op@example.com',
        mfaEnabled: true,
        lastLoginAt: '2026-04-28T12:00:00Z',
        mfaEnrolledAt: '2026-04-26T12:00:00Z',
        backupCodesRemaining: meOpts.backupCodesRemaining ?? 10,
      }),
    });
  });
}

test.describe('Platform-operator UI — dashboard', () => {
  test('renders all 7 action cards across 3 categories', async ({ page }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    await expect(page.getByTestId('platform-dashboard-main')).toBeVisible();

    // Tenant Lifecycle category — 5 cards
    await expect(page.getByTestId('platform-action-tenant-list')).toBeVisible();
    await expect(page.getByTestId('platform-action-tenant-suspend')).toBeVisible();
    await expect(page.getByTestId('platform-action-tenant-unsuspend')).toBeVisible();
    await expect(page.getByTestId('platform-action-tenant-offboard')).toBeVisible();
    await expect(page.getByTestId('platform-action-tenant-hard-delete')).toBeVisible();

    // System Status category — 2 cards
    await expect(page.getByTestId('platform-action-system-health')).toBeVisible();
    await expect(page.getByTestId('platform-action-system-version')).toBeVisible();

    // Operator Management placeholder (no actions in v0.54).
    // Round 8 N5 — assert the copy mentions the documented bootstrap
    // procedure so NIT #9's copy fix can't silently regress.
    const placeholder = page.getByTestId('platform-operator-management-placeholder');
    await expect(placeholder).toBeVisible();
    await expect(placeholder).toContainText(/psql bootstrap/i);
    await expect(placeholder).toContainText(/platform-operator runbook/i);
  });

  test('lifecycle cards (incl. List tenants) render disabled with tooltip', async ({ page }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    // All 5 lifecycle action buttons must be disabled in v0.54 because
    // fabt.tenant.lifecycle.enabled=false. Includes tenant-list (round 7
    // H3 fix — was previously enabled but window.open against an
    // auth-required endpoint).
    const lifecycleIds = [
      'tenant-list',
      'tenant-suspend',
      'tenant-unsuspend',
      'tenant-offboard',
      'tenant-hard-delete',
    ];
    for (const id of lifecycleIds) {
      const btn = page.getByTestId(`platform-action-${id}-button`);
      await expect(btn).toBeDisabled();
      // Tooltip is the `title` attr ("This action is disabled..." copy).
      await expect(btn).toHaveAttribute(
        'title',
        /disabled in this deployment/i,
      );
    }
  });

  test('disabled-card click does NOT open a new tab', async ({ page, context }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    const btn = page.getByTestId('platform-action-tenant-suspend-button');
    await expect(btn).toBeDisabled();

    // Try to force-click the disabled button. The browser should not
    // dispatch a `click` event on a `disabled` button — but be paranoid
    // about it: race the (potential) popup against a timeout, and
    // assert the timeout wins.
    let popupOpened = false;
    const popupPromise = context
      .waitForEvent('page', { timeout: 1000 })
      .then(() => {
        popupOpened = true;
      })
      .catch(() => {
        /* timed out — expected */
      });
    // `force: true` skips Playwright's own disabled check, so we are
    // genuinely testing the browser's behavior, not Playwright's.
    await btn.click({ force: true }).catch(() => {
      /* native browser may throw on disabled button; that's also fine */
    });
    await popupPromise;
    expect(popupOpened).toBe(false);
  });

  test('enabled system-status cards open a new tab on click', async ({ page, context }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    const btn = page.getByTestId('platform-action-system-version-button');
    await expect(btn).toBeEnabled();

    // window.open with target=_blank → context emits a `page` event.
    const [popup] = await Promise.all([
      context.waitForEvent('page'),
      btn.click(),
    ]);
    expect(popup.url()).toMatch(/\/api\/v1\/version$/);
    await popup.close();
  });

  test('button labels are readable verbs (round 7 M7)', async ({ page }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    // Per-action labels — the prior version produced strings like
    // "Open hard-delete tenant" / "Run system health". Each label is
    // now a single verb defined in platformActions.ts.
    await expect(page.getByTestId('platform-action-tenant-suspend-button')).toHaveText('Suspend');
    await expect(page.getByTestId('platform-action-tenant-hard-delete-button')).toHaveText('Hard-delete');
    await expect(page.getByTestId('platform-action-system-health-button')).toHaveText('Open');
    await expect(page.getByTestId('platform-action-system-version-button')).toHaveText('View');
  });

  test('backup-codes urgency: text label complements color (WCAG 1.4.1)', async ({ page }) => {
    // 1 remaining → critical
    await setupAuthedPage(page, { backupCodesRemaining: 1 });
    await page.goto('/platform/dashboard');

    const badge = page.getByTestId('platform-dashboard-backup-codes-badge');
    await expect(badge).toContainText('1 remaining');
    await expect(page.getByTestId('platform-dashboard-backup-codes-urgency')).toContainText('Critical');
  });

  test('backup-codes urgency: 3 remaining → low', async ({ page }) => {
    await setupAuthedPage(page, { backupCodesRemaining: 3 });
    await page.goto('/platform/dashboard');

    await expect(page.getByTestId('platform-dashboard-backup-codes-urgency')).toContainText('Low');
  });

  test('backup-codes urgency: 10 remaining → healthy', async ({ page }) => {
    await setupAuthedPage(page, { backupCodesRemaining: 10 });
    await page.goto('/platform/dashboard');

    await expect(page.getByTestId('platform-dashboard-backup-codes-urgency')).toContainText('Healthy');
  });
});
