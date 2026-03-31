import { defineConfig, devices } from '@playwright/test';

// Environment: BASE_URL for frontend, CI auto-detected by GitHub Actions
const isCI = !!process.env.CI;

// Nginx project is opt-in: npx playwright test --project=nginx
// Default 'npx playwright test' runs only chromium (Vite :5173)
const includeNginx = process.env.NGINX === '1';

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
    // Nginx proxy project — only included when NGINX=1 env var is set
    // Usage: NGINX=1 npx playwright test --project=nginx
    ...(includeNginx ? [{
      name: 'nginx',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://localhost:8081',
      },
    }] : []),
  ],
});
