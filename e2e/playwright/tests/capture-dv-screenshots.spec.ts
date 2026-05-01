/**
 * DV Opaque Referral — dedicated screenshot capture for the DV referral flow.
 *
 * Captures the full lifecycle: request → screening → accept/reject → warm handoff.
 * Output: ../../demo/screenshots/dv-* (findABed docs repo)
 *
 * Requires: dev-start.sh running with seed DV shelter (Safe Haven DV Shelter)
 */
import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');
const API_URL = process.env.API_URL || 'http://localhost:8080';

test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
  // Clean stale referral tokens so we start fresh
  try {
    const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
    });
    const { accessToken } = await loginResp.json();
    await fetch(`${API_URL}/api/v1/test/reset`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${accessToken}`, 'X-Confirm-Reset': 'DESTROY' },
    });
  } catch { /* reset endpoint may not exist */ }
});

test.describe('DV Referral Screenshot Capture', () => {

  test('dv-01 - Search results with Request Referral button', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);
    // Scroll to make the DV shelter visible
    const dvResult = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await dvResult.count() > 0) {
      await dvResult.scrollIntoViewIfNeeded();
      await adminPage.waitForTimeout(500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-01-search-referral-button.png'), fullPage: true });
  });

  test('dv-02 - Referral request modal', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);
    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() > 0) {
      await referralBtn.click();
      await adminPage.waitForTimeout(500);
      // Fill the form for a realistic screenshot
      await adminPage.getByTestId('referral-household-size').fill('4');
      // Click URGENT urgency
      const urgentBtn = adminPage.getByTestId('referral-urgency').locator('button', { hasText: 'URGENT' });
      await urgentBtn.click();
      await adminPage.getByTestId('referral-special-needs').fill('Wheelchair accessible, 2 children under 5');
      await adminPage.getByTestId('referral-callback').fill('919-555-0042');
      await adminPage.waitForTimeout(300);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-02-referral-request-modal.png'), fullPage: false });
  });

  test('dv-03 - Submit referral and see My Referrals pending', async ({ adminPage }) => {
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);
    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() > 0) {
      await referralBtn.click();
      await adminPage.waitForTimeout(500);
      await adminPage.getByTestId('referral-household-size').fill('4');
      await adminPage.getByTestId('referral-callback').fill('919-555-0042');
      await adminPage.getByTestId('referral-submit').click();
      await adminPage.waitForTimeout(2000);
      // Expand My Referrals
      const myReferrals = adminPage.locator('button', { hasText: /My DV Referrals/i });
      if (await myReferrals.count() > 0) {
        await myReferrals.click();
        await adminPage.waitForTimeout(500);
      }
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-03-my-referrals-pending.png'), fullPage: true });
  });

  test('dv-04 - Coordinator screening view with pending referral', async ({ adminPage }) => {
    // Navigate to coordinator dashboard — admin has dvAccess so can see DV shelters
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);
    // Iterate DV cards to find the one with a pending referral (the shelter
    // dv-03 submitted to may not be the .first() DV badge in display order).
    const dvBadges = adminPage.locator('[data-testid^="dv-badge-"]');
    const dvCount = await dvBadges.count();
    let foundShelterId: string | null = null;
    for (let i = 0; i < dvCount; i++) {
      const dvBadge = dvBadges.nth(i);
      const dvShelterId = (await dvBadge.getAttribute('data-testid'))!.replace('dv-badge-', '');
      const cardBtn = adminPage.locator(`[data-testid="shelter-card-${dvShelterId}"]`);
      await cardBtn.click();
      await adminPage.waitForTimeout(1500);
      const screeningCount = await adminPage.locator('[data-testid^="screening-"]').count();
      if (screeningCount > 0) {
        foundShelterId = dvShelterId;
        break;
      }
      // Not this one — collapse and try next
      await cardBtn.click();
      await adminPage.waitForTimeout(300);
    }
    if (!foundShelterId) throw new Error('No DV shelter with pending referral found — dv-03 prerequisite did not produce a visible screening row');
    await adminPage.waitForTimeout(500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-04-coordinator-screening.png'), fullPage: true });
  });

  test('dv-05 - Accept referral confirmation', async ({ adminPage }) => {
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);
    // Iterate DV cards to find the one with a pending referral
    const dvBadges = adminPage.locator('[data-testid^="dv-badge-"]');
    const dvCount = await dvBadges.count();
    let refId: string | null = null;
    for (let i = 0; i < dvCount; i++) {
      const dvBadge = dvBadges.nth(i);
      const dvShelterId = (await dvBadge.getAttribute('data-testid'))!.replace('dv-badge-', '');
      const cardBtn = adminPage.locator(`[data-testid="shelter-card-${dvShelterId}"]`);
      await cardBtn.click();
      await adminPage.waitForTimeout(1500);
      const screeningRow = adminPage.locator('[data-testid^="screening-"]').first();
      if (await screeningRow.count() > 0) {
        refId = (await screeningRow.getAttribute('data-testid'))!.replace('screening-', '');
        break;
      }
      await cardBtn.click();
      await adminPage.waitForTimeout(300);
    }
    if (!refId) throw new Error('No pending referral found for dv-05');
    await adminPage.locator(`[data-testid="accept-referral-${refId}"]`).click();
    // After accept, the screening row is removed (the API marks the
    // referral ACCEPTED and pendingReferrals refetches). Wait for the row
    // to disappear so the screenshot reflects the post-accept state.
    await adminPage.locator(`[data-testid="screening-${refId}"]`).waitFor({ state: 'detached', timeout: 10000 });
    await adminPage.waitForTimeout(500);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-05-referral-accepted.png'), fullPage: true });
  });

  test('dv-06 - Worker sees warm handoff after acceptance', async ({ adminPage }) => {
    // Go to outreach search — My Referrals should show accepted with phone
    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);
    const myReferrals = adminPage.locator('button', { hasText: /My DV Referrals/i });
    if (await myReferrals.count() > 0) {
      await myReferrals.click();
      await adminPage.waitForTimeout(500);
    }
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-06-warm-handoff.png'), fullPage: true });
  });

  test('dv-07 - Submit second referral for reject flow', async ({ adminPage }) => {
    // Clean tokens first for a fresh reject scenario
    try {
      const loginResp = await fetch(`${API_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
      });
      const { accessToken } = await loginResp.json();
      // Delete all referral tokens to allow a new one
      await fetch(`${API_URL}/api/v1/test/reset`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${accessToken}`, 'X-Confirm-Reset': 'DESTROY' },
      });
    } catch { /* silent */ }

    await adminPage.goto('/outreach');
    await adminPage.waitForTimeout(2000);
    const referralBtn = adminPage.locator('[data-testid^="request-referral-"]').first();
    if (await referralBtn.count() > 0) {
      await referralBtn.click();
      await adminPage.waitForTimeout(500);
      await adminPage.getByTestId('referral-household-size').fill('2');
      await adminPage.getByTestId('referral-callback').fill('919-555-0099');
      await adminPage.getByTestId('referral-submit').click();
      await adminPage.waitForTimeout(1500);
    }
    // Now go to coordinator and reject — iterate DV cards to find the pending referral
    await adminPage.goto('/coordinator');
    await adminPage.waitForTimeout(2000);
    const dvBadges = adminPage.locator('[data-testid^="dv-badge-"]');
    const dvCount = await dvBadges.count();
    let refId: string | null = null;
    for (let i = 0; i < dvCount; i++) {
      const dvBadge = dvBadges.nth(i);
      const dvShelterId = (await dvBadge.getAttribute('data-testid'))!.replace('dv-badge-', '');
      const cardBtn = adminPage.locator(`[data-testid="shelter-card-${dvShelterId}"]`);
      await cardBtn.click();
      await adminPage.waitForTimeout(1500);
      const screeningRow = adminPage.locator('[data-testid^="screening-"]').first();
      if (await screeningRow.count() > 0) {
        refId = (await screeningRow.getAttribute('data-testid'))!.replace('screening-', '');
        break;
      }
      await cardBtn.click();
      await adminPage.waitForTimeout(300);
    }
    if (!refId) throw new Error('No pending referral found for dv-07');
    // Click Reject — this opens the inline reject-reason input via setRejectingId
    await adminPage.locator(`[data-testid="reject-referral-${refId}"]`).click();
    const reasonInput = adminPage.locator(`[data-testid="reject-reason-${refId}"]`);
    await reasonInput.waitFor({ state: 'visible', timeout: 5000 });
    await reasonInput.fill('No capacity for pets at this time');
    await adminPage.waitForTimeout(300);
    await adminPage.screenshot({ path: path.join(DEMO_DIR, 'dv-07-referral-reject-reason.png'), fullPage: true });
  });
});
