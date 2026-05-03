import { test, expect } from '@playwright/test';

/**
 * Playwright coverage for warroom rounds 6 + 7 (2026-05-03):
 *
 *  Round 6 — replaced the window.prompt() flow on the platform dashboard
 *  observability cards with an inline-edit form following the W3C ARIA APG
 *  Switch Pattern, type-appropriate inputs, and a 2-step destructive confirm.
 *
 *  Round 7 — wired the previously flag-gated `View` button on the
 *  `tenant-list` card to an inline read-only panel below the Lifecycle
 *  category that fetches via platformFetch (preserving the JWT instead of
 *  the prior new-tab `window.open` that stripped it).
 *
 * Mocks /me + the observability config + the tenant list so the spec
 * runs without a backend, mirroring the convention from
 * platform-ui-dashboard.spec.ts.
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

interface SetupOpts {
  obsConfig?: {
    prometheusEnabled: boolean;
    tracingEnabled: boolean;
    tracingEndpoint: string;
    monitorStaleIntervalMinutes: number;
    monitorDvCanaryIntervalMinutes: number;
    monitorTemperatureIntervalMinutes: number;
  };
  tenantList?: Array<{ id: string; name: string; slug: string; createdAt: string; updatedAt: string }>;
  obsPutHandler?: (body: Record<string, unknown>) => { status: number; body?: Record<string, unknown> };
}

async function setupAuthedPage(page: import('@playwright/test').Page, opts: SetupOpts = {}) {
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
        backupCodesRemaining: 10,
      }),
    });
  });

  const obsConfig = opts.obsConfig ?? {
    prometheusEnabled: true,
    tracingEnabled: false,
    tracingEndpoint: 'http://localhost:4318/v1/traces',
    monitorStaleIntervalMinutes: 5,
    monitorDvCanaryIntervalMinutes: 15,
    monitorTemperatureIntervalMinutes: 60,
  };
  await page.route('**/api/v1/platform/observability', async (route, request) => {
    if (request.method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(obsConfig),
      });
      return;
    }
    if (request.method() === 'PUT') {
      const body = request.postDataJSON() as Record<string, unknown>;
      const result = opts.obsPutHandler?.(body) ?? { status: 200, body: { ...obsConfig, ...body } };
      await route.fulfill({
        status: result.status,
        contentType: 'application/json',
        body: JSON.stringify(result.body ?? {}),
      });
      return;
    }
    await route.fallback();
  });

  const tenantList = opts.tenantList ?? [
    {
      id: '00000000-0000-0000-0000-000000000a01',
      name: 'Development CoC',
      slug: 'dev-coc',
      createdAt: '2026-04-01T12:00:00Z',
      updatedAt: '2026-04-29T08:00:00Z',
    },
    {
      id: '00000000-0000-0000-0000-000000000a02',
      name: 'Blue Ridge CoC',
      slug: 'dev-coc-west',
      createdAt: '2026-04-15T12:00:00Z',
      updatedAt: '2026-04-29T08:00:00Z',
    },
  ];
  await page.route('**/api/v1/tenants', async (route, request) => {
    if (request.method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(tenantList),
      });
      return;
    }
    await route.fallback();
  });
}

test.describe('Platform dashboard — tenant-list inline panel (warroom round 7)', () => {
  test('View opens the panel, lists tenants, Close collapses', async ({ page }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    // Card itself is enabled now (no flagGate).
    const viewBtn = page.getByTestId('platform-action-tenant-list-button');
    await expect(viewBtn).toBeEnabled();

    // Panel is initially absent.
    await expect(page.getByTestId('platform-tenant-list-panel')).toHaveCount(0);

    await viewBtn.click();

    // Panel appears with header + 2 mocked rows.
    const panel = page.getByTestId('platform-tenant-list-panel');
    await expect(panel).toBeVisible();
    await expect(page.getByTestId('platform-tenant-row-dev-coc')).toBeVisible();
    await expect(page.getByTestId('platform-tenant-row-dev-coc-west')).toBeVisible();
    await expect(panel).toContainText(/Tenants \(2\)/);

    // Close collapses.
    await page.getByTestId('platform-tenant-list-close').click();
    await expect(page.getByTestId('platform-tenant-list-panel')).toHaveCount(0);
  });

  test('Refresh re-fetches the list without closing the panel', async ({ page }) => {
    let calls = 0;
    await setupAuthedPage(page);
    // Wrap the route to count GET calls.
    await page.route('**/api/v1/tenants', async (route, request) => {
      if (request.method() === 'GET') {
        calls += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: '00000000-0000-0000-0000-000000000a01',
              name: `Refresh count: ${calls}`,
              slug: 'refresh-counter',
              createdAt: '2026-04-01T12:00:00Z',
              updatedAt: '2026-04-29T08:00:00Z',
            },
          ]),
        });
        return;
      }
      await route.fallback();
    });
    await page.goto('/platform/dashboard');

    await page.getByTestId('platform-action-tenant-list-button').click();
    const panel = page.getByTestId('platform-tenant-list-panel');
    await expect(panel).toContainText('Refresh count: 1');

    await page.getByTestId('platform-tenant-list-refresh').click();
    await expect(panel).toContainText('Refresh count: 2');
    await expect(panel).toBeVisible();
  });

  test('panel surfaces a load error and stays open for retry', async ({ page }) => {
    await setupAuthedPage(page);
    await page.route('**/api/v1/tenants', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({ status: 500, body: 'boom' });
        return;
      }
      await route.fallback();
    });
    await page.goto('/platform/dashboard');

    await page.getByTestId('platform-action-tenant-list-button').click();
    await expect(page.getByTestId('platform-tenant-list-error')).toBeVisible();
    // Panel stays open so the operator can hit Refresh.
    await expect(page.getByTestId('platform-tenant-list-refresh')).toBeVisible();
  });
});

