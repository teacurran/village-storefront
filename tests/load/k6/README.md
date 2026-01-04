# k6 Load Tests

This directory contains k6 load test scripts for Village Storefront.

## Test Scripts

### `checkout.js`
Tests the complete checkout flow including:
- Cart creation and manipulation
- Adding items to cart
- Setting shipping information
- Retrieving shipping methods
- Placing orders

**Performance Targets:**
- 95th percentile < 300ms for API calls
- 95th percentile < 500ms for order placement
- Success rate > 95%

### `media-upload.js`
Tests the media upload and processing pipeline:
- Upload negotiation
- File upload to storage
- Processing status polling
- Derivative generation

**Performance Targets:**
- Upload negotiation < 200ms
- Processing complete < 5s average
- Success rate > 98%

## Running Tests

### Prerequisites
```bash
# Install k6
# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6

# Windows (via Chocolatey)
choco install k6
```

### Local Execution
```bash
# Run checkout test
k6 run checkout.js

# Run with custom base URL
BASE_URL=http://localhost:8080 k6 run checkout.js

# Run media upload test
k6 run media-upload.js

# Run with output to InfluxDB (if configured)
k6 run --out influxdb=http://localhost:8086/k6 checkout.js
```

### CI Execution
```bash
# Run with JSON output for parsing
k6 run --out json=results.json checkout.js

# Run with summary export
k6 run --summary-export=summary.json checkout.js
```

## Interpreting Results

### Key Metrics
- **http_req_duration**: Total request duration (includes network, server processing)
- **http_req_waiting**: Time to first byte (server processing time)
- **http_req_failed**: Rate of failed requests
- **Custom metrics**: checkout_success, upload_success, etc.

### Thresholds
Tests will fail if thresholds are not met:
- ✓ threshold met
- ✗ threshold failed

### Example Output
```
     ✓ checkout_success...............: 96.50% ✓ 193  ✗ 7
     ✓ http_req_duration..............: avg=145ms  min=23ms med=98ms  max=850ms p(95)=287ms p(99)=450ms
     ✓ http_req_failed................: 2.50%  ✓ 5    ✗ 195
```

## Customizing Tests

### Environment Variables
- `BASE_URL`: Base URL for the application (default: http://localhost:8080)
- `API_URL`: Override API base URL
- `VUS`: Number of virtual users
- `DURATION`: Test duration

### Modifying Load Pattern
Edit the `stages` array in `options`:
```javascript
stages: [
  { duration: '30s', target: 10 },  // Ramp up to 10 users in 30s
  { duration: '1m', target: 50 },   // Ramp up to 50 users over 1 minute
  { duration: '2m', target: 100 },  // Ramp to 100 users
  { duration: '30s', target: 0 },   // Ramp down
]
```

## Integration with CI/CD

These tests are integrated into `.github/workflows/test_suite.yml`:
- Run on scheduled basis (nightly)
- Run before releases
- Results archived as JSON
- Metrics exported to monitoring system

## Troubleshooting

### High Error Rates
- Check application logs for errors
- Verify database can handle connection pool size
- Check external service availability (Stripe, storage)

### Slow Response Times
- Profile application with APM tools
- Check database query performance
- Review N+1 queries and missing indexes
- Verify caching is working

### Timeouts
- Increase VUS ramp-up time
- Reduce concurrent users
- Check network latency between load generator and server
