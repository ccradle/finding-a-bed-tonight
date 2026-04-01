import { test, expect } from '../fixtures/auth.fixture';
import type { Page } from '@playwright/test';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Read the offline queue from IndexedDB using the app's own module.
 * Falls back to direct IDB access if the export isn't available.
 */
async function getQueuedActions(page: Page) {
  return page.evaluate(async () => {
    return new Promise<unknown[]>((resolve, reject) => {
      const request = indexedDB.open('fabt-offline-queue', 2);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('actions')) { resolve([]); return; }
        const tx = db.transaction('actions', 'readonly');
        const store = tx.objectStore('actions');
        const getAll = store.getAll();
        getAll.onsuccess = () => resolve(getAll.result);
        getAll.onerror = () => reject(getAll.error);
      };
      request.onupgradeneeded = () => {
        request.result.close();
        resolve([]);
      };
    });
  });
}

/**
 * Go offline: block network + dispatch offline DOM event.
 * Playwright's setOffline() does NOT fire DOM events or change navigator.onLine.
 */
async function goOffline(page: Page) {
  await page.context().setOffline(true);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'onLine', { value: false, writable: true, configurable: true });
    window.dispatchEvent(new Event('offline'));
  });
  await page.waitForTimeout(300);
}

/**
 * Go online: restore network + dispatch online DOM event.
 * Waits for jitter + replay (up to 4 seconds).
 */
async function goOnline(page: Page, waitMs = 4000) {
  await page.context().setOffline(false);
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'onLine', { value: true, writable: true, configurable: true });
    window.dispatchEvent(new Event('online'));
  });
  await page.waitForTimeout(waitMs);
}

/**
 * Enqueue a synthetic action directly into IndexedDB.
 */
