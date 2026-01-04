import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Shopping Cart page
 */
export class CartPage extends BasePage {
  readonly cartItems: Locator;
  readonly cartTotal: Locator;
  readonly checkoutButton: Locator;
  readonly continueShoppingLink: Locator;
  readonly emptyCartMessage: Locator;
  readonly loyaltySummary: Locator;

  constructor(page: Page) {
    super(page);
    this.cartItems = page.locator('[data-test="cart-items"]');
    this.cartTotal = page.locator('[data-test="cart-total"]');
    this.checkoutButton = page.locator('[data-test="checkout-button"]');
    this.continueShoppingLink = page.locator('[data-test="continue-shopping"]');
    this.emptyCartMessage = page.locator('[data-test="empty-cart-message"]');
    this.loyaltySummary = page.locator('[data-test="loyalty-summary"]');
  }

  async gotoCart(): Promise<void> {
    await this.goto('/cart');
    await this.waitForNavigation();
  }

  async getItemCount(): Promise<number> {
    const items = await this.cartItems.locator('[data-test="cart-item"]').all();
    return items.length;
  }

  async removeItem(index: number): Promise<void> {
    const removeButtons = await this.cartItems
      .locator('[data-test="remove-item"]')
      .all();
    if (removeButtons[index]) {
      await removeButtons[index].click();
      await this.page.waitForTimeout(500);
    }
  }

  async updateQuantity(itemIndex: number, quantity: number): Promise<void> {
    const quantityInputs = await this.cartItems
      .locator('[data-test="item-quantity"]')
      .all();
    if (quantityInputs[itemIndex]) {
      await quantityInputs[itemIndex].fill(quantity.toString());
      await this.page.waitForTimeout(500);
    }
  }

  async getCartTotal(): Promise<string> {
    return await this.getText(this.cartTotal);
  }

  async proceedToCheckout(): Promise<void> {
    await this.clickButton(this.checkoutButton);
    await this.waitForNavigation();
  }

  async isCartEmpty(): Promise<boolean> {
    return await this.isVisible(this.emptyCartMessage);
  }

  async getLoyaltyPoints(): Promise<string | null> {
    if (await this.isVisible(this.loyaltySummary)) {
      return await this.getText(this.loyaltySummary);
    }
    return null;
  }
}
