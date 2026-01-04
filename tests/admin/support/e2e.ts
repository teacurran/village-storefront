/**
 * Cypress E2E support file
 * Load custom commands and global configuration
 */

// Import custom commands
import './commands';

// Global before hook for all tests
beforeEach(() => {
  // Clear IndexedDB for POS offline tests
  cy.clearLocalStorage();
  cy.clearCookies();
});

// Global error handling
Cypress.on('uncaught:exception', (err, runnable) => {
  // Prevent test failures from unhandled promise rejections in app code
  // Return false to prevent the error from failing the test
  if (err.message.includes('ResizeObserver')) {
    return false;
  }
  return true;
});
