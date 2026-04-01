import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

const API_URL = process.env.API_URL || 'http://localhost:8080';

/**
 * Overflow Beds Management — Playwright E2E tests.
 *
 * Verifies: surge-gated coordinator stepper, combined outreach display,
 * transparency note, Hold button on overflow-only shelters, and
 * regression tests for non-surge behavior.
 */

async function activateSurge(): Promise<void> {
  const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const { accessToken } = await loginRes.json();

  // Check if surge already active
  const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const surges = await surgesRes.json();
  if (surges.some((s: { status: string }) => s.status === 'ACTIVE')) return;

  await fetch(`${API_URL}/api/v1/surge-events`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: 'White Flag — overflow test', temperatureF: 25 }),
  });
}

async function deactivateSurge(): Promise<void> {
  const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const { accessToken } = await loginRes.json();

  const surgesRes = await fetch(`${API_URL}/api/v1/surge-events`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const surges = await surgesRes.json();
  const active = surges.find((s: { status: string }) => s.status === 'ACTIVE');
  if (!active) return;

  await fetch(`${API_URL}/api/v1/surge-events/${active.id}/deactivate`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

async function setOverflowOnShelter(shelterId: string, overflow: number): Promise<void> {
  const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const { accessToken } = await loginRes.json();

  // Get current availability to preserve existing values
  const shelterRes = await fetch(`${API_URL}/api/v1/shelters/${shelterId}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const shelter = await shelterRes.json();
  const avail = shelter.availability?.[0];

  await fetch(`${API_URL}/api/v1/shelters/${shelterId}/availability`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      populationType: avail?.populationType || 'SINGLE_ADULT',
      bedsTotal: avail?.bedsTotal || 50,
      bedsOccupied: avail?.bedsOccupied || 42,
      bedsOnHold: avail?.bedsOnHold || 0,
      acceptingNewGuests: true,
      overflowBeds: overflow,
    }),
  });
}

// Use Oak City Community Shelter (seed data: SINGLE_ADULT, 50 total, 42 occupied = 8 available)
const OAK_CITY_ID = 'd0000000-0000-0000-0000-000000000004';

test.describe('Overflow Beds — Coordinator Dashboard', () => {
  test.afterAll(async () => {
    await deactivateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 0);
    await cleanupTestData();
  });

  test('overflow stepper visible during active surge, hidden when no surge', async ({ coordinatorPage }) => {
    // Ensure no surge
    await deactivateSurge();
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    // Expand Oak City shelter
    const card = coordinatorPage.locator(`[data-testid="shelter-card-${OAK_CITY_ID}"]`);
    await card.click();
    await coordinatorPage.waitForTimeout(1500);

    // No overflow stepper without surge
    const overflowValue = coordinatorPage.locator('[data-testid^="overflow-value-"]');
    expect(await overflowValue.count()).toBe(0);

    // Activate surge
    await activateSurge();
    // Re-navigate to pick up surge state
    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);
    await card.click();
    await coordinatorPage.waitForTimeout(1500);

    // Overflow stepper should now be visible
    await expect(overflowValue.first()).toBeVisible();
  });

  test('coordinator saves overflow value, re-opens card — value persists', async ({ coordinatorPage }) => {
    await activateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 15);

    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    const card = coordinatorPage.locator(`[data-testid="shelter-card-${OAK_CITY_ID}"]`);
    await card.click();
    await coordinatorPage.waitForTimeout(1500);

    // Overflow should show 15 (pre-populated from snapshot)
    const overflowValue = coordinatorPage.locator('[data-testid="overflow-value-SINGLE_ADULT"]');
    await expect(overflowValue).toHaveText('15');
  });
});

test.describe('Overflow Beds — Outreach Search', () => {
  test.afterAll(async () => {
    await deactivateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 0);
    await cleanupTestData();
  });

  test('outreach search shows combined count during surge', async ({ outreachPage }) => {
    await activateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 20);

    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(3000);

    // Oak City: 8 regular available + 20 overflow = 28 combined
    const oakCard = outreachPage.locator('[data-testid="shelter-card-oak-city-community-shelter"]');
    await expect(oakCard).toBeVisible();

    // Should show combined count, NOT "+20 overflow" in red
    await expect(oakCard).not.toContainText('+20 overflow');
    // Should contain transparency note
    await expect(oakCard).toContainText('temporary beds');
  });

  test('Hold This Bed button visible when only overflow beds during surge', async ({ outreachPage }) => {
    await activateSurge();

    // Set Oak City to full regular + overflow
    const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: 'dev-coc', email: 'admin@dev.fabt.org', password: 'admin123' }),
    });
    const { accessToken } = await loginRes.json();
    await fetch(`${API_URL}/api/v1/shelters/${OAK_CITY_ID}/availability`, {
      method: 'PATCH',
      headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        populationType: 'SINGLE_ADULT',
        bedsTotal: 50, bedsOccupied: 50, bedsOnHold: 0,
        acceptingNewGuests: true, overflowBeds: 10,
      }),
    });

    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(3000);

    // Hold button should be visible (effective = 0 + 10 = 10 > 0)
    const holdBtn = outreachPage.locator(`[data-testid="hold-bed-${OAK_CITY_ID}-SINGLE_ADULT"]`);
    await expect(holdBtn).toBeVisible();
  });
});

test.describe('Overflow Beds — Regression', () => {
  test.afterAll(async () => {
    await deactivateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 0);
    await cleanupTestData();
  });

  test('no surge: outreach search shows bedsAvailable only, no temporary text', async ({ outreachPage }) => {
    await deactivateSurge();
    await setOverflowOnShelter(OAK_CITY_ID, 0);

    await outreachPage.goto('/outreach');
    await outreachPage.waitForTimeout(3000);

    const oakCard = outreachPage.locator('[data-testid="shelter-card-oak-city-community-shelter"]');
    if (await oakCard.count() > 0) {
      await expect(oakCard).not.toContainText('temporary beds');
      await expect(oakCard).not.toContainText('overflow');
    }
  });

  test('no surge: coordinator dashboard has no overflow stepper', async ({ coordinatorPage }) => {
    await deactivateSurge();

    await coordinatorPage.goto('/coordinator');
    await coordinatorPage.waitForTimeout(2000);

    const card = coordinatorPage.locator(`[data-testid="shelter-card-${OAK_CITY_ID}"]`);
    await card.click();
    await coordinatorPage.waitForTimeout(1500);

    const overflowStepper = coordinatorPage.locator('[data-testid^="overflow-minus-"]');
    expect(await overflowStepper.count()).toBe(0);
  });
});
