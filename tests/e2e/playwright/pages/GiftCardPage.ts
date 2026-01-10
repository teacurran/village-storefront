import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Gift Card operations
 * Covers balance checking, code application during checkout, and redemption
 */
export class GiftCardPage extends BasePage {
  readonly giftCardCodeInput: Locator;
  readonly applyGiftCardButton: Locator;
  readonly giftCardApplied: Locator;
  readonly giftCardError: Locator;
  readonly balanceDisplay: Locator;
  readonly checkBalanceButton: Locator;
  readonly balanceResult: Locator;
  readonly removeGiftCardButton: Locator;
  readonly appliedAmount: Locator;

  constructor(page: Page) {
    super(page);
    this.giftCardCodeInput = page.locator('[data-test="gift-card-code-input"]');
    this.applyGiftCardButton = page.locator('[data-test="apply-gift-card"]');
    this.giftCardApplied = page.locator('[data-test="gift-card-applied"]');
    this.giftCardError = page.locator('[data-test="gift-card-error"]');
    this.balanceDisplay = page.locator('[data-test="gift-card-balance"]');
    this.checkBalanceButton = page.locator('[data-test="check-balance-button"]');
    this.balanceResult = page.locator('[data-test="balance-result"]');
    this.removeGiftCardButton = page.locator('[data-test="remove-gift-card"]');
    this.appliedAmount = page.locator('[data-test="gift-card-applied-amount"]');
  }

  /**
   * Navigate to gift card balance check page
   */
  async gotoGiftCardPage(): Promise<void> {
    await this.goto('/giftcards/balance');
    await this.waitForTenantContext();
  }

  /**
   * Check gift card balance
   * @param code Gift card code
   * @returns Balance as number (or 0 if invalid)
   */
  async checkBalance(code: string): Promise<number> {
    await this.fillField(this.giftCardCodeInput, code);
    await this.clickButton(this.checkBalanceButton);

    // Wait for balance API response
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/giftcards/balance') &&
        (response.status() === 200 || response.status() === 404)
    );

    // Wait for result to display
    await Promise.race([
      this.balanceResult.waitFor({ state: 'visible', timeout: 5000 }),
      this.giftCardError.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {}),
    ]);

    if (await this.isVisible(this.giftCardError)) {
      return 0; // Invalid card
    }

    const balanceText = await this.getText(this.balanceResult);
    // Extract dollar amount from text like "$50.00" or "Balance: $50.00"
    const match = balanceText.match(/\$?([\d.]+)/);
    return match ? parseFloat(match[1]) : 0;
  }

  /**
   * Apply gift card to checkout
   * @param code Gift card code
   * @returns true if applied successfully, false otherwise
   */
  async applyGiftCard(code: string): Promise<boolean> {
    await this.fillField(this.giftCardCodeInput, code);
    await this.clickButton(this.applyGiftCardButton);

    // Wait for redemption API response
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/giftcards/redeem') &&
        (response.status() === 200 || response.status() === 400)
    );

    // Check if card was applied or error occurred
    await Promise.race([
      this.giftCardApplied.waitFor({ state: 'visible', timeout: 5000 }),
      this.giftCardError.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {}),
    ]);

    return await this.isVisible(this.giftCardApplied);
  }

  /**
   * Get the amount applied from gift card
   * @returns Dollar amount applied as number
   */
  async getAppliedAmount(): Promise<number> {
    if (!(await this.isVisible(this.appliedAmount))) {
      return 0;
    }

    const amountText = await this.getText(this.appliedAmount);
    // Extract dollar amount from text like "-$50.00" or "$50.00 applied"
    const match = amountText.match(/\$?([\d.]+)/);
    return match ? parseFloat(match[1]) : 0;
  }

  /**
   * Remove applied gift card from checkout
   */
  async removeGiftCard(): Promise<void> {
    if (await this.isVisible(this.removeGiftCardButton)) {
      await this.clickButton(this.removeGiftCardButton);

      // Wait for gift card to be removed
      await this.giftCardApplied.waitFor({ state: 'hidden', timeout: 5000 });
    }
  }

  /**
   * Check if gift card is currently applied
   * @returns true if gift card is applied, false otherwise
   */
  async isGiftCardApplied(): Promise<boolean> {
    return await this.isVisible(this.giftCardApplied);
  }

  /**
   * Get error message for invalid gift card
   * @returns Error message text
   */
  async getErrorMessage(): Promise<string> {
    if (await this.isVisible(this.giftCardError)) {
      return await this.getText(this.giftCardError);
    }
    return '';
  }
}
