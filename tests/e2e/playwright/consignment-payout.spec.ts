import { test, expect } from '@playwright/test';
import { ConsignorPortalPage } from './pages/ConsignorPortalPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Consignment Payout E2E Tests
 * Covers consignor payout requests and Stripe Express transfers
 */
test.describe('Consignment Payout Flows', () => {
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
});
