import { test, expect } from '@playwright/test';

/**
 * F11 §6.11 — Narrated platform-operator training walkthrough.
 *
 * **NOT part of CI gates.** This is a manually-invoked spec that
 * operators can run locally against `./dev-start.sh` to rehearse the
 * full happy-path flow before doing it for real in production. The
 * console output narrates each step so the operator can follow
 * along; failure messages reference the relevant section of the
 * user guide so the operator knows where to look.
 *
 * Run via:
 *
 *     cd e2e/playwright
 *     BASE_URL=http://localhost:8081 npx playwright test \
 *         platform-training-walkthrough.spec.ts \
 *         --reporter=list --headed
 *
 * The `--headed` flag is recommended so the operator can WATCH the
 * spec drive the browser — that's the training value. Without it,
 * the spec passes silently and the operator learns nothing.
 *
 * Linked from: `docs/operations/platform-operator-user-guide.md`
 * section 1 ("First-time setup") — operators read the guide section,
 * then run this spec to see the flow.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify(payload)),
    'sig',
  ].join('.');
}

const TEST_BACKUP_CODES = Array.from(
  { length: 10 },
  (_, i) => `code-${String(i).padStart(4, '0')}`,
);

/**
 * Narrate a step to the operator running this spec. Output is
 * intentionally chatty (in contrast to terse CI specs) — the operator
 * is meant to read along.
 */
function narrate(step: number, what: string): void {
  // eslint-disable-next-line no-console
  console.log(`\n  [training §${step}] ${what}`);
}

