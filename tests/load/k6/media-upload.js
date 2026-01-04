import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

/**
 * k6 Load Test: Media Upload Pipeline
 * Tests media upload and processing performance
 * Target: Upload negotiation < 200ms, processing complete < 5s
 */

// Custom metrics
const uploadSuccessRate = new Rate('upload_success');
const negotiationDuration = new Trend('negotiation_duration');
const uploadDuration = new Trend('upload_duration');
const processingDuration = new Trend('processing_duration');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 5 }, // Ramp up to 5 concurrent uploads
    { duration: '1m', target: 10 }, // Ramp up to 10
    { duration: '2m', target: 20 }, // Ramp up to 20
    { duration: '1m', target: 20 }, // Stay at 20
    { duration: '30s', target: 0 }, // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95% < 2s (upload time)
    http_req_failed: ['rate<0.02'], // Error rate < 2%
    upload_success: ['rate>0.98'], // Success rate > 98%
    negotiation_duration: ['p(95)<200'], // Negotiation < 200ms
    processing_duration: ['avg<5000'], // Processing avg < 5s
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_URL = `${BASE_URL}/api/v1`;

// Simulated image data (1KB test image)
const TEST_IMAGE_DATA = encoding.b64encode('x'.repeat(1024));

/**
 * Setup function
 */
export function setup() {
  console.log(`Starting media upload load test against ${BASE_URL}`);
  // Authenticate as admin user for media uploads
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
 * Main test scenario
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
    Authorization: `Bearer ${authToken}`,
  };

  let uploadId = null;
  let uploadUrl = null;

  group('1. Negotiate Upload', () => {
    const negotiateRes = http.post(
      `${API_URL}/media/upload/negotiate`,
      JSON.stringify({
        filename: `load-test-image-${__VU}-${__ITER}.jpg`,
        contentType: 'image/jpeg',
        size: 1024,
        context: 'product',
        contextId: `prod-load-test-${__VU}`,
      }),
      {
        headers,
        tags: { name: 'NegotiateUpload' },
      }
    );

    const success = check(negotiateRes, {
      'negotiation successful': (r) => r.status === 200 || r.status === 201,
      'has upload url': (r) => {
        try {
          const body = JSON.parse(r.body);
          uploadId = body.uploadId;
          uploadUrl = body.uploadUrl;
          return !!uploadUrl;
        } catch (e) {
          return false;
        }
      },
    });

    negotiationDuration.add(negotiateRes.timings.duration);

    if (!success) {
      console.error('Upload negotiation failed');
      return;
    }
  });

  if (!uploadUrl || !uploadId) {
    return;
  }

  group('2. Upload File', () => {
    const uploadRes = http.put(
      uploadUrl,
      TEST_IMAGE_DATA,
      {
        headers: {
          'Content-Type': 'image/jpeg',
        },
        tags: { name: 'UploadFile' },
      }
    );

    check(uploadRes, {
      'file uploaded': (r) => r.status === 200 || r.status === 204,
    });

    uploadDuration.add(uploadRes.timings.duration);
  });

  group('3. Complete Upload', () => {
    const completeRes = http.post(
      `${API_URL}/media/upload/${uploadId}/complete`,
      JSON.stringify({
        uploadId,
      }),
      {
        headers,
        tags: { name: 'CompleteUpload' },
      }
    );

    check(completeRes, {
      'upload completed': (r) => r.status === 200 || r.status === 202,
    });
  });

  group('4. Poll Processing Status', () => {
    const startTime = Date.now();
    let processed = false;
    let attempts = 0;
    const maxAttempts = 20; // Max 10 seconds polling

    while (!processed && attempts < maxAttempts) {
      const statusRes = http.get(`${API_URL}/media/assets/${uploadId}`, {
        headers,
        tags: { name: 'CheckProcessingStatus' },
      });

      if (statusRes.status === 200) {
        try {
          const body = JSON.parse(statusRes.body);
          if (body.status === 'ready' || body.status === 'processed') {
            processed = true;
            const duration = Date.now() - startTime;
            processingDuration.add(duration);

            check(body, {
              'has derivatives': (b) =>
                b.derivatives && b.derivatives.length > 0,
              'has thumbnail': (b) =>
                b.derivatives &&
                b.derivatives.some((d) => d.format === 'thumbnail'),
            });
          } else if (body.status === 'failed') {
            console.error('Media processing failed');
            break;
          }
        } catch (e) {
          console.error('Failed to parse status response');
          break;
        }
      }

      sleep(0.5);
      attempts++;
    }

    const success = check(
      { processed },
      {
        'processing completed in time': (data) => data.processed === true,
      }
    );

    uploadSuccessRate.add(success);
  });

  sleep(1); // Pause between iterations
}

/**
 * Teardown function
 */
export function teardown(data) {
  console.log(`Media upload load test completed at ${new Date().toISOString()}`);
  console.log(`Started at: ${data.timestamp}`);
}
