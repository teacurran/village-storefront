import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Storefront home and product browsing
 */
export class StorefrontPage extends BasePage {
  readonly productGrid: Locator;
  readonly searchInput: Locator;
  readonly searchButton: Locator;
  readonly cartLink: Locator;
  readonly cartBadge: Locator;

  constructor(page: Page) {
    super(page);
    this.productGrid = page.locator('[data-test="product-grid"]');
    this.searchInput = page.locator('[data-test="search-input"]');
    this.searchButton = page.locator('[data-test="search-button"]');
    this.cartLink = page.locator('[data-test="cart-link"]');
    this.cartBadge = page.locator('[data-test="cart-badge"]');
  }

  async gotoHome(): Promise<void> {
    await this.goto('/');
    await this.waitForTenantContext();
  }

  async searchProducts(query: string): Promise<void> {
    await this.fillField(this.searchInput, query);
    await this.clickButton(this.searchButton);
    await this.waitForNavigation();
  }

  async getProductCount(): Promise<number> {
    const products = await this.productGrid.locator('[data-test="product-card"]').all();
    return products.length;
  }

  async selectProduct(index: number = 0): Promise<void> {
    const products = await this.productGrid.locator('[data-test="product-card"]').all();
    if (products[index]) {
      await products[index].click();
      await this.waitForNavigation();
    }
  }

  async getCartItemCount(): Promise<number> {
    const badgeText = await this.getText(this.cartBadge);
    return parseInt(badgeText) || 0;
  }

  async goToCart(): Promise<void> {
    await this.clickButton(this.cartLink);
    await this.waitForNavigation();
  }
}
