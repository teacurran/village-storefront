import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Platform Admin Console
 */
export class PlatformConsolePage extends BasePage {
  readonly tenantList: Locator;
  readonly createTenantButton: Locator;
  readonly searchTenantsInput: Locator;
  readonly impersonateButtons: Locator;
  readonly auditLogLink: Locator;
  readonly metricsLink: Locator;
  readonly exitImpersonationButton: Locator;
  readonly impersonationBanner: Locator;
  readonly impersonationReasonModal: Locator;
  readonly reasonTextarea: Locator;
  readonly confirmImpersonationButton: Locator;

  constructor(page: Page) {
    super(page);
    this.tenantList = page.locator('[data-test="tenant-list"]');
    this.createTenantButton = page.locator('[data-test="create-tenant"]');
    this.searchTenantsInput = page.locator('[data-test="search-tenants"]');
    this.impersonateButtons = page.locator('[data-test="impersonate-tenant"]');
    this.auditLogLink = page.locator('[data-test="audit-log"]');
    this.metricsLink = page.locator('[data-test="platform-metrics"]');
    this.exitImpersonationButton = page.locator('[data-test="exit-impersonation"]');
    this.impersonationBanner = page.locator('[data-test="impersonation-banner"]');
    this.impersonationReasonModal = page.locator('[data-test="impersonation-reason-modal"]');
    this.reasonTextarea = page.locator('[data-test="impersonation-reason"]');
    this.confirmImpersonationButton = page.locator('[data-test="confirm-impersonation"]');
  }

  async gotoPlatformConsole(): Promise<void> {
    await this.goto('/platform');
    await this.waitForNavigation();
  }

  async searchTenants(query: string): Promise<void> {
    await this.fillField(this.searchTenantsInput, query);
    await this.page.waitForTimeout(500);
  }

  async getTenantCount(): Promise<number> {
    const tenants = await this.tenantList.locator('[data-test="tenant-row"]').all();
    return tenants.length;
  }

  async impersonateTenant(tenantIndex: number, reason: string): Promise<void> {
    const buttons = await this.impersonateButtons.all();
    if (buttons[tenantIndex]) {
      await buttons[tenantIndex].click();
      await this.page.waitForSelector('[data-test="impersonation-reason-modal"]');
      await this.fillField(this.reasonTextarea, reason);
      await this.clickButton(this.confirmImpersonationButton);
      await this.page.waitForTimeout(1000);
    }
  }

  async isImpersonating(): Promise<boolean> {
    return await this.isVisible(this.impersonationBanner);
  }

  async exitImpersonation(): Promise<void> {
    await this.clickButton(this.exitImpersonationButton);
    await this.page.waitForTimeout(500);
  }

  async navigateToAuditLog(): Promise<void> {
    await this.clickButton(this.auditLogLink);
    await this.waitForNavigation();
  }

  async navigateToMetrics(): Promise<void> {
    await this.clickButton(this.metricsLink);
    await this.waitForNavigation();
  }

  async createTenant(tenantData: {
    name: string;
    subdomain: string;
    adminEmail: string;
  }): Promise<void> {
    await this.clickButton(this.createTenantButton);
    await this.page.waitForSelector('[data-test="tenant-form"]');

    await this.fillField(
      this.page.locator('[data-test="tenant-name"]'),
      tenantData.name
    );
    await this.fillField(
      this.page.locator('[data-test="tenant-subdomain"]'),
      tenantData.subdomain
    );
    await this.fillField(
      this.page.locator('[data-test="tenant-admin-email"]'),
      tenantData.adminEmail
    );

    await this.clickButton(this.page.locator('[data-test="save-tenant"]'));
    await this.page.waitForTimeout(1000);
  }
}
