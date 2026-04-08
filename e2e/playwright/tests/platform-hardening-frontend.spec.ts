import { test, expect } from '../fixtures/auth.fixture';
import { cleanupTestData } from '../helpers/test-cleanup';

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

async function getAdminToken(): Promise<string> {
  const res = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: 'admin@dev.fabt.org', password: 'admin123' }),
  });
  const data = await res.json();
  return data.accessToken;
}

async function createApiKey(token: string, label: string): Promise<{ id: string; plaintextKey: string }> {
  const res = await fetch(`${API_URL}/api/v1/api-keys`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ label }),
  });
  return res.json();
}

async function createSubscription(token: string, eventType: string): Promise<{ id: string }> {
  const res = await fetch(`${API_URL}/api/v1/subscriptions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ eventType, callbackUrl: 'https://httpbin.org/post', callbackSecret: 'test-secret-123' }),
  });
  return res.json();
}

test.describe('API Key Management (Platform Hardening)', () => {
  let adminToken: string;
  let testKeyId: string;

  test.beforeAll(async () => {
    adminToken = await getAdminToken();
    const key = await createApiKey(adminToken, `e2e-revoke-${Date.now()}`);
    testKeyId = key.id;
  });

  test.afterAll(async () => { await cleanupTestData(); });

  test('T-38: revoke API key — confirm dialog, status changes to Revoked', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /api keys/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the revoke button for our test key
    const revokeBtn = adminPage.locator(`[data-testid="revoke-key-${testKeyId}"]`);
    await expect(revokeBtn).toBeVisible({ timeout: 5000 });
    await revokeBtn.click();

    // Confirmation dialog should appear
    const dialog = adminPage.locator('[data-testid="revoke-confirm-dialog"]');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#revoke-confirm-title')).toContainText(/revoke/i);

    // Confirm revoke
    await adminPage.locator('[data-testid="revoke-confirm-btn"]').click();
    await adminPage.waitForTimeout(1000);

    // Dialog should close
    await expect(dialog).not.toBeVisible();

    // Status badge should show "Revoked"
    // The row should no longer have revoke/rotate buttons (key is inactive)
    await expect(adminPage.locator(`[data-testid="revoke-key-${testKeyId}"]`)).not.toBeVisible();
  });

  test('T-39: rotate API key — confirm dialog, new key displayed once', async ({ adminPage }) => {
    // Create a fresh key for rotation
    const key = await createApiKey(adminToken, `e2e-rotate-${Date.now()}`);

    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /api keys/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the rotate button
    const rotateBtn = adminPage.locator(`[data-testid="rotate-key-${key.id}"]`);
    await expect(rotateBtn).toBeVisible({ timeout: 5000 });
    await rotateBtn.click();

    // Confirmation dialog should appear
    const dialog = adminPage.locator('[data-testid="rotate-confirm-dialog"]');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#rotate-confirm-title')).toContainText(/rotate/i);

    // Confirm rotation
    await adminPage.locator('[data-testid="rotate-confirm-btn"]').click();
    await adminPage.waitForTimeout(2000);

    // Dialog should close
    await expect(dialog).not.toBeVisible();

    // New key reveal panel should appear with a hex key
    const revealBox = adminPage.locator('[data-testid="api-key-reveal"]');
    await expect(revealBox).toBeVisible();
    const keyText = await revealBox.textContent();
    expect(keyText).toBeTruthy();
    expect(keyText!.length).toBeGreaterThanOrEqual(32);
  });
});

test.describe('Subscription Management (Platform Hardening)', () => {
  let adminToken: string;
  let testSubId: string;

  test.beforeAll(async () => {
    adminToken = await getAdminToken();
    const sub = await createSubscription(adminToken, `e2e.test.${Date.now()}`);
    testSubId = sub.id;
  });

  test.afterAll(async () => { await cleanupTestData(); });

  test('T-40: delete subscription — confirm dialog, row removed', async ({ adminPage }) => {
    // Create a dedicated subscription for deletion
    const sub = await createSubscription(adminToken, `e2e.delete.${Date.now()}`);

    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /subscriptions/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the delete button
    const deleteBtn = adminPage.locator(`[data-testid="delete-sub-${sub.id}"]`);
    await expect(deleteBtn).toBeVisible({ timeout: 5000 });
    await deleteBtn.click();

    // Confirmation dialog should appear
    const dialog = adminPage.locator('[data-testid="delete-confirm-dialog"]');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#delete-confirm-title')).toContainText(/delete/i);

    // Cancel first — verify dialog closes without deletion
    await adminPage.locator('[data-testid="delete-cancel-btn"]').click();
    await expect(dialog).not.toBeVisible();
    await expect(deleteBtn).toBeVisible();

    // Now actually delete
    await deleteBtn.click();
    await expect(dialog).toBeVisible();
    await adminPage.locator('[data-testid="delete-confirm-btn"]').click();
    await adminPage.waitForTimeout(1000);

    // Dialog should close
    await expect(dialog).not.toBeVisible();

    // Row remains but status changes to Cancelled — delete button should be gone
    // (cancelled subscriptions can't be deleted again, and toggle/test buttons are hidden)
    await expect(adminPage.locator(`[data-testid="delete-sub-${sub.id}"]`)).not.toBeVisible();
    await expect(adminPage.locator(`[data-testid="toggle-sub-${sub.id}"]`)).not.toBeVisible();
    await expect(adminPage.locator(`[data-testid="test-sub-${sub.id}"]`)).not.toBeVisible();
  });

  test('T-41: pause subscription — toggle visible, resume', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /subscriptions/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the toggle button for our test subscription
    const toggleBtn = adminPage.locator(`[data-testid="toggle-sub-${testSubId}"]`);
    await expect(toggleBtn).toBeVisible({ timeout: 5000 });

    // Should show "Pause" (subscription is ACTIVE)
    await expect(toggleBtn).toContainText(/pause/i);

    // Click to pause
    await toggleBtn.click();
    await adminPage.waitForTimeout(1000);

    // Should now show "Resume"
    await expect(toggleBtn).toContainText(/resume/i);

    // Click to resume
    await toggleBtn.click();
    await adminPage.waitForTimeout(1000);

    // Should be back to "Pause"
    await expect(toggleBtn).toContainText(/pause/i);
  });

  test('T-42: send test event — result shown inline', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /subscriptions/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the test button
    const testBtn = adminPage.locator(`[data-testid="test-sub-${testSubId}"]`);
    await expect(testBtn).toBeVisible({ timeout: 5000 });

    // Click send test
    await testBtn.click();

    // Wait for result to appear inline (may take a few seconds for HTTP call)
    const result = adminPage.locator(`[data-testid="test-result-${testSubId}"]`);
    await expect(result).toBeVisible({ timeout: 15000 });

    // Result should show a status code and response time
    const resultText = await result.textContent();
    expect(resultText).toMatch(/\d{3}/); // HTTP status code
    expect(resultText).toMatch(/\d+ms/); // Response time
  });

  test('T-35a: delivery log panel expands and collapses', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /subscriptions/i }).click();
    await adminPage.waitForTimeout(1000);

    // Find the deliveries button
    const deliveriesBtn = adminPage.locator(`[data-testid="deliveries-sub-${testSubId}"]`);
    await expect(deliveriesBtn).toBeVisible({ timeout: 5000 });

    // aria-expanded should be false initially
    await expect(deliveriesBtn).toHaveAttribute('aria-expanded', 'false');

    // Click to expand
    await deliveriesBtn.click();
    await adminPage.waitForTimeout(1000);

    // aria-expanded should be true
    await expect(deliveriesBtn).toHaveAttribute('aria-expanded', 'true');

    // Click again to collapse
    await deliveriesBtn.click();
    await adminPage.waitForTimeout(500);

    // aria-expanded should be false again
    await expect(deliveriesBtn).toHaveAttribute('aria-expanded', 'false');
  });

  test('revoke dialog dismisses on Escape key', async ({ adminPage }) => {
    const key = await createApiKey(adminToken, `e2e-esc-${Date.now()}`);

    await adminPage.goto('/admin');
    await adminPage.locator('[role="tab"]', { hasText: /api keys/i }).click();
    await adminPage.waitForTimeout(1000);

    const revokeBtn = adminPage.locator(`[data-testid="revoke-key-${key.id}"]`);
    await expect(revokeBtn).toBeVisible({ timeout: 5000 });
    await revokeBtn.click();

    const dialog = adminPage.locator('[data-testid="revoke-confirm-dialog"]');
    await expect(dialog).toBeVisible();

    // Press Escape
    await adminPage.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();

    // Key should still be active (not revoked)
    await expect(revokeBtn).toBeVisible();
  });
});
