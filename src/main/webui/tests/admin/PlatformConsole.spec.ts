describe('Platform Console', () => {
  const platformFeatureFlags = {
    id: 'platform-root',
    name: 'Platform Admin',
    featureFlags: { platformConsole: true },
  }

  function seedAuth(roles: string[]) {
    cy.window().then((win) => {
      win.localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'token',
          user: {
            id: 'platform-user',
            email: 'ops@example.com',
            roles,
            tenantId: 'platform',
          },
        })
      )
    })
  }

  it('loads store directory when platform role present', () => {
    seedAuth(['PLATFORM_ADMIN'])

    cy.intercept('GET', '/api/v1/tenant/current', platformFeatureFlags)
    cy.intercept('GET', '/api/v1/platform/stores*', {
      statusCode: 200,
      body: {
        stores: [
          {
            id: 'tenant-1',
            subdomain: 'demo',
            name: 'Demo Store',
            status: 'active',
            userCount: 12,
            activeUserCount: 10,
            createdAt: '2026-01-01T00:00:00Z',
            lastActivityAt: '2026-01-10T00:00:00Z',
            plan: 'pro',
            customDomainConfigured: true,
          },
        ],
        page: 0,
        size: 20,
        totalCount: 1,
      },
    }).as('getStores')

    cy.visit('/admin/platform/stores')
    cy.wait('@getStores')
    cy.contains('Store Directory').should('exist')
    cy.contains('Demo Store').should('exist')

    cy.get('.search-input').type('Demo')
    cy.wait('@getStores').its('request.url').should('include', 'search=Demo')
  })

  it('blocks platform routes when user lacks role', () => {
    seedAuth(['REPORTING_VIEW'])
    cy.intercept('GET', '/api/v1/tenant/current', platformFeatureFlags)

    cy.visit('/admin/platform/stores')
    cy.location('pathname').should('eq', '/admin')
  })

  it('enforces ticket requirement in impersonation form', () => {
    seedAuth(['PLATFORM_ADMIN'])
    cy.intercept('GET', '/api/v1/tenant/current', platformFeatureFlags)
    cy.intercept('POST', '/api/v1/platform/impersonate', {
      statusCode: 200,
      body: {
        sessionId: 'session-1',
        platformAdminId: 'platform-user',
        platformAdminEmail: 'ops@example.com',
        targetTenantId: 'tenant-1',
        targetTenantName: 'Demo Store',
        reason: 'Support ticket #1',
        ticketNumber: 'TICKET-1',
        startedAt: '2026-02-01T00:00:00Z',
      },
    }).as('startImpersonation')
    cy.intercept('DELETE', '/api/v1/platform/impersonate', { statusCode: 200 }).as('endImpersonation')

    cy.visit('/admin/platform/impersonation')
    cy.get('button[type="submit"]').should('be.disabled')
    cy.get('input[placeholder="UUID of tenant to impersonate"]').type('tenant-1')
    cy.get('textarea').type('Support ticket #1 investigating fraud')
    cy.get('button[type="submit"]').should('be.disabled')
    cy.get('input[placeholder="e.g. TICKET-12345"]').type('TICKET-1')
    cy.get('button[type="submit"]').should('not.be.disabled').click()
    cy.wait('@startImpersonation')

    cy.contains('Active Session').should('exist')

    cy.contains('End impersonation').click()
    cy.wait('@endImpersonation')
  })

  it('filters audit logs by action', () => {
    seedAuth(['PLATFORM_ADMIN'])
    cy.intercept('GET', '/api/v1/tenant/current', platformFeatureFlags)
    cy.intercept('GET', '/api/v1/platform/audit*', {
      statusCode: 200,
      body: {
        entries: [
          {
            id: 'audit-1',
            actorType: 'platform_admin',
            actorEmail: 'ops@example.com',
            action: 'suspend_store',
            targetType: 'tenant',
            targetId: 'tenant-1',
            reason: 'Fraud investigation',
            ticketNumber: 'TICKET-9',
            occurredAt: '2026-02-02T12:00:00Z',
          },
        ],
        page: 0,
        size: 50,
        totalCount: 1,
      },
    }).as('getAudit')

    cy.visit('/admin/platform/audit')
    cy.wait('@getAudit')
    cy.contains('suspend_store').should('exist')

    cy.get('select').first().select('impersonate_start')
    cy.contains('Apply Filters').click()
    cy.wait('@getAudit').its('request.url').should('include', 'action=impersonate_start')
  })
})
