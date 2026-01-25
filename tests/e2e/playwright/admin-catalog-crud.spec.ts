import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';

/**
 * Admin Catalog CRUD E2E Tests
 * Tests product creation, editing, variant management, and bulk import
 */
test.describe('Admin Catalog CRUD', () => {
  test.describe('Tenant A - Product Management', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      const loginPage = new AdminLoginPage(page);
      await page.goto(`${baseURL}/admin/login`);
      await loginPage.login(tenant.admin.email, tenant.admin.password);
    });

    test('should create new product without variants', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      // Navigate to products section
      await dashboard.navigateToProducts();

      // Click create new product
      await page.click('[data-test="create-product-button"]');

      // Fill product form
      await page.fill('[data-test="product-name"]', 'Test Product - E2E');
      await page.fill('[data-test="product-description"]', 'Created via E2E test');
      await page.fill('[data-test="product-sku"]', `TA-TEST-${Date.now()}`);
      await page.fill('[data-test="product-price"]', '49.99');
      await page.fill('[data-test="product-inventory"]', '100');

      // Select category
      await page.selectOption('[data-test="product-category"]', 'Clothing');

      // Save product
      await page.click('[data-test="save-product-button"]');

      // Wait for success notification
      await expect(page.locator('[data-test="product-save-success"]')).toBeVisible();

      // Verify product appears in list
      await dashboard.navigateToProducts();
      await expect(page.locator('text=Test Product - E2E')).toBeVisible();
    });

    test('should create product with variants (size and color)', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Create new product
      await page.click('[data-test="create-product-button"]');

      await page.fill('[data-test="product-name"]', 'E2E Variant Product');
      await page.fill('[data-test="product-description"]', 'Product with size/color variants');
      await page.fill('[data-test="product-sku"]', `TA-VAR-${Date.now()}`);
      await page.fill('[data-test="product-price"]', '59.99');

      // Enable variants
      await page.check('[data-test="enable-variants-toggle"]');

      // Add variant attributes
      await page.click('[data-test="add-variant-attribute"]');
      await page.selectOption('[data-test="variant-attribute-type"]', 'Size');
      await page.fill('[data-test="variant-attribute-values"]', 'S, M, L, XL');

      await page.click('[data-test="add-variant-attribute"]');
      await page.selectOption('[data-test="variant-attribute-type"]', 'Color');
      await page.fill('[data-test="variant-attribute-values"]', 'Red, Blue, Green');

      // Generate variant combinations
      await page.click('[data-test="generate-variants-button"]');

      // Wait for variant grid to appear (should have 4 sizes × 3 colors = 12 variants)
      const variantRows = page.locator('[data-test="variant-row"]');
      await expect(variantRows).toHaveCount(12);

      // Set inventory for first variant
      await variantRows.first().locator('[data-test="variant-inventory"]').fill('25');

      // Save product
      await page.click('[data-test="save-product-button"]');
      await expect(page.locator('[data-test="product-save-success"]')).toBeVisible();

      // Verify variants saved
      await dashboard.navigateToProducts();
      await page.click('text=E2E Variant Product');

      // Verify variant grid shows
      await expect(page.locator('[data-test="variant-row"]')).toHaveCount(12);
    });

    test('should edit existing product and update inventory', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Find and click first product in list
      const firstProduct = page.locator('[data-test="product-row"]').first();
      await firstProduct.click();

      // Edit product name
      const nameInput = page.locator('[data-test="product-name"]');
      const originalName = await nameInput.inputValue();
      const updatedName = `${originalName} - UPDATED`;

      await nameInput.fill(updatedName);

      // Update inventory
      await page.fill('[data-test="product-inventory"]', '200');

      // Save changes
      await page.click('[data-test="save-product-button"]');
      await expect(page.locator('[data-test="product-save-success"]')).toBeVisible();

      // Verify changes persisted
      await dashboard.navigateToProducts();
      await expect(page.locator(`text=${updatedName}`)).toBeVisible();
    });

    test('should delete product and confirm removal', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Create test product to delete
      await page.click('[data-test="create-product-button"]');
      const testSku = `TA-DELETE-${Date.now()}`;

      await page.fill('[data-test="product-name"]', 'Product to Delete');
      await page.fill('[data-test="product-sku"]', testSku);
      await page.fill('[data-test="product-price"]', '10.00');
      await page.click('[data-test="save-product-button"]');
      await expect(page.locator('[data-test="product-save-success"]')).toBeVisible();

      // Navigate back to products list
      await dashboard.navigateToProducts();

      // Find product by SKU
      const productRow = page.locator(`[data-test="product-row"]:has-text("${testSku}")`);
      await expect(productRow).toBeVisible();

      // Click delete button
      await productRow.locator('[data-test="delete-product-button"]').click();

      // Confirm deletion in modal
      await page.click('[data-test="confirm-delete-button"]');

      // Wait for deletion success
      await expect(page.locator('[data-test="product-delete-success"]')).toBeVisible();

      // Verify product no longer in list
      await expect(page.locator(`text=${testSku}`)).not.toBeVisible();
    });

    test('should bulk import products from CSV', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Click bulk import button
      await page.click('[data-test="bulk-import-button"]');

      // Verify import modal opens
      await expect(page.locator('[data-test="import-modal"]')).toBeVisible();

      // Create CSV content
      const csvContent = `name,sku,description,price,inventory,category
Bulk Import Product 1,TA-BULK-001,First bulk product,29.99,50,Clothing
Bulk Import Product 2,TA-BULK-002,Second bulk product,39.99,75,Accessories
Bulk Import Product 3,TA-BULK-003,Third bulk product,19.99,100,Accessories`;

      // Create CSV file blob
      const csvBlob = new Blob([csvContent], { type: 'text/csv' });

      // Upload CSV file
      const fileInput = page.locator('[data-test="import-file-input"]');
      await fileInput.setInputFiles({
        name: 'products.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(csvContent),
      });

      // Wait for file validation
      await expect(page.locator('[data-test="import-validation-success"]')).toBeVisible();

      // Verify preview shows 3 products
      const previewRows = page.locator('[data-test="import-preview-row"]');
      await expect(previewRows).toHaveCount(3);

      // Confirm import
      await page.click('[data-test="confirm-import-button"]');

      // Wait for import to complete
      await expect(page.locator('[data-test="import-complete-message"]')).toBeVisible({
        timeout: 15000,
      });

      // Verify imported products appear in catalog
      await dashboard.navigateToProducts();
      await expect(page.locator('text=Bulk Import Product 1')).toBeVisible();
      await expect(page.locator('text=Bulk Import Product 2')).toBeVisible();
      await expect(page.locator('text=Bulk Import Product 3')).toBeVisible();
    });

    test('should validate product form and show errors', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();
      await page.click('[data-test="create-product-button"]');

      // Try to save without required fields
      await page.click('[data-test="save-product-button"]');

      // Verify validation errors shown
      await expect(page.locator('[data-test="validation-error-name"]')).toBeVisible();
      await expect(page.locator('[data-test="validation-error-sku"]')).toBeVisible();
      await expect(page.locator('[data-test="validation-error-price"]')).toBeVisible();

      // Fill name but use invalid price
      await page.fill('[data-test="product-name"]', 'Validation Test');
      await page.fill('[data-test="product-sku"]', 'TA-VAL-001');
      await page.fill('[data-test="product-price"]', '-10.00'); // Negative price

      await page.click('[data-test="save-product-button"]');

      // Verify price validation error
      await expect(page.locator('[data-test="validation-error-price"]')).toContainText(
        'positive'
      );
    });

    test('should prevent duplicate SKU creation', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Get SKU of existing product
      const existingProduct = tenant.products[0];
      const duplicateSku = existingProduct.sku;

      // Try to create product with duplicate SKU
      await page.click('[data-test="create-product-button"]');

      await page.fill('[data-test="product-name"]', 'Duplicate SKU Test');
      await page.fill('[data-test="product-sku"]', duplicateSku);
      await page.fill('[data-test="product-price"]', '99.99');

      await page.click('[data-test="save-product-button"]');

      // Verify duplicate SKU error shown
      await expect(page.locator('[data-test="validation-error-sku"]')).toContainText(
        'already exists'
      );
    });
  });

  test.describe('Tenant B - Product Management Isolation', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      const loginPage = new AdminLoginPage(page);
      await page.goto(`${baseURL}/admin/login`);
      await loginPage.login(tenant.admin.email, tenant.admin.password);
    });

    test('should only show Tenant B products in admin catalog', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      // Get all product rows
      const productRows = page.locator('[data-test="product-row"]');
      const count = await productRows.count();

      // Verify all products have TB- SKU prefix
      for (let i = 0; i < count; i++) {
        const rowText = await productRows.nth(i).textContent();

        expect(rowText).toContain('TB-');
        expect(rowText).not.toContain('TA-'); // Should NOT see Tenant A products
      }
    });

    test('should create Tenant B product with TB- SKU prefix', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();

      await page.click('[data-test="create-product-button"]');

      await page.fill('[data-test="product-name"]', 'Tenant B Test Product');
      await page.fill('[data-test="product-sku"]', `TB-TEST-${Date.now()}`);
      await page.fill('[data-test="product-price"]', '89.99');
      await page.fill('[data-test="product-inventory"]', '50');

      await page.click('[data-test="save-product-button"]');
      await expect(page.locator('[data-test="product-save-success"]')).toBeVisible();

      // Verify product saved
      await dashboard.navigateToProducts();
      await expect(page.locator('text=Tenant B Test Product')).toBeVisible();
    });

    test('should bulk import products scoped to Tenant B', async ({ page }) => {
      const dashboard = new AdminDashboardPage(page);

      await dashboard.navigateToProducts();
      await page.click('[data-test="bulk-import-button"]');

      // CSV with TB- SKUs
      const csvContent = `name,sku,description,price,inventory,category
TB Bulk Product 1,TB-BULK-001,Tenant B bulk import,49.99,30,Tech
TB Bulk Product 2,TB-BULK-002,Tenant B bulk import,59.99,40,Tech`;

      const fileInput = page.locator('[data-test="import-file-input"]');
      await fileInput.setInputFiles({
        name: 'products-tenant-b.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(csvContent),
      });

      await expect(page.locator('[data-test="import-validation-success"]')).toBeVisible();
      await page.click('[data-test="confirm-import-button"]');
      await expect(page.locator('[data-test="import-complete-message"]')).toBeVisible({
        timeout: 15000,
      });

      // Verify products imported with Tenant B context
      await dashboard.navigateToProducts();
      await expect(page.locator('text=TB Bulk Product 1')).toBeVisible();
    });
  });
});
