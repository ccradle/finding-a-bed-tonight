import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * F11 §6.9 + §6.12 — axe-core a11y sweep + CSP assertion for the
 * /platform/* surface. CI-blocking gate per ADA Title II WCAG 2.1 AA
 * deadline (2026-04-24). Mirrors the existing `accessibility.spec.ts`
 * pattern (same tags, same formatViolations helper).
 *
 * Coverage:
 *   - /platform/login                          (unauthenticated)
 *   - /platform/mfa-verify                     (mfa-verify-scoped JWT)
 *   - /platform/dashboard                      (post-MFA access JWT)
 *   - All three above × {light, dark}          (6 a11y test instances)
 *   - CSP header present + restrictive on each (3 CSP instances)
 *
 * /platform/mfa-enroll IS scanned (round 11 #3 follow-up). The QR
 * canvas itself is opaque to axe — but the page also renders a manual
 * secret, supported-authenticators copy, and a backup-codes display
 * (10 codes + acknowledge checkbox + Continue button). All of those
 * deserve a scan. We mock /mfa-setup so the page reaches the `scan`
 * phase; the canvas-rendered QR is a no-op for axe semantics. F11
 * §6.10 manual cross-authenticator QA still owns the QR-rendering
 * compatibility check.
 *
 * The /platform/* surface is mocked at the network layer so this spec
 * runs against the SPA in isolation (no backend / no DB / no flake on
 * shared seed data). All four /platform/* mock specs follow the same
 * pattern — see `platform-ui-banner.spec.ts` for the JWT-injection
 * convention.
 */

const PLATFORM_JWT_KEY = 'fabt.platform.jwt.v1';
const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

interface AxeViolation {
  id: string;
  description: string;
  impact: string;
  nodes: { html: string }[];
}

function formatViolations(violations: AxeViolation[]): string {
  return violations
    .map(
      (v) =>
        `[${v.id}] ${v.description} (${v.impact}) — ${v.nodes.length} instance(s)\n` +
        v.nodes
          .slice(0, 3)
          .map((n) => `  → ${n.html.substring(0, 120)}`)
          .join('\n'),
    )
    .join('\n\n');
}

function fakeAccessJwt(opts: { scope?: string; mfaVerified?: boolean } = {}): string {
  const b64 = (s: string) =>
    Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + 900;
  const payload: Record<string, unknown> = {
    iss: 'fabt-platform',
    sub: 'op-1',
    exp,
  };
  if (opts.scope) payload.scope = opts.scope;
  if (opts.mfaVerified !== undefined) payload.mfaVerified = opts.mfaVerified;
  return [
    b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    b64(JSON.stringify(payload)),
    'sig',
  ].join('.');
}

async function setupPostMfaSession(page: import('@playwright/test').Page) {
  const jwt = fakeAccessJwt({ mfaVerified: true });
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
}

async function setupMfaVerifySession(page: import('@playwright/test').Page) {
  const jwt = fakeAccessJwt({ scope: 'mfa-verify' });
  await page.addInitScript(([key, value]) => {
    window.sessionStorage.setItem(key, value);
  }, [PLATFORM_JWT_KEY, jwt]);
}

async function setupMfaEnrollSession(page: import('@playwright/test').Page) {
  const jwt = fakeAccessJwt({ scope: 'mfa-setup' });
  await page.addInitScript(([key, value]) => {
    window.sessionStorage.setItem(key, value);
  }, [PLATFORM_JWT_KEY, jwt]);
  await page.route('**/api/v1/auth/platform/mfa-setup', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        secret: 'JBSWY3DPEHPK3PXP',
        qrUri: 'otpauth://totp/Fabt:test?secret=JBSWY3DPEHPK3PXP&issuer=Fabt',
        // 10 deterministic backup codes (matches the production count).
        backupCodes: Array.from({ length: 10 }, (_, i) => `code-${String(i).padStart(4, '0')}`),
      }),
    });
  });
}

// ============================================================
// Axe-core a11y scan — light + dark theme × 3 platform routes
// ============================================================