test.describe('Platform dashboard — observability inline-edit (warroom round 6)', () => {
  test('toggle card: Edit → switch flips → Save with justification → saved', async ({ page }) => {
    let putBody: Record<string, unknown> | null = null;
    await setupAuthedPage(page, {
      obsPutHandler: (body) => {
        putBody = body;
        return { status: 200, body: { tracingEnabled: true } };
      },
    });
    await page.goto('/platform/dashboard');

    // Resting state — tracing card shows Current: disabled.
    await expect(page.getByTestId('platform-action-obs-tracing-current'))
      .toContainText(/disabled/i);

    // Enter edit.
    await page.getByTestId('platform-action-obs-tracing-edit').click();

    // ARIA APG Switch Pattern (role="switch" + aria-checked).
    const sw = page.getByTestId('platform-action-obs-tracing-toggle');
    await expect(sw).toHaveAttribute('role', 'switch');
    await expect(sw).toHaveAttribute('aria-checked', 'false');
    await sw.click();
    await expect(sw).toHaveAttribute('aria-checked', 'true');

    // Save disabled until justification entered.
    const save = page.getByTestId('platform-action-obs-tracing-save');
    await expect(save).toBeDisabled();
    await page.getByTestId('platform-action-obs-tracing-justification')
      .fill('Enabling tracing for incident triage');
    await expect(save).toBeEnabled();
    await save.click();

    // PUT body shape pinned (snake_case key, boolean true).
    await expect.poll(() => putBody).toEqual({ tracing_enabled: true });

    // Saved toast shows in the resting card.
    await expect(page.getByTestId('platform-action-obs-tracing-saved'))
      .toBeVisible();
  });

  test('number card: bounds violation disables Save + surfaces hint', async ({ page }) => {
    await setupAuthedPage(page);
    await page.goto('/platform/dashboard');

    await page.getByTestId('platform-action-obs-stale-interval-edit').click();
    const input = page.getByTestId('platform-action-obs-stale-interval-input');
    const save = page.getByTestId('platform-action-obs-stale-interval-save');

    // Justification present so only the bounds rule fails.
    await page.getByTestId('platform-action-obs-stale-interval-justification')
      .fill('tightening stale-shelter cadence');

    // Out of range high.
    await input.fill('2000');
    await expect(save).toBeDisabled();
    await expect(page.getByTestId('platform-action-obs-stale-interval-validation'))
      .toContainText(/Must be between 1 and 1440/);

    // In range.
    await input.fill('10');
    await expect(save).toBeEnabled();
  });

  test('destructive card: Save shows 2-step confirm panel before submit', async ({ page }) => {
    let putCalled = false;
    await setupAuthedPage(page, {
      obsPutHandler: (body) => {
        putCalled = true;
        return { status: 200, body };
      },
    });
    await page.goto('/platform/dashboard');

    // DV Canary cadence is destructive.
    await page.getByTestId('platform-action-obs-canary-interval-edit').click();
    await page.getByTestId('platform-action-obs-canary-interval-input').fill('30');
    await page.getByTestId('platform-action-obs-canary-interval-justification')
      .fill('relaxing canary frequency for staging burn-in');

    // For destructive, button label is "Review change", not "Save".
    const reviewBtn = page.getByTestId('platform-action-obs-canary-interval-save');
    await expect(reviewBtn).toContainText(/Review change/i);
    await reviewBtn.click();

    // Confirm panel renders with old → new comparison; PUT not yet fired.
    const confirmPanel = page.getByTestId('platform-action-obs-canary-interval-confirm-panel');
    await expect(confirmPanel).toBeVisible();
    await expect(confirmPanel).toContainText(/From:/);
    await expect(confirmPanel).toContainText(/To:/);
    expect(putCalled).toBe(false);

    // Cancel returns to the form without firing.
    await page.getByTestId('platform-action-obs-canary-interval-confirm-cancel').click();
    await expect(confirmPanel).toHaveCount(0);
    expect(putCalled).toBe(false);

    // Re-open and confirm — PUT fires.
    await reviewBtn.click();
    await page.getByTestId('platform-action-obs-canary-interval-confirm-submit').click();
    await expect.poll(() => putCalled).toBe(true);
  });

  test('Cancel from edit form returns to resting without writing', async ({ page }) => {
    let putCalled = false;
    await setupAuthedPage(page, {
      obsPutHandler: () => {
        putCalled = true;
        return { status: 200 };
      },
    });
    await page.goto('/platform/dashboard');

    await page.getByTestId('platform-action-obs-prometheus-edit').click();
    // Edit visible → toggle changes draft state but not persisted.
    await page.getByTestId('platform-action-obs-prometheus-toggle').click();
    await page.getByTestId('platform-action-obs-prometheus-cancel').click();

    // Back to resting.
    await expect(page.getByTestId('platform-action-obs-prometheus-edit')).toBeVisible();
    expect(putCalled).toBe(false);
  });
});
