import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Product Detail page
 */
export class ProductPage extends BasePage {
  readonly productTitle: Locator;
  readonly productPrice: Locator;
  readonly variantSelector: Locator;
  readonly quantityInput: Locator;
  readonly addToCartButton: Locator;
  readonly productImage: Locator;
  readonly productDescription: Locator;

  constructor(page: Page) {
    super(page);
    this.productTitle = page.locator('[data-test="product-title"]');
    this.productPrice = page.locator('[data-test="product-price"]');
    this.variantSelector = page.locator('[data-test="variant-selector"]');
    this.quantityInput = page.locator('[data-test="quantity-input"]');
    this.addToCartButton = page.locator('[data-test="add-to-cart"]');
    this.productImage = page.locator('[data-test="product-image"]');
    this.productDescription = page.locator('[data-test="product-description"]');
  }

  async selectVariant(variantName: string): Promise<void> {
    await this.variantSelector.selectOption({ label: variantName });
  }

  async setQuantity(quantity: number): Promise<void> {
    await this.fillField(this.quantityInput, quantity.toString());
  }

  async addToCart(): Promise<void> {
    await this.clickButton(this.addToCartButton);
    // Wait for add-to-cart animation/confirmation
    await this.page.waitForTimeout(500);
  }

  async getProductTitle(): Promise<string> {
    return await this.getText(this.productTitle);
  }

  async getProductPrice(): Promise<string> {
    return await this.getText(this.productPrice);
  }
}
