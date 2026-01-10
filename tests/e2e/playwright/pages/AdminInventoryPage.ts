import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

/**
 * Page Object for Admin Inventory Management
 * Covers stock adjustments, transfers, and audit logs
 */
export class AdminInventoryPage extends BasePage {
  readonly inventoryTable: Locator;
  readonly productSearchInput: Locator;
  readonly locationFilter: Locator;
  readonly adjustStockButton: Locator;
  readonly adjustmentModal: Locator;
  readonly quantityInput: Locator;
  readonly reasonSelect: Locator;
  readonly reasonNotes: Locator;
  readonly confirmAdjustmentButton: Locator;
  readonly transferButton: Locator;
  readonly transferModal: Locator;
  readonly fromLocationSelect: Locator;
  readonly toLocationSelect: Locator;
  readonly transferQuantityInput: Locator;
  readonly confirmTransferButton: Locator;
  readonly auditLogButton: Locator;
  readonly auditLogTable: Locator;
  readonly adjustmentSuccess: Locator;
  readonly transferSuccess: Locator;

  constructor(page: Page) {
    super(page);
    this.inventoryTable = page.locator('[data-test="inventory-table"]');
    this.productSearchInput = page.locator('[data-test="product-search"]');
    this.locationFilter = page.locator('[data-test="location-filter"]');
    this.adjustStockButton = page.locator('[data-test="adjust-stock"]');
    this.adjustmentModal = page.locator('[data-test="adjustment-modal"]');
    this.quantityInput = page.locator('[data-test="quantity-input"]');
    this.reasonSelect = page.locator('[data-test="reason-select"]');
    this.reasonNotes = page.locator('[data-test="reason-notes"]');
    this.confirmAdjustmentButton = page.locator('[data-test="confirm-adjustment"]');
    this.transferButton = page.locator('[data-test="transfer-stock"]');
    this.transferModal = page.locator('[data-test="transfer-modal"]');
    this.fromLocationSelect = page.locator('[data-test="from-location"]');
    this.toLocationSelect = page.locator('[data-test="to-location"]');
    this.transferQuantityInput = page.locator('[data-test="transfer-quantity"]');
    this.confirmTransferButton = page.locator('[data-test="confirm-transfer"]');
    this.auditLogButton = page.locator('[data-test="view-audit-log"]');
    this.auditLogTable = page.locator('[data-test="audit-log-table"]');
    this.adjustmentSuccess = page.locator('[data-test="adjustment-success"]');
    this.transferSuccess = page.locator('[data-test="transfer-success"]');
  }

  /**
   * Navigate to inventory management page
   */
  async gotoInventory(): Promise<void> {
    await this.goto('/admin/inventory');
    await this.waitForTenantContext();
  }

  /**
   * Search for product in inventory
   * @param query Product name or SKU
   */
  async searchProduct(query: string): Promise<void> {
    await this.fillField(this.productSearchInput, query);
    await this.page.keyboard.press('Enter');
    await this.waitForNavigation();
  }

  /**
   * Select product from inventory table by row index
   * @param index Row index (0-based)
   */
  async selectProductByIndex(index: number): Promise<void> {
    const rows = await this.inventoryTable.locator('[data-test="inventory-row"]').all();
    if (rows[index]) {
      await rows[index].click();
    }
  }

  /**
   * Adjust stock quantity for selected product
   * @param quantity Quantity to adjust (positive or negative)
   * @param reason Reason code (e.g., 'RESTOCK', 'DAMAGED', 'SOLD')
   * @param notes Optional notes
   */
  async adjustStock(quantity: number, reason: string, notes?: string): Promise<void> {
    await this.clickButton(this.adjustStockButton);
    await this.adjustmentModal.waitFor({ state: 'visible' });

    await this.fillField(this.quantityInput, quantity.toString());
    await this.reasonSelect.selectOption({ value: reason });

    if (notes) {
      await this.fillField(this.reasonNotes, notes);
    }

    await this.clickButton(this.confirmAdjustmentButton);

    // Wait for adjustment API call
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/admin/inventory/adjust') &&
        response.status() === 200
    );

    await this.adjustmentSuccess.waitFor({ state: 'visible', timeout: 10000 });
  }

  /**
   * Create stock transfer between locations
   * @param quantity Quantity to transfer
   * @param fromLocation Source location
   * @param toLocation Destination location
   */
  async createTransfer(
    quantity: number,
    fromLocation: string,
    toLocation: string
  ): Promise<void> {
    await this.clickButton(this.transferButton);
    await this.transferModal.waitFor({ state: 'visible' });

    await this.fromLocationSelect.selectOption({ label: fromLocation });
    await this.toLocationSelect.selectOption({ label: toLocation });
    await this.fillField(this.transferQuantityInput, quantity.toString());

    await this.clickButton(this.confirmTransferButton);

    // Wait for transfer API call
    await this.page.waitForResponse(
      (response) =>
        response.url().includes('/admin/inventory/transfer') &&
        response.status() === 200
    );

    await this.transferSuccess.waitFor({ state: 'visible', timeout: 10000 });
  }

  /**
   * View audit log
   */
  async viewAuditLog(): Promise<void> {
    await this.clickButton(this.auditLogButton);
    await this.auditLogTable.waitFor({ state: 'visible' });
  }

  /**
   * Get audit log entries
   * @returns Array of audit log records
   */
  async getAuditLogEntries(): Promise<
    Array<{
      timestamp: string;
      user: string;
      action: string;
      details: string;
    }>
  > {
    await this.auditLogTable.waitFor({ state: 'visible' });
    const rows = await this.auditLogTable.locator('[data-test="audit-row"]').all();

    const entries: Array<{
      timestamp: string;
      user: string;
      action: string;
      details: string;
    }> = [];

    for (const row of rows) {
      const timestamp =
        (await row.locator('[data-test="audit-timestamp"]').textContent()) || '';
      const user = (await row.locator('[data-test="audit-user"]').textContent()) || '';
      const action = (await row.locator('[data-test="audit-action"]').textContent()) || '';
      const details = (await row.locator('[data-test="audit-details"]').textContent()) || '';

      entries.push({
        timestamp: timestamp.trim(),
        user: user.trim(),
        action: action.trim(),
        details: details.trim(),
      });
    }

    return entries;
  }

  /**
   * Get current stock level for product at location
   * @param productName Product name
   * @param location Location name
   * @returns Stock quantity
   */
  async getStockLevel(productName: string, location?: string): Promise<number> {
    if (location) {
      await this.locationFilter.selectOption({ label: location });
      await this.waitForNavigation();
    }

    await this.searchProduct(productName);

    const firstRow = this.inventoryTable.locator('[data-test="inventory-row"]').first();
    const stockText =
      (await firstRow.locator('[data-test="stock-quantity"]').textContent()) || '0';

    return parseInt(stockText.replace(/\D/g, '')) || 0;
  }

  /**
   * Filter inventory by location
   * @param location Location name
   */
  async filterByLocation(location: string): Promise<void> {
    await this.locationFilter.selectOption({ label: location });
    await this.waitForNavigation();
  }
}
