import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 Load Test: Checkout Flow
 * Tests checkout API performance under load
 * Target: 95th percentile < 300ms (excluding external calls)
 */

// Custom metrics
const checkoutSuccessRate = new Rate('checkout_success');
const checkoutDuration = new Trend('checkout_duration');
const cartDuration = new Trend('cart_duration');
const orderPlacementDuration = new Trend('order_placement_duration');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 10 }, // Ramp up to 10 users
    { duration: '1m', target: 50 }, // Ramp up to 50 users
    { duration: '2m', target: 100 }, // Ramp up to 100 users
    { duration: '2m', target: 100 }, // Stay at 100 users
    { duration: '1m', target: 0 }, // Ramp down to 0
  ],
  thresholds: {
    http_req_duration: ['p(95)<300'], // 95% of requests < 300ms
    http_req_failed: ['rate<0.05'], // Error rate < 5%
    checkout_success: ['rate>0.95'], // Success rate > 95%
    cart_duration: ['p(95)<200'], // Cart operations < 200ms
    order_placement_duration: ['p(95)<500'], // Order placement < 500ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_URL = `${BASE_URL}/api/v1`;

// Test data
const PRODUCTS = [
  { id: 'prod-001', name: 'Test Product 1', price: 29.99 },
  { id: 'prod-002', name: 'Test Product 2', price: 49.99 },
  { id: 'prod-003', name: 'Test Product 3', price: 19.99 },
];

/**
 * Setup function - runs once per VU
 */
export function setup() {
  console.log(`Starting checkout load test against ${BASE_URL}`);
  return { timestamp: new Date().toISOString() };
}

/**
 * Main test scenario
 */
export default function () {
  let sessionToken = null;
  let cartId = null;

  group('1. Create Cart', () => {
    const createCartRes = http.post(
      `${API_URL}/cart`,
      JSON.stringify({
        sessionId: `load-test-session-${__VU}-${__ITER}`,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-ID': 'test-tenant',
        },
        tags: { name: 'CreateCart' },
      }
    );

    check(createCartRes, {
      'cart created': (r) => r.status === 201 || r.status === 200,
      'cart has id': (r) => {
        try {
          const body = JSON.parse(r.body);
          cartId = body.id || body.cartId;
          return !!cartId;
        } catch (e) {
          return false;
        }
      },
    });

    cartDuration.add(createCartRes.timings.duration);
  });

  if (!cartId) {
    console.error('Failed to create cart, skipping iteration');
    return;
  }

  group('2. Add Items to Cart', () => {
    // Add 2-3 random products
    const itemCount = Math.floor(Math.random() * 2) + 2;

    for (let i = 0; i < itemCount; i++) {
      const product = PRODUCTS[Math.floor(Math.random() * PRODUCTS.length)];

      const addItemRes = http.post(
        `${API_URL}/cart/${cartId}/items`,
        JSON.stringify({
          productId: product.id,
          variantId: `${product.id}-variant-default`,
          quantity: Math.floor(Math.random() * 3) + 1,
        }),
        {
          headers: {
            'Content-Type': 'application/json',
            'X-Tenant-ID': 'test-tenant',
          },
          tags: { name: 'AddToCart' },
        }
      );

      check(addItemRes, {
        'item added': (r) => r.status === 200 || r.status === 201,
      });

      cartDuration.add(addItemRes.timings.duration);

      sleep(0.2); // Small delay between adding items
    }
  });

  group('3. Get Cart Summary', () => {
    const cartRes = http.get(`${API_URL}/cart/${cartId}`, {
      headers: {
        'X-Tenant-ID': 'test-tenant',
      },
      tags: { name: 'GetCart' },
    });

    check(cartRes, {
      'cart retrieved': (r) => r.status === 200,
      'cart has items': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.items && body.items.length > 0;
        } catch (e) {
          return false;
        }
      },
      'cart has total': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.total && body.total.amount > 0;
        } catch (e) {
          return false;
        }
      },
    });

    cartDuration.add(cartRes.timings.duration);
  });

  group('4. Checkout - Shipping Info', () => {
    const shippingRes = http.post(
      `${API_URL}/checkout/${cartId}/shipping`,
      JSON.stringify({
        email: `loadtest-${__VU}@example.com`,
        firstName: 'Load',
        lastName: `Test${__VU}`,
        address: {
          line1: '123 Test St',
          city: 'Testville',
          state: 'CA',
          postalCode: '12345',
          country: 'US',
        },
        phone: '555-0100',
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-ID': 'test-tenant',
        },
        tags: { name: 'SetShipping' },
      }
    );

    check(shippingRes, {
      'shipping set': (r) => r.status === 200 || r.status === 201,
    });

    checkoutDuration.add(shippingRes.timings.duration);
  });

  group('5. Checkout - Get Shipping Methods', () => {
    const methodsRes = http.get(`${API_URL}/checkout/${cartId}/shipping-methods`, {
      headers: {
        'X-Tenant-ID': 'test-tenant',
      },
      tags: { name: 'GetShippingMethods' },
    });

    check(methodsRes, {
      'methods retrieved': (r) => r.status === 200,
      'has methods': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.methods && body.methods.length > 0;
        } catch (e) {
          return false;
        }
      },
    });

    checkoutDuration.add(methodsRes.timings.duration);
  });

  group('6. Place Order', () => {
    const orderRes = http.post(
      `${API_URL}/checkout/${cartId}/place-order`,
      JSON.stringify({
        shippingMethodId: 'standard',
        paymentMethod: {
          type: 'test',
          token: 'tok_test_success',
        },
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-ID': 'test-tenant',
        },
        tags: { name: 'PlaceOrder' },
      }
    );

    const success = check(orderRes, {
      'order placed': (r) => r.status === 200 || r.status === 201,
      'has order id': (r) => {
        try {
          const body = JSON.parse(r.body);
          return !!body.orderId;
        } catch (e) {
          return false;
        }
      },
    });

    checkoutSuccessRate.add(success);
    orderPlacementDuration.add(orderRes.timings.duration);
  });

  sleep(1); // Pause between iterations
}

/**
 * Teardown function - runs once at end
 */
export function teardown(data) {
  console.log(`Checkout load test completed at ${new Date().toISOString()}`);
  console.log(`Started at: ${data.timestamp}`);
}
