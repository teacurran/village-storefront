import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Category browsing page
 */
export class CategoryPage extends BasePage {
  readonly categoryTitle: Locator;
  readonly productGrid: Locator;
  readonly breadcrumbs: Locator;
  readonly filterPanel: Locator;
  readonly sortDropdown: Locator;

  constructor(page: Page) {
    super(page);
    this.categoryTitle = page.locator('[data-test="category-title"]');
    this.productGrid = page.locator('[data-test="product-grid"]');
    this.breadcrumbs = page.locator('[data-test="breadcrumbs"]');
    this.filterPanel = page.locator('[data-test="filter-panel"]');
    this.sortDropdown = page.locator('[data-test="sort-dropdown"]');
  }

  async getCategoryTitle(): Promise<string> {
    return await this.getText(this.categoryTitle);
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

  async getProductNames(): Promise<string[]> {
    const productCards = await this.productGrid.locator('[data-test="product-card"]').all();
    const names: string[] = [];
    for (const card of productCards) {
      const name = await card.locator('[data-test="product-name"]').textContent();
      if (name) names.push(name.trim());
    }
    return names;
  }

  async sortBy(option: string): Promise<void> {
    await this.sortDropdown.selectOption({ label: option });
    await this.page.waitForLoadState('networkidle');
  }
}
