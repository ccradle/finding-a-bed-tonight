import { test, expect } from '../fixtures/auth.fixture';
import { test as baseTest } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import * as fs from 'fs';
import * as path from 'path';

/**
 * issue-reporting-feedback §5 — Playwright coverage for the in-app
 * "Report a Problem" footer link, mobile kebab "Help" item, landing-page
 * Feedback & Support section, DV-policy mailto gate, JS-disabled fallback,
 * and small-viewport kebab overflow.
 *
 * Backed by the contract documented in:
 *   - frontend/src/components/ReportProblemLink.tsx (URL allowlist + DV gate)
 *   - frontend/src/components/Layout.tsx (footer + kebab Help wiring)
 *   - frontend/index.html (noscript fallback)
 *   - openspec/changes/issue-reporting-feedback/{proposal,design,tasks}.md
 *
 * Run: BASE_URL=http://localhost:8081 NGINX=1 npx playwright test \
 *      tests/feedback-link-discipline.spec.ts --trace on
 */

const MOBILE_VP = { width: 412, height: 915 };
const WCAG_MIN_VP = { width: 320, height: 568 };
const LANDSCAPE_TINY_VP = { width: 568, height: 320 };

const GH_ISSUES_BASE = 'https://github.com/ccradle/finding-a-bed-tonight/issues';

// Mock contact-info responses. Shape mirrors ContactInfoResponse in
// frontend/src/contact/ContactInfoContext.tsx so the deriveContactInfoState
// reducer constructs the same { resolvedEmail, tenant } the production code
// expects.
function contactInfoMock(opts: {
  platformEmail?: string | null;
  tenantSlug?: string | null;
  tenantEmail?: string | null;
  dvPolicyEnabled?: boolean;
}) {
  const body: Record<string, unknown> = {};
  if (opts.platformEmail !== undefined) {
    body.platform = { email: opts.platformEmail };
  }
  if (opts.tenantSlug !== undefined) {
    body.tenant = {
      slug: opts.tenantSlug,
      email: opts.tenantEmail ?? null,
      dvPolicyEnabled: opts.dvPolicyEnabled === true,
    };
  }
  return JSON.stringify(body);
}

async function mockContactInfo(
  page: import('@playwright/test').Page,
  body: string,
): Promise<void> {
  await page.route('**/api/v1/public/contact-info', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body,
    });
  });
}

// =========================================================================
// §5.1-§5.3 — Footer "Report a Problem" link presence + href + version
// =========================================================================

test.describe('§5.1 footer link present across roles', () => {
  test('outreach sees footer Report a Problem link', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.getByTestId('footer-report-problem')).toBeVisible();
  });

  test('coordinator (cocadmin) sees footer Report a Problem link', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await expect(coordinatorPage.getByTestId('footer-report-problem')).toBeVisible();
  });

  test('admin sees footer Report a Problem link', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await expect(adminPage.getByTestId('footer-report-problem')).toBeVisible();
  });
});

test.describe('§5.2 footer link href + target', () => {
  test('href matches buildReportProblemUrl + opens in new tab', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    const link = outreachPage.getByTestId('footer-report-problem');
    await expect(link).toBeVisible();
    const href = await link.getAttribute('href');
    expect(href, 'href must point at the GH issue new form').toContain(`${GH_ISSUES_BASE}/new`);
    expect(href, 'href must pre-fill the report-a-problem template').toContain('template=report-a-problem.yml');
    expect(href, 'href must apply the triage label').toContain('labels=triage');
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', /noopener/);
  });
});

test.describe('§5.3 footer link includes fabt_version', () => {
  test('href carries fabt_version param when /api/v1/version resolves', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    // Footer rendered immediately (un-gated). The version param appears
    // once /api/v1/version resolves. Wait for the link to acquire it.
    const link = outreachPage.getByTestId('footer-report-problem');
    await expect(link).toBeVisible();
    await expect.poll(async () => link.getAttribute('href'), {
      timeout: 8000,
      message: 'href must eventually contain fabt_version once /api/v1/version resolves',
    }).toMatch(/fabt_version=/);
  });
});

