import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Admin Order Management
 * Covers order details, refunds, status updates, and customer communication
 */
export class AdminOrderPage extends BasePage {
  readonly ordersTable: Locator;
  readonly orderSearch: Locator;
  readonly statusFilter: Locator;
  readonly viewOrderButton: Locator;
  readonly orderDetails: Locator;
  readonly orderNumber: Locator;
  readonly customerEmail: Locator;
  readonly orderTotal: Locator;
  readonly orderStatus: Locator;
  readonly orderItems: Locator;
  readonly refundButton: Locator;
  readonly refundModal: Locator;
  readonly refundAmountInput: Locator;
  readonly refundReasonSelect: Locator;
  readonly refundNotesInput: Locator;
  readonly confirmRefundButton: Locator;
  readonly partialRefundCheckbox: Locator;
  readonly refundSuccess: Locator;
  readonly emailLogSection: Locator;
  readonly updateStatusButton: Locator;
  readonly statusSelect: Locator;

  constructor(page: Page) {
    super(page);
    this.ordersTable = page.locator('[data-test="orders-table"]');
    this.orderSearch = page.locator('[data-test="order-search"]');
    this.statusFilter = page.locator('[data-test="status-filter"]');
    this.viewOrderButton = page.locator('[data-test="view-order"]');
    this.orderDetails = page.locator('[data-test="order-details"]');
    this.orderNumber = page.locator('[data-test="order-number"]');
    this.customerEmail = page.locator('[data-test="customer-email"]');
    this.orderTotal = page.locator('[data-test="order-total"]');
    this.orderStatus = page.locator('[data-test="order-status"]');
    this.orderItems = page.locator('[data-test="order-items"]');
    this.refundButton = page.locator('[data-test="refund-order"]');
    this.refundModal = page.locator('[data-test="refund-modal"]');
    this.refundAmountInput = page.locator('[data-test="refund-amount"]');
    this.refundReasonSelect = page.locator('[data-test="refund-reason"]');
    this.refundNotesInput = page.locator('[data-test="refund-notes"]');
    this.confirmRefundButton = page.locator('[data-test="confirm-refund"]');
    this.partialRefundCheckbox = page.locator('[data-test="partial-refund"]');
    this.refundSuccess = page.locator('[data-test="refund-success"]');
    this.emailLogSection = page.locator('[data-test="email-log"]');
    this.updateStatusButton = page.locator('[data-test="update-status"]');
    this.statusSelect = page.locator('[data-test="status-select"]');
  }

  /**
   * Navigate to admin orders page
   */
  async gotoOrders(): Promise<void> {
    await this.goto('/admin/orders');
    await this.waitForTenantContext();
  }

  /**
   * Search for order by order number or customer email
   * @param query Order number or email
   */
  async searchOrder(query: string): Promise<void> {
    await this.fillField(this.orderSearch, query);
    await this.page.keyboard.press('Enter');
    await this.waitForNavigation();
  }

  /**
   * Filter orders by status
   * @param status Order status (e.g., 'PENDING', 'COMPLETED', 'REFUNDED')
   */
  async filterByStatus(status: string): Promise<void> {
    await this.statusFilter.selectOption({ value: status });
    await this.waitForNavigation();
  }

  /**
   * View order details by selecting first order in table
   */
  async viewFirstOrder(): Promise<void> {
    const firstOrderRow = this.ordersTable.locator('[data-test="order-row"]').first();
    await firstOrderRow.locator('[data-test="view-order"]').click();
    await this.orderDetails.waitFor({ state: 'visible' });
  }

  /**
   * View specific order by order number
   * @param orderNumber Order number
   */
  async viewOrderByNumber(orderNumber: string): Promise<void> {
    await this.goto(`/admin/orders/${orderNumber}`);
    await this.orderDetails.waitFor({ state: 'visible' });
  }

  /**
   * Get order number from details page
   * @returns Order number
   */
  async getOrderNumber(): Promise<string> {
    return await this.getText(this.orderNumber);
  }

  /**
   * Get customer email from order details
   * @returns Customer email
   */
  async getCustomerEmail(): Promise<string> {
    return await this.getText(this.customerEmail);
  }

