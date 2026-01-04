/**
 * Cypress E2E Tests for POS Offline Mode
 * Tests offline queue management, device pairing, and sync scenarios
 */

describe('POS Offline Mode', () => {
  beforeEach(() => {
    // Login to POS
    cy.loginPOS('TEST-DEVICE-001', '1234');
  });

  it('should queue transactions while offline', () => {
    // Start a transaction
    cy.get('[data-test="new-transaction"]').click();
    cy.get('[data-test="product-search"]').type('test product');
    cy.get('[data-test="product-result"]').first().click();
    cy.get('[data-test="add-to-cart"]').click();

    // Go offline
    cy.goOffline();

    // Verify offline indicator appears
    cy.get('[data-test="offline-indicator"]').should('be.visible');

    // Complete the transaction
    cy.get('[data-test="checkout"]').click();
    cy.get('[data-test="payment-cash"]').click();
    cy.get('[data-test="amount-tendered"]').type('20.00');
    cy.get('[data-test="complete-transaction"]').click();

    // Verify transaction queued
    cy.get('[data-test="transaction-queued-message"]').should('be.visible');
    cy.get('[data-test="queue-count"]').should('contain', '1');

    // Verify transaction in IndexedDB
    cy.getIndexedDB('pending_transactions').then((transactions) => {
      expect(transactions).to.have.length(1);
      expect(transactions[0]).to.have.property('status', 'pending');
    });
  });

  it('should sync queued transactions when online', () => {
    // Seed offline queue with test transactions
    const testTransactions = [
      {
        id: 'offline-txn-001',
        timestamp: Date.now(),
        items: [{ productId: 'prod-123', quantity: 1, price: 10.99 }],
        total: 10.99,
        paymentMethod: 'cash',
        status: 'pending',
      },
      {
        id: 'offline-txn-002',
        timestamp: Date.now(),
        items: [{ productId: 'prod-456', quantity: 2, price: 15.5 }],
        total: 31.0,
        paymentMethod: 'card',
        status: 'pending',
      },
    ];

    cy.seedOfflineQueue(testTransactions);

    // Verify queue count
    cy.get('[data-test="queue-count"]').should('contain', '2');

    // Go online
    cy.goOnline();

    // Verify online indicator
    cy.get('[data-test="online-indicator"]').should('be.visible');

    // Trigger sync
    cy.get('[data-test="sync-button"]').click();

    // Wait for sync to complete
    cy.get('[data-test="sync-progress"]', { timeout: 10000 }).should('not.exist');

    // Verify queue cleared
    cy.get('[data-test="queue-count"]').should('contain', '0');

    // Verify transactions synced to server
    cy.getIndexedDB('pending_transactions').then((transactions) => {
      expect(transactions).to.have.length(0);
    });
  });

  it('should handle sync failures gracefully', () => {
    // Seed queue with a transaction that will fail validation
    const invalidTransaction = [
      {
        id: 'invalid-txn-001',
        timestamp: Date.now(),
        items: [], // Invalid: no items
        total: 0,
        paymentMethod: 'cash',
        status: 'pending',
      },
    ];

    cy.seedOfflineQueue(invalidTransaction);

    // Intercept sync API call to simulate failure
    cy.intercept('POST', '**/api/pos/offline/sync', {
      statusCode: 422,
      body: {
        error: 'Validation failed',
        details: 'Transaction must contain at least one item',
      },
    }).as('syncFailed');

    cy.goOnline();
    cy.get('[data-test="sync-button"]').click();

    // Wait for sync attempt
    cy.wait('@syncFailed');

    // Verify error message displayed
    cy.get('[data-test="sync-error"]').should('be.visible');
    cy.get('[data-test="sync-error"]').should('contain', 'Validation failed');

    // Verify transaction still in queue
    cy.get('[data-test="queue-count"]').should('contain', '1');
  });

  it('should display sync status for each transaction', () => {
    const transactions = [
      {
        id: 'txn-001',
        timestamp: Date.now() - 3600000, // 1 hour ago
        items: [{ productId: 'prod-123', quantity: 1, price: 10.99 }],
        total: 10.99,
        paymentMethod: 'cash',
        status: 'pending',
      },
      {
        id: 'txn-002',
        timestamp: Date.now() - 1800000, // 30 minutes ago
        items: [{ productId: 'prod-456', quantity: 1, price: 25.0 }],
        total: 25.0,
        paymentMethod: 'card',
        status: 'pending',
      },
    ];

    cy.seedOfflineQueue(transactions);

    // Open queue viewer
    cy.get('[data-test="view-queue"]').click();

    // Verify both transactions listed
    cy.get('[data-test="queue-item"]').should('have.length', 2);

    // Verify transaction details displayed
    cy.get('[data-test="queue-item"]').first().within(() => {
      cy.get('[data-test="transaction-id"]').should('contain', 'txn-001');
      cy.get('[data-test="transaction-total"]').should('contain', '$10.99');
      cy.get('[data-test="transaction-status"]').should('contain', 'pending');
    });
  });

  it('should allow manual retry of failed transactions', () => {
    const failedTransaction = [
      {
        id: 'failed-txn-001',
        timestamp: Date.now(),
        items: [{ productId: 'prod-789', quantity: 1, price: 50.0 }],
        total: 50.0,
        paymentMethod: 'card',
        status: 'failed',
        error: 'Network timeout',
      },
    ];

    cy.seedOfflineQueue(failedTransaction);

    // Open queue viewer
    cy.get('[data-test="view-queue"]').click();

    // Find failed transaction and retry
    cy.get('[data-test="queue-item"]').first().within(() => {
      cy.get('[data-test="transaction-status"]').should('contain', 'failed');
      cy.get('[data-test="retry-button"]').click();
    });

    // Intercept retry API call
    cy.intercept('POST', '**/api/pos/offline/sync', {
      statusCode: 200,
      body: { success: true, syncedIds: ['failed-txn-001'] },
    }).as('retrySuccess');

    cy.wait('@retrySuccess');

    // Verify transaction removed from queue
    cy.get('[data-test="queue-item"]').should('have.length', 0);
  });
});
