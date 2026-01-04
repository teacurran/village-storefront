/**
 * Cypress E2E Tests for POS Device Pairing
 * Tests device registration, terminal pairing, and security flows
 */

describe('POS Device Pairing', () => {
  it('should initiate device pairing flow', () => {
    cy.visit('/admin/pos/pairing');

    // Start pairing
    cy.get('[data-test="start-pairing"]').click();

    // Verify pairing code displayed
    cy.get('[data-test="pairing-code"]').should('be.visible');
    cy.get('[data-test="pairing-code"]').should('match', /[A-Z0-9]{6}/);

    // Verify countdown timer
    cy.get('[data-test="pairing-timeout"]').should('be.visible');
  });

  it('should complete device pairing with valid code', () => {
    cy.visit('/admin/pos/pairing');

    // Intercept pairing initiation
    cy.intercept('POST', '**/api/pos/pairing/initiate', {
      statusCode: 200,
      body: {
        pairingCode: 'ABC123',
        expiresAt: Date.now() + 300000, // 5 minutes
      },
    }).as('pairingInitiated');

    cy.get('[data-test="start-pairing"]').click();
    cy.wait('@pairingInitiated');

    // Simulate successful pairing from backend
    cy.intercept('GET', '**/api/pos/pairing/status?code=ABC123', {
      statusCode: 200,
      body: {
        status: 'completed',
        deviceId: 'DEVICE-001',
        deviceToken: 'mock-jwt-token',
      },
    }).as('pairingCompleted');

    // Polling should detect completion
    cy.wait('@pairingCompleted');

    // Verify success message
    cy.get('[data-test="pairing-success"]').should('be.visible');
    cy.get('[data-test="device-id"]').should('contain', 'DEVICE-001');

    // Verify redirect to POS dashboard
    cy.url().should('include', '/admin/pos');
  });

  it('should handle pairing timeout', () => {
    cy.visit('/admin/pos/pairing');

    cy.intercept('POST', '**/api/pos/pairing/initiate', {
      statusCode: 200,
      body: {
        pairingCode: 'XYZ789',
        expiresAt: Date.now() + 5000, // 5 seconds for faster test
      },
    }).as('pairingInitiated');

    cy.get('[data-test="start-pairing"]').click();
    cy.wait('@pairingInitiated');

    // Wait for timeout
    cy.get('[data-test="pairing-timeout-message"]', { timeout: 10000 }).should(
      'be.visible'
    );

    // Verify pairing code cleared
    cy.get('[data-test="pairing-code"]').should('not.exist');

    // Verify can restart pairing
    cy.get('[data-test="start-pairing"]').should('be.visible');
  });

  it('should pair Stripe Terminal device', () => {
    // First complete POS pairing
    cy.loginPOS('TEST-DEVICE-001', '1234');

    // Navigate to terminal pairing
    cy.get('[data-test="settings"]').click();
    cy.get('[data-test="pair-terminal"]').click();

    // Mock Stripe Terminal discovery
    cy.window().then((win: any) => {
      win.mockStripeTerminal = {
        discoverReaders: () =>
          Promise.resolve({
            discoveredReaders: [
              {
                id: 'tmr_test123',
                label: 'BBPOS WisePad 3',
                serialNumber: 'WP123456',
              },
            ],
          }),
        connectReader: () =>
          Promise.resolve({
            reader: {
              id: 'tmr_test123',
              label: 'BBPOS WisePad 3',
            },
          }),
      };
    });

    // Start terminal discovery
    cy.get('[data-test="discover-terminals"]').click();

    // Wait for discovery
    cy.get('[data-test="terminal-list"]', { timeout: 5000 }).should('be.visible');

    // Select terminal
    cy.get('[data-test="terminal-item"]').first().click();

    // Confirm pairing
    cy.get('[data-test="confirm-terminal-pairing"]').click();

    // Verify terminal paired
    cy.get('[data-test="terminal-paired-message"]').should('be.visible');
    cy.get('[data-test="terminal-id"]').should('contain', 'tmr_test123');
  });

  it('should display paired devices list', () => {
    cy.visit('/admin/pos/devices');

    // Mock devices list
    cy.intercept('GET', '**/api/pos/devices', {
      statusCode: 200,
      body: {
        devices: [
          {
            id: 'DEVICE-001',
            name: 'Register 1',
            status: 'active',
            lastSeen: Date.now() - 3600000,
            terminalId: 'tmr_test123',
          },
          {
            id: 'DEVICE-002',
            name: 'Register 2',
            status: 'offline',
            lastSeen: Date.now() - 86400000,
            terminalId: null,
          },
        ],
      },
    }).as('devicesList');

    cy.wait('@devicesList');

    // Verify devices displayed
    cy.get('[data-test="device-row"]').should('have.length', 2);

    // Verify device details
    cy.get('[data-test="device-row"]').first().within(() => {
      cy.get('[data-test="device-name"]').should('contain', 'Register 1');
      cy.get('[data-test="device-status"]').should('contain', 'active');
      cy.get('[data-test="terminal-paired-badge"]').should('be.visible');
    });

    cy.get('[data-test="device-row"]').eq(1).within(() => {
      cy.get('[data-test="device-name"]').should('contain', 'Register 2');
      cy.get('[data-test="device-status"]').should('contain', 'offline');
      cy.get('[data-test="terminal-paired-badge"]').should('not.exist');
    });
  });

  it('should unpair device', () => {
    cy.visit('/admin/pos/devices');

    cy.intercept('DELETE', '**/api/pos/devices/DEVICE-001', {
      statusCode: 200,
      body: { success: true },
    }).as('deviceUnpaired');

    // Click unpair button
    cy.get('[data-test="device-row"]').first().within(() => {
      cy.get('[data-test="unpair-device"]').click();
    });

    // Confirm unpair
    cy.get('[data-test="confirm-unpair"]').click();

    cy.wait('@deviceUnpaired');

    // Verify device removed from list
    cy.get('[data-test="device-row"]').should('have.length', 1);
  });
});
