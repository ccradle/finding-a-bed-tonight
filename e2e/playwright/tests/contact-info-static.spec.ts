import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';

/**
 * info-email-contact §10.5 + §10.8 — static-site contact-placeholder
 * coverage.
 *
 * <p><b>When/who runs this:</b> developer-workstation only today —
 * the tests resolve the docs-repo path via {@code FABT_DOCS_ROOT} env
 * var (or a default sibling-layout fallback). CI checks out only the
 * code repo and gets clean skips with descriptive reasons. To enable
 * in CI, add a docs-repo checkout step + set {@code FABT_DOCS_ROOT}
 * (planned alongside the future weekly-nginx-mode job that
 * {@code audience-pages-a11y.spec.ts} also waits on).
 *
 * <p>Two scenarios:
 *
 * §10.5 — JS-disabled fallback (M2): load each in-scope HTML page with
 *          JavaScript disabled in the browser context. The
 *          {@code <a class="contact-email" hidden>} placeholder stays
 *          hidden (the script that removes the {@code hidden} attribute
 *          never runs), and the {@code <noscript>} GitHub-Issues fallback
 *          link renders. The DOM contains NO plain-text {@code @findabed.org}
 *          email — bot scrapers and JS-disabled visitors get the GitHub
 *          fallback, never the platform email.
 *
 * §10.8 — Lang-aware /contact.js dict (Q1): the lang-detection branch in
 *          {@code /contact.js}'s embedded i18n dict picks ES copy when
 *          {@code <html lang="es">} is set. The static audience pages stay
 *          {@code lang="en"} today (per §7.15 ground-truth) — this test
 *          mutates the document at load time to verify the future
 *          Spanish-localization branch works.
 *
 * Static pages live in the SEPARATE {@code findABed} docs repo. CI checks
 * out only the code repo, so the default {@code DOCS_ROOT} resolution may
 * find no files → tests skip cleanly. Mirrors {@code audience-pages-a11y.spec.ts}.
 */

const DOCS_ROOT = process.env.FABT_DOCS_ROOT
    ?? path.resolve(__dirname, '..', '..', '..', '..');

// Subset of the 14 in-scope pages. The §9 CI guard verifies all 14 have
// the placeholder + noscript markup; this spec exercises a representative
// subset to keep test wall-time reasonable. The chosen 3 cover (a) the
// root index, (b) a demo audience page, (c) the 404 page — three different
// HTML layouts to confirm the script + noscript pattern works across them.
const PAGES = [
    { name: 'root index', file: 'index.html' },
    { name: 'demo for-cities', file: 'demo/for-cities.html' },
    { name: '404', file: '404.html' },
];

const SKIP_REASON = (filePath: string) =>
    `Static page not found at ${filePath}. This spec requires the separate `
    + `'findABed' docs repo checked out alongside. Set FABT_DOCS_ROOT to its `
    + `path, or run this spec only on developer workstations with both repos cloned.`;

const FORBIDDEN_EMAIL_REGEX = /[A-Za-z0-9._%+-]+@findabed\.org/;

// ----- §10.5: JS-disabled fallback ----------------------------------------