// =========================================================================
// §5.4-§5.5 — Mobile kebab "Help" item
// =========================================================================

test.describe('§5.4 kebab Help item presence', () => {
  test('mobile kebab on /outreach renders Help with data-testid', async ({ outreachPage }) => {
    await outreachPage.setViewportSize(MOBILE_VP);
    await outreachPage.goto('/outreach');
    await outreachPage.getByTestId('header-kebab-menu').click();
    await expect(outreachPage.getByTestId('header-overflow-dropdown')).toBeVisible();
    await expect(outreachPage.getByTestId('header-overflow-help')).toBeVisible();
  });
});

test.describe('§5.5 kebab Help opens correct URL in new tab', () => {
  test('admin kebab Help href is the GH issue chooser, opens in new tab', async ({ adminPage }) => {
    await adminPage.setViewportSize(MOBILE_VP);
    await adminPage.goto('/admin');
    await adminPage.getByTestId('header-kebab-menu').click();
    const help = adminPage.getByTestId('header-overflow-help');
    await expect(help).toBeVisible();
    const href = await help.getAttribute('href');
    expect(href, 'kebab Help must open the GH issue chooser on non-DV tenants').toBe(`${GH_ISSUES_BASE}/new/choose`);
    await expect(help).toHaveAttribute('target', '_blank');
    await expect(help).toHaveAttribute('rel', /noopener/);
  });
});

// =========================================================================
// §5.6 — Landing page Feedback & Support section
// =========================================================================

const DOCS_ROOT = process.env.FABT_DOCS_ROOT
  ?? path.resolve(__dirname, '..', '..', '..', '..');
const INDEX_HTML = path.join(DOCS_ROOT, 'index.html');

test.describe('§5.6 landing-page Feedback & Support section', () => {
  test('three feedback links render with correct hrefs', async ({ page }) => {
    test.skip(
      !fs.existsSync(INDEX_HTML),
      `Static landing index.html not found at ${INDEX_HTML}; set FABT_DOCS_ROOT to the docs repo path.`,
    );
    await page.goto(`file://${INDEX_HTML.replace(/\\/g, '/')}`);

    const section = page.getByTestId('landing-feedback-support');
    await expect(section).toBeVisible();

    const reportLink = page.getByTestId('landing-feedback-report-problem');
    await expect(reportLink).toBeVisible();
    expect(await reportLink.getAttribute('href')).toContain('template=report-a-problem.yml');

    const featureLink = page.getByTestId('landing-feedback-request-feature');
    await expect(featureLink).toBeVisible();
    expect(await featureLink.getAttribute('href')).toContain('template=feature-request.yml');

    const askLink = page.getByTestId('landing-feedback-ask-question');
    await expect(askLink).toBeVisible();
    expect(await askLink.getAttribute('href')).toContain('/discussions/categories/q-a');

    for (const link of [reportLink, featureLink, askLink]) {
      await expect(link).toHaveAttribute('target', '_blank');
      await expect(link).toHaveAttribute('rel', /noopener/);
    }
  });
});

// =========================================================================
// §5.7 — axe-core regression on the surfaces touched by this change
// =========================================================================

test.describe('§5.7 axe-core scan on /outreach after feedback wiring', () => {
  test('zero new Critical/Serious violations from the always-rendered footer link', async ({ outreachPage }) => {
    await outreachPage.setViewportSize(MOBILE_VP);
    await outreachPage.goto('/outreach');
    await expect(outreachPage.getByTestId('footer-report-problem')).toBeVisible();
    // Do NOT open the kebab here. axe-core scans the full DOM; the footer
    // link is in the tree from page-load (always-rendered per §2.1). Opening
    // the kebab surfaces a pre-existing `aria-required-children` violation
    // on `<div role="menu">` containing `<select data-testid="locale-selector">`
    // — that pattern predates this change and is mirrored in
    // `mobile-header.spec.ts §3.9`. The kebab Help item itself is the
    // same `<a role="menuitem">` shape as the other menu items already
    // scanned in that companion spec.

    const results = await new AxeBuilder({ page: outreachPage })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    const serious = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(
      serious,
      serious.map((v) => `[${v.id}] ${v.description}`).join('\n'),
    ).toEqual([]);
  });
});

