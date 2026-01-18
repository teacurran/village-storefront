# Village Storefront Headless API - Onboarding Guide

Welcome to the Village Storefront Headless API! This guide will help you get started with integrating our commerce platform into your application.

---

## Quick Start

**Base URLs:**
- **Production:** `https://api.villagecompute.com/v1`
- **Staging:** `https://api-staging.villagecompute.com/v1`
- **Local Development:** `http://localhost:8080/api/v1`

**Interactive Documentation:**
- **Swagger UI:** https://api.villagecompute.com/q/swagger-ui
- **OpenAPI Spec:** [openapi.yaml](./v1/openapi.yaml)

---

## 1. Request API Credentials

Contact **api-support@villagecompute.com** with the following information:

- **Company Name:** Your organization
- **Integration Use Case:** Mobile app, embedded storefront, channel partner, etc.
- **Expected Traffic:** Estimated requests per hour
- **Requested Scopes:** (see [Available Scopes](#available-scopes) below)

You'll receive:
- `client_id`: Your unique API client identifier
- `client_secret`: Secret key (**store securely**)
- `tenant_id`: Your tenant identifier

**Security Best Practices:**
- Store credentials in environment variables or secret management system
- Never commit credentials to version control
- Rotate credentials quarterly

---

## 2. Obtain Access Token

Use OAuth 2.0 client credentials grant:

```bash
curl -X POST https://api.villagecompute.com/oauth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=YOUR_CLIENT_ID" \
  -d "client_secret=YOUR_CLIENT_SECRET" \
  -d "scope=catalog:read checkout:write"
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "catalog:read checkout:write"
}
```

**Token Lifetime:** 1 hour. Request new token when expired (no refresh tokens for client credentials).

---

## 3. Make Your First Request

Fetch product catalog:

```bash
curl https://api.villagecompute.com/v1/products \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "X-Tenant-ID: YOUR_TENANT_ID"
```

**Response:**
```json
{
  "data": [
    {
      "id": "prod_abc123",
      "sku": "WIDGET-001",
      "name": "Premium Widget",
      "price": { "amount": "29.99", "currency": "USD" },
      "status": "active"
    }
  ],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 150
  }
}
```

---

## Available Scopes

| Scope | Description | Endpoints |
|-------|-------------|-----------|
| `catalog:read` | Read products, categories | `GET /products`, `GET /categories` |
| `catalog:write` | Create/update products | `POST /products`, `PATCH /products/{id}` |
| `checkout:read` | Read carts, orders | `GET /carts`, `GET /orders` |
| `checkout:write` | Create orders, payments | `POST /checkout/commit` |
| `customer:read` | Read customer profiles | `GET /customers` |
| `customer:write` | Create/update customers | `POST /customers` |

**Request only the scopes you need.** Example multi-scope request:
```bash
-d "scope=catalog:read checkout:write customer:read"
```

---

## Rate Limiting

| Tier | Requests/Hour | Burst Limit |
|------|---------------|-------------|
| Standard | 1,000 | 20/sec |
| Premium | 10,000 | 100/sec |

**Rate Limit Headers:**
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 995
X-RateLimit-Reset: 1640995200
```

**429 Error Response:**
```json
{
  "error": "rate_limit_exceeded",
  "message": "Rate limit exceeded. Retry after 2026-01-18T15:30:00Z"
}
```

**Best Practices:**
- Monitor `X-RateLimit-Remaining` header
- Implement exponential backoff on 429 errors
- Cache catalog data (refresh every 15-30 minutes)

---

## Code Examples

### JavaScript/TypeScript

```javascript
const axios = require('axios');

const BASE_URL = 'https://api.villagecompute.com/v1';
const CLIENT_ID = process.env.API_CLIENT_ID;
const CLIENT_SECRET = process.env.API_CLIENT_SECRET;
const TENANT_ID = process.env.API_TENANT_ID;

async function getAccessToken() {
  const response = await axios.post(`${BASE_URL}/../oauth/token`,
    `grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}&scope=catalog:read`,
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
  );
  return response.data.access_token;
}

async function fetchProducts() {
  const token = await getAccessToken();
  const response = await axios.get(`${BASE_URL}/products`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Tenant-ID': TENANT_ID
    }
  });
  return response.data;
}
```

### Python

```python
import requests
import os

