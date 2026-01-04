import { Page, Locator } from '@playwright/test';

/**
 * Base page object with common functionality for all pages
 */
export class BasePage {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  async goto(path: string): Promise<void> {
    await this.page.goto(path);
  }

  async waitForNavigation(): Promise<void> {
    await this.page.waitForLoadState('networkidle');
  }

  async fillField(locator: Locator, value: string): Promise<void> {
    await locator.fill(value);
  }

  async clickButton(locator: Locator): Promise<void> {
    await locator.click();
  }

  async getText(locator: Locator): Promise<string> {
    return await locator.textContent() || '';
  }

  async isVisible(locator: Locator): Promise<boolean> {
    return await locator.isVisible();
  }

  /**
   * Wait for tenant-specific branding to load (multi-tenant indicator)
   */
  async waitForTenantContext(): Promise<void> {
    await this.page.waitForFunction(
      () => document.body.dataset.tenantLoaded === 'true',
      { timeout: 5000 }
    ).catch(() => {
      // Graceful fallback if tenant context not set
    });
  }
}