test.describe('§10.5 — JS-disabled noscript fallback', () => {
    for (const pg of PAGES) {
        const filePath = path.join(DOCS_ROOT, pg.file);
        const fileExists = fs.existsSync(filePath);

        test(`${pg.name}: noscript GH-Issues link visible, no @findabed.org email in DOM`, async ({ browser }) => {
            test.skip(!fileExists, SKIP_REASON(filePath));

            // setJavaScriptEnabled is per-context, not per-page — must
            // create a context with JS off and use a page from it.
            const context = await browser.newContext({ javaScriptEnabled: false });
            const page = await context.newPage();
            try {
                await page.goto(`file://${filePath.replace(/\\/g, '/')}`);

                // Visible <noscript> render: the browser converts
                // <noscript>...</noscript> contents to live DOM when JS
                // is off. The link inside that block IS clickable and
                // visible to the user.
                //
                // for-cities has TWO noscript blocks (the §8.1 Pilot
                // Inquiries CTA AND the §7.7 footer). The user-facing
                // contract is "at least one fallback is visible";
                // .first() suffices for the visibility assertion.
                // Separately verify every matching link carries the
                // expected GH-Issues text — catches a regression where
                // a future page edit retains the URL but mangles the
                // surrounding "Contact via GitHub Issues" copy.
                const noscriptLinks = page.locator(
                    'a[href="https://github.com/ccradle/finding-a-bed-tonight/issues"]',
                );
                const linkCount = await noscriptLinks.count();
                expect(
                    linkCount,
                    'at least one noscript GH-Issues fallback link must render when JS is disabled',
                ).toBeGreaterThanOrEqual(1);
                // Warroom round 1 N1-Marcus: assert EVERY matching link is
                // visible, not just .first(). A regression that hid one of
                // the two for-cities placeholders (§8.1 CTA OR §7.7 footer)
                // via CSS would otherwise pass — the spec's intent is "every
                // page surface that PROMISES a fallback delivers it".
                for (let i = 0; i < linkCount; i++) {
                    await expect(
                        noscriptLinks.nth(i),
                        `noscript fallback link #${i} must be visible to JS-disabled visitors`,
                    ).toBeVisible();
                    const text = await noscriptLinks.nth(i).textContent();
                    expect(
                        text,
                        `noscript fallback link #${i} must carry the localized "Contact via GitHub Issues" text`,
                    ).toContain('Contact via GitHub Issues');
                }

                // The hidden placeholder retains its `hidden` attribute
                // because the script that removes it never ran. Assert
                // attribute presence rather than computed CSS visibility
                // because some pages have CSS rules that override the
                // UA-stylesheet `display: none` for `<a>` elements
                // (verified empirically: index.html does not, but
                // demo/for-cities.html does). The script's contract is
                // about the attribute, not the computed style; tying the
                // assertion to the attribute matches the contract.
                // for-cities has TWO placeholders (§8.1 CTA + §7.7 footer);
                // every other in-scope page has exactly 1. Iterate all
                // matches and verify each retains its hidden attribute.
                const placeholders = page.locator('a.contact-email');
                const placeholderCount = await placeholders.count();
                expect(
                    placeholderCount,
                    'at least one contact-email placeholder must be present',
                ).toBeGreaterThanOrEqual(1);
                for (let i = 0; i < placeholderCount; i++) {
                    await expect(
                        placeholders.nth(i),
                        `placeholder #${i} MUST keep its hidden attribute when JS is disabled`,
                    ).toHaveAttribute('hidden', /.*/);
                }

                // No @findabed.org address in the rendered DOM. This is
                // the spec's bot-scrape defense: with JS disabled, a
                // bot user-agent that follows the same path sees only
                // the GitHub-Issues fallback, never the platform email.
                const bodyText = await page.locator('body').textContent();
                expect(
                    bodyText ?? '',
                    'no plain-text @findabed.org address allowed in DOM when JS is disabled',
                ).not.toMatch(FORBIDDEN_EMAIL_REGEX);
            } finally {
                await context.close();
            }
        });
    }
});

// ----- §10.8: Lang-aware /contact.js dict ---------------------------------

test.describe('§10.8 — lang-aware /contact.js dict smoke', () => {
    const indexPath = path.join(DOCS_ROOT, 'index.html');
    const contactJsPath = path.join(DOCS_ROOT, 'contact.js');
    const filesExist = fs.existsSync(indexPath) && fs.existsSync(contactJsPath);

    /**
     * Load the page from file://, mutate {@code document.documentElement.lang}
     * to the target value BEFORE injecting {@code /contact.js}, mock
     * {@code window.fetch} to return a synthetic contact-info body, then
     * inject the script content directly (since the {@code <script defer
     * src="/contact.js">} reference does not resolve under file:// — the
     * absolute path doesn't map to anything servable). Asserts the lead-in
     * span text matches the dict entry for the chosen lang.
     */
    async function exerciseLang(
        page: import('@playwright/test').Page,
        lang: 'en' | 'es',
        expectedLeadIn: string,
    ) {
        await page.goto(`file://${indexPath.replace(/\\/g, '/')}`);
        await page.evaluate((targetLang) => {
            document.documentElement.lang = targetLang;
            // Replace fetch with a synchronous-resolving mock so the
            // script's success branch executes. Returns a 200 with a
            // platform email that the test does NOT assert on; only the
            // lead-in copy is verified here.
            (window as unknown as { fetch: typeof fetch }).fetch = (() =>
                Promise.resolve(new Response(
                    JSON.stringify({ platform: { email: 'mock@findabed.test' } }),
                    { status: 200, headers: { 'Content-Type': 'application/json' } },
                ))) as typeof fetch;
        }, lang);

        const contactJsContent = fs.readFileSync(contactJsPath, 'utf8');
        await page.addScriptTag({ content: contactJsContent });

        // The script's hydrateLeadIns runs synchronously after script
        // injection; the fetch-driven mailto/fallback rendering is
        // async. We assert only the lead-in text since that's the
        // §10.8 contract.
        const leadIn = page.locator('span.footer-contact-leadin').first();
        await expect(leadIn).toHaveText(expectedLeadIn, { timeout: 2000 });
    }

    test('lang="en" renders EN lead-in', async ({ page }) => {
        test.skip(!filesExist, SKIP_REASON(indexPath));
        await exerciseLang(page, 'en', 'Contact the FABT project team:');
    });

    test('lang="es" renders ES lead-in', async ({ page }) => {
        test.skip(!filesExist, SKIP_REASON(indexPath));
        await exerciseLang(page, 'es', 'Contacte al equipo del proyecto FABT:');
    });
});
