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
    this.tenantList = page.locator('[data-test="stores-table"]');
    this.createTenantButton = page.locator('[data-test="create-tenant"]');
    this.searchTenantsInput = page.locator('[data-test="search-stores"]');
    this.impersonateButtons = page.locator('[data-test="impersonate-btn"]');
    this.auditLogLink = page.locator('[data-test="audit-log"]');
    this.metricsLink = page.locator('[data-test="platform-metrics"]');
    this.exitImpersonationButton = page.locator('[data-test="end-impersonation"]');
    this.impersonationBanner = page.locator('[data-test="impersonation-banner"]');
    this.impersonationReasonModal = page.locator('[data-test="impersonate-dialog"]');
    this.reasonTextarea = page.locator('[data-test="impersonate-reason"]');
    this.confirmImpersonationButton = page.locator('[data-test="start-impersonate"]');
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

  async impersonateTenant(tenantIndex: number, reason: string, ticketNumber: string = 'TICKET-12345'): Promise<void> {
    const buttons = await this.impersonateButtons.all();
    if (buttons[tenantIndex]) {
      await buttons[tenantIndex].click();
      await this.page.waitForSelector('[data-test="impersonate-dialog"]');
      await this.fillField(this.reasonTextarea, reason);
      await this.fillField(this.page.locator('[data-test="impersonate-ticket"]'), ticketNumber);
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

  /**
   * Get tenant health metrics
   * @param tenantIndex Index of tenant in list
   * @returns Health metrics object
   */
  async getTenantHealth(tenantIndex: number): Promise<{
    status: string;
    orderCount: number;
    revenue: number;
  }> {
    const rows = await this.tenantList.locator('[data-test="tenant-row"]').all();

    if (!rows[tenantIndex]) {
      throw new Error(`Tenant at index ${tenantIndex} not found`);
    }

    const row = rows[tenantIndex];
    const status =
      (await row.locator('[data-test="tenant-status"]').textContent()) || 'UNKNOWN';
    const orderCountText =
      (await row.locator('[data-test="tenant-order-count"]').textContent()) || '0';
    const revenueText =
      (await row.locator('[data-test="tenant-revenue"]').textContent()) || '$0';

    const orderCount = parseInt(orderCountText.replace(/\D/g, '')) || 0;
    const revenueMatch = revenueText.match(/\$?([\d,]+\.?\d*)/);
    const revenue = revenueMatch ? parseFloat(revenueMatch[1].replace(/,/g, '')) : 0;

    return {
      status: status.trim(),
      orderCount,
      revenue,
    };
  }

  /**
   * Verify impersonation audit trail entry
   * @param reason Impersonation reason
   * @returns true if audit entry found, false otherwise
   */
  async verifyImpersonationAuditTrail(reason: string): Promise<boolean> {
    await this.navigateToAuditLog();

    const auditTable = this.page.locator('[data-test="audit-log-table"]');
    await auditTable.waitFor({ state: 'visible' });

    const entries = await auditTable.locator('[data-test="audit-row"]').all();

    for (const entry of entries) {
      const action = await entry.locator('[data-test="audit-action"]').textContent();
      const details = await entry.locator('[data-test="audit-details"]').textContent();

      if (
        action?.includes('IMPERSONATION_START') &&
        details?.includes(reason)
      ) {
        return true;
      }
    }

    return false;
  }

  /**
   * Get impersonation session elapsed time
   * @returns Elapsed time string (e.g. "5m 23s")
   */
  async getImpersonationElapsedTime(): Promise<string> {
    if (!(await this.isImpersonating())) {
      return '';
    }

    const timerText = await this.impersonationBanner
      .locator('[data-test="impersonation-timer"]')
      .textContent();

    return timerText || '';
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
