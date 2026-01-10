describe('Reporting Dashboard', () => {
  beforeEach(() => {
    cy.window().then((win) => {
      win.localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'token',
          user: {
            id: 'user-1',
            email: 'admin@example.com',
            roles: ['REPORTS_VIEW', 'REPORTS_EXPORT'],
            tenantId: 'tenant-1',
          },
        })
      )
    })

    cy.intercept('GET', '/api/v1/tenant/current', {
      id: 'tenant-1',
      name: 'Demo Store',
      featureFlags: {},
    })

    cy.intercept('GET', '/api/v1/admin/reports/aggregates/sales*', [
      {
        id: 'agg-1',
        periodStart: '2026-01-01',
        periodEnd: '2026-01-07',
        totalAmount: 12000,
        orderCount: 15,
      },
    ]).as('getSales')

    cy.intercept('GET', '/api/v1/admin/reports/aggregates/inventory-aging', [
      {
        id: 'inv-1',
        variant: { sku: 'SKU-1', name: 'Product A' },
        location: { name: 'Main' },
        quantity: 5,
        daysInStock: 45,
      },
    ])

    cy.intercept('GET', '/api/v1/admin/reports/jobs', {
      jobs: [
        {
          jobId: 'job-1',
          reportType: 'sales',
          status: 'completed',
          createdAt: '2026-01-02T12:00:00Z',
          downloadUrl: 'https://example.com/report.csv',
        },
      ],
    }).as('getJobs')
  })

  it('renders KPI cards and slow movers', () => {
    cy.visit('/admin/reports')
    cy.wait('@getSales')
    cy.contains('Reports & Analytics').should('be.visible')
    cy.contains('Total Revenue').should('be.visible')
    cy.contains('SKU-1').should('be.visible')
  })

  it('requests export when export button clicked', () => {
    cy.intercept('POST', '/api/v1/admin/reports/sales/export', {
      statusCode: 202,
      body: { jobId: 'job-2', status: 'pending' },
    }).as('exportReport')

    cy.visit('/admin/reports')
    cy.wait('@getSales')
    cy.contains('button', 'Export Report').click()
    cy.wait('@exportReport').its('request.body').should('include', { format: 'csv' })
  })
})
