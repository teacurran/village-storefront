import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 Load Test: Admin Dashboard Operations
 * Tests admin API performance for common dashboard operations
 * Target: API p95 < 300ms, p50 < 200ms
 */

// Custom metrics
const apiSuccessRate = new Rate('api_success');
const productListDuration = new Trend('product_list_duration');
const orderListDuration = new Trend('order_list_duration');
const inventoryAdjustDuration = new Trend('inventory_adjust_duration');
const dashboardLoadDuration = new Trend('dashboard_load_duration');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 5 },   // Warmup: ramp to 5 users
    { duration: '1m', target: 20 },   // Ramp up to 20 users
    { duration: '2m', target: 50 },   // Ramp up to 50 users
    { duration: '2m', target: 50 },   // Stay at 50 users
    { duration: '1m', target: 0 },    // Ramp down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(50)<200', 'p(95)<300'], // p50 < 200ms, p95 < 300ms
    'http_req_failed': ['rate<0.05'], // Error rate < 5%
    'api_success': ['rate>0.95'], // Success rate > 95%
    'product_list_duration': ['p(95)<300'],
    'order_list_duration': ['p(95)<300'],
    'inventory_adjust_duration': ['p(95)<300'],
    'dashboard_load_duration': ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_URL = `${BASE_URL}/api/v1`;

/**
 * Setup function - authenticate as admin
 */
export function setup() {
  console.log(`Starting admin dashboard load test against ${BASE_URL}`);

  const loginRes = http.post(
    `${API_URL}/auth/login`,
    JSON.stringify({
      email: 'admin@test.tenant',
      password: 'TestPassword123!',
    }),
    {
      headers: { 'Content-Type': 'application/json' },
    }
  );

  let authToken = null;
  if (loginRes.status === 200) {
    try {
      const body = JSON.parse(loginRes.body);
      authToken = body.accessToken || body.token;
    } catch (e) {
      console.error('Failed to parse auth response');
    }
  }

  return { authToken, timestamp: new Date().toISOString() };
}

/**
 * Main test scenario - simulates common admin workflows
 */
