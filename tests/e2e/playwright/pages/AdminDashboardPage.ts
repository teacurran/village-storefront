import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Admin Dashboard
 */
export class AdminDashboardPage extends BasePage {
  readonly navigation: Locator;
  readonly productsLink: Locator;
  readonly ordersLink: Locator;
  readonly inventoryLink: Locator;
  readonly consignmentLink: Locator;
  readonly reportsLink: Locator;
  readonly settingsLink: Locator;
  readonly userMenu: Locator;
  readonly logoutButton: Locator;
  readonly dashboardStats: Locator;

  constructor(page: Page) {
    super(page);
    this.navigation = page.locator('[data-test="admin-nav"]');
    this.productsLink = this.navigation.locator('[data-test="nav-products"]');
    this.ordersLink = this.navigation.locator('[data-test="nav-orders"]');
    this.inventoryLink = this.navigation.locator('[data-test="nav-inventory"]');
    this.consignmentLink = this.navigation.locator('[data-test="nav-consignment"]');
    this.reportsLink = this.navigation.locator('[data-test="nav-reports"]');
    this.settingsLink = this.navigation.locator('[data-test="nav-settings"]');
    this.userMenu = page.locator('[data-test="user-menu"]');
    this.logoutButton = page.locator('[data-test="logout-button"]');
    this.dashboardStats = page.locator('[data-test="dashboard-stats"]');
  }

  async gotoDashboard(): Promise<void> {
    await this.goto('/admin');
    await this.waitForNavigation();
  }

  async navigateToProducts(): Promise<void> {
    await this.clickButton(this.productsLink);
    await this.waitForNavigation();
  }

  async navigateToInventory(): Promise<void> {
    await this.clickButton(this.inventoryLink);
    await this.waitForNavigation();
  }

  async navigateToConsignment(): Promise<void> {
    await this.clickButton(this.consignmentLink);
    await this.waitForNavigation();
  }

  async navigateToReports(): Promise<void> {
    await this.clickButton(this.reportsLink);
    await this.waitForNavigation();
  }

  async logout(): Promise<void> {
    await this.clickButton(this.userMenu);
    await this.clickButton(this.logoutButton);
    await this.page.waitForURL('**/admin/login');
  }

  async getSalesTotal(): Promise<string> {
    const salesElement = this.dashboardStats.locator('[data-test="total-sales"]');
    return await this.getText(salesElement);
  }

  async getOrderCount(): Promise<string> {
    const ordersElement = this.dashboardStats.locator('[data-test="order-count"]');
    return await this.getText(ordersElement);
  }
}
