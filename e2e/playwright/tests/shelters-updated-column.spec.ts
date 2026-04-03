import { test, expect } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';

test.describe('Shelters Updated Column (#32)', () => {

  test('admin shelters tab shows availability freshness, not profile edit date', async ({ page }) => {
    // Login as admin
    await page.goto(`${BASE_URL}/login`);
    await page.locator('[data-testid="login-tenant-slug"]').fill('dev-coc');
    await page.locator('[data-testid="login-email"]').fill('admin@dev.fabt.org');
    await page.locator('[data-testid="login-password"]').fill('admin123');
    await page.locator('[data-testid="login-submit"]').click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });

    // Click Shelters tab
    // Admin lands on Users tab. Click "Shelters" tab (it's a button in the tab bar, not the left nav)
    await page.getByRole('tab', { name: 'Shelters' }).click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: 'shelters-updated-column.png' });

    // The Updated column should NOT show "4 days" or "5 days" for all shelters
    // (that would mean it's showing the seed data profile creation date)
    // It SHOULD show recent times like "X mins", "X hrs" for shelters with recent availability
    const pageText = await page.textContent('body');

    // At least one shelter should show a time less than 24 hours if demo activity data was seeded
    // The seed script creates availability snapshots, so some should be recent
    const hasRecentUpdate = pageText?.match(/\d+\s*(min|hr|sec)/i);
    expect(hasRecentUpdate).toBeTruthy();
  });
});