// =========================================================================
// §5.9 / §5.10 — Mailto secondary link on non-DV tenants
// =========================================================================

test.describe('§5.9 footer mailto secondary renders when API returns email', () => {
  test('non-DV tenant with platform email → secondary mailto link visible', async ({ outreachPage }) => {
    await mockContactInfo(
      outreachPage,
      contactInfoMock({
        platformEmail: 'team@findabed.test',
        tenantSlug: 'dev-coc',
        tenantEmail: null,
        dvPolicyEnabled: false,
      }),
    );
    await outreachPage.goto('/outreach');
    await expect(outreachPage.getByTestId('footer-report-problem')).toBeVisible();
    const mailto = outreachPage.getByTestId('footer-report-problem-email');
    await expect(mailto).toBeVisible();
    expect(await mailto.getAttribute('href')).toBe('mailto:team@findabed.test');
  });
});

test.describe('§5.10 footer mailto secondary absent when API returns no email', () => {
  test('non-DV tenant with no resolved email → secondary mailto NOT present', async ({ outreachPage }) => {
    await mockContactInfo(
      outreachPage,
      contactInfoMock({
        platformEmail: null,
        tenantSlug: 'dev-coc',
        tenantEmail: null,
        dvPolicyEnabled: false,
      }),
    );
    await outreachPage.goto('/outreach');
    await expect(outreachPage.getByTestId('footer-report-problem')).toBeVisible();
    await expect(outreachPage.getByTestId('footer-report-problem-email')).toHaveCount(0);
  });
});

// =========================================================================
// §5.11 — noscript fallback when JavaScript is disabled
// =========================================================================

