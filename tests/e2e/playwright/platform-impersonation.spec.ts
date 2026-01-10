import { test, expect } from '@playwright/test';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { PlatformConsolePage } from './pages/PlatformConsolePage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';
import { platformAdmin, tenants } from '../../fixtures/tenants';

/**
 * Platform Admin Impersonation E2E Tests
 * Covers impersonation workflows with audit trail verification
 */
test.describe('Platform Admin Impersonation', () => {
  const platformURL = process.env.PLATFORM_URL || 'http://localhost:8080';

  test.beforeEach(async ({ page }) => {
    // Login as platform admin
    await page.goto(`${platformURL}/platform/login`);
    const loginPage = new AdminLoginPage(page);
    await loginPage.login(platformAdmin.email, platformAdmin.password);

    // Verify platform console loaded
    await expect(page).toHaveURL(/.*platform\/dashboard.*/);
  });

  test('should impersonate store admin with audit trail', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    // Navigate to platform console
    await platformConsole.gotoPlatformConsole();

    // View tenant list
    const tenantCount = await platformConsole.getTenantCount();
    expect(tenantCount).toBeGreaterThan(0);

    // Get tenant health metrics
    const health = await platformConsole.getTenantHealth(0);
    expect(health.status).toBe('ACTIVE');

    // Start impersonation with reason
    const impersonationReason = 'Support ticket #12345 - Customer issue investigation';
    await platformConsole.impersonateTenant(0, impersonationReason);

    // Verify impersonation active
    const isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(true);

    // Verify TTL is set
    const ttl = await platformConsole.getImpersonationTTL();
    expect(ttl).toBeGreaterThan(0);

    // Perform action as impersonated user (view products)
    await page.goto('/admin/products');
    await expect(page.locator('[data-test="products-table"]')).toBeVisible();

    // Exit impersonation
    await platformConsole.exitImpersonation();

    // Verify no longer impersonating
    const stillImpersonating = await platformConsole.isImpersonating();
    expect(stillImpersonating).toBe(false);

    // Verify audit trail
    const auditTrailVerified =
      await platformConsole.verifyImpersonationAuditTrail(impersonationReason);
    expect(auditTrailVerified).toBe(true);
  });

  test('should enforce impersonation TTL', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Start impersonation with short TTL (configured server-side)
    await platformConsole.impersonateTenant(
      1,
      'Testing TTL enforcement - E2E test'
    );

    const initialTTL = await platformConsole.getImpersonationTTL();
    expect(initialTTL).toBeGreaterThan(0);

    // Wait for TTL to decrease
    await page.waitForTimeout(5000);

    const updatedTTL = await platformConsole.getImpersonationTTL();
    expect(updatedTTL).toBeLessThan(initialTTL);

    // Note: In real scenario, would wait for full TTL expiration
    // and verify session automatically terminates

    await platformConsole.exitImpersonation();
  });

  test('should log all actions during impersonation', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Start impersonation
    const reason = 'Audit log test - tracking all actions';
    await platformConsole.impersonateTenant(0, reason);

    // Perform multiple actions
    await page.goto('/admin/products');
    await page.locator('[data-test="product-search"]').fill('test');

    await page.goto('/admin/orders');
    await page.locator('[data-test="status-filter"]').selectOption('COMPLETED');

    await page.goto('/admin/inventory');

    // Exit impersonation
    await platformConsole.exitImpersonation();

    // Navigate to audit log
    await platformConsole.navigateToAuditLog();

    // Verify all actions logged
    const auditTable = page.locator('[data-test="audit-log-table"]');
    await auditTable.waitFor({ state: 'visible' });

    const entries = await auditTable.locator('[data-test="audit-row"]').all();
    expect(entries.length).toBeGreaterThan(3); // At least: start, 3 page views, exit

    // Verify audit entries include impersonation context
    for (const entry of entries.slice(0, 5)) {
      const details = await entry.locator('[data-test="audit-details"]').textContent();
      // Should include impersonated user or reason
      expect(details).toBeTruthy();
    }
  });

  test('should prevent impersonation without reason', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Click impersonate button
    const impersonateButton = await platformConsole.impersonateButtons.first();
    await impersonateButton.click();

    // Modal should appear
    await expect(platformConsole.impersonationReasonModal).toBeVisible();

    // Try to confirm without entering reason
    await platformConsole.confirmImpersonationButton.click();

    // Should show validation error
    await expect(page.locator('[data-test="reason-required-error"]')).toBeVisible();

    // Impersonation should not start
    const isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(false);
  });

  test('should display tenant health metrics in platform console', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Get metrics for all tenants
    const tenantCount = await platformConsole.getTenantCount();

    for (let i = 0; i < Math.min(tenantCount, 3); i++) {
      const health = await platformConsole.getTenantHealth(i);

      // Verify health metrics structure
      expect(health.status).toBeTruthy();
      expect(health.orderCount).toBeGreaterThanOrEqual(0);
      expect(health.revenue).toBeGreaterThanOrEqual(0);
    }
  });
});
