import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 Load Test: POS REST API Operations
 * Tests POS terminal API performance for in-store transactions
 * Target: POS operations p95 < 500ms, API p50 < 200ms
 */

// Custom metrics
const posSuccessRate = new Rate('pos_success');
const loginDuration = new Trend('login_duration');
const productSearchDuration = new Trend('product_search_duration');
const saleProcessDuration = new Trend('sale_process_duration');
const paymentDuration = new Trend('payment_duration');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 5 },   // Warmup: ramp to 5 terminals
    { duration: '1m', target: 15 },   // Ramp up to 15 terminals
    { duration: '2m', target: 30 },   // Ramp up to 30 terminals
    { duration: '2m', target: 30 },   // Stay at 30 terminals
    { duration: '1m', target: 0 },    // Ramp down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(50)<200', 'p(95)<500'], // p50 < 200ms, p95 < 500ms
    'http_req_failed': ['rate<0.05'], // Error rate < 5%
    'pos_success': ['rate>0.95'], // Success rate > 95%
    'login_duration': ['p(95)<300'],
    'product_search_duration': ['p(95)<300'],
    'sale_process_duration': ['p(95)<500'],
    'payment_duration': ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_URL = `${BASE_URL}/api/v1`;

// Test data: Product SKUs for quick lookup
const TEST_PRODUCTS = [
  { sku: 'SKU-001', name: 'Test Product 1', price: 29.99 },
  { sku: 'SKU-002', name: 'Test Product 2', price: 49.99 },
  { sku: 'SKU-003', name: 'Test Product 3', price: 19.99 },
  { sku: 'SKU-004', name: 'Test Product 4', price: 39.99 },
  { sku: 'SKU-005', name: 'Test Product 5', price: 59.99 },
];

/**
 * Setup function
 */
export function setup() {
  console.log(`Starting POS load test against ${BASE_URL}`);
  return { timestamp: new Date().toISOString() };
}

/**
 * Main test scenario - simulates POS transaction workflow
 */
export default function (data) {
  const terminalId = `terminal-${__VU}`;
  let authToken = null;

  group('1. POS Login', () => {
    const loginRes = http.post(
      `${API_URL}/pos/auth/login`,
      JSON.stringify({
        terminalId: terminalId,
        pin: '1234',
        storeId: 'store-001',
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-ID': 'test-tenant',
        },
        tags: { name: 'POSLogin' },
      }
    );

    const loginSuccess = check(loginRes, {
      'login successful': (r) => r.status === 200,
      'has auth token': (r) => {
        try {
          const body = JSON.parse(r.body);
          authToken = body.accessToken || body.token;
          return !!authToken;
        } catch (e) {
          return false;
        }
      },
    });

    posSuccessRate.add(loginSuccess);
    loginDuration.add(loginRes.timings.duration);

    if (!loginSuccess) {
      console.error('POS login failed, skipping iteration');
      return;
    }
  });

  if (!authToken) {
    return;
  }

  const headers = {
    'Content-Type': 'application/json',
    'X-Tenant-ID': 'test-tenant',
    'Authorization': `Bearer ${authToken}`,
  };

  // Scenario: Process a sale (80% of traffic)
  if (Math.random() < 0.80) {
    group('2. Product Search (Barcode Scan)', () => {
      // Simulate barcode scanning multiple items
      const itemCount = Math.floor(Math.random() * 3) + 1; // 1-3 items
      const scannedProducts = [];

      for (let i = 0; i < itemCount; i++) {
        const product = TEST_PRODUCTS[Math.floor(Math.random() * TEST_PRODUCTS.length)];

        const searchRes = http.get(
          `${API_URL}/pos/products/search?sku=${product.sku}`,
          {
            headers,
            tags: { name: 'ProductSearch' },
          }
        );

        const searchSuccess = check(searchRes, {
          'product found': (r) => r.status === 200,
          'has price': (r) => {
            try {
              const body = JSON.parse(r.body);
              scannedProducts.push(body);
              return body.price !== undefined;
            } catch (e) {
              return false;
            }
          },
        });

        productSearchDuration.add(searchRes.timings.duration);

        sleep(0.2); // Small delay between scans
      }

      group('3. Create Sale', () => {
        const saleStartTime = Date.now();

        const createSaleRes = http.post(
          `${API_URL}/pos/sales`,
          JSON.stringify({
            terminalId: terminalId,
            items: scannedProducts.map(p => ({
              productId: p.id,
              variantId: p.variants ? p.variants[0]?.id : null,
              quantity: 1,
              price: p.price,
            })),
            customerId: Math.random() < 0.3 ? `customer-${Math.floor(Math.random() * 100)}` : null, // 30% loyalty members
          }),
          {
            headers,
            tags: { name: 'CreateSale' },
          }
        );

        let saleId = null;
        const createSuccess = check(createSaleRes, {
          'sale created': (r) => r.status === 201 || r.status === 200,
          'has sale id': (r) => {
            try {
              const body = JSON.parse(r.body);
              saleId = body.id || body.saleId;
              return !!saleId;
            } catch (e) {
              return false;
            }
          },
        });

        if (!createSuccess || !saleId) {
          console.error('Failed to create sale');
          return;
        }

        group('4. Process Payment', () => {
          // Simulate different payment methods
          const paymentMethods = ['cash', 'card', 'gift_card', 'store_credit'];
          const method = paymentMethods[Math.floor(Math.random() * paymentMethods.length)];

          const paymentRes = http.post(
            `${API_URL}/pos/sales/${saleId}/payment`,
            JSON.stringify({
              method: method,
              amount: 100.00, // Assume calculated total
              tenderAmount: method === 'cash' ? 120.00 : 100.00, // Cash overpayment
              ...(method === 'card' && {
                cardToken: 'tok_test_card',
                last4: '4242',
              }),
            }),
            {
              headers,
              tags: { name: 'ProcessPayment' },
            }
          );

          const paymentSuccess = check(paymentRes, {
            'payment processed': (r) => r.status === 200 || r.status === 201,
            'has receipt': (r) => {
              try {
                const body = JSON.parse(r.body);
                return !!body.receiptId || !!body.transactionId;
              } catch (e) {
                return false;
              }
            },
          });

          posSuccessRate.add(paymentSuccess);
          paymentDuration.add(paymentRes.timings.duration);
        });

        saleProcessDuration.add(Date.now() - saleStartTime);
      });
    });
  }

  // Scenario: Check inventory (10% of traffic)
  if (Math.random() < 0.10) {
    group('Inventory Check', () => {
      const product = TEST_PRODUCTS[Math.floor(Math.random() * TEST_PRODUCTS.length)];

      const inventoryRes = http.get(
        `${API_URL}/pos/inventory?sku=${product.sku}&locationId=store-001`,
        {
          headers,
          tags: { name: 'CheckInventory' },
        }
      );

      check(inventoryRes, {
        'inventory retrieved': (r) => r.status === 200,
      });
    });
  }

  // Scenario: Lookup customer (10% of traffic)
  if (Math.random() < 0.10) {
    group('Customer Lookup', () => {
      const customerRes = http.get(
        `${API_URL}/pos/customers/search?q=test@example.com`,
        {
          headers,
          tags: { name: 'CustomerLookup' },
        }
      );

      check(customerRes, {
        'customer lookup completed': (r) => r.status === 200,
      });
    });
  }

  sleep(2); // Pause between transactions (realistic POS flow)
}

/**
 * Teardown function
 */
export function teardown(data) {
  console.log(`POS load test completed at ${new Date().toISOString()}`);
  console.log(`Started at: ${data.timestamp}`);
}
