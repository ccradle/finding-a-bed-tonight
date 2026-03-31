import { test as base, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const API_URL = process.env.API_URL || 'http://localhost:8080';
const TENANT_SLUG = 'dev-coc';

const USERS = {
  admin: { email: 'admin@dev.fabt.org', password: 'admin123' },
  cocadmin: { email: 'cocadmin@dev.fabt.org', password: 'admin123' },
  outreach: { email: 'outreach@dev.fabt.org', password: 'admin123' },
};

type Role = keyof typeof USERS;

/**
 * Check if the cached auth state has a valid (non-expired) access token.
 * Portfolio Lesson 42: Cached tokens expire (15-min lifespan for FABT).
 * If token is expired or will expire within 60 seconds, force re-login.
 */
function isAuthStateValid(stateFile: string): boolean {
  if (!fs.existsSync(stateFile)) return false;
  try {
    const state = JSON.parse(fs.readFileSync(stateFile, 'utf-8'));
    const origins = state.origins || [];

    // Invalidate if the cached state is for a different origin (e.g., Vite :5173 vs nginx :8081).
    // localStorage is origin-scoped — tokens saved for one origin don't work on another.
    const currentBaseURL = process.env.BASE_URL || (process.env.NGINX === '1' ? 'http://localhost:8081' : 'http://localhost:5173');
    const cachedOrigin = origins[0]?.origin;
    if (cachedOrigin && !currentBaseURL.startsWith(cachedOrigin)) {
      return false;
    }

    for (const origin of origins) {
      for (const item of origin.localStorage || []) {
        if (item.name === 'fabt_access_token' && item.value) {
          // Decode JWT payload (base64url)
          const parts = item.value.split('.');
          if (parts.length !== 3) return false;
          let payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
          while (payload.length % 4) payload += '=';
          const decoded = JSON.parse(Buffer.from(payload, 'base64').toString());
          const nowSec = Math.floor(Date.now() / 1000);
          // Valid if more than 60 seconds until expiry
          return decoded.exp > nowSec + 60;
        }
      }
    }
  } catch {
    return false;
  }
  return false;
}

async function loginAndSaveState(page: Page, role: Role): Promise<void> {
  const authDir = path.join(__dirname, '..', 'auth');
  const stateFile = path.join(authDir, `${role}.json`);

  if (!fs.existsSync(authDir)) {
    fs.mkdirSync(authDir, { recursive: true });
  }

  // Navigate to login page and wait for form to be ready
  await page.goto('/login');
  await page.locator('[data-testid="login-tenant-slug"]').waitFor({ state: 'visible', timeout: 10000 });

  // Fill login form
  await page.locator('[data-testid="login-tenant-slug"]').fill(TENANT_SLUG);
  await page.locator('[data-testid="login-email"]').fill(USERS[role].email);
  await page.locator('[data-testid="login-password"]').fill(USERS[role].password);
  await page.locator('[data-testid="login-submit"]').click();

  // Wait for redirect away from login
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });

  // Save storage state
  await page.context().storageState({ path: stateFile });
}

/**
 * Extended test fixture that provides pre-authenticated browser contexts per role.
 * Usage: import { test } from '../fixtures/auth.fixture';
 *        test('my test', async ({ outreachPage }) => { ... });
 */
export const test = base.extend<{
  outreachPage: Page;
  coordinatorPage: Page;
  adminPage: Page;
}>({
  outreachPage: async ({ browser }, use) => {
    const stateFile = path.join(__dirname, '..', 'auth', 'outreach.json');
    if (!isAuthStateValid(stateFile)) {
      const setupContext = await browser.newContext();
      const setupPage = await setupContext.newPage();
      await loginAndSaveState(setupPage, 'outreach');
      await setupContext.close();
    }
    const context = await browser.newContext({ storageState: stateFile });
    const page = await context.newPage();
    await use(page);
    await context.close();
  },
  coordinatorPage: async ({ browser }, use) => {
    const stateFile = path.join(__dirname, '..', 'auth', 'cocadmin.json');
    if (!isAuthStateValid(stateFile)) {
      const setupContext = await browser.newContext();
      const setupPage = await setupContext.newPage();
      await loginAndSaveState(setupPage, 'cocadmin');
      await setupContext.close();
    }
    const context = await browser.newContext({ storageState: stateFile });
    const page = await context.newPage();
    await use(page);
    await context.close();
  },
  adminPage: async ({ browser }, use) => {
    const stateFile = path.join(__dirname, '..', 'auth', 'admin.json');
    if (!isAuthStateValid(stateFile)) {
      const setupContext = await browser.newContext();
      const setupPage = await setupContext.newPage();
      await loginAndSaveState(setupPage, 'admin');
      await setupContext.close();
    }
    const context = await browser.newContext({ storageState: stateFile });
    const page = await context.newPage();
    await use(page);
    await context.close();
  },
});

export { expect };
