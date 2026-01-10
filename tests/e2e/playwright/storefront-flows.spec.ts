import { test, expect } from '@playwright/test';
import { StorefrontPage } from './pages/StorefrontPage';
import { ProductPage } from './pages/ProductPage';
import { CartPage } from './pages/CartPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { LoyaltyPage } from './pages/LoyaltyPage';
import { GiftCardPage } from './pages/GiftCardPage';
import { AdminLoginPage } from './pages/AdminLoginPage';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Advanced Storefront Flow E2E Tests
 * Covers authenticated checkout, loyalty redemption, and gift card flows
 */
test.describe('Storefront Advanced Flows', () => {
  const tenant = tenants.tenantA;
  const baseURL = getTenantBaseUrl(tenant);

  test.beforeEach(async ({ page }) => {
    // Configure for multi-tenant testing
    await page.goto(baseURL);
  });

  test('should complete authenticated checkout with loyalty points redemption', async ({
    page,
  }) => {
    const loyaltyPage = new LoyaltyPage(page);
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    // Login as loyalty member with existing points
    await page.goto(`${baseURL}/login`);
    const loginPage = new AdminLoginPage(page);
    await loginPage.login(
      tenant.loyaltyMember!.email,
      tenant.loyaltyMember!.password
    );

    // Verify login successful
    await expect(page).toHaveURL(/.*account.*/);

    // Navigate to storefront
    await storefront.gotoHome();

    // Browse and add product to cart
    await storefront.selectProduct(0);
    await expect(productPage.productTitle).toBeVisible();

    const productPrice = await productPage.getPrice();
    await productPage.setQuantity(2);
    await productPage.addToCart();

    // Go to cart
    await storefront.goToCart();
    const itemCount = await cartPage.getItemCount();
    expect(itemCount).toBe(2);

    // Proceed to checkout
    await cartPage.proceedToCheckout();

    // Check loyalty points balance
    await loyaltyPage.gotoLoyaltyPage();
    const pointsBalance = await loyaltyPage.getPointsBalance();
    expect(pointsBalance).toBeGreaterThan(0);

    // Navigate back to checkout
    await page.goto(`${baseURL}/checkout`);

    // Fill shipping information
    await checkoutPage.fillShippingInfo({
      email: tenant.loyaltyMember!.email,
      firstName: tenant.loyaltyMember!.firstName!,
      lastName: tenant.loyaltyMember!.lastName!,
      address: '123 Loyalty Lane',
      city: 'Pointsville',
      state: 'CA',
      zip: '90210',
      phone: '555-1000',
    });

    // Select shipping method
    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Redeem loyalty points (redeem 500 points = $5.00 discount)
    await loyaltyPage.redeemPoints(500);

    // Verify discount applied
    const discount = await loyaltyPage.getRedemptionAmount();
    expect(discount).toBeGreaterThan(0);

    // Get final total (should be reduced by loyalty discount)
    const finalTotal = await checkoutPage.getOrderTotal();
    expect(finalTotal).toBeLessThan(productPrice * 2);

    // Fill payment info with test card
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '12/28',
      cvc: '123',
      zip: '90210',
    });

    // Place order
    await checkoutPage.placeOrder();

    // Verify order confirmation
    await expect(page).toHaveURL(/.*order-confirmation.*/);
    await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

    // Verify points were deducted
    await loyaltyPage.gotoLoyaltyPage();
    const updatedBalance = await loyaltyPage.getPointsBalance();
    expect(updatedBalance).toBe(pointsBalance - 500);
  });

  test('should apply gift card and complete checkout with partial balance', async ({
    page,
  }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);
    const giftCardPage = new GiftCardPage(page);

    // Browse and add expensive product to cart (price > gift card balance)
    await storefront.gotoHome();
    await storefront.selectProduct(2); // Select higher-priced product

    const productPrice = await productPage.getPrice();
    expect(productPrice).toBeGreaterThan(50); // Ensure product costs more than gift card

    await productPage.addToCart();

    // Go to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo({
      email: 'giftcard@example.com',
      firstName: 'Gift',
      lastName: 'Recipient',
      address: '789 Gift Ave',
      city: 'Presentville',
      state: 'NY',
      zip: '10001',
      phone: '555-2000',
    });

    await checkoutPage.selectShippingMethod('Standard Shipping');

    // Get order total before gift card
    const totalBeforeGiftCard = await checkoutPage.getOrderTotal();

    // Apply gift card (GIFT-A-50 has $50 balance)
    const giftCardApplied = await giftCardPage.applyGiftCard(tenant.giftCards[1].code);
    expect(giftCardApplied).toBe(true);

    // Verify gift card discount applied
    const giftCardAmount = await giftCardPage.getAppliedAmount();
    expect(giftCardAmount).toBe(tenant.giftCards[1].balance);

    // Get new total (should be reduced by gift card amount)
    const totalAfterGiftCard = await checkoutPage.getOrderTotal();
    expect(totalAfterGiftCard).toBe(totalBeforeGiftCard - giftCardAmount);
    expect(totalAfterGiftCard).toBeGreaterThan(0); // Still have remaining balance

    // Complete payment for remaining balance
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '06/27',
      cvc: '456',
      zip: '10001',
    });

    await checkoutPage.placeOrder();

    // Verify order confirmation
    await expect(page).toHaveURL(/.*order-confirmation.*/);
    await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();

    // Verify gift card balance is now zero
    await giftCardPage.gotoGiftCardPage();
    const remainingBalance = await giftCardPage.checkBalance(tenant.giftCards[1].code);
    expect(remainingBalance).toBe(0);
  });

  test('should handle invalid gift card gracefully', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);
    const giftCardPage = new GiftCardPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(0);
    await productPage.addToCart();

    // Go to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo({
      email: 'test@example.com',
      firstName: 'Test',
      lastName: 'User',
      address: '123 Test St',
      city: 'Testville',
      state: 'CA',
      zip: '12345',
      phone: '555-3000',
    });

    // Try to apply invalid gift card
    const invalidCode = 'INVALID-GIFT-CARD-123';
    const applied = await giftCardPage.applyGiftCard(invalidCode);
    expect(applied).toBe(false);

    // Verify error message displayed
    const errorMessage = await giftCardPage.getErrorMessage();
    expect(errorMessage.toLowerCase()).toContain('invalid');

    // Verify checkout can still proceed without gift card
    await checkoutPage.selectShippingMethod('Standard Shipping');
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '09/26',
      cvc: '789',
      zip: '12345',
    });

    await checkoutPage.placeOrder();
    await expect(page).toHaveURL(/.*order-confirmation.*/);
  });

  test('should enroll in loyalty program and earn points from purchase', async ({
    page,
  }) => {
    const loyaltyPage = new LoyaltyPage(page);
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);

    const customerEmail = 'newloyalty@example.com';

    // Navigate to loyalty page
    await loyaltyPage.gotoLoyaltyPage();

    // Enroll in loyalty program
    await loyaltyPage.enrollInProgram(customerEmail);

    // Verify enrollment success
    const isEnrolled = await loyaltyPage.isEnrolled();
    expect(isEnrolled).toBe(true);

    // Initial balance should be zero
    const initialBalance = await loyaltyPage.getPointsBalance();
    expect(initialBalance).toBe(0);

    // Make a purchase to earn points
    await storefront.gotoHome();
    await storefront.selectProduct(0);

    const productPrice = await productPage.getPrice();
    await productPage.addToCart();

    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Complete checkout
    await checkoutPage.fillShippingInfo({
      email: customerEmail,
      firstName: 'New',
      lastName: 'Member',
      address: '456 Points Blvd',
      city: 'Earningston',
      state: 'TX',
      zip: '75001',
      phone: '555-4000',
    });

    await checkoutPage.selectShippingMethod('Standard Shipping');
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '03/29',
      cvc: '321',
      zip: '75001',
    });

    await checkoutPage.placeOrder();
    await expect(page).toHaveURL(/.*order-confirmation.*/);

    // Navigate back to loyalty page
    await loyaltyPage.gotoLoyaltyPage();

    // Verify points earned (pointsPerDollar * purchase amount)
    const updatedBalance = await loyaltyPage.getPointsBalance();
    const expectedPoints = Math.floor(productPrice * tenant.loyaltyProgram.pointsPerDollar);
    expect(updatedBalance).toBeGreaterThanOrEqual(expectedPoints);

    // Verify transaction history shows points earned
    const transactions = await loyaltyPage.getTransactionHistory();
    expect(transactions.length).toBeGreaterThan(0);
    expect(transactions[0]).toContain('earned');
  });

  test('should remove gift card and re-apply different card', async ({ page }) => {
    const storefront = new StorefrontPage(page);
    const productPage = new ProductPage(page);
    const cartPage = new CartPage(page);
    const checkoutPage = new CheckoutPage(page);
    const giftCardPage = new GiftCardPage(page);

    // Add product to cart
    await storefront.gotoHome();
    await storefront.selectProduct(1);
    await productPage.addToCart();

    // Go to checkout
    await storefront.goToCart();
    await cartPage.proceedToCheckout();

    // Fill shipping information
    await checkoutPage.fillShippingInfo({
      email: 'multicard@example.com',
      firstName: 'Multi',
      lastName: 'Card',
      address: '321 Switch St',
      city: 'Changeville',
      state: 'FL',
      zip: '33101',
      phone: '555-5000',
    });

    // Apply first gift card
    const firstCard = tenant.giftCards[0];
    await giftCardPage.applyGiftCard(firstCard.code);
    const firstAmount = await giftCardPage.getAppliedAmount();
    expect(firstAmount).toBeGreaterThan(0);

    // Remove gift card
    await giftCardPage.removeGiftCard();
    const isApplied = await giftCardPage.isGiftCardApplied();
    expect(isApplied).toBe(false);

    // Apply second gift card
    const secondCard = tenant.giftCards[1];
    await giftCardPage.applyGiftCard(secondCard.code);
    const secondAmount = await giftCardPage.getAppliedAmount();
    expect(secondAmount).toBe(secondCard.balance);
    expect(secondAmount).not.toBe(firstAmount); // Different amounts

    // Complete checkout
    await checkoutPage.selectShippingMethod('Standard Shipping');
    await checkoutPage.fillPaymentInfo({
      cardNumber: '4242424242424242',
      expiry: '11/27',
      cvc: '654',
      zip: '33101',
    });

    await checkoutPage.placeOrder();
    await expect(page).toHaveURL(/.*order-confirmation.*/);
  });
});
