import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { POSPage } from './pages/POSPage';

/**
 * POS Offline Queue E2E Tests
 * Tests offline transaction queueing and synchronization
 */
test.describe('POS Offline Queue Flow', () => {
  test.describe('Tenant A - Offline Queue Management', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      // Login as admin to access POS
      const loginPage = new AdminLoginPage(page);
      await page.goto(`${baseURL}/admin/login`);
      await loginPage.login(tenant.admin.email, tenant.admin.password);
    });

    test('should submit transaction to offline queue when network unavailable', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Add product to cart
      const product = tenant.products[0];
      await posPage.addProductToCart(product.sku, 2);

      // Verify cart total
      const total = await posPage.getCartTotal();
      expect(total).toBe(product.price * 2);

      // Proceed to checkout
      await posPage.proceedToCheckout();

      // Select payment method
      await posPage.selectPaymentMethod('cash');

      // Complete transaction while offline (network route intercepted)
      await posPage.completeTransaction({ offline: true });

      // Verify offline indicator shows
      const isOffline = await posPage.isOffline();
      expect(isOffline).toBe(true);

      // Verify transaction queued (confirmation message different for offline)
      await expect(posPage.transactionConfirmation).toContainText('queued');
    });

    test('should view queued transactions in offline queue', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Add and complete first transaction offline
      const product1 = tenant.products[0];
      await posPage.addProductToCart(product1.sku, 1);
      await posPage.proceedToCheckout();
      await posPage.selectPaymentMethod('cash');
      await posPage.completeTransaction({ offline: true });

      // Navigate back to POS (start new transaction)
      await posPage.gotoPOS();

      // Add and complete second transaction offline
      const product2 = tenant.products[1];
      await posPage.addProductToCart(product2.sku, 1);
      await posPage.proceedToCheckout();
      await posPage.selectPaymentMethod('card');
      await posPage.completeTransaction({ offline: true });

      // View offline queue
      await posPage.viewOfflineQueue();

      // Verify 2 transactions in queue
      const queueCount = await posPage.getQueuedTransactionCount();
      expect(queueCount).toBe(2);
    });

    test('should sync queued transactions when network restored', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Add product to cart
      const product = tenant.products[2];
      await posPage.addProductToCart(product.sku, 1);
      await posPage.proceedToCheckout();
      await posPage.selectPaymentMethod('cash');

      // Complete transaction offline
      await posPage.completeTransaction({ offline: true });

      // Verify transaction queued
      await posPage.viewOfflineQueue();
      const beforeSyncCount = await posPage.getQueuedTransactionCount();
      expect(beforeSyncCount).toBeGreaterThan(0);

      // Sync queue (this will unroute network and trigger sync)
      await posPage.syncOfflineQueue();

      // Verify sync status shows complete
      const syncStatus = await posPage.getSyncStatus();
      expect(syncStatus).toMatch(/synced|complete/i);

      // Verify queue is now empty (or count decreased)
      const afterSyncCount = await posPage.getQueuedTransactionCount();
      expect(afterSyncCount).toBeLessThan(beforeSyncCount);
    });

    test('should maintain transaction integrity during sync (no data loss)', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Create offline transaction with specific details
      const product = tenant.products[0];
      const quantity = 3;
      const expectedTotal = product.price * quantity;

      await posPage.addProductToCart(product.sku, quantity);
      await posPage.proceedToCheckout();

      // Capture cart total before offline submit
      const cartTotal = await posPage.getCartTotal();
      expect(cartTotal).toBe(expectedTotal);

      await posPage.selectPaymentMethod('cash');
      await posPage.completeTransaction({ offline: true });

      // View queue and verify transaction details preserved
      await posPage.viewOfflineQueue();
      const queuedItem = page.locator('[data-test="queued-transaction-item"]').first();

      // Verify SKU and amount in queued item
      await expect(queuedItem).toContainText(product.sku);
      await expect(queuedItem).toContainText(`$${expectedTotal.toFixed(2)}`);

      // Sync and verify
      await posPage.syncOfflineQueue();

      const syncStatus = await posPage.getSyncStatus();
      expect(syncStatus).toMatch(/synced|complete/i);
    });
  });

  test.describe('Tenant B - Offline Queue Isolation', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      const loginPage = new AdminLoginPage(page);
      await page.goto(`${baseURL}/admin/login`);
      await loginPage.login(tenant.admin.email, tenant.admin.password);
    });

    test('should not see Tenant A queued transactions in Tenant B offline queue', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Add Tenant B product
      const product = tenant.products[0];
      await posPage.addProductToCart(product.sku, 1);
      await posPage.proceedToCheckout();
      await posPage.selectPaymentMethod('cash');
      await posPage.completeTransaction({ offline: true });

      // View offline queue
      await posPage.viewOfflineQueue();

      // Verify only contains Tenant B products (not Tenant A SKUs)
      const queuedItems = page.locator('[data-test="queued-transaction-item"]');
      const count = await queuedItems.count();

      for (let i = 0; i < count; i++) {
        const itemText = await queuedItems.nth(i).textContent();

        // Should contain Tenant B SKU prefix "TB-"
        expect(itemText).toContain('TB-');

        // Should NOT contain Tenant A SKU prefix "TA-"
        expect(itemText).not.toContain('TA-');
      }
    });

    test('should use Tenant B product pricing in offline transactions', async ({ page }) => {
      const posPage = new POSPage(page);

      // Navigate to POS
      await posPage.gotoPOS();

      // Add Tenant B product (different pricing than Tenant A)
      const product = tenant.products[1]; // Wireless Mouse - $24.99
      const quantity = 2;
      const expectedTotal = product.price * quantity; // $49.98

      await posPage.addProductToCart(product.sku, quantity);
      await posPage.proceedToCheckout();

      const cartTotal = await posPage.getCartTotal();
      expect(cartTotal).toBe(expectedTotal);

      await posPage.selectPaymentMethod('card');
      await posPage.completeTransaction({ offline: true });

      // View queue and verify Tenant B pricing
      await posPage.viewOfflineQueue();
      const queuedItem = page.locator('[data-test="queued-transaction-item"]').first();

      await expect(queuedItem).toContainText(`$${expectedTotal.toFixed(2)}`);
    });
  });
});
