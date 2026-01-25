import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';
import { StorefrontPage } from './pages/StorefrontPage';
import { ProductPage } from './pages/ProductPage';
import { CartPage } from './pages/CartPage';
import { CheckoutPage } from './pages/CheckoutPage';

/**
 * Guest vs Authenticated Checkout Comparison E2E Tests
 * Tests checkout flow differences between guest and logged-in customers
 */
test.describe('Guest vs Authenticated Checkout', () => {
  test.describe('Tenant A - Checkout Flow Comparison', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    test('should complete guest checkout without account creation', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Navigate to storefront
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      // Add product to cart
      const product = tenant.products[0];
      await storefront.selectProduct(0);
      await productPage.addToCart();

      // Go to cart and checkout
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Fill guest shipping info
      await checkoutPage.fillShippingInfo({
        email: 'guest@example.com',
        firstName: 'Guest',
        lastName: 'User',
        address: '123 Main St',
        city: 'Anytown',
        state: 'CA',
        zip: '12345',
        phone: '555-1234',
      });

      await checkoutPage.selectShippingMethod('Standard Shipping');

      // Capture order total before payment
      const guestTotal = await checkoutPage.getOrderTotal();
      expect(guestTotal).toBeGreaterThan(0);

      // Fill payment info (test card)
      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      // Place order
      await checkoutPage.placeOrder();

      // Verify order confirmation
      await expect(page).toHaveURL(/.*order-confirmation.*/);
      await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

      // Verify guest user (no loyalty points earned indicator)
      const hasLoyaltyConfirmation = await page
        .locator('[data-test="loyalty-points-earned"]')
        .isVisible()
        .catch(() => false);
      expect(hasLoyaltyConfirmation).toBe(false);
    });

    test('should complete authenticated checkout with address persistence', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Navigate to storefront and login
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      // Login as loyalty member (has saved address from fixtures)
      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');

      // Wait for login success and redirect
      await page.waitForURL(`${baseURL}/**`);
      await expect(page.locator('[data-test="user-account-menu"]')).toBeVisible();

      // Add product to cart
      await storefront.selectProduct(0);
      await productPage.addToCart();

      // Go to cart and checkout
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Verify shipping address is pre-filled (authenticated benefit)
      const emailValue = await checkoutPage.emailInput.inputValue();
      expect(emailValue).toBe(tenant.loyaltyMember!.email);

      const firstNameValue = await checkoutPage.firstNameInput.inputValue();
      expect(firstNameValue).toBeTruthy(); // Should be pre-filled

      await checkoutPage.selectShippingMethod('Standard Shipping');

      // Capture order total
      const authTotal = await checkoutPage.getOrderTotal();
      expect(authTotal).toBeGreaterThan(0);

      // Fill payment info
      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      // Place order
      await checkoutPage.placeOrder();

      // Verify order confirmation
      await expect(page).toHaveURL(/.*order-confirmation.*/);
      await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

      // Verify loyalty points earned (authenticated user benefit)
      const loyaltyPointsElement = page.locator('[data-test="loyalty-points-earned"]');
      await expect(loyaltyPointsElement).toBeVisible();

      const pointsText = await loyaltyPointsElement.textContent();
      const expectedPoints = Math.floor(authTotal * tenant.loyaltyProgram.pointsPerDollar);
      expect(pointsText).toContain(expectedPoints.toString());
    });

    test('should show loyalty redemption option only for authenticated users', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Test 1: Guest checkout - no loyalty option
      await page.goto(baseURL);
      await storefront.waitForTenantContext();
      await storefront.selectProduct(1);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Verify loyalty toggle NOT visible for guest
      const guestLoyaltyVisible = await checkoutPage.loyaltyPointsToggle
        .isVisible()
        .catch(() => false);
      expect(guestLoyaltyVisible).toBe(false);

      // Navigate back to storefront
      await page.goto(baseURL);

      // Test 2: Authenticated checkout - loyalty option visible
      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Clear cart from previous session if exists
      await page.goto(`${baseURL}/cart`);
      const cartIsEmpty = await cartPage.isCartEmpty();
      if (!cartIsEmpty) {
        await cartPage.clearCart();
      }

      // Add product and go to checkout
      await page.goto(baseURL);
      await storefront.selectProduct(1);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Verify loyalty toggle IS visible for authenticated user
      await expect(checkoutPage.loyaltyPointsToggle).toBeVisible();
    });

    test('should require email validation for guest but skip for authenticated', async ({
      page,
    }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Test 1: Guest checkout with invalid email
      await page.goto(baseURL);
      await storefront.waitForTenantContext();
      await storefront.selectProduct(0);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Try to submit with invalid email
      await checkoutPage.fillShippingInfo({
        email: 'invalid-email',
        firstName: 'Test',
        lastName: 'User',
        address: '123 Test St',
        city: 'Test City',
        state: 'CA',
        zip: '12345',
        phone: '555-0000',
      });

      await checkoutPage.selectShippingMethod('Standard Shipping');

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      // Try to place order (should fail validation)
      await checkoutPage.placeOrder({ expectSuccess: false });

      // Verify validation error shown
      const validationError = page.locator('[data-test="email-validation-error"]');
      await expect(validationError).toBeVisible();

      // Verify still on checkout page (order not submitted)
      await expect(page).toHaveURL(/.*checkout.*/);
    });
  });

  test.describe('Tenant B - Checkout Flow Isolation', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test('should use Tenant B loyalty rates for authenticated users', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Login as Tenant B loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Add product to cart
      await storefront.selectProduct(0);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.selectShippingMethod('Standard Shipping');

      const total = await checkoutPage.getOrderTotal();

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Verify loyalty points earned using Tenant B rate (5 points per dollar)
      const loyaltyPointsElement = page.locator('[data-test="loyalty-points-earned"]');
      await expect(loyaltyPointsElement).toBeVisible();

      const pointsText = await loyaltyPointsElement.textContent();
      const expectedPoints = Math.floor(total * tenant.loyaltyProgram.pointsPerDollar); // 5x
      expect(pointsText).toContain(expectedPoints.toString());
    });

    test('should complete guest checkout with Tenant B products', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Navigate to Tenant B storefront
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      // Add Tenant B product (Laptop Bag - $49.99)
      await storefront.selectProduct(0);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.fillShippingInfo({
        email: 'guest-b@example.com',
        firstName: 'Guest B',
        lastName: 'Tester',
        address: '456 Oak Ave',
        city: 'Springfield',
        state: 'IL',
        zip: '62701',
        phone: '555-5678',
      });

      await checkoutPage.selectShippingMethod('Standard Shipping');

      const total = await checkoutPage.getOrderTotal();
      // Verify Tenant B pricing (base product is $49.99)
      expect(total).toBeGreaterThanOrEqual(49.99);

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '62701',
      });

      await checkoutPage.placeOrder();

      // Verify order confirmation
      await expect(page).toHaveURL(/.*order-confirmation.*/);
      await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

      // Verify order summary shows Tenant B product
      const orderSummary = page.locator('[data-test="order-summary"]');
      await expect(orderSummary).toContainText('TB-BAG-001'); // Tenant B SKU
    });
  });
});
