import { test, expect } from '@playwright/test';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';
import { AdminInventoryPage } from './pages/AdminInventoryPage';
import { AdminOrderPage } from './pages/AdminOrderPage';
import { StorefrontPage } from './pages/StorefrontPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Admin Dashboard Flow E2E Tests
 * Covers catalog CRUD, inventory management, and order refunds
 */
test.describe('Admin Dashboard Flows', () => {
  const tenant = tenants.tenantA;
  const baseURL = getTenantBaseUrl(tenant);

  test.beforeEach(async ({ page }) => {
    // Login as admin before each test
    await page.goto(`${baseURL}/admin/login`);
    const loginPage = new AdminLoginPage(page);
    await loginPage.login(tenant.admin.email, tenant.admin.password);

    // Verify admin dashboard loaded
    await expect(page).toHaveURL(/.*admin\/dashboard.*/);
  });

  test('should create product with variants and publish to storefront', async ({
    page,
  }) => {
    const dashboard = new AdminDashboardPage(page);

    // Navigate to Products
    await page.goto(`${baseURL}/admin/products`);

    // Click create new product
    await page.locator('[data-test="create-product"]').click();
    await page.waitForURL(/.*admin\/products\/new.*/);

    // Fill product details
    const productName = `Test Product ${Date.now()}`;
    const productSKU = `TEST-SKU-${Date.now()}`;

    await page.locator('[data-test="product-name"]').fill(productName);
    await page
      .locator('[data-test="product-description"]')
      .fill('High quality test product for E2E testing');
    await page.locator('[data-test="product-price"]').fill('49.99');
    await page.locator('[data-test="product-sku"]').fill(productSKU);

    // Add variants
    await page.locator('[data-test="add-variant"]').click();

    // Variant 1: Small / Red
    await page.locator('[data-test="variant-name-0"]').fill('Small / Red');
    await page.locator('[data-test="variant-sku-0"]').fill(`${productSKU}-S-RED`);
    await page.locator('[data-test="variant-price-0"]').fill('49.99');
    await page.locator('[data-test="variant-inventory-0"]').fill('25');

    // Add second variant
    await page.locator('[data-test="add-variant"]').click();

    // Variant 2: Large / Blue
    await page.locator('[data-test="variant-name-1"]').fill('Large / Blue');
    await page.locator('[data-test="variant-sku-1"]').fill(`${productSKU}-L-BLUE`);
    await page.locator('[data-test="variant-price-1"]').fill('59.99');
    await page.locator('[data-test="variant-inventory-1"]').fill('15');

    // Publish product
    await page.locator('[data-test="publish-product"]').click();

    // Wait for product creation API call
    await page.waitForResponse(
      (response) =>
        response.url().includes('/admin/products') && response.status() === 201
    );

    // Verify success message
    await expect(page.locator('[data-test="product-created-success"]')).toBeVisible();

    // Navigate to storefront to verify product is visible
    await page.goto(baseURL);
    const storefront = new StorefrontPage(page);
    await storefront.searchProducts(productName);

    // Verify product appears in search results
    const productCount = await storefront.getProductCount();
    expect(productCount).toBeGreaterThan(0);

    // Verify product details
    await storefront.selectProduct(0);
    await expect(page.locator('[data-test="product-title"]')).toContainText(
      productName
    );

    // Verify variants are displayed
    await expect(page.locator('[data-test="variant-selector"]')).toBeVisible();
    const variantOptions = await page.locator('[data-test="variant-option"]').all();
    expect(variantOptions.length).toBe(2);
  });

  test('should adjust stock and create transfer with audit log', async ({ page }) => {
    const inventoryPage = new AdminInventoryPage(page);

    // Navigate to inventory management
    await inventoryPage.gotoInventory();

    // Search for first product
    const firstProduct = tenant.products[0];
    await inventoryPage.searchProduct(firstProduct.name);

    // Get initial stock level
    const initialStock = await inventoryPage.getStockLevel(firstProduct.name);

    // Select product
    await inventoryPage.selectProductByIndex(0);

    // Adjust stock (add 50 units)
    await inventoryPage.adjustStock(50, 'RESTOCK', 'Replenishment from supplier');

    // Verify stock increased
    await inventoryPage.gotoInventory();
    await inventoryPage.searchProduct(firstProduct.name);
    const updatedStock = await inventoryPage.getStockLevel(firstProduct.name);
    expect(updatedStock).toBe(initialStock + 50);

    // Create transfer between locations
    await inventoryPage.selectProductByIndex(0);
    await inventoryPage.createTransfer(10, 'Main Warehouse', 'Retail Store');

    // Navigate to audit log
    await inventoryPage.viewAuditLog();

    // Verify audit log entries
    const auditEntries = await inventoryPage.getAuditLogEntries();
    expect(auditEntries.length).toBeGreaterThan(0);

    // Verify adjustment entry exists
    const adjustmentEntry = auditEntries.find((entry) =>
      entry.action.includes('STOCK_ADJUSTMENT')
    );
    expect(adjustmentEntry).toBeDefined();
    expect(adjustmentEntry?.details).toContain('50');
    expect(adjustmentEntry?.details).toContain('RESTOCK');
    expect(adjustmentEntry?.user).toContain(tenant.admin.email);

    // Verify transfer entry exists
    const transferEntry = auditEntries.find((entry) =>
      entry.action.includes('STOCK_TRANSFER')
    );
    expect(transferEntry).toBeDefined();
    expect(transferEntry?.details).toContain('10');
    expect(transferEntry?.details).toContain('Main Warehouse');
    expect(transferEntry?.details).toContain('Retail Store');
  });

  test('should process partial refund and send email', async ({ page }) => {
    const orderPage = new AdminOrderPage(page);

    // Navigate to orders
    await orderPage.gotoOrders();

    // Filter to show completed orders
    await orderPage.filterByStatus('COMPLETED');

    // View first order
    await orderPage.viewFirstOrder();

    // Get order details
    const orderNumber = await orderPage.getOrderNumber();
    const customerEmail = await orderPage.getCustomerEmail();
    const orderTotal = await orderPage.getOrderTotal();
    const itemCount = await orderPage.getOrderItemCount();

    expect(orderNumber).toBeTruthy();
    expect(customerEmail).toBeTruthy();
    expect(orderTotal).toBeGreaterThan(0);

    // Process partial refund (half of order total)
    const refundAmount = Math.round((orderTotal / 2) * 100) / 100;
    await orderPage.processPartialRefund(
      refundAmount,
      'CUSTOMER_REQUEST',
      'Customer requested partial refund for one item'
    );

    // Verify refund success message
    await expect(page.locator('[data-test="refund-success"]')).toBeVisible();

    // Verify order status updated
    const updatedStatus = await orderPage.getOrderStatus();
    expect(updatedStatus).toContain('PARTIALLY_REFUNDED');

    // Verify refund email was sent
    const emailSent = await orderPage.isRefundEmailSent();
    expect(emailSent).toBe(true);

    // Get email log
    const emailLog = await orderPage.getEmailLog();
    const refundEmail = emailLog.find((email) =>
      email.subject.toLowerCase().includes('refund')
    );

    expect(refundEmail).toBeDefined();
    expect(refundEmail?.subject).toBeTruthy();
  });

  test('should edit product details and verify changes on storefront', async ({
    page,
  }) => {
    // Navigate to products
    await page.goto(`${baseURL}/admin/products`);

    // Find existing product
    const firstProduct = tenant.products[0];
    await page.locator('[data-test="product-search"]').fill(firstProduct.name);
    await page.keyboard.press('Enter');

    // Click edit on first product
    await page.locator('[data-test="edit-product"]').first().click();
    await page.waitForURL(/.*admin\/products\/.*\/edit.*/);

    // Update product details
    const newPrice = '35.99';
    const newDescription = `Updated description ${Date.now()}`;

    await page.locator('[data-test="product-price"]').fill(newPrice);
    await page.locator('[data-test="product-description"]').fill(newDescription);

    // Save changes
    await page.locator('[data-test="save-product"]').click();

    // Wait for update API call
    await page.waitForResponse(
      (response) =>
        response.url().includes('/admin/products') &&
        (response.status() === 200 || response.status() === 204)
    );

    // Verify success message
    await expect(page.locator('[data-test="product-updated-success"]')).toBeVisible();

    // Navigate to storefront and verify changes
    await page.goto(baseURL);
    const storefront = new StorefrontPage(page);
    await storefront.searchProducts(firstProduct.name);
    await storefront.selectProduct(0);

    // Verify updated price
    await expect(page.locator('[data-test="product-price"]')).toContainText(newPrice);

    // Verify updated description
    await expect(page.locator('[data-test="product-description"]')).toContainText(
      newDescription
    );
  });

  test('should unpublish product and verify hidden from storefront', async ({
    page,
  }) => {
    // Navigate to products
    await page.goto(`${baseURL}/admin/products`);

    const testProduct = tenant.products[1];
    await page.locator('[data-test="product-search"]').fill(testProduct.name);
    await page.keyboard.press('Enter');

    // Click edit on product
    await page.locator('[data-test="edit-product"]').first().click();
    await page.waitForURL(/.*admin\/products\/.*\/edit.*/);

    // Unpublish product
    await page.locator('[data-test="product-status"]').selectOption('DRAFT');
    await page.locator('[data-test="save-product"]').click();

    // Wait for update
    await page.waitForResponse(
      (response) =>
        response.url().includes('/admin/products') &&
        (response.status() === 200 || response.status() === 204)
    );

    // Navigate to storefront
    await page.goto(baseURL);
    const storefront = new StorefrontPage(page);
    await storefront.searchProducts(testProduct.name);

    // Verify product not found in search results
    const productCount = await storefront.getProductCount();
    expect(productCount).toBe(0);
  });

  test('should process full refund and verify order status', async ({ page }) => {
    const orderPage = new AdminOrderPage(page);

    // Navigate to orders
    await orderPage.gotoOrders();

    // Filter to show completed orders
    await orderPage.filterByStatus('COMPLETED');

    // View first order
    await orderPage.viewFirstOrder();

    const orderTotal = await orderPage.getOrderTotal();

    // Process full refund
    await orderPage.processFullRefund(
      'DEFECTIVE_PRODUCT',
      'Product arrived damaged, full refund issued'
    );

    // Verify refund success
    await expect(page.locator('[data-test="refund-success"]')).toBeVisible();

    // Verify order status updated to REFUNDED
    const updatedStatus = await orderPage.getOrderStatus();
    expect(updatedStatus).toContain('REFUNDED');

    // Verify email sent
    const emailSent = await orderPage.isRefundEmailSent();
    expect(emailSent).toBe(true);
  });

  test('should handle inventory filter by location', async ({ page }) => {
    const inventoryPage = new AdminInventoryPage(page);

    // Navigate to inventory
    await inventoryPage.gotoInventory();

    // Filter by Main Warehouse
    await inventoryPage.filterByLocation('Main Warehouse');

    // Verify inventory table shows filtered results
    await expect(page.locator('[data-test="inventory-table"]')).toBeVisible();

    // Filter by Retail Store
    await inventoryPage.filterByLocation('Retail Store');

    // Verify inventory table updated
    await expect(page.locator('[data-test="inventory-table"]')).toBeVisible();

    // Note: Would verify actual stock levels differ by location in real implementation
  });
});
