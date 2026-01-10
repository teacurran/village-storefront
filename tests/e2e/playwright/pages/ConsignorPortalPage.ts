import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Consignor Portal
 * Covers payout requests, balance viewing, and transaction history
 */
export class ConsignorPortalPage extends BasePage {
  readonly loginEmail: Locator;
  readonly loginPassword: Locator;
  readonly loginButton: Locator;
  readonly pendingBalance: Locator;
  readonly requestPayoutButton: Locator;
  readonly payoutHistory: Locator;
  readonly payoutConfirmation: Locator;
  readonly payoutRequestModal: Locator;
  readonly confirmPayoutButton: Locator;
  readonly salesList: Locator;
  readonly totalEarnings: Locator;

  constructor(page: Page) {
    super(page);
    this.loginEmail = page.locator('[data-test="consignor-login-email"]');
    this.loginPassword = page.locator('[data-test="consignor-login-password"]');
    this.loginButton = page.locator('[data-test="consignor-login-button"]');
    this.pendingBalance = page.locator('[data-test="pending-balance"]');
    this.requestPayoutButton = page.locator('[data-test="request-payout"]');
    this.payoutHistory = page.locator('[data-test="payout-history"]');
    this.payoutConfirmation = page.locator('[data-test="payout-confirmation"]');
    this.payoutRequestModal = page.locator('[data-test="payout-request-modal"]');
    this.confirmPayoutButton = page.locator('[data-test="confirm-payout"]');
    this.salesList = page.locator('[data-test="consignor-sales-list"]');
    this.totalEarnings = page.locator('[data-test="total-earnings"]');
  }

  /**
   * Navigate to consignor portal login
   */
  async gotoConsignorPortal(): Promise<void> {
    await this.goto('/consignor/login');
    await this.waitForTenantContext();
  }

  /**
   * Login as consignor
   * @param email Consignor email
   * @param password Consignor password
   */
  async login(email: string, password: string): Promise<void> {
    await this.fillField(this.loginEmail, email);
    await this.fillField(this.loginPassword, password);
    await this.clickButton(this.loginButton);

    // Wait for login API call and navigation
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/auth/login') && response.status() === 200
    );

    await this.page.waitForURL(/.*consignor\/dashboard.*/);
    await this.waitForTenantContext();
  }

  /**
   * Get pending payout balance
   * @returns Balance as number
   */
  async getPendingBalance(): Promise<number> {
    await this.pendingBalance.waitFor({ state: 'visible' });
    const balanceText = await this.getText(this.pendingBalance);

    // Extract dollar amount from text like "$125.50" or "Pending: $125.50"
    const match = balanceText.match(/\$?([\d,]+\.?\d*)/);
    if (match) {
      return parseFloat(match[1].replace(/,/g, ''));
    }
    return 0;
  }

  /**
   * Request payout of pending balance
   */
  async requestPayout(): Promise<void> {
    await this.clickButton(this.requestPayoutButton);

    // Wait for confirmation modal
    await this.payoutRequestModal.waitFor({ state: 'visible' });
    await this.clickButton(this.confirmPayoutButton);

    // Wait for payout request API call (Stripe Express transfer)
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/consignor/payouts/request') &&
        response.status() === 200
    );

    // Wait for confirmation message
    await this.payoutConfirmation.waitFor({ state: 'visible', timeout: 10000 });
  }

  /**
   * Get payout confirmation message
   * @returns Confirmation text
   */
  async getPayoutConfirmationMessage(): Promise<string> {
    return await this.getText(this.payoutConfirmation);
  }

  /**
   * Get payout history
   * @returns Array of payout records with date, amount, status
   */
  async getPayoutHistory(): Promise<Array<{ date: string; amount: string; status: string }>> {
    await this.payoutHistory.waitFor({ state: 'visible' });
    const rows = await this.payoutHistory.locator('[data-test="payout-row"]').all();

    const history: Array<{ date: string; amount: string; status: string }> = [];

    for (const row of rows) {
      const date = await row.locator('[data-test="payout-date"]').textContent();
      const amount = await row.locator('[data-test="payout-amount"]').textContent();
      const status = await row.locator('[data-test="payout-status"]').textContent();

      history.push({
        date: date?.trim() || '',
        amount: amount?.trim() || '',
        status: status?.trim() || '',
      });
    }

    return history;
  }

  /**
   * Get consignor sales list
   * @returns Number of sales
   */
  async getSalesCount(): Promise<number> {
    await this.salesList.waitFor({ state: 'visible' });
    const salesRows = await this.salesList.locator('[data-test="sale-row"]').all();
    return salesRows.length;
  }

  /**
   * Get total earnings (all time)
   * @returns Total earnings as number
   */
  async getTotalEarnings(): Promise<number> {
    const earningsText = await this.getText(this.totalEarnings);

    // Extract dollar amount
    const match = earningsText.match(/\$?([\d,]+\.?\d*)/);
    if (match) {
      return parseFloat(match[1].replace(/,/g, ''));
    }
    return 0;
  }

  /**
   * Check if payout request was successful
   * @returns true if confirmation displayed, false otherwise
   */
  async isPayoutConfirmed(): Promise<boolean> {
    return await this.isVisible(this.payoutConfirmation);
  }
}
