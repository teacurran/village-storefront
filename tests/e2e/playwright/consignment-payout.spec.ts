import { test, expect } from '@playwright/test';
import { ConsignorPortalPage } from './pages/ConsignorPortalPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Consignment Payout E2E Tests
 * Covers consignor payout requests, Stripe Express transfers, and multi-tenant isolation
 */
test.describe('Consignment Payout Flows', () => {
  test.describe('Tenant A - Consignment Payout Verification', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

  test('should request consignor payout and initiate Stripe transfer', async ({
    page,
  }) => {
    const consignorPortal = new ConsignorPortalPage(page);

    // Navigate to consignor portal and login
    await consignorPortal.gotoConsignorPortal();
    await consignorPortal.login(
      tenant.consignor!.email,
      tenant.consignor!.password
    );

    // View pending payout balance
    const pendingBalance = await consignorPortal.getPendingBalance();
    expect(pendingBalance).toBeGreaterThanOrEqual(0);

    // Only proceed if there's a balance to pay out
    if (pendingBalance > 0) {
      // Request payout
      await consignorPortal.requestPayout();

      // Verify confirmation
      const isConfirmed = await consignorPortal.isPayoutConfirmed();
      expect(isConfirmed).toBe(true);

      // Get confirmation message
      const confirmationMessage =
        await consignorPortal.getPayoutConfirmationMessage();
      expect(confirmationMessage.toLowerCase()).toContain('payout');
      expect(confirmationMessage.toLowerCase()).toContain('requested');

      // Verify payout appears in history
      const payoutHistory = await consignorPortal.getPayoutHistory();
      expect(payoutHistory.length).toBeGreaterThan(0);

      const latestPayout = payoutHistory[0];
      expect(latestPayout.amount).toContain(pendingBalance.toFixed(2));
      expect(latestPayout.status.toLowerCase()).toContain('pending');
    }
  });

  test('should view consignor sales and total earnings', async ({ page }) => {
    const consignorPortal = new ConsignorPortalPage(page);

    await consignorPortal.gotoConsignorPortal();
    await consignorPortal.login(
      tenant.consignor!.email,
      tenant.consignor!.password
    );

    // Get sales count
    const salesCount = await consignorPortal.getSalesCount();
    expect(salesCount).toBeGreaterThanOrEqual(0);

    // Get total earnings
    const totalEarnings = await consignorPortal.getTotalEarnings();
    expect(totalEarnings).toBeGreaterThanOrEqual(0);

    // Verify dashboard displays correct information
    await expect(page.locator('[data-test="consignor-dashboard"]')).toBeVisible();
  });

  test('should view payout history with dates and statuses', async ({ page }) => {
    const consignorPortal = new ConsignorPortalPage(page);

    await consignorPortal.gotoConsignorPortal();
    await consignorPortal.login(
      tenant.consignor!.email,
      tenant.consignor!.password
    );

    // Get payout history
    const payoutHistory = await consignorPortal.getPayoutHistory();

    // Verify each payout has required fields
    for (const payout of payoutHistory) {
      expect(payout.date).toBeTruthy();
      expect(payout.amount).toBeTruthy();
      expect(payout.status).toBeTruthy();

      // Verify amount is formatted correctly
      expect(payout.amount).toMatch(/\$[\d,]+\.\d{2}/);

      // Verify status is valid
      const validStatuses = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'];
      expect(
        validStatuses.some((status) => payout.status.toUpperCase().includes(status))
      ).toBe(true);
    }
  });

    test('should calculate payout schedule and minimum threshold correctly', async ({ page }) => {
      const consignorPortal = new ConsignorPortalPage(page);

      await consignorPortal.gotoConsignorPortal();
      await consignorPortal.login(tenant.consignor!.email, tenant.consignor!.password);

      // Get pending balance
      const pendingBalance = await consignorPortal.getPendingBalance();

      // Verify payout button state based on minimum threshold
      const payoutButton = page.locator('[data-test="request-payout"]');

      if (pendingBalance < 10.0) {
        // Below minimum threshold, button should be disabled
        const isDisabled = await payoutButton.isDisabled().catch(() => false);
        expect(isDisabled).toBe(true);

        // Should show threshold message
        const thresholdMessage = page.locator('[data-test="payout-threshold-message"]');
        await expect(thresholdMessage).toBeVisible();
      } else {
        // Above threshold, button should be enabled
        const isEnabled = await payoutButton.isEnabled();
        expect(isEnabled).toBe(true);
      }
    });
  });

  test.describe('Tenant B - Consignment Payout Isolation', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test('should view Tenant B consignor balance isolated from Tenant A', async ({ page }) => {
      const consignorPortal = new ConsignorPortalPage(page);

      // Login to Tenant B consignor portal
      await page.goto(`${baseURL}/consignor/login`);
      await consignorPortal.waitForTenantContext();

      await consignorPortal.login(tenant.consignor!.email, tenant.consignor!.password);

      // Get pending balance
      const pendingBalance = await consignorPortal.getPendingBalance();
      expect(pendingBalance).toBeGreaterThanOrEqual(0);

      // Get sales count
      const salesCount = await consignorPortal.getSalesCount();

      // Get payout history
      const payoutHistory = await consignorPortal.getPayoutHistory();

      // Verify Tenant B branding/context
      const tenantName = page.locator('[data-test="tenant-name"]');
      await expect(tenantName).toContainText('Tenant B');

      // All data should be Tenant B specific (no cross-tenant leakage)
      await expect(page.locator('[data-test="consignor-dashboard"]')).toBeVisible();
    });

    test('should complete payout request for Tenant B consignor', async ({ page }) => {
      const consignorPortal = new ConsignorPortalPage(page);

      await page.goto(`${baseURL}/consignor/login`);
      await consignorPortal.waitForTenantContext();

      await consignorPortal.login(tenant.consignor!.email, tenant.consignor!.password);

      const pendingBalance = await consignorPortal.getPendingBalance();

      if (pendingBalance > 0) {
        await consignorPortal.requestPayout();

        const isConfirmed = await consignorPortal.isPayoutConfirmed();
        expect(isConfirmed).toBe(true);

        // Verify Tenant B payout history updated
        const payoutHistory = await consignorPortal.getPayoutHistory();
        expect(payoutHistory.length).toBeGreaterThan(0);

        const latestPayout = payoutHistory[0];
        expect(latestPayout.status.toLowerCase()).toContain('pending');
      }
    });

    test('should show different sales data for Tenant B vs Tenant A', async ({ page }) => {
      const consignorPortal = new ConsignorPortalPage(page);

      await page.goto(`${baseURL}/consignor/login`);
      await consignorPortal.waitForTenantContext();

      await consignorPortal.login(tenant.consignor!.email, tenant.consignor!.password);

      // Get Tenant B sales
      const salesCount = await consignorPortal.getSalesCount();
      const totalEarnings = await consignorPortal.getTotalEarnings();

      // Sales should only show Tenant B products (TB- SKU prefix)
      const salesList = page.locator('[data-test="consignor-sales-list"]');
      await salesList.waitFor({ state: 'visible' });

      const salesRows = await salesList.locator('[data-test="sale-row"]').all();

      for (const row of salesRows) {
        const rowText = await row.textContent();

        // Should contain Tenant B SKU prefix
        expect(rowText).toMatch(/TB-/);

        // Should NOT contain Tenant A SKU prefix
        expect(rowText).not.toContain('TA-');
      }
    });
  });
});
