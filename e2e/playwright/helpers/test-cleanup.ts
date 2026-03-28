/**
 * Shared test cleanup utility.
 * Calls the test reset endpoint to clean up data created by E2E tests.
 * Tests should call this in afterAll to leave the DB clean for capture scripts.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';
const ADMIN_EMAIL = 'admin@dev.fabt.org';
const ADMIN_PASSWORD = 'admin123';

export async function cleanupTestData(): Promise<void> {
  try {
    const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantSlug: TENANT_SLUG, email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    });
    const { accessToken } = await loginRes.json();

    await fetch(`${API_URL}/api/v1/test/reset`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${accessToken}`, 'X-Confirm-Reset': 'DESTROY' },
    });
  } catch {
    // Cleanup failure should not fail tests — log and continue
    console.warn('Test cleanup failed — test reset endpoint may be unavailable');
  }
}