  /**
   * Get order total
   * @returns Order total as number
   */
  async getOrderTotal(): Promise<number> {
    const totalText = await this.getText(this.orderTotal);
    const match = totalText.match(/\$?([\d,]+\.?\d*)/);
    return match ? parseFloat(match[1].replace(/,/g, '')) : 0;
  }

  /**
   * Get current order status
   * @returns Order status
   */
  async getOrderStatus(): Promise<string> {
    return await this.getText(this.orderStatus);
  }

  /**
   * Process full refund
   * @param reason Refund reason
   * @param notes Optional notes
   */
  async processFullRefund(reason: string, notes?: string): Promise<void> {
    await this.clickButton(this.refundButton);
    await this.refundModal.waitFor({ state: 'visible' });

    await this.refundReasonSelect.selectOption({ value: reason });

    if (notes) {
      await this.fillField(this.refundNotesInput, notes);
    }

    await this.clickButton(this.confirmRefundButton);

    // Wait for Stripe refund API call
    await this.page.waitForResponse(
      (response) =>
        (response.url().includes('/admin/orders') &&
          response.url().includes('/refund') &&
          response.status() === 200) ||
        response.url().includes('/stripe/refund')
    );

    await this.refundSuccess.waitFor({ state: 'visible', timeout: 15000 });
  }

  /**
   * Process partial refund
   * @param amount Refund amount
   * @param reason Refund reason
   * @param notes Optional notes
   */
  async processPartialRefund(
    amount: number,
    reason: string,
    notes?: string
  ): Promise<void> {
    await this.clickButton(this.refundButton);
    await this.refundModal.waitFor({ state: 'visible' });

    // Enable partial refund
    await this.partialRefundCheckbox.check();

    await this.fillField(this.refundAmountInput, amount.toString());
    await this.refundReasonSelect.selectOption({ value: reason });

    if (notes) {
      await this.fillField(this.refundNotesInput, notes);
    }

    await this.clickButton(this.confirmRefundButton);

    // Wait for Stripe refund API call
    await this.page.waitForResponse(
      (response) =>
        (response.url().includes('/admin/orders') &&
          response.url().includes('/refund') &&
          response.status() === 200) ||
        response.url().includes('/stripe/refund')
    );

    await this.refundSuccess.waitFor({ state: 'visible', timeout: 15000 });
  }

  /**
   * Check if refund email was sent
   * @returns true if email log shows refund email, false otherwise
   */
  async isRefundEmailSent(): Promise<boolean> {
    await this.emailLogSection.waitFor({ state: 'visible' });

    const emailEntries = await this.emailLogSection
      .locator('[data-test="email-entry"]')
      .all();

    for (const entry of emailEntries) {
      const subject = await entry.locator('[data-test="email-subject"]').textContent();
      if (subject && subject.toLowerCase().includes('refund')) {
        return true;
      }
    }

    return false;
  }

  /**
   * Get email log entries
   * @returns Array of email log entries
   */
  async getEmailLog(): Promise<Array<{ subject: string; sentAt: string }>> {
    await this.emailLogSection.waitFor({ state: 'visible' });

    const emailEntries = await this.emailLogSection
      .locator('[data-test="email-entry"]')
      .all();

    const log: Array<{ subject: string; sentAt: string }> = [];

    for (const entry of emailEntries) {
      const subject =
        (await entry.locator('[data-test="email-subject"]').textContent()) || '';
      const sentAt = (await entry.locator('[data-test="email-sent"]').textContent()) || '';

      log.push({
        subject: subject.trim(),
        sentAt: sentAt.trim(),
      });
    }

    return log;
  }

  /**
   * Update order status
   * @param status New status
   */
  async updateOrderStatus(status: string): Promise<void> {
    await this.statusSelect.selectOption({ value: status });
    await this.clickButton(this.updateStatusButton);

    // Wait for status update API call
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/admin/orders') &&
        response.url().includes('/status') &&
        response.status() === 200
    );

    await this.page.waitForTimeout(1000); // Brief wait for UI update
  }

  /**
   * Get number of items in order
   * @returns Item count
   */
  async getOrderItemCount(): Promise<number> {
    const items = await this.orderItems.locator('[data-test="order-item"]').all();
    return items.length;
  }
}
