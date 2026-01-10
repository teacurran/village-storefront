import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';

/**
 * Headless API E2E Tests
 * Covers OAuth authentication and API-driven order creation
 */
test.describe('Headless API Flows', () => {
  const tenant = tenants.tenantA;
  const baseURL = getTenantBaseUrl(tenant);
  const oauthClient = tenant.oauthClients[0];

  let accessToken: string;

  test.beforeAll(async ({ request }) => {
    // Authenticate with OAuth client credentials
    const response = await request.post(`${baseURL}/api/v1/oauth/token`, {
      data: {
        grant_type: 'client_credentials',
        client_id: oauthClient.clientId,
        client_secret: oauthClient.clientSecret,
        scope: oauthClient.scopes.join(' '),
      },
    });

    expect(response.ok()).toBe(true);

    const tokenData = await response.json();
    accessToken = tokenData.access_token;

    expect(accessToken).toBeTruthy();
  });

  test('should create order via API with OAuth authentication', async ({
    request,
  }) => {
    // Create cart
    const cartResponse = await request.post(`${baseURL}/api/v1/cart`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    expect(cartResponse.ok()).toBe(true);
    const cart = await cartResponse.json();
    const cartId = cart.id;

    // Add items to cart
    const product = tenant.products[0];
    const addItemResponse = await request.post(`${baseURL}/api/v1/cart/items`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      data: {
        productId: product.id,
        quantity: 2,
      },
    });

    expect(addItemResponse.ok()).toBe(true);

    // Get shipping rates
    const shippingResponse = await request.post(
      `${baseURL}/api/v1/shipping/rates`,
      {
        headers: {
          Authorization: `Bearer ${accessToken}`,
        },
        data: {
          cartId: cartId,
          address: {
            street: '123 API St',
            city: 'Headless City',
            state: 'CA',
            zip: '90210',
            country: 'US',
          },
        },
      }
    );

    expect(shippingResponse.ok()).toBe(true);
    const rates = await shippingResponse.json();
    expect(rates.length).toBeGreaterThan(0);

    // Submit order
    const orderResponse = await request.post(`${baseURL}/api/v1/checkout/commit`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      data: {
        cartId: cartId,
        shipping: {
          firstName: 'API',
          lastName: 'Customer',
          address: {
            street: '123 API St',
            city: 'Headless City',
            state: 'CA',
            zip: '90210',
            country: 'US',
          },
          method: rates[0].id,
        },
        payment: {
          token: 'tok_visa', // Stripe test token
        },
      },
    });

    expect(orderResponse.ok()).toBe(true);
    const order = await orderResponse.json();

    // Verify order created
    expect(order.id).toBeTruthy();
    expect(order.status).toBe('PROCESSING');

    // Get order details
    const orderDetailsResponse = await request.get(
      `${baseURL}/api/v1/orders/${order.id}`,
      {
        headers: {
          Authorization: `Bearer ${accessToken}`,
        },
      }
    );

    expect(orderDetailsResponse.ok()).toBe(true);
    const orderDetails = await orderDetailsResponse.json();

    expect(orderDetails.id).toBe(order.id);
    expect(orderDetails.items.length).toBeGreaterThan(0);
    expect(orderDetails.total).toBeGreaterThan(0);
  });

  test('should fetch catalog via API', async ({ request }) => {
    // Get products
    const productsResponse = await request.get(`${baseURL}/api/v1/products`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    expect(productsResponse.ok()).toBe(true);
    const products = await productsResponse.json();

    expect(products.length).toBeGreaterThan(0);

    // Verify product structure
    const firstProduct = products[0];
    expect(firstProduct.id).toBeTruthy();
    expect(firstProduct.name).toBeTruthy();
    expect(firstProduct.price).toBeGreaterThan(0);

    // Get specific product
    const productDetailsResponse = await request.get(
      `${baseURL}/api/v1/products/${firstProduct.id}`,
      {
        headers: {
          Authorization: `Bearer ${accessToken}`,
        },
      }
    );

    expect(productDetailsResponse.ok()).toBe(true);
    const productDetails = await productDetailsResponse.json();

    expect(productDetails.id).toBe(firstProduct.id);
    expect(productDetails.name).toBe(firstProduct.name);
  });

  test('should respect OAuth scopes', async ({ request }) => {
    // Try to access admin endpoint with client credentials (should fail)
    const adminResponse = await request.get(`${baseURL}/api/v1/admin/users`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    // Should be forbidden (403) because client doesn't have admin scope
    expect(adminResponse.status()).toBe(403);
  });
});
