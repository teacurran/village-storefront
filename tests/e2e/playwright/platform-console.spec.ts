import { test, expect } from '@playwright/test';
import { PlatformConsolePage } from './pages/PlatformConsolePage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';

/**
 * Platform Console E2E Tests
 * Tests platform admin functionality including tenant management and impersonation
 */
test.describe('Platform Admin Console', () => {
  test.beforeEach(async ({ page }) => {
    // Assume platform admin is logged in via separate authentication
    // In real scenario, would handle platform-level auth here
  });

  test('should display tenant list', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    await expect(platformConsole.tenantList).toBeVisible();

    const tenantCount = await platformConsole.getTenantCount();
    expect(tenantCount).toBeGreaterThan(0);
  });

  test('should search for tenants', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    const initialCount = await platformConsole.getTenantCount();

    await platformConsole.searchTenants('test');
    await page.waitForTimeout(500);

    const filteredCount = await platformConsole.getTenantCount();
    expect(filteredCount).toBeLessThanOrEqual(initialCount);
  });

  test('should impersonate tenant with reason', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Impersonate first tenant
    await platformConsole.impersonateTenant(
      0,
      'Testing checkout flow for support ticket #12345'
    );

    // Verify impersonation banner is visible
    const isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(true);

    await expect(platformConsole.impersonationBanner).toBeVisible();
  });

  test('should exit impersonation', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Start impersonation
    await platformConsole.impersonateTenant(
      0,
      'Testing impersonation exit flow'
    );

    // Verify impersonating
    let isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(true);

    // Exit impersonation
    await platformConsole.exitImpersonation();

    // Verify banner gone
    isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(false);
  });

  test('should navigate to audit log', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();
    await platformConsole.navigateToAuditLog();

    await expect(page).toHaveURL(/.*\/platform\/audit.*/);
    await expect(page.locator('[data-test="audit-log-table"]')).toBeVisible();
  });

  test('should navigate to platform metrics', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();
    await platformConsole.navigateToMetrics();

    await expect(page).toHaveURL(/.*\/platform\/metrics.*/);
    await expect(page.locator('[data-test="metrics-dashboard"]')).toBeVisible();
  });

  test('should create new tenant', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    const initialCount = await platformConsole.getTenantCount();

    await platformConsole.createTenant({
      name: 'Test Store',
      subdomain: 'teststore',
      adminEmail: 'admin@teststore.example.com',
    });

    // Verify tenant was added
    const newCount = await platformConsole.getTenantCount();
    expect(newCount).toBeGreaterThan(initialCount);
  });

  test('should enforce impersonation reason requirement', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Click impersonate button
    const buttons = await platformConsole.impersonateButtons.all();
    if (buttons[0]) {
      await buttons[0].click();

      // Verify reason modal appears
      await expect(platformConsole.impersonationReasonModal).toBeVisible();

      // Try to confirm without reason (should fail or show validation error)
      await platformConsole.confirmImpersonationButton.click();

      // Modal should still be visible (validation failed)
      await expect(platformConsole.impersonationReasonModal).toBeVisible();
    }
  });
});
