import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 Load Test: Storefront Browsing
 * Tests storefront page rendering and API performance for customer browsing
 * Target: Page load p95 < 1s, API p50 < 200ms
 */

// Custom metrics
const pageLoadSuccessRate = new Rate('page_load_success');
const apiSuccessRate = new Rate('api_success');
const homePageDuration = new Trend('home_page_duration');
const categoryPageDuration = new Trend('category_page_duration');
const productPageDuration = new Trend('product_page_duration');
const searchDuration = new Trend('search_duration');
const apiResponseDuration = new Trend('api_response_duration');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 10 },  // Warmup: ramp to 10 users
    { duration: '1m', target: 50 },   // Ramp up to 50 users
    { duration: '2m', target: 100 },  // Ramp up to 100 users
    { duration: '2m', target: 100 },  // Stay at 100 users
    { duration: '1m', target: 0 },    // Ramp down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(50)<200', 'p(95)<1000'], // p50 < 200ms, p95 < 1s
    'http_req_failed': ['rate<0.05'], // Error rate < 5%
    'page_load_success': ['rate>0.95'], // Page success rate > 95%
    'api_success': ['rate>0.95'], // API success rate > 95%
    'home_page_duration': ['p(95)<1000'], // Home page < 1s
    'category_page_duration': ['p(95)<1000'], // Category page < 1s
    'product_page_duration': ['p(95)<1000'], // Product page < 1s
    'search_duration': ['p(95)<500'], // Search API < 500ms
    'api_response_duration': ['p(50)<200'], // API median < 200ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_URL = `${BASE_URL}/api/v1`;

// Test data
const CATEGORIES = ['shirts', 'pants', 'shoes', 'accessories', 'outerwear'];
const SEARCH_TERMS = ['blue', 'cotton', 'leather', 'vintage', 'summer'];
const PRODUCT_SLUGS = [
  'sample-product-1',
  'sample-product-2',
  'sample-product-3',
  'sample-product-4',
  'sample-product-5',
];

/**
 * Setup function
 */
export function setup() {
  console.log(`Starting storefront browsing load test against ${BASE_URL}`);
  return { timestamp: new Date().toISOString() };
}

/**
 * Main test scenario - simulates customer browsing behavior
 */
