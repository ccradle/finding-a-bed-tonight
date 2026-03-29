/**
 * Notification Bell — dedicated screenshot capture.
 *
 * Captures the notification bell in context: header with bell, dropdown open (empty state).
 * The bell also appears naturally in all other screenshot captures since it's in Layout.
 *
 * Output: ../../demo/screenshots/notif-* (findABed docs repo)
 *
 * Usage: npx playwright test tests/capture-notification-screenshots.spec.ts
 * Requires: dev-start.sh running
 */
import { test, expect } from '../fixtures/auth.fixture';
import * as path from 'path';
import * as fs from 'fs';

const DEMO_DIR = path.join(__dirname, '..', '..', '..', '..', 'demo', 'screenshots');

test.beforeAll(async () => {
  if (!fs.existsSync(DEMO_DIR)) {
    fs.mkdirSync(DEMO_DIR, { recursive: true });
  }
});

test.describe('Notification Screenshot Capture', () => {

  test('notif-01 - Header with notification bell (outreach worker)', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1500);

    // Capture full page — bell visible in header between locale and password
    await outreachPage.screenshot({
      path: path.join(DEMO_DIR, 'notif-01-header-bell.png'),
      fullPage: false,
    });
  });

  test('notif-02 - Notification dropdown (empty state)', async ({ outreachPage }) => {
    await outreachPage.goto('/outreach');
    await expect(outreachPage.locator('main')).toBeVisible();
    await outreachPage.waitForTimeout(1000);

    // Open the notification dropdown
    await outreachPage.locator('[data-testid="notification-bell-button"]').click();
    await outreachPage.waitForTimeout(500);

    // Capture with dropdown open showing "No notifications"
    await outreachPage.screenshot({
      path: path.join(DEMO_DIR, 'notif-02-dropdown-empty.png'),
      fullPage: false,
    });
  });

  test('notif-03 - Coordinator view with notification bell', async ({ coordinatorPage }) => {
    await coordinatorPage.goto('/coordinator');
    await expect(coordinatorPage.locator('main')).toBeVisible();
    await coordinatorPage.waitForTimeout(1500);

    await coordinatorPage.screenshot({
      path: path.join(DEMO_DIR, 'notif-03-coordinator-bell.png'),
      fullPage: false,
    });
  });
});
