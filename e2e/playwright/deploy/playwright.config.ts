import { defineConfig, devices } from '@playwright/test';

/**
 * Deployment verification config — isolated from the main test suite.
 *
 * These specs target specific release versions or the live demo site.
 * They are NOT part of the regression suite and should never run via
 * the default `npx playwright test` command.
 *
 * Usage:
 *   cd e2e/playwright
 *   BASE_URL=https://findabed.org npx playwright test --config=deploy/playwright.config.ts --reporter=list --trace on
 *
 * To run a specific version check:
 *   BASE_URL=https://findabed.org npx playwright test --config=deploy/playwright.config.ts deploy-verify-v0.29.5 --trace on
 */
export default defineConfig({
  testDir: '.',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL || 'https://findabed.org',
    trace: 'on',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