test.describe('§5.11 noscript fallback', () => {
  // Uses baseTest (not the auth.fixture) — auth fixtures require JS to log in.
  baseTest('frontend/index.html noscript GH-Issues link renders when JS is off', async ({ browser }) => {
    const baseURL = process.env.BASE_URL || (process.env.NGINX === '1' ? 'http://localhost:8081' : 'http://localhost:5173');
    const context = await browser.newContext({ javaScriptEnabled: false });
    const page = await context.newPage();
    try {
      await page.goto(`${baseURL}/`, { waitUntil: 'domcontentloaded' });
      // The noscript block sits outside #root and contains a static GH
      // issues index link. Browser renders noscript children as live DOM
      // when JS is disabled.
      const noscriptLink = page.locator(`a[href="${GH_ISSUES_BASE}"]`);
      await baseTest.expect(noscriptLink).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

// =========================================================================
// §5.12 — Kebab overflow at 320×568 and 568×320
// =========================================================================

test.describe('§5.12 kebab viewport overflow', () => {
  for (const vp of [WCAG_MIN_VP, LANDSCAPE_TINY_VP]) {
    test(`kebab at ${vp.width}×${vp.height}: opens, all items reachable, no horizontal page scroll`, async ({ outreachPage }) => {
      await outreachPage.setViewportSize(vp);
      await outreachPage.goto('/outreach');

      await outreachPage.getByTestId('header-kebab-menu').click();
      const dropdown = outreachPage.getByTestId('header-overflow-dropdown');
      await expect(dropdown).toBeVisible();

      // All six menu items must be reachable inside the dropdown.
      await expect(outreachPage.getByTestId('header-overflow-username')).toBeVisible();
      await expect(outreachPage.getByTestId('header-overflow-password')).toBeVisible();
      await expect(outreachPage.getByTestId('header-overflow-security')).toBeVisible();
      await expect(outreachPage.getByTestId('header-overflow-help')).toBeVisible();
      await expect(outreachPage.getByTestId('header-overflow-signout')).toBeVisible();
      await expect(dropdown.locator('[data-testid="locale-selector"]')).toBeVisible();

      // The page itself must not introduce a horizontal scroll because of
      // the kebab + dropdown layout. The dropdown is allowed to scroll
      // internally; the documentElement must not.
      const hasHScroll = await outreachPage.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
      );
      expect(hasHScroll, 'documentElement must not introduce horizontal page scroll').toBe(false);
    });
  }
});

// =========================================================================
// §2.11 / §3.8 — DV-policy tenant routes BOTH surfaces to mailto:
// =========================================================================

test.describe('§2.11 DV-policy tenant footer link is mailto', () => {
  test('dvPolicyEnabled=true + resolvedEmail → footer primary is mailto, no secondary', async ({ outreachPage }) => {
    await mockContactInfo(
      outreachPage,
      contactInfoMock({
        platformEmail: 'platform@findabed.test',
        tenantSlug: 'dev-coc',
        tenantEmail: 'dv-team@findabed.test',
        dvPolicyEnabled: true,
      }),
    );
    await outreachPage.goto('/outreach');
    const primary = outreachPage.getByTestId('footer-report-problem');
    await expect(primary).toBeVisible();
    // tenant-level email wins over platform email when present, so the
    // mailto target is the DV tenant's own email — survivors' messages
    // route to the coordinator's mailbox, not the cross-tenant platform
    // contact.
    expect(await primary.getAttribute('href')).toBe('mailto:dv-team@findabed.test');
    // DV-routed primary intentionally omits target/rel — mailto: handlers
    // don't open browser tabs and noopener doesn't apply.
    expect(await primary.getAttribute('target')).toBeNull();
    expect(await primary.getAttribute('rel')).toBeNull();
    // No secondary mailto on DV-routed primary.
    await expect(outreachPage.getByTestId('footer-report-problem-email')).toHaveCount(0);
  });
});

test.describe('§3.8 DV-policy mobile kebab Help is mailto', () => {
  test('dvPolicyEnabled=true + resolvedEmail → kebab Help href is mailto', async ({ outreachPage }) => {
    await mockContactInfo(
      outreachPage,
      contactInfoMock({
        platformEmail: 'platform@findabed.test',
        tenantSlug: 'dev-coc',
        tenantEmail: 'dv-team@findabed.test',
        dvPolicyEnabled: true,
      }),
    );
    await outreachPage.setViewportSize(MOBILE_VP);
    await outreachPage.goto('/outreach');
    await outreachPage.getByTestId('header-kebab-menu').click();
    const help = outreachPage.getByTestId('header-overflow-help');
    await expect(help).toBeVisible();
    expect(await help.getAttribute('href')).toBe('mailto:dv-team@findabed.test');
    expect(await help.getAttribute('target')).toBeNull();
    expect(await help.getAttribute('rel')).toBeNull();
  });
});

// =========================================================================
// §3.9 — Non-DV tenant (the platform-operator fall-through case)
// =========================================================================
//
// There is intentionally no platformOperatorPage fixture (per auth.fixture
// comment) and the Layout.tsx kebab does not render on the platform-
// operator UI. The §3.9 contract is therefore "when the calling context
// does NOT route to mailto (tenant=null, dvPolicyEnabled=false, OR
// resolvedEmail empty), the kebab Help falls through to the GH chooser."
// We exercise that fall-through by mocking the contact-info endpoint to
// return an empty body, mirroring the platform-operator context where
// no tenant is bound.

test.describe('§3.9 fall-through to GitHub chooser when no DV+mailto', () => {
  test('empty contact-info → kebab Help href is GH chooser', async ({ outreachPage }) => {
    await mockContactInfo(outreachPage, contactInfoMock({}));
    await outreachPage.setViewportSize(MOBILE_VP);
    await outreachPage.goto('/outreach');
    await outreachPage.getByTestId('header-kebab-menu').click();
    const help = outreachPage.getByTestId('header-overflow-help');
    await expect(help).toBeVisible();
    expect(await help.getAttribute('href')).toBe(`${GH_ISSUES_BASE}/new/choose`);
    await expect(help).toHaveAttribute('target', '_blank');
    await expect(help).toHaveAttribute('rel', /noopener/);
  });
});
