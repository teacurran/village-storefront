import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';
import { StorefrontPage } from './pages/StorefrontPage';
import { ProductPage } from './pages/ProductPage';
import { CartPage } from './pages/CartPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { LoyaltyPage } from './pages/LoyaltyPage';

/**
 * Loyalty Program End-to-End Flow Tests
 * Tests complete loyalty lifecycle: earn points, view balance, redeem, verify deductions
 */
test.describe('Loyalty End-to-End Flow', () => {
  test.describe('Tenant A - Loyalty Lifecycle (10 points per dollar)', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    test('should earn loyalty points on purchase and show in balance', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);
      const loyaltyPage = new LoyaltyPage(page);

      // Login as loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Check initial loyalty balance
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();
      const initialBalance = await loyaltyPage.getPointsBalance();

      // Add product to cart and complete purchase
      await page.goto(baseURL);
      const product = tenant.products[1]; // Deluxe Jeans - $79.99
      await storefront.selectProduct(1);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.selectShippingMethod('Standard Shipping');
      const orderTotal = await checkoutPage.getOrderTotal();

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Verify order confirmation shows points earned
      const pointsEarned = page.locator('[data-test="loyalty-points-earned"]');
      await expect(pointsEarned).toBeVisible();

      const expectedPoints = Math.floor(orderTotal * tenant.loyaltyProgram.pointsPerDollar);
      const pointsText = await pointsEarned.textContent();
      expect(pointsText).toContain(expectedPoints.toString());

      // Navigate to loyalty page and verify balance updated
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();

      const newBalance = await loyaltyPage.getPointsBalance();
      expect(newBalance).toBe(initialBalance + expectedPoints);
    });

    test('should redeem loyalty points during checkout and reduce order total', async ({
      page,
    }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);
      const loyaltyPage = new LoyaltyPage(page);

      // Login as loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Check initial loyalty balance
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();
      const initialBalance = await loyaltyPage.getPointsBalance();

      // Ensure member has enough points (at least 100 for $1 redemption)
      expect(initialBalance).toBeGreaterThanOrEqual(100);

      // Add product to cart
      await page.goto(baseURL);
      await storefront.selectProduct(0); // Premium T-Shirt - $29.99
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.selectShippingMethod('Standard Shipping');

      // Capture total before redemption
      const totalBeforeRedemption = await checkoutPage.getOrderTotal();

      // Redeem loyalty points
      await checkoutPage.redeemLoyaltyPoints();

      // Wait for total to update
      await page.waitForTimeout(1000);

      // Capture total after redemption
      const totalAfterRedemption = await checkoutPage.getOrderTotal();

      // Calculate expected discount ($1 per 100 points for Tenant A)
      const pointsToRedeem = Math.min(initialBalance, 500); // Cap at 500 points ($5 discount)
      const expectedDiscount = (pointsToRedeem / 100) * tenant.loyaltyProgram.redemptionRate;

      // Verify total reduced by discount
      expect(totalAfterRedemption).toBeLessThan(totalBeforeRedemption);
      expect(totalAfterRedemption).toBeCloseTo(totalBeforeRedemption - expectedDiscount, 2);

      // Complete purchase
      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Verify order confirmation shows points redeemed
      const pointsRedeemed = page.locator('[data-test="loyalty-points-redeemed"]');
      await expect(pointsRedeemed).toBeVisible();
      expect(await pointsRedeemed.textContent()).toContain(pointsToRedeem.toString());

      // Verify loyalty balance decreased
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();

      const finalBalance = await loyaltyPage.getPointsBalance();
      expect(finalBalance).toBeLessThan(initialBalance);
    });

    test('should show loyalty transaction history with earn and redeem events', async ({
      page,
    }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);
      const loyaltyPage = new LoyaltyPage(page);

      // Login as loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Make a purchase to earn points
      await storefront.selectProduct(0);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();
      await checkoutPage.selectShippingMethod('Standard Shipping');

      const orderTotal = await checkoutPage.getOrderTotal();

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Navigate to loyalty page
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();

      // View transaction history
      const transactions = await loyaltyPage.getTransactionHistory();

      // Verify at least one transaction exists
      expect(transactions.length).toBeGreaterThan(0);

      // Verify most recent transaction is "Earned" type
      const latestTransaction = transactions[0];
      expect(latestTransaction.type).toMatch(/earned|purchase/i);

      const expectedPoints = Math.floor(orderTotal * tenant.loyaltyProgram.pointsPerDollar);
      expect(latestTransaction.points).toBe(expectedPoints);
    });

    test('should not allow redemption exceeding available balance', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);
      const loyaltyPage = new LoyaltyPage(page);

      // Login as loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Check current balance
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();
      const currentBalance = await loyaltyPage.getPointsBalance();

      // Add high-value product to cart
      await page.goto(baseURL);
      await storefront.selectProduct(2); // Sneakers - $99.99
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.selectShippingMethod('Standard Shipping');

      // Try to redeem more points than available (if balance is low)
      if (currentBalance < 100) {
        // Verify redemption toggle is disabled or shows insufficient balance
        const loyaltyToggle = checkoutPage.loyaltyPointsToggle;

        const isDisabled = await loyaltyToggle.isDisabled().catch(() => false);
        if (!isDisabled) {
          await checkoutPage.redeemLoyaltyPoints();

          // Verify error message shown
          const insufficientBalanceError = page.locator(
            '[data-test="loyalty-insufficient-balance"]'
          );
          await expect(insufficientBalanceError).toBeVisible();
        } else {
          // Toggle disabled as expected
          expect(isDisabled).toBe(true);
        }
      }
    });
  });

  test.describe('Tenant B - Loyalty with Different Rates (5 points per dollar, $0.50 per 100)', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test('should earn points at Tenant B rate and redeem at Tenant B rate', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);
      const loyaltyPage = new LoyaltyPage(page);

      // Login as Tenant B loyalty member
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.loyaltyMember!.email);
      await page.fill('[data-test="login-password"]', tenant.loyaltyMember!.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Check initial balance
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();
      const initialBalance = await loyaltyPage.getPointsBalance();

      // Make purchase
      await page.goto(baseURL);
      const product = tenant.products[2]; // Keyboard - $129.99
      await storefront.selectProduct(2);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      await checkoutPage.selectShippingMethod('Standard Shipping');
      const orderTotal = await checkoutPage.getOrderTotal();

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Verify points earned at Tenant B rate (5 points per dollar)
      const pointsEarned = page.locator('[data-test="loyalty-points-earned"]');
      await expect(pointsEarned).toBeVisible();

      const expectedPoints = Math.floor(orderTotal * tenant.loyaltyProgram.pointsPerDollar); // 5x
      const pointsText = await pointsEarned.textContent();
      expect(pointsText).toContain(expectedPoints.toString());

      // Verify balance updated
      await page.goto(`${baseURL}/account/loyalty`);
      await loyaltyPage.waitForTenantContext();

      const newBalance = await loyaltyPage.getPointsBalance();
      expect(newBalance).toBe(initialBalance + expectedPoints);
    });
  });

  test.describe('Tenant C - Loyalty Disabled', () => {
    const tenant = tenants.tenantC;
    const baseURL = getTenantBaseUrl(tenant);

    test('should not show loyalty features when program disabled', async ({ page }) => {
      const storefront = new StorefrontPage(page);
      const productPage = new ProductPage(page);
      const cartPage = new CartPage(page);
      const checkoutPage = new CheckoutPage(page);

      // Login as Tenant C customer
      await page.goto(baseURL);
      await storefront.waitForTenantContext();

      await page.click('[data-test="login-link"]');
      await page.fill('[data-test="login-email"]', tenant.customer.email);
      await page.fill('[data-test="login-password"]', tenant.customer.password);
      await page.click('[data-test="login-submit"]');
      await page.waitForURL(`${baseURL}/**`);

      // Verify loyalty menu item NOT visible in account dropdown
      await page.click('[data-test="user-account-menu"]');
      const loyaltyLink = page.locator('[data-test="loyalty-menu-link"]');
      const isLoyaltyVisible = await loyaltyLink.isVisible().catch(() => false);
      expect(isLoyaltyVisible).toBe(false);

      // Add product and go to checkout
      await page.goto(baseURL);
      await storefront.selectProduct(0);
      await productPage.addToCart();
      await storefront.goToCart();
      await cartPage.proceedToCheckout();

      // Verify NO loyalty redemption option in checkout
      const loyaltyToggle = checkoutPage.loyaltyPointsToggle;
      const isToggleVisible = await loyaltyToggle.isVisible().catch(() => false);
      expect(isToggleVisible).toBe(false);

      await checkoutPage.selectShippingMethod('Standard Shipping');

      await checkoutPage.fillPaymentInfo({
        cardNumber: '4242424242424242',
        expiry: '12/28',
        cvc: '123',
        zip: '12345',
      });

      await checkoutPage.placeOrder();

      // Verify order confirmation does NOT show loyalty points
      const pointsEarned = page.locator('[data-test="loyalty-points-earned"]');
      const isPointsVisible = await pointsEarned.isVisible().catch(() => false);
      expect(isPointsVisible).toBe(false);
    });
  });
});
