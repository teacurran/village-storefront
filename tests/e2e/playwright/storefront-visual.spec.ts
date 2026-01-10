import { test, expect } from '@playwright/test';
import percySnapshot from '@percy/playwright';

/**
 * Storefront Visual Regression Tests
 * Captures baseline screenshots for Percy visual testing
 *
 * Requirements from I4.T1:
 * - Pages render sample data from catalog/checkout services
 * - Accept-Language toggles (en/es) update text via MessageBundle
 * - CI screenshot tests (Percy) baseline captured
 */

test.describe('Storefront Visual Regression - English', () => {
  test.use({ locale: 'en-US' });

  test.beforeEach(async ({ page }) => {
    await page.setExtraHTTPHeaders({
      'Accept-Language': 'en-US,en;q=0.9',
    });
  });

  test('Homepage - English', async ({ page }) => {
    await page.goto('/');

    // Wait for key content to load
    await expect(page.locator('h2:has-text("Shop by Category")')).toBeVisible();
    await expect(page.locator('h2:has-text("Featured Products")')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Homepage - EN', {
      widths: [375, 768, 1280], // Mobile, Tablet, Desktop
    });
  });

  test('Category Listing - English', async ({ page }) => {
    await page.goto('/category/all');

    // Wait for products to load
    await expect(page.locator('article').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Category Listing - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Category Listing with Filters - English', async ({ page }) => {
    await page.goto('/category/all');

    // Wait for filter panel
    await expect(page.locator('[data-test="filters"]')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Category Listing with Filters - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Product Detail - English', async ({ page }) => {
    await page.goto('/product/sample-product');

    // Wait for product details to load
    await expect(page.locator('h1').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Product Detail - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Shopping Cart - Empty - English', async ({ page }) => {
    await page.goto('/cart');

    // Wait for empty cart message
    await expect(page.locator('[data-test="empty-cart"]')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Cart Empty - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Shopping Cart - With Items - English', async ({ page }) => {
    // Add item to cart first
    await page.goto('/product/sample-product');
    const addToCartButton = page.locator('[data-test="add-to-cart"]');
    if (await addToCartButton.isVisible()) {
      await addToCartButton.click();
      await page.waitForTimeout(500); // Wait for cart update
    }

    await page.goto('/cart');

    // Wait for cart items
    await expect(page.locator('[data-test="cart-items"]').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Cart with Items - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Checkout - Contact Step - English', async ({ page }) => {
    await page.goto('/checkout');

    // Wait for checkout form
    await expect(page.locator('[data-test="checkout-form"]')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Checkout Contact - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Account Dashboard - English', async ({ page }) => {
    await page.goto('/account');

    // Wait for account content
    await expect(page.locator('h1').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Account Dashboard - EN', {
      widths: [375, 768, 1280],
    });
  });

  test('Account Dashboard - With Loyalty Panel - English', async ({ page }) => {
    await page.goto('/account');

    // Check if loyalty panel is visible (feature flag dependent)
    const loyaltyPanel = page.locator('[data-test="loyalty-panel"]');
    if (await loyaltyPanel.isVisible()) {
      // Capture Percy snapshot
      await percySnapshot(page, 'Account Dashboard with Loyalty - EN', {
        widths: [375, 768, 1280],
      });
    }
  });
});

test.describe('Storefront Visual Regression - Spanish', () => {
  test.use({ locale: 'es-ES' });

  test.beforeEach(async ({ page }) => {
    await page.setExtraHTTPHeaders({
      'Accept-Language': 'es-ES,es;q=0.9',
    });
  });

  test('Homepage - Spanish', async ({ page }) => {
    await page.goto('/?lang=es');

    // Wait for key content to load (Spanish text)
    await expect(page.locator('h2').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Homepage - ES', {
      widths: [375, 768, 1280],
    });
  });

  test('Category Listing - Spanish', async ({ page }) => {
    await page.goto('/category/all?lang=es');

    // Wait for products to load
    await expect(page.locator('article').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Category Listing - ES', {
      widths: [375, 768, 1280],
    });
  });

  test('Product Detail - Spanish', async ({ page }) => {
    await page.goto('/product/sample-product?lang=es');

    // Wait for product details to load
    await expect(page.locator('h1').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Product Detail - ES', {
      widths: [375, 768, 1280],
    });
  });

  test('Shopping Cart - Empty - Spanish', async ({ page }) => {
    await page.goto('/cart?lang=es');

    // Wait for empty cart message
    await expect(page.locator('[data-test="empty-cart"]')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Cart Empty - ES', {
      widths: [375, 768, 1280],
    });
  });

  test('Checkout - Contact Step - Spanish', async ({ page }) => {
    await page.goto('/checkout?lang=es');

    // Wait for checkout form
    await expect(page.locator('[data-test="checkout-form"]')).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Checkout Contact - ES', {
      widths: [375, 768, 1280],
    });
  });

  test('Account Dashboard - Spanish', async ({ page }) => {
    await page.goto('/account?lang=es');

    // Wait for account content
    await expect(page.locator('h1').first()).toBeVisible();

    // Capture Percy snapshot
    await percySnapshot(page, 'Account Dashboard - ES', {
      widths: [375, 768, 1280],
    });
  });
});

