import { test, expect } from '@playwright/test';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { AdminDashboardPage } from './pages/AdminDashboardPage';

/**
 * Admin Dashboard E2E Tests
 * Tests admin authentication and core admin workflows
 */
test.describe('Admin Flows', () => {
  test('should login to admin dashboard successfully', async ({ page }) => {
    const loginPage = new AdminLoginPage(page);
    const dashboard = new AdminDashboardPage(page);

    await loginPage.gotoAdminLogin();

    // Attempt login with valid credentials
    await loginPage.login('admin@tenant.test', 'TestPassword123!');

    // Verify redirect to dashboard
    await expect(page).toHaveURL(/.*\/admin.*/);
    await expect(dashboard.navigation).toBeVisible();
  });

  test('should display error with invalid credentials', async ({ page }) => {
    const loginPage = new AdminLoginPage(page);

    await loginPage.gotoAdminLogin();
    await loginPage.login('invalid@example.com', 'wrongpassword');

    const hasError = await loginPage.hasError();
    expect(hasError).toBe(true);

    const errorMessage = await loginPage.getErrorMessage();
    expect(errorMessage).toBeTruthy();
  });

  test('should navigate between admin sections', async ({ page }) => {
    const loginPage = new AdminLoginPage(page);
    const dashboard = new AdminDashboardPage(page);

    // Login first
    await loginPage.gotoAdminLogin();
    await loginPage.login('admin@tenant.test', 'TestPassword123!');

    // Navigate to products
    await dashboard.navigateToProducts();
    await expect(page).toHaveURL(/.*\/admin\/products.*/);

    // Navigate to inventory
    await dashboard.navigateToInventory();
    await expect(page).toHaveURL(/.*\/admin\/inventory.*/);

    // Navigate to consignment
    await dashboard.navigateToConsignment();
    await expect(page).toHaveURL(/.*\/admin\/consignment.*/);

    // Navigate to reports
    await dashboard.navigateToReports();
    await expect(page).toHaveURL(/.*\/admin\/reports.*/);
  });

  test('should display dashboard statistics', async ({ page }) => {
    const loginPage = new AdminLoginPage(page);
    const dashboard = new AdminDashboardPage(page);

    await loginPage.gotoAdminLogin();
    await loginPage.login('admin@tenant.test', 'TestPassword123!');

    await dashboard.gotoDashboard();

    // Verify dashboard stats are visible
    await expect(dashboard.dashboardStats).toBeVisible();

    const salesTotal = await dashboard.getSalesTotal();
    expect(salesTotal).toBeTruthy();

    const orderCount = await dashboard.getOrderCount();
    expect(orderCount).toBeTruthy();
  });

  test('should logout successfully', async ({ page }) => {
    const loginPage = new AdminLoginPage(page);
    const dashboard = new AdminDashboardPage(page);

    await loginPage.gotoAdminLogin();
    await loginPage.login('admin@tenant.test', 'TestPassword123!');

    await dashboard.logout();

    // Verify redirected back to login
    await expect(page).toHaveURL(/.*\/admin\/login.*/);
  });
});
