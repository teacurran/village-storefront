describe('Orders Dashboard', () => {
  beforeEach(() => {
    cy.window().then((win) => {
      win.localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'token',
          user: {
            id: 'user-1',
            email: 'admin@example.com',
            roles: ['ORDERS_VIEW', 'ORDERS_EDIT', 'ORDERS_EXPORT'],
            tenantId: 'tenant-1',
          },
        })
      )
    })

    cy.intercept('GET', '/api/v1/tenant/current', {
      id: 'tenant-1',
      name: 'Demo Store',
      featureFlags: { orders: true },
    })

    cy.intercept('GET', '/api/v1/admin/orders*', {
      statusCode: 200,
      body: {
        items: [
          {
            id: 'order-1',
            orderNumber: 'ORD-001',
            customerName: 'Alex Smith',
            customerEmail: 'alex@example.com',
            status: 'PENDING',
            total: { amount: 12000, currency: 'USD' },
            itemCount: 2,
            createdAt: '2026-01-01T12:00:00Z',
            updatedAt: '2026-01-01T12:00:00Z',
            paymentMethod: 'credit_card',
          },
        ],
        total: 1,
      },
    }).as('getOrders')

    cy.intercept('GET', '/api/v1/admin/orders/stats', {
      totalOrders: 25,
      pendingOrders: 2,
      processingOrders: 3,
      shippedOrders: 5,
      revenue: { amount: 2500000, currency: 'USD' },
      avgOrderValue: { amount: 100000, currency: 'USD' },
    })
  })

  it('loads orders and displays metrics', () => {
    cy.visit('/admin/orders')
    cy.wait('@getOrders')
    cy.contains('Orders').should('be.visible')
    cy.contains('Total Orders').should('exist')
    cy.contains('ORD-001').should('exist')
  })

  it('applies status filter', () => {
    cy.visit('/admin/orders')
    cy.wait('@getOrders')
    cy.get('select').select('SHIPPED')
    cy.wait('@getOrders').its('request.url').should('include', 'status=SHIPPED')
  })

  it('hides export for users without permission', () => {
    cy.window().then((win) => {
      win.localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'token',
          user: {
            id: 'user-2',
            email: 'readonly@example.com',
            roles: ['ORDERS_VIEW'],
            tenantId: 'tenant-1',
          },
        })
      )
    })

    cy.visit('/admin/orders')
    cy.contains('Export').should('not.exist')
  })
})