async function enqueueSynthetic(page: Page, action: {
  id: string; idempotencyKey: string; type: string; url: string; method: string; body: string; timestamp: number;
}) {
  await page.evaluate(async (a) => {
    const request = indexedDB.open('fabt-offline-queue', 2);
    await new Promise<void>((resolve, reject) => {
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('actions', 'readwrite');
        tx.objectStore('actions').add(a);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      };
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        const store = db.createObjectStore('actions', { keyPath: 'id' });
        store.createIndex('by-timestamp', 'timestamp');
      };
    });
  }, action);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Offline Behavior', () => {
  test('offline banner appears on connectivity loss', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    await goOffline(outreachPage);

    const banner = outreachPage.locator('text=/offline/i');
    await expect(banner).toBeVisible({ timeout: 5000 });

    await goOnline(outreachPage);
  });

  test('search results remain visible while offline (stale cache)', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main div[style*="border"]', { timeout: 10000 });

    await goOffline(outreachPage);

    const mainContent = outreachPage.locator('main');
    await expect(mainContent).not.toBeEmpty();
    await expect(outreachPage.locator('main')).not.toContainText(/500|internal server error/i);

    await goOnline(outreachPage);
  });

  // -------------------------------------------------------------------------
  // Availability update queued and replayed
  // -------------------------------------------------------------------------
  test('queued availability update replays on reconnect', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForSelector('[data-testid^="shelter-card-"]', { timeout: 10000 });

    // Click a shelter that HAS availability data (avail badge visible)
    const shelterWithAvail = coordinatorPage.locator('[data-testid^="shelter-card-"]', {
      has: coordinatorPage.locator('[data-testid^="avail-badge-"]'),
    }).first();
    await shelterWithAvail.click();
    await coordinatorPage.waitForTimeout(2000);

    const saveBtn = coordinatorPage.locator('[data-testid^="save-avail-"]').first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    await goOffline(coordinatorPage);

    await saveBtn.click();
    await coordinatorPage.waitForTimeout(1000);

    const actions = await getQueuedActions(coordinatorPage);
    expect(actions.length).toBeGreaterThanOrEqual(1);
    const queuedAction = actions[0] as { type: string; idempotencyKey: string };
    expect(queuedAction.type).toBe('UPDATE_AVAILABILITY');
    expect(queuedAction.idempotencyKey).toBeTruthy();

    await goOnline(coordinatorPage, 5000);

    const actionsAfter = await getQueuedActions(coordinatorPage);
    expect(actionsAfter.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Hold queued and replayed
  // -------------------------------------------------------------------------
  test('hold queued offline and replayed on reconnect', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('[data-testid^="hold-bed-"]', { timeout: 15000 });

    await goOffline(outreachPage);

    const holdBtn = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    await holdBtn.click();
    await outreachPage.waitForTimeout(1000);

    const queuedText = outreachPage.locator('text=/Hold queued/i');
    await expect(queuedText).toBeVisible({ timeout: 5000 });

    const actions = await getQueuedActions(outreachPage);
    expect(actions.length).toBeGreaterThanOrEqual(1);
    const holdAction = (actions as { type: string }[]).find(a => a.type === 'HOLD_BED');
    expect(holdAction).toBeTruthy();

    await goOnline(outreachPage, 5000);

    const actionsAfter = await getQueuedActions(outreachPage);
    const remainingHolds = (actionsAfter as { type: string }[]).filter(a => a.type === 'HOLD_BED');
    expect(remainingHolds.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Hold expiry
  // -------------------------------------------------------------------------
  test('expired hold is skipped on replay with notification', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    await enqueueSynthetic(outreachPage, {
      id: 'test-expired-hold',
      idempotencyKey: 'test-key-expired',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: Date.now() - (120 * 60 * 1000), // 2 hours ago
    });

    const actionsBefore = await getQueuedActions(outreachPage);
    expect(actionsBefore.length).toBeGreaterThanOrEqual(1);

    // Trigger replay via explicit online event (no need for setOffline cycle)
    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(4000);

    const actionsAfter = await getQueuedActions(outreachPage);
    const expiredHold = (actionsAfter as { id: string }[]).find(a => a.id === 'test-expired-hold');
    expect(expiredHold).toBeUndefined();

    const notification = outreachPage.locator('text=/expired/i');
    await expect(notification).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // Conflict (409) on replay
  // -------------------------------------------------------------------------
  test('conflict (409) shows actionable notification on replay', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ status: 409, error: 'Conflict', message: 'No beds available' }),
        });
      } else {
        route.continue();
      }
    });

    await enqueueSynthetic(outreachPage, {
      id: 'test-conflict-hold',
      idempotencyKey: 'test-key-conflict',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: Date.now() - (5 * 60 * 1000),
    });

    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(4000);

    const actionsAfter = await getQueuedActions(outreachPage);
    const conflictHold = (actionsAfter as { id: string }[]).find(a => a.id === 'test-conflict-hold');
    expect(conflictHold).toBeUndefined();

    const notification = outreachPage.locator('text=/taken while offline/i');
    await expect(notification).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // Queue status indicator
  // -------------------------------------------------------------------------
  test('queue status badge appears when actions pending and disappears after replay', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('[data-testid^="hold-bed-"]', { timeout: 15000 });

    const badge = outreachPage.locator('[data-testid="queue-status-badge"]');
    await expect(badge).not.toBeVisible({ timeout: 3000 });

    await goOffline(outreachPage);

    const holdBtn = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    await holdBtn.click();
    await outreachPage.waitForTimeout(2500);

    await expect(badge).toBeVisible({ timeout: 5000 });
    const count = outreachPage.locator('[data-testid="queue-count"]');
    await expect(count).toHaveText('1');

    await goOnline(outreachPage, 6000);

    await expect(badge).not.toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // NEGATIVE: Double online event — no duplicate replay
  // -------------------------------------------------------------------------
  test('double online event does not cause duplicate API calls', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    // Track API calls and return success for synthetic action
    const apiCalls: string[] = [];
    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        apiCalls.push('POST');
        route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'fake-id', status: 'HELD', remainingSeconds: 5400 }) });
      } else {
        route.continue();
      }
    });

    await enqueueSynthetic(outreachPage, {
      id: 'test-double-event',
      idempotencyKey: 'test-key-double',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: Date.now() - (60 * 1000), // 1 minute ago
    });

    // Fire two online events rapidly
    await outreachPage.evaluate(() => {
      window.dispatchEvent(new Event('online'));
      window.dispatchEvent(new Event('online'));
    });
    await outreachPage.waitForTimeout(5000);

    // Should have at most 1 POST (concurrent guard prevents double replay)
    expect(apiCalls.filter(c => c === 'POST').length).toBeLessThanOrEqual(1);

    const actionsAfter = await getQueuedActions(outreachPage);
    expect(actionsAfter.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // NEGATIVE: Try/catch fallback — online but network fails
  // -------------------------------------------------------------------------
  test('hold enqueued when online but network request fails', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('[data-testid^="hold-bed-"]', { timeout: 15000 });

    // Intercept hold API to simulate network failure
    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        route.abort('connectionfailed');
      } else {
        route.continue();
      }
    });

    // Click hold while nominally online — should trigger try/catch fallback
    const holdBtn = outreachPage.locator('[data-testid^="hold-bed-"]').first();
    await holdBtn.click();
    await outreachPage.waitForTimeout(2000);

    // Verify action was enqueued (fallback worked), not an error displayed
    const actions = await getQueuedActions(outreachPage);
    const holdAction = (actions as { type: string }[]).find(a => a.type === 'HOLD_BED');
    expect(holdAction).toBeTruthy();

    // Should show queued state, not error
    const queuedText = outreachPage.locator('text=/Hold queued|queued/i');
    // The reservation panel should show the queued hold
    const badge = outreachPage.locator('[data-testid="queue-status-badge"]');
    await expect(badge).toBeVisible({ timeout: 5000 });

    // Clean up: remove route intercept, replay
    await outreachPage.unroute('**/api/v1/reservations');
    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(5000);

    const actionsAfter = await getQueuedActions(outreachPage);
    expect(actionsAfter.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Multi-action queue replay — order and multiple state transitions
  // -------------------------------------------------------------------------
  test('multiple queued actions replay in order', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    // Track API call order and return success for synthetic actions
    const apiCalls: { method: string; url: string }[] = [];
    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        apiCalls.push({ method: 'POST', url: route.request().url() });
        route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'fake-id', status: 'HELD', remainingSeconds: 5400 }) });
      } else { route.continue(); }
    });
    await outreachPage.route('**/api/v1/shelters/*/availability', (route) => {
      if (route.request().method() === 'PATCH') {
        apiCalls.push({ method: 'PATCH', url: route.request().url() });
        route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      } else { route.continue(); }
    });

    // Enqueue 3 actions with staggered timestamps to ensure order
    const now = Date.now();
    await enqueueSynthetic(outreachPage, {
      id: 'multi-1-hold-a',
      idempotencyKey: 'key-multi-1',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: now - 3000, // oldest
    });
    await enqueueSynthetic(outreachPage, {
      id: 'multi-2-avail',
      idempotencyKey: 'key-multi-2',
      type: 'UPDATE_AVAILABILITY',
      url: '/api/v1/shelters/00000000-0000-0000-0000-000000000001/availability',
      method: 'PATCH',
      body: JSON.stringify({ populationType: 'SINGLE_ADULT', bedsTotal: 10, bedsOccupied: 5, bedsOnHold: 0, acceptingNewGuests: true }),
      timestamp: now - 2000, // middle
    });
    await enqueueSynthetic(outreachPage, {
      id: 'multi-3-hold-b',
      idempotencyKey: 'key-multi-3',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000002', populationType: 'VETERAN' }),
      timestamp: now - 1000, // newest
    });

    const actionsBefore = await getQueuedActions(outreachPage);
    expect(actionsBefore.length).toBe(3);

    // Trigger replay
    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(5000);

    // Verify all 3 replayed — queue should be empty
    const actionsAfter = await getQueuedActions(outreachPage);
    expect(actionsAfter.length).toBe(0);

    // Verify API calls arrived in timestamp order
    expect(apiCalls.length).toBeGreaterThanOrEqual(3);
    const replayCalls = apiCalls.slice(-3);
    expect(replayCalls[0].method).toBe('POST'); // hold A (oldest)
    expect(replayCalls[1].method).toBe('PATCH'); // availability (middle)
    expect(replayCalls[2].method).toBe('POST'); // hold B (newest)
  });

  // -------------------------------------------------------------------------
  // Try/catch fallback for coordinator availability updates
  // -------------------------------------------------------------------------
  test('availability update enqueued when online but network fails (coordinator)', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForSelector('[data-testid^="shelter-card-"]', { timeout: 10000 });

    // Click a shelter that HAS availability data
    const shelterWithAvail = coordinatorPage.locator('[data-testid^="shelter-card-"]', {
      has: coordinatorPage.locator('[data-testid^="avail-badge-"]'),
    }).first();
    await shelterWithAvail.click();
    await coordinatorPage.waitForTimeout(2000);

    const saveBtn = coordinatorPage.locator('[data-testid^="save-avail-"]').first();
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    // Intercept PATCH to simulate network failure while nominally online
    await coordinatorPage.route('**/api/v1/shelters/*/availability', (route) => {
      if (route.request().method() === 'PATCH') {
        route.abort('connectionfailed');
      } else {
        route.continue();
      }
    });

    // Submit availability update — should trigger try/catch fallback
    await saveBtn.click();
    await coordinatorPage.waitForTimeout(2000);

    // Verify action was enqueued (not just an error)
    const actions = await getQueuedActions(coordinatorPage);
    const availAction = (actions as { type: string }[]).find(a => a.type === 'UPDATE_AVAILABILITY');
    expect(availAction).toBeTruthy();

    // Should show queued message, not generic error
    const queuedMsg = coordinatorPage.locator('text=/queued/i');
    await expect(queuedMsg).toBeVisible({ timeout: 3000 });

    // Clean up: remove intercept, replay
    await coordinatorPage.unroute('**/api/v1/shelters/*/availability');
    await goOnline(coordinatorPage, 5000);

    const actionsAfter = await getQueuedActions(coordinatorPage);
    expect(actionsAfter.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // FAILED state rendering (non-409 replay error)
  // -------------------------------------------------------------------------
  test('FAILED state shown when replay gets non-409 error', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    // Intercept hold API to return 500
    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: 'Database unavailable' }),
        });
      } else {
        route.continue();
      }
    });

    await enqueueSynthetic(outreachPage, {
      id: 'test-failed-hold',
      idempotencyKey: 'test-key-failed',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: Date.now() - (60 * 1000),
    });

    // Trigger replay
    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(4000);

    // Action should REMAIN in queue (non-409 = retry later)
    const actionsAfter = await getQueuedActions(outreachPage);
    const failedHold = (actionsAfter as { id: string }[]).find(a => a.id === 'test-failed-hold');
    expect(failedHold).toBeTruthy();

    // Notification should mention failure
    const notification = outreachPage.locator('text=/failed|retry/i');
    await expect(notification).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // Replay success notification toast
  // -------------------------------------------------------------------------
  test('replay success notification shows action count', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await outreachPage.waitForSelector('main', { timeout: 10000 });

    // Intercept APIs to return success for synthetic actions
    await outreachPage.route('**/api/v1/reservations', (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'fake-id', status: 'HELD', remainingSeconds: 5400 }) });
      } else { route.continue(); }
    });
    await outreachPage.route('**/api/v1/shelters/*/availability', (route) => {
      if (route.request().method() === 'PATCH') {
        route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      } else { route.continue(); }
    });

    // Enqueue 2 actions
    await enqueueSynthetic(outreachPage, {
      id: 'toast-1',
      idempotencyKey: 'key-toast-1',
      type: 'HOLD_BED',
      url: '/api/v1/reservations',
      method: 'POST',
      body: JSON.stringify({ shelterId: '00000000-0000-0000-0000-000000000001', populationType: 'SINGLE_ADULT' }),
      timestamp: Date.now() - (60 * 1000),
    });
    await enqueueSynthetic(outreachPage, {
      id: 'toast-2',
      idempotencyKey: 'key-toast-2',
      type: 'UPDATE_AVAILABILITY',
      url: '/api/v1/shelters/00000000-0000-0000-0000-000000000001/availability',
      method: 'PATCH',
      body: JSON.stringify({ populationType: 'SINGLE_ADULT', bedsTotal: 10, bedsOccupied: 5, bedsOnHold: 0, acceptingNewGuests: true }),
      timestamp: Date.now() - (30 * 1000),
    });

    // Trigger replay
    await outreachPage.evaluate(() => window.dispatchEvent(new Event('online')));
    await outreachPage.waitForTimeout(4000);

    // Verify toast notification shows count
    const toast = outreachPage.locator('text=/2 queued actions sent/i');
    await expect(toast).toBeVisible({ timeout: 6000 });

    // Queue should be empty
    const actionsAfter = await getQueuedActions(outreachPage);
    expect(actionsAfter.length).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Hospital use case — service worker blocked (proper API)
  // -------------------------------------------------------------------------
  test('app functions when service worker is blocked (hospital use case)', async ({ browser }) => {
    // Use Playwright's proper API to block service workers BEFORE page load
    const context = await browser.newContext({ serviceWorkers: 'block' });
    const page = await context.newPage();

    // Login
    await page.goto('/login');
    await page.locator('[data-testid="login-tenant-slug"]').waitFor({ state: 'visible', timeout: 10000 });
    await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
    await page.locator('[data-testid="login-email"]').fill('outreach@dev.fabt.org');
    await page.locator('[data-testid="login-password"]').fill('admin123');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });

    // Navigate to search
    await page.goto('/outreach');
    await page.waitForTimeout(3000);

    // Search results should load
    const shelterCount = page.locator('text=/shelters? found/i');
    await expect(shelterCount).toBeVisible({ timeout: 5000 });

    // Filters work
    const popFilter = page.locator('select[aria-label="Filter by population type"]');
    await expect(popFilter).toBeVisible();

    // Hold a bed should work
    const holdBtn = page.locator('button', { hasText: /Hold This Bed/i }).first();
    if (await holdBtn.count() > 0) {
      await holdBtn.click();
      await page.waitForTimeout(2000);
      const hasError = await page.locator('text=/500|internal server error/i').count();
      expect(hasError).toBe(0);
    }

    await expect(page.locator('main')).not.toBeEmpty();
    await context.close();
  });
});
