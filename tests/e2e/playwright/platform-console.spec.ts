import { test, expect } from '@playwright/test';
import { PlatformConsolePage } from './pages/PlatformConsolePage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';

/**
 * Platform Console E2E Tests
 * Tests platform admin functionality including dashboard, tenant management, and impersonation
 *
 * Acceptance Criteria (Task I5.T2):
 * - Dashboard shows KPIs, queue depth, alerts
 * - Store table supports filters + actions
 * - Impersonation banner persists across app, includes exit button + timer
 * - Impersonation disables destructive actions if reason missing
 * - Playwright spec covers impersonation start/stop and verifies audit log entry visible
 */
test.describe('Platform Admin Console', () => {
  test.beforeEach(async ({ page }) => {
    // Assume platform admin is logged in via separate authentication
    // In real scenario, would handle platform-level auth here
  });

  test('should display dashboard with KPIs and metrics', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Verify dashboard elements are visible
    await expect(page.locator('[data-test="platform-dashboard"]')).toBeVisible();
    await expect(page.locator('[data-test="platform-status"]')).toBeVisible();
    await expect(page.locator('[data-test="kpi-grid"]')).toBeVisible();

    // Verify key KPI cards are present
    await expect(page.locator('[data-test="kpi-tenants"]')).toBeVisible();
    await expect(page.locator('[data-test="kpi-users"]')).toBeVisible();
    await expect(page.locator('[data-test="kpi-queue"]')).toBeVisible();
    await expect(page.locator('[data-test="kpi-latency"]')).toBeVisible();

    // Verify alert feed section
    await expect(page.locator('[data-test="alert-feed"]')).toBeVisible();

    // Verify quick actions
    await expect(page.locator('[data-test="quick-actions"]')).toBeVisible();
  });

  test('should navigate to store directory from dashboard', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await platformConsole.gotoPlatformConsole();

    // Click "Manage Stores" quick action
    await page.locator('a[href="/admin/platform/stores"]').first().click();

    await expect(page).toHaveURL(/.*\/platform\/stores.*/);
    await expect(page.locator('[data-test="store-directory"]')).toBeVisible();
  });

  test('should display tenant list with filters', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    await expect(platformConsole.tenantList).toBeVisible();

    const tenantCount = await platformConsole.getTenantCount();
    expect(tenantCount).toBeGreaterThan(0);

    // Verify filters are present
    await expect(platformConsole.searchTenantsInput).toBeVisible();
    await expect(page.locator('[data-test="filter-status"]')).toBeVisible();
  });

  test('should filter tenants by search query', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    const initialCount = await platformConsole.getTenantCount();

    await platformConsole.searchTenants('test');
    await page.waitForTimeout(500);

    const filteredCount = await platformConsole.getTenantCount();
    expect(filteredCount).toBeLessThanOrEqual(initialCount);
  });

  test('should impersonate tenant with reason and ticket number', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    // Impersonate first tenant
    await platformConsole.impersonateTenant(
      0,
      'Testing checkout flow for support ticket',
      'TICKET-12345'
    );

    // Verify impersonation banner is visible with all required elements
    const isImpersonating = await platformConsole.isImpersonating();
    expect(isImpersonating).toBe(true);

    await expect(platformConsole.impersonationBanner).toBeVisible();
    await expect(page.locator('[data-test="impersonated-tenant"]')).toBeVisible();
    await expect(page.locator('[data-test="impersonation-reason"]')).toBeVisible();
    await expect(page.locator('[data-test="ticket-number"]')).toBeVisible();
    await expect(page.locator('[data-test="end-impersonation"]')).toBeVisible();
  });

  test('should display impersonation timer', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    // Start impersonation
    await platformConsole.impersonateTenant(
      0,
      'Testing impersonation timer',
      'TICKET-99999'
    );

    // Wait a couple seconds and verify timer is updating
    await page.waitForTimeout(2000);

    const elapsedTime = await platformConsole.getImpersonationElapsedTime();
    expect(elapsedTime).toBeTruthy();
    expect(elapsedTime).toMatch(/\d+s/); // Should contain seconds

    // Clean up
    await platformConsole.exitImpersonation();
  });

  test('should persist impersonation banner across navigation', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    // Start impersonation
    await platformConsole.impersonateTenant(
      0,
      'Testing banner persistence',
      'TICKET-11111'
    );

    await expect(platformConsole.impersonationBanner).toBeVisible();

    // Navigate to dashboard
    await page.goto('/admin/platform');
    await page.waitForLoadState('networkidle');

    // Banner should still be visible
    await expect(platformConsole.impersonationBanner).toBeVisible();

    // Navigate to audit
    await page.goto('/admin/platform/audit');
    await page.waitForLoadState('networkidle');

    // Banner should still be visible
    await expect(platformConsole.impersonationBanner).toBeVisible();

    // Clean up
    await platformConsole.exitImpersonation();
  });

  test('should exit impersonation and verify banner disappears', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    // Start impersonation
    await platformConsole.impersonateTenant(
      0,
      'Testing impersonation exit flow',
      'TICKET-54321'
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

  test('should enforce impersonation reason and ticket requirement', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    // Click impersonate button
    const buttons = await platformConsole.impersonateButtons.all();
    if (buttons[0]) {
      await buttons[0].click();

      // Verify impersonation modal appears
      await expect(platformConsole.impersonationReasonModal).toBeVisible();

      // Try to confirm without reason (should be disabled or validation should fail)
      const confirmButton = platformConsole.confirmImpersonationButton;
      const isDisabled = await confirmButton.isDisabled();
      expect(isDisabled).toBe(true);

      // Fill only reason (not ticket) - button should still be disabled
      await platformConsole.reasonTextarea.fill('Short');
      const stillDisabled = await confirmButton.isDisabled();
      expect(stillDisabled).toBe(true);

      // Fill proper reason and ticket
      await platformConsole.reasonTextarea.fill('Proper reason with at least 10 chars');
      await page.locator('[data-test="impersonate-ticket"]').fill('TICKET-123');

      // Now button should be enabled
      const nowEnabled = !(await confirmButton.isDisabled());
      expect(nowEnabled).toBe(true);

      // Close modal
      await page.locator('[data-test="impersonate-dialog"]').press('Escape');
    }
  });

  test('should verify impersonation audit log entry', async ({ page }) => {
    const platformConsole = new PlatformConsolePage(page);

    await page.goto('/admin/platform/stores');
    await page.waitForLoadState('networkidle');

    const testReason = 'Automated E2E test impersonation for audit verification';
    const testTicket = 'TICKET-E2E-AUDIT';

    // Start impersonation
    await platformConsole.impersonateTenant(0, testReason, testTicket);

    // Verify impersonating
    expect(await platformConsole.isImpersonating()).toBe(true);

    // Navigate to audit log
    await page.goto('/admin/platform/audit');
    await page.waitForLoadState('networkidle');

    // Verify audit log table is visible
    const auditTable = page.locator('[data-test="audit-log-table"]');
    await expect(auditTable).toBeVisible();

    // Look for impersonation entry (should be recent, near top of list)
    const auditRows = auditTable.locator('[data-test="audit-row"]');
    const firstRow = auditRows.first();

    // Verify the first audit entry contains impersonation action
    const actionText = await firstRow.locator('[data-test="audit-action"]').textContent();
    expect(actionText).toContain('IMPERSONATION');

    // End impersonation for cleanup
    await platformConsole.exitImpersonation();
  });
});
