import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Checkout flow
 */
export class CheckoutPage extends BasePage {
  readonly emailInput: Locator;
  readonly firstNameInput: Locator;
  readonly lastNameInput: Locator;
  readonly addressLine1Input: Locator;
  readonly cityInput: Locator;
  readonly stateSelect: Locator;
  readonly zipInput: Locator;
  readonly phoneInput: Locator;
  readonly shippingMethodSelect: Locator;
  readonly paymentFrame: Locator;
  readonly placeOrderButton: Locator;
  readonly orderSummary: Locator;
  readonly giftCardInput: Locator;
  readonly applyGiftCardButton: Locator;
  readonly loyaltyPointsToggle: Locator;

  constructor(page: Page) {
    super(page);
    this.emailInput = page.locator('[data-test="email"]');
    this.firstNameInput = page.locator('[data-test="first-name"]');
    this.lastNameInput = page.locator('[data-test="last-name"]');
    this.addressLine1Input = page.locator('[data-test="address-line1"]');
    this.cityInput = page.locator('[data-test="city"]');
    this.stateSelect = page.locator('[data-test="state"]');
    this.zipInput = page.locator('[data-test="zip"]');
    this.phoneInput = page.locator('[data-test="phone"]');
    this.shippingMethodSelect = page.locator('[data-test="shipping-method"]');
    this.paymentFrame = page.frameLocator('[data-test="payment-frame"]');
    this.placeOrderButton = page.locator('[data-test="place-order"]');
    this.orderSummary = page.locator('[data-test="order-summary"]');
    this.giftCardInput = page.locator('[data-test="gift-card-code"]');
    this.applyGiftCardButton = page.locator('[data-test="apply-gift-card"]');
    this.loyaltyPointsToggle = page.locator('[data-test="use-loyalty-points"]');
  }

  async fillShippingInfo(info: {
    email: string;
    firstName: string;
    lastName: string;
    address: string;
    city: string;
    state: string;
    zip: string;
    phone: string;
  }): Promise<void> {
    await this.fillField(this.emailInput, info.email);
    await this.fillField(this.firstNameInput, info.firstName);
    await this.fillField(this.lastNameInput, info.lastName);
    await this.fillField(this.addressLine1Input, info.address);
    await this.fillField(this.cityInput, info.city);
    await this.stateSelect.selectOption(info.state);
    await this.fillField(this.zipInput, info.zip);
    await this.fillField(this.phoneInput, info.phone);
  }

  async selectShippingMethod(method: string): Promise<void> {
    await this.shippingMethodSelect.selectOption({ label: method });
    await this.page.waitForTimeout(1000); // Wait for shipping cost calculation
  }

  async applyGiftCard(code: string): Promise<void> {
    await this.fillField(this.giftCardInput, code);
    await this.clickButton(this.applyGiftCardButton);
    await this.page.waitForTimeout(500);
  }

  async redeemLoyaltyPoints(): Promise<void> {
    await this.loyaltyPointsToggle.check();
    await this.page.waitForTimeout(500);
  }

  async fillPaymentInfo(info: {
    cardNumber: string;
    expiry: string;
    cvc: string;
    zip: string;
  }): Promise<void> {
    // Stripe Elements integration - simplified for test
    const cardNumberInput = this.paymentFrame.locator('[name="cardnumber"]');
    const expiryInput = this.paymentFrame.locator('[name="exp-date"]');
    const cvcInput = this.paymentFrame.locator('[name="cvc"]');
    const zipInput = this.paymentFrame.locator('[name="postal"]');

    await cardNumberInput.fill(info.cardNumber);
    await expiryInput.fill(info.expiry);
    await cvcInput.fill(info.cvc);
    await zipInput.fill(info.zip);
  }

  async placeOrder(): Promise<void> {
    await this.clickButton(this.placeOrderButton);
    await this.page.waitForURL('**/order-confirmation/**', { timeout: 15000 });
  }

  async getOrderTotal(): Promise<string> {
    const totalElement = this.orderSummary.locator('[data-test="total-amount"]');
    return await this.getText(totalElement);
  }
}