export default function (data) {
  const headers = {
    'X-Tenant-ID': 'test-tenant',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  };

  const apiHeaders = {
    'X-Tenant-ID': 'test-tenant',
    'Content-Type': 'application/json',
  };

  // Scenario 1: Homepage visit (30% of users)
  if (Math.random() < 0.30) {
    group('1. Homepage Visit', () => {
      const homeRes = http.get(`${BASE_URL}/`, {
        headers,
        tags: { name: 'HomePage' },
      });

      const homeSuccess = check(homeRes, {
        'homepage loaded': (r) => r.status === 200,
        'has content': (r) => r.body && r.body.length > 0,
      });

      pageLoadSuccessRate.add(homeSuccess);
      homePageDuration.add(homeRes.timings.duration);

      sleep(1); // User reads homepage
    });
  }

  // Scenario 2: Category browsing (50% of users)
  if (Math.random() < 0.50) {
    group('2. Category Browsing', () => {
      const category = CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
      const page = Math.floor(Math.random() * 3); // Random page 0-2

      const categoryRes = http.get(`${BASE_URL}/category/${category}?page=${page}`, {
        headers,
        tags: { name: 'CategoryPage' },
      });

      const categorySuccess = check(categoryRes, {
        'category page loaded': (r) => r.status === 200,
        'has products': (r) => r.body && r.body.includes('product'),
      });

      pageLoadSuccessRate.add(categorySuccess);
      categoryPageDuration.add(categoryRes.timings.duration);

      sleep(0.5);

      // Load category products via API
      const apiRes = http.get(
        `${API_URL}/catalog/categories/${category}/products?page=${page}&size=20`,
        {
          headers: apiHeaders,
          tags: { name: 'CategoryProductsAPI' },
        }
      );

      const apiSuccess = check(apiRes, {
        'category API loaded': (r) => r.status === 200,
        'has product data': (r) => {
          try {
            const body = JSON.parse(r.body);
            return body.content && Array.isArray(body.content);
          } catch (e) {
            return false;
          }
        },
      });

      apiSuccessRate.add(apiSuccess);
      apiResponseDuration.add(apiRes.timings.duration);

      sleep(2); // User browses products
    });
  }

  // Scenario 3: Product detail view (60% of users)
  if (Math.random() < 0.60) {
    group('3. Product Detail View', () => {
      const slug = PRODUCT_SLUGS[Math.floor(Math.random() * PRODUCT_SLUGS.length)];

      const productRes = http.get(`${BASE_URL}/product/${slug}`, {
        headers,
        tags: { name: 'ProductPage' },
      });

      const productSuccess = check(productRes, {
        'product page loaded': (r) => r.status === 200 || r.status === 404, // 404 acceptable if product doesn't exist
        'has content': (r) => r.body && r.body.length > 0,
      });

      pageLoadSuccessRate.add(productSuccess);
      productPageDuration.add(productRes.timings.duration);

      sleep(0.5);

      // Load product details via API
      const apiRes = http.get(`${API_URL}/catalog/products/${slug}`, {
        headers: apiHeaders,
        tags: { name: 'ProductDetailAPI' },
      });

      const apiSuccess = check(apiRes, {
        'product API loaded': (r) => r.status === 200 || r.status === 404,
        'has product data': (r) => {
          if (r.status === 404) return true; // 404 is acceptable
          try {
            const body = JSON.parse(r.body);
            return body.name !== undefined;
          } catch (e) {
            return false;
          }
        },
      });

      apiSuccessRate.add(apiSuccess);
      apiResponseDuration.add(apiRes.timings.duration);

      sleep(3); // User reads product details
    });
  }

  // Scenario 4: Search (25% of users)
  if (Math.random() < 0.25) {
    group('4. Product Search', () => {
      const searchTerm = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];

      const searchRes = http.get(
        `${API_URL}/catalog/products/search?q=${searchTerm}&page=0&size=20`,
        {
          headers: apiHeaders,
          tags: { name: 'ProductSearch' },
        }
      );

      const searchSuccess = check(searchRes, {
        'search completed': (r) => r.status === 200,
        'has results': (r) => {
          try {
            const body = JSON.parse(r.body);
            return body.content !== undefined;
          } catch (e) {
            return false;
          }
        },
      });

      apiSuccessRate.add(searchSuccess);
      apiResponseDuration.add(searchRes.timings.duration);
      searchDuration.add(searchRes.timings.duration);

      sleep(1.5); // User reviews search results
    });
  }

  // Scenario 5: View cart (15% of users)
  if (Math.random() < 0.15) {
    group('5. View Cart', () => {
      const cartRes = http.get(`${BASE_URL}/cart`, {
        headers,
        tags: { name: 'CartPage' },
      });

      const cartSuccess = check(cartRes, {
        'cart page loaded': (r) => r.status === 200,
      });

      pageLoadSuccessRate.add(cartSuccess);

      sleep(1); // User views cart
    });
  }

  // Scenario 6: Account page (10% of users - requires auth)
  if (Math.random() < 0.10) {
    group('6. Account Page', () => {
      const accountRes = http.get(`${BASE_URL}/account`, {
        headers,
        tags: { name: 'AccountPage' },
      });

      // May redirect to login if not authenticated - that's OK
      check(accountRes, {
        'account page loaded or redirect': (r) => r.status === 200 || r.status === 302 || r.status === 401,
      });

      sleep(1);
    });
  }

  sleep(1); // Pause between page visits
}

/**
 * Teardown function
 */
export function teardown(data) {
  console.log(`Storefront browsing load test completed at ${new Date().toISOString()}`);
  console.log(`Started at: ${data.timestamp}`);
}
