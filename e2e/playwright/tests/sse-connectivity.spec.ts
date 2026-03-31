import { test, expect } from '../fixtures/auth.fixture';

test.describe('SSE Connection Stability', () => {
  test.slow(); // Triple timeout — this test waits 15 seconds

  test('SSE notification stream stays connected for 15 seconds without reconnecting', async ({ outreachPage }) => {
    // Instrument: track SSE connection attempts and requests
    let sseConnectionCount = 0;
    let bedSearchCount = 0;
    let referralCount = 0;

    outreachPage.on('request', (request) => {
      const url = request.url();
      if (url.includes('/api/v1/notifications/stream')) sseConnectionCount++;
      if (url.includes('/api/v1/queries/beds')) bedSearchCount++;
      if (url.includes('/api/v1/dv-referrals/mine')) referralCount++;
    });

    // Navigate to outreach search (establishes SSE)
    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(2000); // Let initial load settle

    // Record baseline counts after initial load
    const baselineBedSearch = bedSearchCount;
    const baselineReferral = referralCount;
    const baselineSse = sseConnectionCount;

    // Wait 15 seconds — sufficient to detect the v0.22.1 pattern (reconnect every 5s)
    await outreachPage.waitForTimeout(15000);

    // Assert: no additional SSE connections (no reconnects)
    expect(sseConnectionCount).toBe(baselineSse);

    // Assert: no additional bed search or referral refetches (no catchUp storm)
    expect(bedSearchCount).toBeLessThanOrEqual(baselineBedSearch);
    expect(referralCount).toBeLessThanOrEqual(baselineReferral);
  });

  test('SSE connection does not cause rapid API refetches over 30 seconds', async ({ outreachPage }) => {
    let bedSearchCount = 0;
    let referralCount = 0;

    outreachPage.on('request', (request) => {
      const url = request.url();
      if (url.includes('/api/v1/queries/beds')) bedSearchCount++;
      if (url.includes('/api/v1/dv-referrals/mine')) referralCount++;
    });

    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(30000);

    // Over 30 seconds, max 2 bed search requests (initial + possibly 1 real SSE event)
    // and max 2 referral requests. If we see 5+ of either, it's a refetch storm.
    expect(bedSearchCount).toBeLessThanOrEqual(3);
    expect(referralCount).toBeLessThanOrEqual(3);
  });
});