test.describe('Platform-operator UI — a11y (axe-core)', () => {
  for (const colorScheme of ['light', 'dark'] as const) {
    test.describe(`theme=${colorScheme}`, () => {
      test.use({ colorScheme });

      test('/platform/login has no WCAG 2.1 AA violations', async ({ page }) => {
        await page.goto('/platform/login');
        // Wait for hydration + any async content. Mirrors the 1s
        // wait used by the canonical accessibility.spec.ts:78.
        await page.waitForTimeout(1000);

        const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
        expect(
          results.violations,
          formatViolations(results.violations as AxeViolation[]),
        ).toEqual([]);
      });

      test('/platform/mfa-verify has no WCAG 2.1 AA violations', async ({ page }) => {
        await setupMfaVerifySession(page);
        await page.goto('/platform/mfa-verify');
        await page.waitForTimeout(1000);

        const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
        expect(
          results.violations,
          formatViolations(results.violations as AxeViolation[]),
        ).toEqual([]);
      });

      test('/platform/dashboard has no WCAG 2.1 AA violations', async ({ page }) => {
        await setupPostMfaSession(page);
        await page.goto('/platform/dashboard');
        // Wait for /me fetch + dashboard render. The metadata grid
        // mounts after the resolve; scan too early gives a false-clean.
        await page.waitForSelector('[data-testid="platform-dashboard-email"]', {
          timeout: 5000,
        });

        const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
        expect(
          results.violations,
          formatViolations(results.violations as AxeViolation[]),
        ).toEqual([]);
      });

      test('/platform/mfa-enroll (scan phase) has no WCAG 2.1 AA violations', async ({ page }) => {
        // Round-11 #3: scan the enrollment page in its `scan` phase
        // (post-/mfa-setup-resolve). Covers the manual-secret display,
        // supported-authenticators copy, and the form. The QR canvas
        // is opaque to axe — F11 §6.10 manual cross-authenticator QA
        // is the canonical compatibility check for QR rendering.
        await setupMfaEnrollSession(page);
        await page.goto('/platform/mfa-enroll');
        // Wait for the manual-secret to render — that's the marker
        // that the page transitioned out of `loading` into `scan`.
        await page.waitForSelector('[data-testid="platform-mfa-manual-secret"]', {
          timeout: 5000,
        });

        const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
        expect(
          results.violations,
          formatViolations(results.violations as AxeViolation[]),
        ).toEqual([]);
      });
    });
  }
});

// ============================================================
// CSP header assertion — F11 §6.12
// ============================================================

const EXPECTED_CSP_TOKENS = [
  "default-src 'self'",
  "script-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
];

const FORBIDDEN_CSP_TOKENS = [
  // Marcus condition: no script eval, no script unsafe-inline. The
  // tenant-side CSP allows 'unsafe-inline' for STYLE only (Vite
  // hashed-style limitation); script-src must remain strict.
  "'unsafe-eval'",
  // Note: this checks for unsafe-inline appearing ANYWHERE in script-src
  // specifically. style-src 'unsafe-inline' is allowed by the tenant CSP
  // and inherited by /platform/*; the assertion below targets script-src.
];

test.describe('Platform-operator UI — CSP header', () => {
  for (const route of ['/platform/login', '/platform/dashboard', '/platform/mfa-verify']) {
    test(`CSP header is present + restrictive on ${route}`, async ({ page }) => {
      // page.goto returns the main-document Response; the CSP header
      // is set by nginx at this layer. SPA-internal navigation
      // doesn't re-evaluate CSP, so the document response is the
      // load-bearing assertion.
      const response = await page.goto(route);
      expect(response, `no response for ${route}`).not.toBeNull();
      const csp = response!.headers()['content-security-policy'];
      expect(csp, `Content-Security-Policy header missing on ${route}`).toBeTruthy();

      for (const token of EXPECTED_CSP_TOKENS) {
        expect(csp, `CSP missing required directive: ${token}`).toContain(token);
      }
      for (const token of FORBIDDEN_CSP_TOKENS) {
        expect(csp, `CSP must not contain: ${token}`).not.toContain(token);
      }
      // script-src must NOT carry unsafe-inline — defense against an
      // accidental relaxation that would let an injected <script>
      // execute. The directive is parsed positionally; we test the
      // exact substring "script-src 'self'" without inline tokens.
      const scriptSrcMatch = csp.match(/script-src ([^;]+)/);
      expect(scriptSrcMatch, 'script-src directive missing from CSP').toBeTruthy();
      expect(
        scriptSrcMatch![1],
        `script-src must not include 'unsafe-inline' (got: "${scriptSrcMatch![1]}")`,
      ).not.toContain("'unsafe-inline'");
    });
  }

  test('zero CSP violations during /platform/dashboard load', async ({ page }) => {
    // Browser console emits a violation report whenever the CSP
    // blocks an inline script / eval / cross-origin request. We
    // collect any such message during a full dashboard load and
    // assert none fired.
    const cspViolations: string[] = [];
    page.on('console', (msg) => {
      const text = msg.text();
      // Round-11 #2: dropped the bare `'CSP'` substring — modern
      // Chromium violation messages always include either
      // `'Content Security Policy'` (the directive name) or
      // `'Refused to'` (the action verb). Bare `'CSP'` is a false-
      // positive magnet for any unrelated log line containing the
      // three letters.
      if (
        msg.type() === 'error' &&
        (text.includes('Content Security Policy') ||
          text.includes('Refused to'))
      ) {
        cspViolations.push(text);
      }
    });

    await setupPostMfaSession(page);
    await page.goto('/platform/dashboard');
    await page.waitForSelector('[data-testid="platform-dashboard-email"]', {
      timeout: 5000,
    });
    // Give the QR library + lazy chunks time to attempt anything that
    // would trip CSP (qrcode renders to canvas — no eval, but if a
    // future dep change adds eval/inline, the violation fires here).
    await page.waitForTimeout(500);

    expect(
      cspViolations,
      `Unexpected CSP violations during dashboard load:\n${cspViolations.join('\n')}`,
    ).toEqual([]);
  });
});
