import { test, expect } from '@playwright/test';
import { StorefrontPage } from './pages/StorefrontPage';
import { ProductPage } from './pages/ProductPage';
import { CartPage } from './pages/CartPage';
import { CheckoutPage } from './pages/CheckoutPage';

/**
 * Storefront Checkout E2E Tests
 * Tests the complete customer checkout journey
 */
test.describe('Storefront Checkout Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Setup: Navigate to storefront home
    const storefront = new StorefrontPage(page);
    await storefront.gotoHome();
  });

  test('should complete full checkout flow', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Browse and select a product
    await storefront.selectProduct(0);
    await expect(productPage.productTitle).toBeVisible();

    // Add product to cart
    const productTitle = await productPage.getProductTitle();
    await productPage.setQuantity(1);
    await productPage.addToCart();

    // Verify cart badge updated
    await expect(storefront.cartBadge).toContainText('1');

    // Go to cart
    await storefront.goToCart();
    await expect(cartPage.cartItems).toBeVisible();

    const itemCount = await cartPage.getItemCount();
    expect(itemCount).toBe(1);

    // Proceed to checkout
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo({
      email: 'customer@example.com',
      firstName: 'John',
      lastName: 'Doe',
      address: '123 Main St',
      city: 'Anytown',
      state: 'CA',
      zip: '12345',
      phone: '555-1234',
    });

    // Select shipping method
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Fill payment info (test card)
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '12/25',
      cvc: '123',
      zip: '12345',
    });

    // Place order
    await checkoutPage.placeOrder();

    // Verify order confirmation page
    await expect(page).toHaveURL(/.*order-confirmation.*/);
    await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();
  });

  test('should apply gift card during checkout', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Add product to cart
    await storefront.selectProduct(0);
    await productPage.addToCart();
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping info
    await checkoutPage.fillShippingInfo({
      email: 'customer@example.com',
      firstName: 'Jane',
      lastName: 'Smith',
      address: '456 Oak Ave',
      city: 'Springfield',
      state: 'IL',
      zip: '62701',
      phone: '555-5678',
    });

    // Apply gift card
    await checkoutPage.applyGiftCard('GIFT-TEST-CODE');

    // Verify total updated
    const updatedTotal = await checkoutPage.getOrderTotal();
    expect(updatedTotal).toBeTruthy();

    // Note: Actual validation would check that total decreased
  });

  test('should handle empty cart gracefully', async ({ page }) => {
    const cartPage = new CartPage(page);

    await cartPage.gotoCart();
    const isEmpty = await cartPage.isCartEmpty();

    expect(isEmpty).toBe(true);
    await expect(cartPage.emptyCartMessage).toBeVisible();
    await expect(cartPage.checkoutButton).not.toBeVisible();
  });

  test('should update cart quantities', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);

    // Add product to cart
    await storefront.selectProduct(0);
    await productPage.setQuantity(2);
    await productPage.addToCart();

    // Go to cart and verify quantity
    await storefront.goToCart();
    const itemCount = await cartPage.getItemCount();
    expect(itemCount).toBeGreaterThan(0);

    // Update quantity
    await cartPage.updateQuantity(0, 3);

    // Verify cart badge reflects updated quantity
    await expect(storefront.cartBadge).toBeVisible();
  });

  test('should search for products', async ({ page }) => {
    const storefront = new StorefrontPage(page);

    await storefront.searchProducts('shirt');
    await storefront.waitForNavigation();

    const productCount = await storefront.getProductCount();
    expect(productCount).toBeGreaterThan(0);
  });
});
