import { defineConfig, devices } from '@playwright/test';

// Environment: BASE_URL for frontend, CI auto-detected by GitHub Actions
const isCI = !!process.env.CI;

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 3 : 1,
  reporter: [
    ['html', { open: isCI ? 'never' : 'on-failure' }],
    ['list'],
  ],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:5173',
    trace: isCI ? 'on-first-retry' : 'off',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
