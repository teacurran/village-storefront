/**
 * Catalog E2E Tests
 *
 * End-to-end tests for catalog management workflows including
 * product listing, creation, editing, and deletion.
 */

describe('Catalog Management', () => {
  beforeEach(() => {
    // Mock authentication
    cy.window().then((win) => {
      win.localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'mock-token',
          refreshToken: 'mock-refresh',
          tokenExpiresAt: Date.now() + 900000,
          user: {
            id: '1',
            email: 'admin@test.com',
            firstName: 'Admin',
            lastName: 'User',
            roles: ['ADMIN'],
            tenantId: 'tenant-1',
          },
        })
      )
    })

    // Intercept API calls
    cy.intercept('GET', '/api/v1/admin/catalog/products*', {
      statusCode: 200,
      body: {
        items: [
          {
            id: '1',
            name: 'Test Product 1',
            slug: 'test-product-1',
            sku: 'TEST-001',
            status: 'ACTIVE',
            price: { value: 1999, currency: 'USD' },
            trackInventory: true,
            totalInventory: 50,
            lowStockThreshold: 10,
            variantCount: 0,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
          },
          {
            id: '2',
            name: 'Test Product 2',
            slug: 'test-product-2',
            sku: 'TEST-002',
            status: 'DRAFT',
            price: { value: 2999, currency: 'USD' },
            trackInventory: false,
            totalInventory: 0,
            variantCount: 2,
            createdAt: '2024-01-02T00:00:00Z',
            updatedAt: '2024-01-02T00:00:00Z',
          },
        ],
        pagination: {
          page: 1,
          pageSize: 20,
          totalItems: 2,
          totalPages: 1,
        },
      },
    }).as('getProducts')

    cy.intercept('GET', '/api/v1/admin/catalog/categories', {
      statusCode: 200,
      body: [
        { id: 'cat-1', name: 'Clothing', slug: 'clothing', sortOrder: 1 },
        { id: 'cat-2', name: 'Accessories', slug: 'accessories', sortOrder: 2 },
      ],
    }).as('getCategories')

    cy.visit('/admin/catalog/products')
  })

  describe('Product List', () => {
    it('should display product table', () => {
      cy.wait('@getProducts')

      // Check table is visible
      cy.get('[data-cy="product-table"]').should('be.visible')

      // Check products are displayed
      cy.contains('Test Product 1').should('be.visible')
      cy.contains('Test Product 2').should('be.visible')
      cy.contains('TEST-001').should('be.visible')
    })

    it('should filter products by search', () => {
      cy.wait('@getProducts')

      // Enter search term
      cy.get('input[placeholder*="Search"]').type('Product 1')

      // API should be called with search parameter
      cy.wait('@getProducts').its('request.url').should('include', 'q=Product+1')
    })

    it('should filter products by status', () => {
      cy.wait('@getProducts')

      // Select status filter
      cy.get('select').contains('All Statuses').parent().select('active')

      // API should be called with status filter
      cy.wait('@getProducts').its('request.url').should('include', 'status=active')
    })

    it('should clear filters', () => {
      cy.wait('@getProducts')

      // Set filters
      cy.get('input[placeholder*="Search"]').type('test')
      cy.get('select').contains('All Statuses').parent().select('active')

      // Clear filters
      cy.contains('Clear Filters').click()

      // Inputs should be cleared
      cy.get('input[placeholder*="Search"]').should('have.value', '')
      cy.get('select').contains('All Statuses').parent().should('have.value', '')
    })

    it('should navigate to new product page', () => {
      cy.wait('@getProducts')

      // Click new product button
      cy.contains('New Product').click()

      // Should navigate to new product page
      cy.url().should('include', '/catalog/products/new')
    })

    it('should navigate to edit product page', () => {
      cy.wait('@getProducts')

      // Click edit button for first product
      cy.get('button[title="Edit"]').first().click()

      // Should navigate to edit page
      cy.url().should('include', '/catalog/products/1')
    })

    it('should delete product with confirmation', () => {
      cy.wait('@getProducts')

      // Mock delete API
      cy.intercept('DELETE', '/api/v1/admin/catalog/products/1', {
        statusCode: 204,
      }).as('deleteProduct')

      // Click delete button
      cy.get('button[title="Delete"]').first().click()

      // Confirm deletion
      cy.on('window:confirm', () => true)

      // Wait for delete request
      cy.wait('@deleteProduct')

      // Product should be removed from list
      // (This would be visible in the UI update after re-render)
    })
  })

  describe('Product Editor', () => {
    beforeEach(() => {
      cy.intercept('GET', '/api/v1/admin/catalog/products/1', {
        statusCode: 200,
        body: {
          id: '1',
          name: 'Test Product',
          slug: 'test-product',
          sku: 'TEST-001',
          description: 'A test product',
          status: 'ACTIVE',
          price: { value: 1999, currency: 'USD' },
          trackInventory: true,
          totalInventory: 50,
          lowStockThreshold: 10,
          categoryId: 'cat-1',
        },
      }).as('getProduct')

      cy.visit('/admin/catalog/products/1')
    })

    it('should load product details', () => {
      cy.wait('@getProduct')
      cy.wait('@getCategories')

      // Check form is populated
      cy.get('input[placeholder*="Classic Cotton"]').should('have.value', 'Test Product')
      cy.get('input[type="number"][step="0.01"]').first().should('have.value', '19.99')
      cy.get('input[placeholder*="SHIRT-BLK"]').should('have.value', 'TEST-001')
    })

    it('should validate required fields', () => {
      cy.wait('@getProduct')
      cy.wait('@getCategories')

      // Clear product name
      cy.get('input[placeholder*="Classic Cotton"]').clear()

      // Try to save
      cy.contains('Save Product').click()

      // Should show validation error
      cy.contains('Product name is required').should('be.visible')
    })

    it('should save product changes', () => {
      cy.wait('@getProduct')
      cy.wait('@getCategories')

      // Mock save API
      cy.intercept('PUT', '/api/v1/admin/catalog/products/1', {
        statusCode: 200,
        body: {
          id: '1',
          name: 'Updated Product',
          status: 'ACTIVE',
        },
      }).as('updateProduct')

      // Update product name
      cy.get('input[placeholder*="Classic Cotton"]').clear().type('Updated Product')

      // Save
      cy.contains('Save Product').click()

      // Wait for save request
      cy.wait('@updateProduct')

      // Should redirect to product list
      cy.url().should('include', '/catalog/products')
      cy.url().should('not.include', '/catalog/products/1')
    })

    it('should cancel editing', () => {
      cy.wait('@getProduct')
      cy.wait('@getCategories')

      // Click cancel
      cy.contains('Cancel').click()

      // Confirm navigation
      cy.on('window:confirm', () => true)

      // Should redirect to product list
      cy.url().should('include', '/catalog/products')
      cy.url().should('not.include', '/catalog/products/1')
    })
  })

  describe('Product Creation', () => {
    beforeEach(() => {
      cy.visit('/admin/catalog/products/new')
      cy.wait('@getCategories')
    })

    it('should create new product', () => {
      // Mock create API
      cy.intercept('POST', '/api/v1/admin/catalog/products', {
        statusCode: 201,
        body: {
          id: '3',
          name: 'New Product',
          slug: 'new-product',
          status: 'DRAFT',
        },
      }).as('createProduct')

      // Fill form
      cy.get('input[placeholder*="Classic Cotton"]').type('New Product')
      cy.get('input[type="number"][step="0.01"]').first().type('29.99')
      cy.get('input[placeholder*="SHIRT-BLK"]').type('NEW-001')
      cy.get('textarea').type('A new product description')

      // Select category
      cy.get('select').contains('No category').parent().select('cat-1')

      // Save
      cy.contains('Save Product').click()

      // Wait for create request
      cy.wait('@createProduct')

      // Should redirect to product list
      cy.url().should('include', '/catalog/products')
      cy.url().should('not.include', '/new')
    })

    it('should require product name', () => {
      // Try to save without name
      cy.contains('Save Product').click()

      // Should show validation error
      cy.contains('Product name is required').should('be.visible')
    })

    it('should generate slug from name', () => {
      // Mock create API to verify slug generation
      cy.intercept('POST', '/api/v1/admin/catalog/products', (req) => {
        expect(req.body.slug).to.equal('test-product-name')
        req.reply({
          statusCode: 201,
          body: { id: '3', name: 'Test Product Name' },
        })
      }).as('createProduct')

      // Fill name
      cy.get('input[placeholder*="Classic Cotton"]').type('Test Product Name')
      cy.get('input[type="number"][step="0.01"]').first().type('10.00')

      // Save
      cy.contains('Save Product').click()

      cy.wait('@createProduct')
    })
  })
})