export default function (data) {
  const authToken = data.authToken;

  if (!authToken) {
    console.error('No auth token available, skipping iteration');
    return;
  }

  const headers = {
    'Content-Type': 'application/json',
    'X-Tenant-ID': 'test-tenant',
    'Authorization': `Bearer ${authToken}`,
  };

  // Scenario 1: Dashboard overview (25% of users)
  if (Math.random() < 0.25) {
    group('Dashboard Overview', () => {
      const startTime = Date.now();

      // Load dashboard metrics
      const metricsRes = http.get(`${API_URL}/admin/dashboard/metrics`, {
        headers,
        tags: { name: 'DashboardMetrics' },
      });

      const metricsSuccess = check(metricsRes, {
        'metrics loaded': (r) => r.status === 200,
        'has revenue data': (r) => {
          try {
            const body = JSON.parse(r.body);
            return body.revenue !== undefined;
          } catch (e) {
            return false;
          }
        },
      });

      apiSuccessRate.add(metricsSuccess);

      // Load recent orders
      const ordersRes = http.get(`${API_URL}/admin/orders?page=0&size=10&sort=createdAt,desc`, {
        headers,
        tags: { name: 'RecentOrders' },
      });

      check(ordersRes, {
        'recent orders loaded': (r) => r.status === 200,
      });

      dashboardLoadDuration.add(Date.now() - startTime);
    });
  }

  // Scenario 2: Product catalog management (40% of users)
  if (Math.random() < 0.40) {
    group('Product Catalog Management', () => {
      // List products with pagination
      const page = Math.floor(Math.random() * 5); // Random page 0-4
      const listRes = http.get(
        `${API_URL}/admin/catalog/products?page=${page}&size=20&sort=name,asc`,
        {
          headers,
          tags: { name: 'ListProducts' },
        }
      );

      const listSuccess = check(listRes, {
        'products listed': (r) => r.status === 200,
        'has content': (r) => {
          try {
            const body = JSON.parse(r.body);
            return body.content && Array.isArray(body.content);
          } catch (e) {
            return false;
          }
        },
      });

      apiSuccessRate.add(listSuccess);
      productListDuration.add(listRes.timings.duration);

      sleep(0.5);

      // Search products
      const searchTerms = ['shirt', 'pants', 'shoes', 'jacket', 'hat'];
      const searchTerm = searchTerms[Math.floor(Math.random() * searchTerms.length)];

      const searchRes = http.get(
        `${API_URL}/admin/catalog/products/search?q=${searchTerm}`,
        {
          headers,
          tags: { name: 'SearchProducts' },
        }
      );

      check(searchRes, {
        'search completed': (r) => r.status === 200,
      });
    });
  }

  // Scenario 3: Order management (25% of users)
  if (Math.random() < 0.25) {
    group('Order Management', () => {
      // List orders with filters
      const statuses = ['pending', 'processing', 'shipped', 'completed'];
      const status = statuses[Math.floor(Math.random() * statuses.length)];

      const ordersRes = http.get(
        `${API_URL}/admin/orders?status=${status}&page=0&size=20`,
        {
          headers,
          tags: { name: 'ListOrders' },
        }
      );

      const ordersSuccess = check(ordersRes, {
        'orders listed': (r) => r.status === 200,
      });

      apiSuccessRate.add(ordersSuccess);
      orderListDuration.add(ordersRes.timings.duration);

      sleep(0.5);

      // Get order details (if we have orders)
      if (ordersRes.status === 200) {
        try {
          const body = JSON.parse(ordersRes.body);
          if (body.content && body.content.length > 0) {
            const orderId = body.content[0].id;

            const detailRes = http.get(`${API_URL}/admin/orders/${orderId}`, {
              headers,
              tags: { name: 'OrderDetail' },
            });

            check(detailRes, {
              'order detail loaded': (r) => r.status === 200,
            });
          }
        } catch (e) {
          // Ignore parse errors
        }
      }
    });
  }

  // Scenario 4: Inventory adjustment (10% of users)
  if (Math.random() < 0.10) {
    group('Inventory Management', () => {
      // Get product for inventory adjustment
      const productRes = http.get(
        `${API_URL}/admin/catalog/products?page=0&size=1`,
        {
          headers,
          tags: { name: 'GetProductForInventory' },
        }
      );

      if (productRes.status === 200) {
        try {
          const body = JSON.parse(productRes.body);
          if (body.content && body.content.length > 0) {
            const productId = body.content[0].id;
            const variantId = body.content[0].variants ? body.content[0].variants[0]?.id : null;

            if (variantId) {
              // Adjust inventory
              const adjustmentRes = http.post(
                `${API_URL}/admin/inventory/adjustments`,
                JSON.stringify({
                  variantId: variantId,
                  locationId: 'default-location',
                  quantity: Math.floor(Math.random() * 10) - 5, // Random adjustment -5 to +5
                  reason: 'stock_count',
                  notes: `Load test adjustment ${__VU}-${__ITER}`,
                }),
                {
                  headers,
                  tags: { name: 'AdjustInventory' },
                }
              );

              const adjustSuccess = check(adjustmentRes, {
                'inventory adjusted': (r) => r.status === 200 || r.status === 201,
              });

              apiSuccessRate.add(adjustSuccess);
              inventoryAdjustDuration.add(adjustmentRes.timings.duration);
            }
          }
        } catch (e) {
          // Ignore parse errors
        }
      }
    });
  }

  sleep(1); // Pause between iterations
}

/**
 * Teardown function
 */
export function teardown(data) {
  console.log(`Admin dashboard load test completed at ${new Date().toISOString()}`);
  console.log(`Started at: ${data.timestamp}`);
}