BASE_URL = 'https://api.villagecompute.com/v1'
CLIENT_ID = os.getenv('API_CLIENT_ID')
CLIENT_SECRET = os.getenv('API_CLIENT_SECRET')
TENANT_ID = os.getenv('API_TENANT_ID')

def get_access_token():
    response = requests.post(
        f'{BASE_URL}/../oauth/token',
        data={
            'grant_type': 'client_credentials',
            'client_id': CLIENT_ID,
            'client_secret': CLIENT_SECRET,
            'scope': 'catalog:read'
        }
    )
    return response.json()['access_token']

def fetch_products():
    token = get_access_token()
    response = requests.get(
        f'{BASE_URL}/products',
        headers={
            'Authorization': f'Bearer {token}',
            'X-Tenant-ID': TENANT_ID
        }
    )
    return response.json()
```

---

## Common Workflows

### Create Order

1. **Create Cart:**
```bash
curl -X POST https://api.villagecompute.com/v1/carts \
  -H "Authorization: Bearer TOKEN" \
  -H "X-Tenant-ID: TENANT_ID" \
  -d '{"customerId": null}'
```

2. **Add Items:**
```bash
curl -X POST https://api.villagecompute.com/v1/carts/{cartId}/items \
  -H "Authorization: Bearer TOKEN" \
  -H "X-Tenant-ID: TENANT_ID" \
  -d '{"productId": "prod_123", "quantity": 2}'
```

3. **Checkout:**
```bash
curl -X POST https://api.villagecompute.com/v1/checkout/commit \
  -H "Authorization: Bearer TOKEN" \
  -H "X-Tenant-ID: TENANT_ID" \
  -H "X-Idempotency-Key: UNIQUE_UUID" \
  -d '{
    "cartId": "cart_123",
    "paymentMethodId": "pm_stripe",
    "shippingAddress": {...}
  }'
```

---

## Error Handling

All errors follow [RFC 7807 Problem Details](https://www.rfc-editor.org/rfc/rfc7807):

```json
{
  "type": "https://api.villagecompute.com/errors/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "The 'email' field must be a valid email address",
  "instance": "/api/v1/customers",
  "errors": [
    {
      "field": "email",
      "message": "must be a valid email address"
    }
  ]
}
```

**Common Status Codes:**
- `400 Bad Request` - Invalid payload
- `401 Unauthorized` - Invalid/expired token
- `403 Forbidden` - Insufficient permissions
- `404 Not Found` - Resource not found
- `429 Too Many Requests` - Rate limit exceeded
- `500 Internal Server Error` - Server error

---

## Idempotency

Use idempotency keys for order creation to prevent duplicate orders:

```bash
curl -X POST https://api.villagecompute.com/v1/checkout/commit \
  -H "X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  ...
```

**Guidelines:**
- Generate unique UUID v4 for each order attempt
- Reuse same key on retry (prevents duplicate orders)
- Keys expire after 24 hours

---

## Testing

### Postman Collection

Download: [Village Storefront API.postman_collection.json](./postman/village-storefront-api.postman_collection.json)

### Sandbox Environment

Use staging environment for testing:
- **Base URL:** https://api-staging.villagecompute.com/v1
- Request staging credentials from api-support@villagecompute.com

---

## Support

- **API Issues:** api-support@villagecompute.com
- **Slack:** #api-partners (request access)
- **Status Page:** https://status.villagecompute.com
- **Documentation:** [OpenAPI Spec](./v1/openapi.yaml) | [Swagger UI](https://api.villagecompute.com/q/swagger-ui)

---

## Next Steps

1. ✅ Obtain API credentials
2. ✅ Authenticate and fetch products
3. ⬜ Implement cart/checkout flow
4. ⬜ Set up error handling and retries
5. ⬜ Configure rate limit monitoring
6. ⬜ Subscribe to webhooks (coming Q2 2026)

**Questions?** Contact api-support@villagecompute.com
