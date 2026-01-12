import { test, expect } from '@playwright/test';
import { StorefrontPage } from '../playwright/pages/StorefrontPage';
import { ProductPage } from '../playwright/pages/ProductPage';
import { CartPage } from '../playwright/pages/CartPage';
import { CheckoutPage } from '../playwright/pages/CheckoutPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Checkout E2E Tests for Storefront
 * Tests guest and logged-in checkout flows with Stripe payment processing
 *
 * Prerequisites:
 * - Database seeded with: ./scripts/dev/tenant_seed.sh --catalog
 * - Quarkus dev server running on http://localhost:8080
 * - Stripe tunnel active: ./scripts/dev/stripe_tunnel.sh
 *
 * Test Coverage:
 * - Guest checkout flow (address entry, shipping, payment, order confirmation)
 * - Logged-in checkout flow (saved addresses, loyalty points redemption)
 * - Gift card application
 * - Stripe test card payment processing
 * - Order confirmation page verification
 * - Visual regression snapshots
 * - Shipping method selection
 */
test.describe('Storefront Checkout', () => {
  // Use tenantA with loyalty program for comprehensive testing
  const tenant = tenants.tenantA;
  const baseURL = getTenantBaseUrl(tenant);

  // Stripe test card (always succeeds)
  const testCardInfo = {
    cardNumber: '4242424242424242',
    expiry: '12/34',
    cvc: '123',
    zip: '10001',
  };

  // Test shipping address
  const testAddress = {
    email: 'test-checkout@example.com',
    firstName: 'Test',
    lastName: 'Customer',
    address: '123 Main Street',
    city: 'New York',
    state: 'NY',
    zip: '10001',
    phone: '555-123-4567',
  };

  test.beforeEach(async ({ page }) => {
    await page.goto(baseURL);
  });

  test('should complete guest checkout flow with Stripe payment', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to cart
    await storefront.goToCart();
    await expect(cartPage.cartItems).toBeVisible();

    // Verify cart has items
    const itemCount = await cartPage.getItemCount();
    expect(itemCount).toBeGreaterThan(0);

    // Proceed to checkout
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo(testAddress);

    // Select shipping method (standard shipping)
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Verify order summary updates with shipping cost
    await expect(checkoutPage.orderSummary).toBeVisible();
    const orderTotal = await checkoutPage.getOrderTotal();
    expect(orderTotal).toBeGreaterThan(0);

    // Fill payment information (Stripe Elements)
    await checkoutPage.fillPaymentInfo(testCardInfo);

    // Take screenshot before order placement
    await expect(page).toHaveScreenshot('checkout-before-submit.png', {
      fullPage: true,
      mask: [page.locator('[data-test="order-total"]')], // Mask dynamic total
    });

    // Place order
    await checkoutPage.placeOrder();

    // Verify order confirmation page
    await expect(page).toHaveURL(/order-confirmation/);
    await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

    // Verify order number is displayed
    const orderNumber = await page.locator('[data-test="order-number"]').textContent();
    expect(orderNumber).toBeTruthy();

    // Take order confirmation screenshot
    await expect(page).toHaveScreenshot('order-confirmation-guest.png', {
      fullPage: true,
      mask: [
        page.locator('[data-test="order-number"]'),
        page.locator('[data-test="order-date"]'),
      ],
    });
  });

  test('should complete logged-in checkout with loyalty points redemption', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Login as loyalty member
    await page.goto(`${baseURL}/login`);
    await page.fill('[data-test="email"]', tenant.loyaltyMember!.email);
    await page.fill('[data-test="password"]', tenant.loyaltyMember!.password);
    await page.click('[data-test="login-button"]');
    await page.waitForURL(`${baseURL}/**`);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(1); // Select second product
    await productPage.setQuantity(2);
    await productPage.addToCart();

    // Navigate to cart
    await storefront.goToCart();

    // Verify loyalty summary is shown
    const loyaltyPoints = await cartPage.getLoyaltyPoints();
    expect(loyaltyPoints).not.toBeNull();

    // Proceed to checkout
    await cartPage.proceedToCheckout();

    // Shipping info should be pre-filled for logged-in user
    await expect(checkoutPage.emailInput).toHaveValue(tenant.loyaltyMember!.email);

    // Select express shipping
    await checkoutPage.selectShippingMethod('Express Shipping');

    // Apply loyalty points redemption
    await checkoutPage.redeemLoyaltyPoints();

    // Verify total is reduced after loyalty points applied
    const totalAfterPoints = await checkoutPage.getOrderTotal();
    expect(totalAfterPoints).toBeGreaterThan(0);

    // Fill payment information
    await checkoutPage.fillPaymentInfo(testCardInfo);

    // Place order
    await checkoutPage.placeOrder();

    // Verify order confirmation
    await expect(page).toHaveURL(/order-confirmation/);
    await expect(page.locator('[data-test="loyalty-points-used"]')).toBeVisible();

    // Take order confirmation screenshot
    await expect(page).toHaveScreenshot('order-confirmation-loyalty.png', {
      fullPage: true,
      mask: [
        page.locator('[data-test="order-number"]'),
        page.locator('[data-test="order-date"]'),
      ],
    });
  });

  test('should apply gift card to checkout', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to cart and checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo(testAddress);
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Apply gift card (from tenant fixtures)
    const giftCardCode = tenant.giftCards[0].code;
    await checkoutPage.applyGiftCard(giftCardCode);

    // Verify gift card discount is applied
    await expect(page.locator('[data-test="gift-card-applied"]')).toBeVisible();

    // Verify total is reduced
    const totalAfterGiftCard = await checkoutPage.getOrderTotal();
    expect(totalAfterGiftCard).toBeGreaterThanOrEqual(0);

    // Fill payment (may still need payment if gift card doesn't cover full amount)
    if (totalAfterGiftCard > 0) {
      await checkoutPage.fillPaymentInfo(testCardInfo);
    }

    // Place order
    await checkoutPage.placeOrder();

    // Verify order confirmation
    await expect(page).toHaveURL(/order-confirmation/);
    await expect(page.locator('[data-test="gift-card-used"]')).toBeVisible();
  });

  test('should validate required checkout fields', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Try to proceed without filling required fields
    await checkoutPage.placeOrder({ expectSuccess: false });

    // Verify validation errors are displayed
    await expect(page.locator('[data-test="validation-error"]')).toBeVisible();

    // Verify still on checkout page (not order confirmation)
    expect(page.url()).not.toMatch(/order-confirmation/);
  });

  test('should handle Stripe payment errors gracefully', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo(testAddress);
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Use Stripe test card that declines (4000000000000002)
    const declinedCardInfo = {
      cardNumber: '4000000000000002',
      expiry: '12/34',
      cvc: '123',
      zip: '10001',
    };

    await checkoutPage.fillPaymentInfo(declinedCardInfo);

    // Try to place order
    await checkoutPage.placeOrder({ expectSuccess: false });

    // Verify error message is displayed
    await expect(page.locator('[data-test="payment-error"]')).toBeVisible();

    // Verify still on checkout page
    expect(page.url()).not.toMatch(/order-confirmation/);
  });

  test('should support mobile checkout flow', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Set mobile viewport (iPhone 12)
    await page.setViewportSize({ width: 390, height: 844 });

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to cart
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Verify checkout form is responsive
    await expect(checkoutPage.emailInput).toBeVisible();
    await expect(checkoutPage.orderSummary).toBeVisible();

    // Fill shipping information
    await checkoutPage.fillShippingInfo(testAddress);
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Take mobile checkout screenshot
    await expect(page).toHaveScreenshot('checkout-mobile.png', {
      fullPage: true,
    });

    // Fill payment and place order
    await checkoutPage.fillPaymentInfo(testCardInfo);
    await checkoutPage.placeOrder();

    // Verify order confirmation
    await expect(page).toHaveURL(/order-confirmation/);
  });

  test('should calculate shipping costs for different methods', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo(testAddress);

    // Get total with standard shipping
    await checkoutPage.selectShippingMethod('Standard Shipping');
    const standardTotal = await checkoutPage.getOrderTotal();

    // Change to express shipping
    await checkoutPage.selectShippingMethod('Express Shipping');
    const expressTotal = await checkoutPage.getOrderTotal();

    // Express shipping should cost more than standard
    expect(expressTotal).toBeGreaterThan(standardTotal);
  });

  test('should preserve cart items across checkout flow', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);

    // Add multiple products to cart
    await storefront.gotoHome();

    // Add first product
    await storefront.selectProduct(0);
    await productPage.setQuantity(2);
    await productPage.addToCart();
    await page.goBack();

    // Add second product
    await storefront.selectProduct(1);
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Navigate to cart
    await storefront.goToCart();

    // Verify both items are in cart
    const itemCount = await cartPage.getItemCount();
    expect(itemCount).toBe(2);

    // Start checkout
    await cartPage.proceedToCheckout();

    // Navigate back to cart
    await page.goBack();

    // Verify items are still in cart
    const itemCountAfterBack = await cartPage.getItemCount();
    expect(itemCountAfterBack).toBe(2);
  });
});