test.describe('Storefront Component Tests', () => {
  test('Header Navigation - Desktop', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto('/');

    // Capture Percy snapshot
    await percySnapshot(page, 'Header Navigation - Desktop', {
      widths: [1280],
      minHeight: 200,
    });
  });

  test('Header Navigation - Mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');

    // Open mobile menu
    const menuToggle = page.locator('[data-mobile-menu-toggle]');
    if (await menuToggle.isVisible()) {
      await menuToggle.click();
      await page.waitForTimeout(300); // Wait for animation
    }

    // Capture Percy snapshot
    await percySnapshot(page, 'Header Navigation - Mobile Menu Open', {
      widths: [375],
      minHeight: 400,
    });
  });

  test('Product Card - Grid View', async ({ page }) => {
    await page.goto('/category/all');

    // Wait for product cards
    await expect(page.locator('article').first()).toBeVisible();

    // Capture Percy snapshot focused on product grid
    await percySnapshot(page, 'Product Card Grid', {
      widths: [375, 768, 1280],
    });
  });

  test('Mini Cart - Dropdown', async ({ page }) => {
    await page.goto('/');

    // Open mini cart
    const cartTrigger = page.locator('[data-mini-cart-trigger]');
    if (await cartTrigger.isVisible()) {
      await cartTrigger.click();
      await page.waitForTimeout(300); // Wait for animation

      // Capture Percy snapshot
      await percySnapshot(page, 'Mini Cart Dropdown', {
        widths: [768, 1280],
      });
    }
  });

  test('Footer - Complete', async ({ page }) => {
    await page.goto('/');

    // Scroll to footer
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(500);

    // Capture Percy snapshot
    await percySnapshot(page, 'Footer', {
      widths: [375, 768, 1280],
      minHeight: 400,
    });
  });
});

test.describe('Storefront Responsive Behavior', () => {
  test('Homepage - Responsive Layout Test', async ({ page }) => {
    await page.goto('/');

    // Wait for content
    await expect(page.locator('h2').first()).toBeVisible();

    // Capture Percy snapshot at all breakpoints
    await percySnapshot(page, 'Homepage - All Breakpoints', {
      widths: [375, 768, 1024, 1280, 1920],
    });
  });

  test('Category Grid - Responsive Columns', async ({ page }) => {
    await page.goto('/category/all');

    // Wait for products
    await expect(page.locator('article').first()).toBeVisible();

    // Capture Percy snapshot at all breakpoints
    await percySnapshot(page, 'Category Grid - All Breakpoints', {
      widths: [375, 768, 1024, 1280, 1920],
    });
  });
});
