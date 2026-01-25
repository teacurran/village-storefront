import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for POS (Point of Sale) interface
 * Handles offline queue management and transaction sync
 */
export class POSPage extends BasePage {
  readonly productSearch: Locator;
  readonly productList: Locator;
  readonly addToCartButton: Locator;
  readonly cartItems: Locator;
  readonly totalAmount: Locator;
  readonly checkoutButton: Locator;
  readonly cashPaymentButton: Locator;
  readonly cardPaymentButton: Locator;
  readonly completeTransactionButton: Locator;
  readonly offlineIndicator: Locator;
  readonly offlineQueueButton: Locator;
  readonly queuedTransactionsList: Locator;
  readonly syncQueueButton: Locator;
  readonly syncStatusIndicator: Locator;
  readonly transactionConfirmation: Locator;

  constructor(page: Page) {
    super(page);
    this.productSearch = page.locator('[data-test="pos-product-search"]');
    this.productList = page.locator('[data-test="pos-product-list"]');
    this.addToCartButton = page.locator('[data-test="pos-add-to-cart"]');
    this.cartItems = page.locator('[data-test="pos-cart-items"]');
    this.totalAmount = page.locator('[data-test="pos-total-amount"]');
    this.checkoutButton = page.locator('[data-test="pos-checkout"]');
    this.cashPaymentButton = page.locator('[data-test="pos-payment-cash"]');
    this.cardPaymentButton = page.locator('[data-test="pos-payment-card"]');
    this.completeTransactionButton = page.locator('[data-test="pos-complete-transaction"]');
    this.offlineIndicator = page.locator('[data-test="pos-offline-indicator"]');
    this.offlineQueueButton = page.locator('[data-test="pos-queue-button"]');
    this.queuedTransactionsList = page.locator('[data-test="pos-queued-transactions"]');
    this.syncQueueButton = page.locator('[data-test="pos-sync-queue"]');
    this.syncStatusIndicator = page.locator('[data-test="pos-sync-status"]');
    this.transactionConfirmation = page.locator('[data-test="pos-transaction-confirmation"]');
  }

  async gotoPOS(): Promise<void> {
    await this.goto('/admin/pos');
    await this.waitForTenantContext();
  }

  async searchProduct(query: string): Promise<void> {
    await this.fillField(this.productSearch, query);
    await this.page.waitForTimeout(500); // Wait for search results
  }

  async selectProductByIndex(index: number): Promise<void> {
    const products = this.productList.locator('[data-test="pos-product-item"]');
    await products.nth(index).click();
  }

  async addProductToCart(productSku: string, quantity: number = 1): Promise<void> {
    await this.searchProduct(productSku);
    await this.selectProductByIndex(0);

    const quantityInput = this.page.locator('[data-test="pos-quantity-input"]');
    await this.fillField(quantityInput, quantity.toString());
    await this.clickButton(this.addToCartButton);
    await this.page.waitForTimeout(300);
  }

  async getCartTotal(): Promise<number> {
    const totalText = await this.getText(this.totalAmount);
    const match = totalText.match(/\$?([\d,]+\.?\d*)/);
    return match ? parseFloat(match[1].replace(/,/g, '')) : 0;
  }

  async proceedToCheckout(): Promise<void> {
    await this.clickButton(this.checkoutButton);
    await this.page.waitForTimeout(500);
  }

  async selectPaymentMethod(method: 'cash' | 'card'): Promise<void> {
    const button = method === 'cash' ? this.cashPaymentButton : this.cardPaymentButton;
    await this.clickButton(button);
  }

  async completeTransaction(options: { offline?: boolean } = {}): Promise<void> {
    const { offline = false } = options;

    if (offline) {
      // Simulate offline mode by intercepting network requests
      await this.page.route('**/api/pos/transactions', route => route.abort());
    }

    await this.clickButton(this.completeTransactionButton);
    await this.page.waitForTimeout(1000);
  }

  async isOffline(): Promise<boolean> {
    return await this.isVisible(this.offlineIndicator);
  }

  async viewOfflineQueue(): Promise<void> {
    await this.clickButton(this.offlineQueueButton);
    await this.page.waitForTimeout(500);
  }

  async getQueuedTransactionCount(): Promise<number> {
    const items = this.queuedTransactionsList.locator('[data-test="queued-transaction-item"]');
    return await items.count();
  }

  async syncOfflineQueue(): Promise<void> {
    // Clear network route to allow sync
    await this.page.unroute('**/api/pos/transactions');

    await this.clickButton(this.syncQueueButton);

    // Wait for sync to complete (indicated by status change)
    await this.page.waitForFunction(
      () => {
        const status = document.querySelector('[data-test="pos-sync-status"]');
        return status?.textContent?.includes('Synced') || status?.textContent?.includes('Complete');
      },
      { timeout: 10000 }
    );
  }

  async getSyncStatus(): Promise<string> {
    return await this.getText(this.syncStatusIndicator);
  }

  async getTransactionId(): Promise<string> {
    const confirmationText = await this.getText(this.transactionConfirmation);
    const match = confirmationText.match(/Transaction #(\w+)/);
    return match ? match[1] : '';
  }
}
