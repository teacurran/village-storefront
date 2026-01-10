/**
 * E2E Tests for POS Offline Mode
 *
 * Tests cover:
 * - Device pairing workflow
 * - Offline transaction queuing
 * - Queue synchronization
 * - Accessibility requirements
 * - Cart management
 * - Payment tender entry
 */

describe('POS Offline Mode', () => {
  beforeEach(() => {
    cy.visit('/admin/pos')
    // Mock authentication
    cy.window().then((win) => {
      win.localStorage.setItem('auth_token', 'mock-jwt-token')
      win.localStorage.setItem('user', JSON.stringify({ id: 1, email: 'staff@example.com' }))
    })
  })

  describe('Device Pairing', () => {
    it('shows pairing form when device not paired', () => {
      cy.contains('Complete Device Pairing').should('be.visible')
      cy.get('input#pairing-code').should('be.visible')
      cy.get('button').contains('Pair Device').should('be.visible')
    })

    it('completes device pairing successfully', () => {
      // Mock pairing response
      cy.intercept('POST', '/api/pos/devices/complete-pairing', {
        statusCode: 200,
        body: {
          deviceId: 1,
          deviceName: 'Test POS Terminal',
          encryptionKey: 'dGVzdC1lbmNyeXB0aW9uLWtleS1iYXNlNjQ=',
          encryptionKeyVersion: 1,
          stripeConnectionToken: 'pst_test_mock_token_12345',
          stripeTokenExpiresAt: new Date(Date.now() + 86400000).toISOString()
        }
      }).as('completePairing')

      cy.get('input#pairing-code').type('TEST1234')
      cy.get('button').contains('Pair Device').click()

      cy.wait('@completePairing')

      cy.contains('Device Paired').should('be.visible')
      cy.contains('Test POS Terminal').should('be.visible')
      cy.contains('Stripe Terminal Token').should('be.visible')
    })

    it('shows error for invalid pairing code', () => {
      cy.intercept('POST', '/api/pos/devices/complete-pairing', {
        statusCode: 404,
        body: { message: 'Pairing code not found or expired' }
      }).as('failedPairing')

      cy.get('input#pairing-code').type('INVALID1')
      cy.get('button').contains('Pair Device').click()

      cy.wait('@failedPairing')

      cy.contains('Pairing code not found or expired').should('be.visible')
    })
  })

  describe('Cart Management', () => {
    beforeEach(() => {
      // Setup paired device
      cy.window().then((win) => {
        const mockDevice = {
          deviceId: 1,
          deviceName: 'Test POS',
          status: 'paired',
          pairedAt: new Date().toISOString()
        }
        win.localStorage.setItem('pos_device', JSON.stringify(mockDevice))
        win.localStorage.setItem('pos_encryption_key', 'mock-key-base64')
        win.localStorage.setItem('pos_key_version', '1')
      })
      cy.reload()
    })

    it('displays empty cart initially', () => {
      cy.contains('Cart is empty').should('be.visible')
    })

    it('searches for products', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').should('be.visible')
      cy.contains('$29.99').should('be.visible')
    })

    it('adds product to cart', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      cy.contains('Added to Cart').should('be.visible')
      cy.get('.cart-item').should('have.length', 1)
      cy.contains('Widget Pro').should('be.visible')
    })

    it('increments item quantity', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      cy.get('.qty-btn').contains('+').click()
      cy.get('.quantity').should('contain', '2')
      cy.get('.cart-total').should('contain', '$59.98')
    })

    it('decrements item quantity', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      cy.get('.qty-btn').contains('+').click()
      cy.get('.qty-btn').contains('-').click()
      cy.get('.quantity').should('contain', '1')
    })

    it('removes item from cart', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      cy.get('.cart-item button[aria-label="Remove item from cart"]').click()
      cy.contains('Cart is empty').should('be.visible')
    })

    it('clears entire cart', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()
      cy.get('.search-input').type('Gadget')
      cy.contains('Gadget Plus').click()

      cy.get('.cart-item').should('have.length', 2)

      cy.get('button[aria-label="Clear cart"]').click()
      cy.contains('Cart is empty').should('be.visible')
    })

    it('calculates cart total correctly', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click() // $29.99
      cy.get('.search-input').type('Gadget')
      cy.contains('Gadget Plus').click() // $49.99

      cy.get('.cart-total').should('contain', '$79.98')
    })
  })

  describe('Offline Transaction Queuing', () => {
    beforeEach(() => {
      // Setup paired device
      cy.window().then((win) => {
        const mockDevice = {
          deviceId: 1,
          deviceName: 'Test POS',
          status: 'paired',
          pairedAt: new Date().toISOString()
        }
        win.localStorage.setItem('pos_device', JSON.stringify(mockDevice))
        win.localStorage.setItem('pos_encryption_key', 'dGVzdC1lbmNyeXB0aW9uLWtleS1iYXNlNjQ=')
        win.localStorage.setItem('pos_key_version', '1')
      })
      cy.reload()
    })

    it('queues transaction when offline', () => {
      // Simulate offline
      cy.window().then((win) => {
        Object.defineProperty(win.navigator, 'onLine', {
          value: false,
          writable: true,
          configurable: true
        })
        win.dispatchEvent(new Event('offline'))
      })

      // Wait for offline indicator
      cy.contains('Offline', { timeout: 2000 }).should('be.visible')

      // Add item to cart
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      // Select payment method
      cy.get('#payment-method').select('cash')

      // Complete sale
      cy.contains('Complete Sale').click()

      // Verify queued
      cy.contains('Sale Queued').should('be.visible')
      cy.contains('will sync when online').should('be.visible')
    })

    it('shows offline indicator when network lost', () => {
      cy.window().then((win) => {
        Object.defineProperty(win.navigator, 'onLine', {
          value: false,
          writable: true,
          configurable: true
        })
        win.dispatchEvent(new Event('offline'))
      })

      cy.get('.offline-indicator').should('be.visible')
      cy.contains('Offline').should('be.visible')
    })
  })

  describe('Queue Synchronization', () => {
    beforeEach(() => {
      // Setup paired device with queued transactions
      cy.window().then((win) => {
        const mockDevice = {
          deviceId: 1,
          deviceName: 'Test POS',
          status: 'paired',
          pairedAt: new Date().toISOString()
        }
        win.localStorage.setItem('pos_device', JSON.stringify(mockDevice))
        win.localStorage.setItem('pos_encryption_key', 'dGVzdC1lbmNyeXB0aW9uLWtleS1iYXNlNjQ=')
        win.localStorage.setItem('pos_key_version', '1')
      })
      cy.reload()
    })

    it('syncs queue when online', () => {
      // Mock sync endpoint
      cy.intercept('POST', '/api/pos/offline/upload', {
        statusCode: 200,
        body: {
          enqueued: 1,
          duplicates: 0,
          errors: []
        }
      }).as('syncQueue')

      // Add transaction to queue first
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()
      cy.get('#payment-method').select('cash')
      cy.contains('Complete Sale').click()

      // Wait for transaction to queue
      cy.wait(500)

      // Trigger sync
      cy.contains('Sync Now').click()

      // Verify sync called
      cy.wait('@syncQueue')
      cy.contains('synced successfully').should('be.visible')
    })

    it('shows sync progress', () => {
      cy.intercept('POST', '/api/pos/offline/upload', {
        statusCode: 200,
        delay: 1000,
        body: { enqueued: 1, duplicates: 0 }
      }).as('slowSync')

      // Queue a transaction
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()
      cy.get('#payment-method').select('cash')
      cy.contains('Complete Sale').click()

      // Trigger sync
      cy.contains('Sync Now').click()

      // Should show syncing state
      cy.contains('Syncing').should('be.visible')
    })

    it('handles sync errors gracefully', () => {
      cy.intercept('POST', '/api/pos/offline/upload', {
        statusCode: 500,
        body: { message: 'Server error' }
      }).as('failedSync')

      // Queue a transaction
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()
      cy.get('#payment-method').select('cash')
      cy.contains('Complete Sale').click()

      cy.contains('Sync Now').click()

      cy.wait('@failedSync')
      cy.contains('Sync failed').should('be.visible')
    })
  })

  describe('Accessibility Requirements', () => {
    beforeEach(() => {
      cy.window().then((win) => {
        const mockDevice = {
          deviceId: 1,
          deviceName: 'Test POS',
          status: 'paired',
          pairedAt: new Date().toISOString()
        }
        win.localStorage.setItem('pos_device', JSON.stringify(mockDevice))
      })
      cy.reload()
    })

    it('all buttons meet minimum touch target size (48px)', () => {
      cy.get('button').each(($btn) => {
        cy.wrap($btn).invoke('outerHeight').should('be.gte', 48)
      })
    })

    it('focus states are visible on interactive elements', () => {
      // Tab through interactive elements
      cy.get('.search-input').focus()
      cy.get('.search-input').should('have.css', 'outline-width').and('match', /2px/)

      cy.get('button').first().focus()
      cy.get('button').first().should('have.css', 'outline-width')
    })

    it('all interactive elements have ARIA labels', () => {
      // Search input
      cy.get('.search-input').should('have.attr', 'aria-label')

      // Payment select
      cy.get('#payment-method').should('have.attr', 'aria-label')

      // Buttons with icons only
      cy.get('button[aria-label]').should('exist')
    })

    it('supports keyboard navigation', () => {
      // Add product via keyboard
      cy.get('.search-input').type('Widget')
      cy.get('.search-result-item').first().trigger('keydown', { key: 'Enter' })

      cy.contains('Added to Cart').should('be.visible')
    })

    it('complete sale button is properly labeled', () => {
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()

      cy.get('button[aria-label="Complete sale"]').should('exist')
    })
  })

  describe('Payment Tender Entry', () => {
    beforeEach(() => {
      cy.window().then((win) => {
        const mockDevice = {
          deviceId: 1,
          deviceName: 'Test POS',
          status: 'paired',
          pairedAt: new Date().toISOString()
        }
        win.localStorage.setItem('pos_device', JSON.stringify(mockDevice))
      })
      cy.reload()

      // Add item to cart
      cy.get('.search-input').type('Widget')
      cy.contains('Widget Pro').click()
    })

    it('tender section only shows when cart has items', () => {
      cy.contains('Tender').should('be.visible')

      // Clear cart
      cy.get('button[aria-label="Clear cart"]').click()
      cy.contains('Tender').should('not.exist')
    })

    it('shows payment method options', () => {
      cy.get('#payment-method').select('cash')
      cy.get('#payment-method').should('have.value', 'cash')

      cy.get('#payment-method').select('card')
      cy.get('#payment-method').should('have.value', 'card')

      cy.get('#payment-method').select('terminal')
      cy.get('#payment-method').should('have.value', 'terminal')
    })

    it('complete sale button disabled without payment method', () => {
      cy.get('button[aria-label="Complete sale"]').should('be.disabled')
    })

    it('complete sale button enabled with payment method', () => {
      cy.get('#payment-method').select('cash')
      cy.get('button[aria-label="Complete sale"]').should('not.be.disabled')
    })

    it('shows processing state during transaction', () => {
      cy.intercept('POST', '/api/pos/offline/upload', {
        statusCode: 200,
        delay: 1000,
        body: { enqueued: 1, duplicates: 0 }
      })

      cy.get('#payment-method').select('cash')
      cy.get('button[aria-label="Complete sale"]').click()

      cy.contains('Processing...').should('be.visible')
    })
  })
})
