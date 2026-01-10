import { test, expect } from '@playwright/test';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { StorefrontPage } from './pages/StorefrontPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Multi-Tenant Isolation E2E Tests
 * Verifies tenant data isolation and cross-tenant access prevention
 */
test.describe('Multi-Tenant Isolation', () => {
  const tenantA = tenants.tenantA;
  const tenantB = tenants.tenantB;
  const baseURLTenantA = getTenantBaseUrl(tenantA);
  const baseURLTenantB = getTenantBaseUrl(tenantB);

  test('should enforce tenant isolation for products', async ({ page, request }) => {
    // Login to Tenant A as admin
    await page.goto(`${baseURLTenantA}/admin/login`);
    const loginPageA = new AdminLoginPage(page);
    await loginPageA.login(tenantA.admin.email, tenantA.admin.password);

    // Fetch Tenant A products
    const productsAResponse = await request.get(`${baseURLTenantA}/api/v1/admin/products`, {
      headers: {
        Cookie: await page.context().cookies().then(cookies =>
          cookies.map(c => `${c.name}=${c.value}`).join('; ')
        ),
      },
    });

    expect(productsAResponse.ok()).toBe(true);
    const productsA = await productsAResponse.json();
    const productAIds = productsA.map((p: any) => p.id);

    // Login to Tenant B as admin
    await page.goto(`${baseURLTenantB}/admin/login`);
    const loginPageB = new AdminLoginPage(page);
    await loginPageB.login(tenantB.admin.email, tenantB.admin.password);

    // Fetch Tenant B products
    const productsBResponse = await request.get(`${baseURLTenantB}/api/v1/admin/products`, {
      headers: {
        Cookie: await page.context().cookies().then(cookies =>
          cookies.map(c => `${c.name}=${c.value}`).join('; ')
        ),
      },
    });

    expect(productsBResponse.ok()).toBe(true);
    const productsB = await productsBResponse.json();
    const productBIds = productsB.map((p: any) => p.id);

    // Verify product IDs are completely different (no overlap)
    const overlap = productAIds.filter((id: string) => productBIds.includes(id));
    expect(overlap.length).toBe(0);

    // Verify expected products exist in each tenant
    expect(productAIds).toContain(tenantA.products[0].id);
    expect(productBIds).toContain(tenantB.products[0].id);

    // Verify Tenant B does not have Tenant A products
    expect(productBIds).not.toContain(tenantA.products[0].id);

    // Verify Tenant A does not have Tenant B products
    expect(productAIds).not.toContain(tenantB.products[0].id);
  });

  test('should prevent cross-tenant product access via API', async ({ request, page }) => {
    // Login to Tenant A
    await page.goto(`${baseURLTenantA}/admin/login`);
    const loginPage = new AdminLoginPage(page);
    await loginPage.login(tenantA.admin.email, tenantA.admin.password);

    // Get Tenant A cookies for authentication
    const cookies = await page.context().cookies();
    const cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join('; ');

    // Try to access Tenant B product while logged into Tenant A
    const tenantBProductId = tenantB.products[0].id;

    const crossTenantResponse = await request.get(
      `${baseURLTenantA}/api/v1/admin/products/${tenantBProductId}`,
      {
        headers: {
          Cookie: cookieHeader,
        },
      }
    );

    // Should return 404 (not found) because product doesn't exist in Tenant A
    expect(crossTenantResponse.status()).toBe(404);
  });

  test('should isolate storefront products by subdomain', async ({ page }) => {
    const storefrontA = new StorefrontPage(page);

    // Visit Tenant A storefront
    await page.goto(baseURLTenantA);
    await storefrontA.gotoHome();

    // Count products on Tenant A
    const productCountA = await storefrontA.getProductCount();
    expect(productCountA).toBeGreaterThan(0);

    // Get first product name on Tenant A
    const firstProductA = await page
      .locator('[data-test="product-card"]')
      .first()
      .locator('[data-test="product-name"]')
      .textContent();

    // Visit Tenant B storefront
    await page.goto(baseURLTenantB);
    await storefrontA.gotoHome();

    // Count products on Tenant B
    const productCountB = await storefrontA.getProductCount();
    expect(productCountB).toBeGreaterThan(0);

    // Verify Tenant A's first product is NOT on Tenant B storefront
    const productNamesB = await page
      .locator('[data-test="product-card"]')
      .locator('[data-test="product-name"]')
      .allTextContents();

    expect(productNamesB).not.toContain(firstProductA);
  });

  test('should isolate orders between tenants', async ({ page, request }) => {
    // Login to Tenant A
    await page.goto(`${baseURLTenantA}/admin/login`);
    const loginPageA = new AdminLoginPage(page);
    await loginPageA.login(tenantA.admin.email, tenantA.admin.password);

    // Fetch Tenant A orders
    const ordersAResponse = await request.get(`${baseURLTenantA}/api/v1/admin/orders`, {
      headers: {
        Cookie: await page.context().cookies().then(cookies =>
          cookies.map(c => `${c.name}=${c.value}`).join('; ')
        ),
      },
    });

    expect(ordersAResponse.ok()).toBe(true);
    const ordersA = await ordersAResponse.json();

    // Login to Tenant B
    await page.goto(`${baseURLTenantB}/admin/login`);
    const loginPageB = new AdminLoginPage(page);
    await loginPageB.login(tenantB.admin.email, tenantB.admin.password);

    // Fetch Tenant B orders
    const ordersBResponse = await request.get(`${baseURLTenantB}/api/v1/admin/orders`, {
      headers: {
        Cookie: await page.context().cookies().then(cookies =>
          cookies.map(c => `${c.name}=${c.value}`).join('; ')
        ),
      },
    });

    expect(ordersBResponse.ok()).toBe(true);
    const ordersB = await ordersBResponse.json();

    // Verify no order ID overlap
    const orderAIds = ordersA.map((o: any) => o.id);
    const orderBIds = ordersB.map((o: any) => o.id);

    const overlap = orderAIds.filter((id: string) => orderBIds.includes(id));
    expect(overlap.length).toBe(0);
  });

  test('should enforce tenant-specific feature flags', async ({ page }) => {
    // Tenant A has loyalty enabled
    await page.goto(`${baseURLTenantA}/loyalty`);
    await expect(page.locator('[data-test="loyalty-program"]')).toBeVisible();

    // Tenant C has loyalty disabled
    const tenantC = tenants.tenantC;
    const baseURLTenantC = getTenantBaseUrl(tenantC);

    await page.goto(`${baseURLTenantC}/loyalty`);

    // Should show "feature not available" or redirect
    await expect(
      page.locator('[data-test="feature-unavailable"]')
    ).toBeVisible().catch(() => {
      // Or verify redirect to home if loyalty not available
      expect(page.url()).not.toContain('/loyalty');
    });
  });
});
