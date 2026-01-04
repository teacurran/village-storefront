import { defineConfig } from 'cypress';

/**
 * Cypress configuration for Village Storefront
 * Focused on POS offline scenarios and admin component testing
 */
export default defineConfig({
  e2e: {
    baseUrl: process.env.BASE_URL || 'http://localhost:8080',
    supportFile: './support/e2e.ts',
    specPattern: 'e2e/**/*.cy.ts',
    videosFolder: '../../target/cypress-videos',
    screenshotsFolder: '../../target/cypress-screenshots',
    video: !!process.env.CI,
    screenshotOnRunFailure: true,
    viewportWidth: 1280,
    viewportHeight: 720,
    retries: {
      runMode: 2,
      openMode: 0,
    },
    env: {
      apiUrl: process.env.API_URL || 'http://localhost:8080/api',
    },
  },
  component: {
    devServer: {
      framework: 'vue',
      bundler: 'vite',
    },
    supportFile: './support/component.ts',
    specPattern: 'component/**/*.cy.ts',
    videosFolder: '../../target/cypress-videos',
    screenshotsFolder: '../../target/cypress-screenshots',
  },
});