test.describe('F11 platform-operator training walkthrough (manual rehearsal)', () => {
  // Prevent CI from running this by accident. The spec has no
  // technical reason to skip — but skipping in CI keeps it as a
  // training tool, not a coverage gate.
  test.skip(!!process.env.CI, 'training walkthrough is manual-only — skipped in CI');

  test('full happy-path: login → MFA enroll → save codes → dashboard → click disabled-tooltip → logout', async ({ page }) => {
    // Mock the backend so this runs without coupling to seed-data state.
    // An operator can swap these mocks for live-backend hits when they
    // want to rehearse against real data — for the narrated rehearsal,
    // mocks are deterministic and fast.
    const setupJwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      scope: 'mfa-setup',
      exp: Math.floor(Date.now() / 1000) + 600,
    });
    const accessJwt = fakeJwt({
      iss: 'fabt-platform',
      sub: 'op-1',
      mfaVerified: true,
      exp: Math.floor(Date.now() / 1000) + 900,
    });

    await page.route('**/api/v1/auth/platform/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          scope: 'mfa-setup',
          token: setupJwt,
          expiresInSeconds: 600,
        }),
      });
    });
    await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          secret: 'JBSWY3DPEHPK3PXP',
          qrUri: 'otpauth://totp/Fabt:trainee@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fabt',
          backupCodes: TEST_BACKUP_CODES,
        }),
      });
    });
    await page.route('**/api/v1/auth/platform/mfa-confirm', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ token: accessJwt, expiresInSeconds: 900 }),
      });
    });
    await page.route('**/api/v1/auth/platform/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: '00000000-0000-0000-0000-000000000fab',
          email: 'trainee@example.com',
          mfaEnabled: true,
          lastLoginAt: '2026-04-28T12:00:00Z',
          mfaEnrolledAt: '2026-04-26T12:00:00Z',
          backupCodesRemaining: 10,
        }),
      });
    });
    // Warroom round 6: PlatformDashboard mounts a useEffect that GETs
    // /api/v1/platform/observability so each obs card can render its
    // "Current: …" line. Mock it so the walkthrough doesn't hit a real
    // backend (or get redirected to /platform/login on a 401).
    await page.route('**/api/v1/platform/observability', async (route, request) => {
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            prometheusEnabled: true,
            tracingEnabled: false,
            tracingEndpoint: 'http://localhost:4318/v1/traces',
            monitorStaleIntervalMinutes: 5,
            monitorDvCanaryIntervalMinutes: 15,
            monitorTemperatureIntervalMinutes: 60,
          }),
        });
        return;
      }
      await route.fallback();
    });

    narrate(1, 'Open /platform/login. You should see "Platform Operator Sign-In" + a cross-link to /login.');
    await page.goto('/platform/login');
    await expect(page.getByRole('heading', { level: 1 })).toContainText(
      /Platform Operator Sign-In/,
    );
    await expect(page.getByTestId('platform-login-tenant-crosslink')).toHaveAttribute(
      'href',
      '/login',
    );

    narrate(2, 'Enter email + password. The mock here returns the mfa-setup branch (first-time login).');
    await page.getByTestId('platform-login-email').fill('trainee@example.com');
    await page.getByTestId('platform-login-password').fill('any-password');
    await page.getByTestId('platform-login-submit').click();

    narrate(3, 'Page lands on /platform/mfa-enroll. QR + manual secret + supported-authenticator list visible.');
    await expect(page).toHaveURL('/platform/mfa-enroll');
    await expect(page.getByTestId('platform-mfa-qr-canvas')).toBeVisible();
    await expect(page.getByTestId('platform-mfa-manual-secret')).toHaveText(
      'JBSWY3DPEHPK3PXP',
    );

    narrate(
      4,
      'In real life: scan the QR with Google Authenticator / 1Password / Authy / etc., then type the 6-digit code. We submit `123456` — the mocked backend accepts it.',
    );
    await page.getByTestId('platform-mfa-confirm-input').fill('123456');
    await page.getByTestId('platform-mfa-confirm-submit').click();

    narrate(5, 'Backup-codes phase mounts. All 10 codes visible. Save them somewhere safe NOW — they will not be shown again.');
    await expect(page.getByTestId('platform-backup-codes-list')).toBeVisible();
    for (let i = 0; i < TEST_BACKUP_CODES.length; i++) {
      await expect(page.getByTestId(`platform-backup-code-${i}`)).toHaveText(
        TEST_BACKUP_CODES[i],
      );
    }

    narrate(
      6,
      'Continue is disabled until the acknowledgement checkbox is checked. The intent: force the operator to consciously confirm they saved the codes.',
    );
    await expect(page.getByTestId('platform-backup-codes-continue')).toBeDisabled();
    await page.getByTestId('platform-backup-codes-confirm-checkbox').check();
    await expect(page.getByTestId('platform-backup-codes-continue')).toBeEnabled();
    await page.getByTestId('platform-backup-codes-continue').click();

    narrate(7, 'Dashboard renders. Banner shows masked email + 14:5x countdown. Backup-codes badge is "10 remaining · Healthy".');
    await expect(page).toHaveURL('/platform/dashboard');
    await expect(page.getByTestId('platform-operator-banner')).toBeVisible();
    await expect(page.getByTestId('platform-banner-email')).toHaveText(/^t\*\*\*@/);
    await expect(page.getByTestId('platform-banner-countdown')).toContainText(
      /Session expires in 1[45]:/,
    );
    await expect(page.getByTestId('platform-dashboard-backup-codes-badge')).toContainText(
      /10 remaining/,
    );
    await expect(page.getByTestId('platform-dashboard-backup-codes-urgency')).toContainText(
      /Healthy/,
    );

    narrate(
      8,
      'Hover any lifecycle card (e.g. Suspend tenant). The button is disabled in this deployment (fabt.tenant.lifecycle.enabled=false) — the tooltip explains.',
    );
    const suspendBtn = page.getByTestId('platform-action-tenant-suspend-button');
    // Warroom round 7 (2026-05-03): native `disabled` → `aria-disabled` so
    // keyboard-only operators can focus the button + read the tooltip.
    await expect(suspendBtn).toHaveAttribute('aria-disabled', 'true');
    await expect(suspendBtn).toHaveAttribute(
      'title',
      /disabled in this deployment/i,
    );

    narrate(
      9,
      'Click the Open button on the System Health card — it opens /actuator/health in a new tab (no JWT required, permitAll endpoint).',
    );
    // We DON'T actually open it in this rehearsal — just assert the
    // button is present + enabled. Opening would dirty the test state.
    await expect(
      page.getByTestId('platform-action-system-health-button'),
    ).toBeEnabled();

    narrate(10, 'Click Logout. Page returns to /platform/login, sessionStorage is cleared.');
    await page.route('**/api/v1/auth/platform/logout', async (route) => {
      await route.fulfill({ status: 204 });
    });
    await page.getByTestId('platform-banner-logout').click();
    await expect(page).toHaveURL('/platform/login');
    const stored = await page.evaluate(
      (key) => window.sessionStorage.getItem(key),
      PLATFORM_JWT_KEY,
    );
    expect(stored).toBeNull();

    narrate(
      11,
      'Walkthrough complete. You\'ve exercised the full operator journey end-to-end. See `docs/operations/platform-operator-user-guide.md` for production-specific notes.',
    );
  });
});
