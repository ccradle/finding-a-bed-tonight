import { test, expect } from '@playwright/test';
import { requireReachable } from './_helpers/probe-target';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';

/**
 * Cache header tests (#45).
 *
 * Service worker and manifest MUST NOT be cached with long TTLs.
 * Hashed static assets (JS, CSS) SHOULD be cached aggressively.
 * index.html MUST NOT be cached.
 *
 * Riley's lens: positive tests (correct headers present) AND negative
 * tests (dangerous headers absent).
 *
 * These assertions verify NGINX's response headers — the dev/prod
 * reverse proxy is the system that owns these headers, not Vite. The
 * suite skips when nginx isn't reachable (e.g. CI runs only Vite +
 * backend; run dev-start.sh --nginx locally to exercise this spec).
 */
test.describe('Cache Headers (#45)', () => {

  test.beforeAll(async () => {
    await requireReachable(`${BASE_URL}/`, 'nginx (dev-start.sh --nginx)');
  });


  // --- sw.js: must NOT be cached ---

  test('sw.js has no-cache headers', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/sw.js`);
    expect(response.status()).toBe(200);

    const cacheControl = response.headers()['cache-control'];
    expect(cacheControl).toBeDefined();
    expect(cacheControl).toContain('no-cache');
    expect(cacheControl).toContain('no-store');
    expect(cacheControl).toContain('must-revalidate');
  });

  test('sw.js does NOT have immutable or max-age=31536000', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/sw.js`);
    const cacheControl = response.headers()['cache-control'] || '';
    expect(cacheControl).not.toContain('immutable');
    expect(cacheControl).not.toContain('max-age=31536000');
  });

  // --- manifest.webmanifest: must NOT be aggressively cached ---

  test('manifest.webmanifest has no-cache header', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/manifest.webmanifest`);
    expect(response.status()).toBe(200);

    const cacheControl = response.headers()['cache-control'];
    expect(cacheControl).toBeDefined();
    expect(cacheControl).toContain('no-cache');
  });

  test('manifest.webmanifest does NOT have immutable', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/manifest.webmanifest`);
    const cacheControl = response.headers()['cache-control'] || '';
    expect(cacheControl).not.toContain('immutable');
  });

  test('manifest.webmanifest has correct content type', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/manifest.webmanifest`);
    const contentType = response.headers()['content-type'] || '';
    expect(contentType).toContain('application/manifest+json');
  });

  // --- index.html: must NOT be cached ---

  test('index.html has no-cache headers', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/`);
    expect(response.status()).toBe(200);

    const cacheControl = response.headers()['cache-control'];
    expect(cacheControl).toBeDefined();
    expect(cacheControl).toContain('no-cache');
  });

  // --- Hashed JS assets: SHOULD be cached aggressively ---

  test('hashed JS assets have long cache and immutable', async ({ page }) => {
    // Load the page and find a hashed JS file in the network
    const jsRequests: string[] = [];
    page.on('response', (response) => {
      const url = response.url();
      // Match hashed JS: contains a hash-like pattern (e.g., index-BGyAIaNA.js)
      if (url.match(/\/assets\/.*-[a-zA-Z0-9]{6,}\.(js|css)$/)) {
        jsRequests.push(url);
      }
    });

    await page.goto(`${BASE_URL}/login`);
    await page.waitForTimeout(2000);

    // Should have found at least one hashed asset
    expect(jsRequests.length).toBeGreaterThan(0);

    // Check the cache headers on the first hashed asset
    const response = await page.request.get(jsRequests[0]);
    const cacheControl = response.headers()['cache-control'] || '';
    // Hashed assets should have long cache (immutable or max-age > 1 day)
    const hasLongCache = cacheControl.includes('immutable') || cacheControl.includes('max-age=31536000');
    expect(hasLongCache).toBe(true);
  });
});
