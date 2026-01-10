import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Loyalty Program interactions
 * Covers enrollment, points display, and redemption during checkout
 */
export class LoyaltyPage extends BasePage {
  readonly enrollButton: Locator;
  readonly pointsBalance: Locator;
  readonly redeemPointsInput: Locator;
  readonly applyButton: Locator;
  readonly enrollmentForm: Locator;
  readonly emailInput: Locator;
  readonly confirmEnrollButton: Locator;
  readonly enrollmentSuccess: Locator;
  readonly transactionHistory: Locator;
  readonly redemptionConfirmation: Locator;

  constructor(page: Page) {
    super(page);
    this.enrollButton = page.locator('[data-test="enroll-loyalty"]');
    this.pointsBalance = page.locator('[data-test="points-balance"]');
    this.redeemPointsInput = page.locator('[data-test="redeem-points-input"]');
    this.applyButton = page.locator('[data-test="apply-points"]');
    this.enrollmentForm = page.locator('[data-test="loyalty-enrollment-form"]');
    this.emailInput = page.locator('[data-test="loyalty-email-input"]');
    this.confirmEnrollButton = page.locator('[data-test="confirm-enroll"]');
    this.enrollmentSuccess = page.locator('[data-test="enrollment-success"]');
    this.transactionHistory = page.locator('[data-test="loyalty-transactions"]');
    this.redemptionConfirmation = page.locator('[data-test="redemption-confirmation"]');
  }

  /**
   * Navigate to loyalty program page
   */
  async gotoLoyaltyPage(): Promise<void> {
    await this.goto('/loyalty');
    await this.waitForTenantContext();
  }

  /**
   * Enroll customer in loyalty program
   * @param email Customer email address
   */
  async enrollInProgram(email: string): Promise<void> {
    await this.clickButton(this.enrollButton);
    await this.enrollmentForm.waitFor({ state: 'visible' });
    await this.fillField(this.emailInput, email);
    await this.clickButton(this.confirmEnrollButton);

    // Wait for enrollment API call to complete
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/loyalty/enroll') && response.status() === 200
    );

    await this.enrollmentSuccess.waitFor({ state: 'visible' });
  }

  /**
   * Get current loyalty points balance
   * @returns Points balance as number
   */
  async getPointsBalance(): Promise<number> {
    const text = await this.getText(this.pointsBalance);
    // Extract number from text like "1,250 points" or "1250"
    const cleanedText = text.replace(/[^\d]/g, '');
    return parseInt(cleanedText) || 0;
  }

  /**
   * Redeem loyalty points during checkout
   * @param points Number of points to redeem
   */
  async redeemPoints(points: number): Promise<void> {
    await this.fillField(this.redeemPointsInput, points.toString());
    await this.clickButton(this.applyButton);

    // Wait for redemption API call to complete
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/loyalty/redeem') && response.status() === 200
    );

    await this.redemptionConfirmation.waitFor({ state: 'visible', timeout: 10000 });
  }

  /**
   * Get redemption discount amount
   * @returns Dollar amount as number
   */
  async getRedemptionAmount(): Promise<number> {
    const confirmationText = await this.getText(this.redemptionConfirmation);
    // Extract dollar amount from text like "$10.00 discount applied"
    const match = confirmationText.match(/\$?([\d.]+)/);
    return match ? parseFloat(match[1]) : 0;
  }

  /**
   * View loyalty transaction history
   * @returns Array of transaction descriptions
   */
  async getTransactionHistory(): Promise<string[]> {
    await this.transactionHistory.waitFor({ state: 'visible' });
    const transactions = await this.transactionHistory
      .locator('[data-test="transaction-row"]')
      .all();

    const history: string[] = [];
    for (const transaction of transactions) {
      const text = await transaction.textContent();
      if (text) {
        history.push(text.trim());
      }
    }

    return history;
  }

  /**
   * Check if user is enrolled in loyalty program
   * @returns true if enrolled, false otherwise
   */
  async isEnrolled(): Promise<boolean> {
    return await this.isVisible(this.pointsBalance);
  }
}
