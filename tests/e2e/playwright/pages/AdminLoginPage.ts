import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Admin Login
 */
export class AdminLoginPage extends BasePage {
  readonly emailInput: Locator;
  readonly passwordInput: Locator;
  readonly loginButton: Locator;
  readonly errorMessage: Locator;
  readonly forgotPasswordLink: Locator;

  constructor(page: Page) {
    super(page);
    this.emailInput = page.locator('[data-test="admin-email"]');
    this.passwordInput = page.locator('[data-test="admin-password"]');
    this.loginButton = page.locator('[data-test="admin-login-button"]');
    this.errorMessage = page.locator('[data-test="login-error"]');
    this.forgotPasswordLink = page.locator('[data-test="forgot-password"]');
  }

  async gotoAdminLogin(): Promise<void> {
    await this.goto('/admin/login');
    await this.waitForNavigation();
  }

  async login(email: string, password: string): Promise<void> {
    await this.fillField(this.emailInput, email);
    await this.fillField(this.passwordInput, password);
    await this.clickButton(this.loginButton);
    await this.page.waitForURL('**/admin/**', { timeout: 10000 });
  }

  async hasError(): Promise<boolean> {
    return await this.isVisible(this.errorMessage);
  }

  async getErrorMessage(): Promise<string> {
    return await this.getText(this.errorMessage);
  }
}
