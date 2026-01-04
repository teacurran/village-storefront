import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Village Storefront E2E tests
 * Covers storefront checkout, admin flows, and platform console
 */
export default defineConfig({
  testDir: './',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI
    ? [
        ['html', { outputFolder: '../../../target/playwright-report' }],
        ['json', { outputFile: '../../../target/playwright-results.json' }],
        ['junit', { outputFile: '../../../target/playwright-junit.xml' }],
      ]
    : 'html',
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'mobile-safari',
      use: { ...devices['iPhone 12'] },
    },
  ],
  webServer: process.env.CI
    ? undefined
    : {
        command: 'cd ../../.. && ./mvnw quarkus:dev',
        url: 'http://localhost:8080',
        reuseExistingServer: true,
        timeout: 120 * 1000,
      },
});
