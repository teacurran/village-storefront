# Headless API Usage Guide

## Overview

The Village Storefront Headless API provides OAuth-secured REST endpoints for building custom storefronts, mobile apps, and third-party integrations. This guide covers authentication, rate limits, caching behavior, and code examples.

## Table of Contents

- [Getting Started](#getting-started)
- [Authentication](#authentication)
- [Rate Limits](#rate-limits)
- [Catalog API](#catalog-api)
- [Cart API](#cart-api)
- [Caching](#caching)
- [Error Handling](#error-handling)
- [Code Examples](#code-examples)

---

## Getting Started

### Prerequisites

1. **Tenant Access**: You must have admin access to a Village Storefront tenant
2. **Feature Flag**: The `headless.api.enabled` feature flag must be enabled for your tenant
3. **OAuth Client**: Create an OAuth client via the admin dashboard

### Creating an OAuth Client

1. Log in to your store's admin dashboard at `https://yourstore.villagecompute.com/admin`
2. Navigate to **Settings → OAuth Clients**
3. Click **Create OAuth Client**
4. Configure:
   - **Name**: Descriptive name (e.g., "Mobile App", "Custom Storefront")
   - **Description**: Optional description
   - **Scopes**: Select required scopes (`catalog:read`, `cart:read`, `cart:write`)
   - **Rate Limit**: Default 5000 requests/minute (adjustable)
5. Save and securely store the generated `client_id` and `client_secret`

⚠️ **Security Warning**: Client secrets are only shown once. Store them securely (e.g., environment variables, secrets manager). Never commit to version control.

---

## Authentication

The Headless API uses **OAuth 2.0 Client Credentials** flow with HTTP Basic Authentication.

### Authentication Flow

1. Encode your `client_id:client_secret` as Base64
2. Pass in the `Authorization` header on every request

### Example

```bash
# Credentials
CLIENT_ID="your-client-id"
CLIENT_SECRET="your-client-secret"

# Encode as Base64
AUTH_HEADER=$(echo -n "$CLIENT_ID:$CLIENT_SECRET" | base64)

# Make API request
curl https://yourstore.villagecompute.com/api/v1/headless/catalog/products \
  -H "Authorization: Basic $AUTH_HEADER"
```

### Scopes

| Scope | Description |
|-------|-------------|
| `catalog:read` | Read product catalog (list products, get product details) |
| `cart:read` | Read cart contents |
| `cart:write` | Modify cart (add/update/remove items) |
| `orders:read` | Read order history (future) |
| `orders:write` | Create and update orders (future) |

---

## Rate Limits

Each OAuth client has a configurable rate limit (default: **5000 requests/minute**).

### Partner Quotas

- **Launch** tenants start at 2,000 requests/minute while onboarding and graduate to the default quota after the first successful order.
- **Scale** tenants ship with 5,000 requests/minute and can burst to 10,000 requests/minute by enabling adaptive throttling in the admin console.
- **Enterprise** tenants negotiate bespoke limits (up to 50,000 requests/minute) backed by contractual SLAs.
- Need more? Email `partners@villagecompute.com` with your `client_id` and tenant subdomain so the integrations team can schedule a quota increase.

### Rate Limit Headers

All responses include rate limit headers:

```http
X-RateLimit-Limit: 5000
X-RateLimit-Remaining: 4998
X-RateLimit-Reset: 1704110460
```

- **X-RateLimit-Limit**: Maximum requests allowed in current window
- **X-RateLimit-Remaining**: Requests remaining in current window
- **X-RateLimit-Reset**: Unix timestamp when window resets

### Rate Limit Exceeded (429 Response)

When rate limit is exceeded, the API returns `429 Too Many Requests`:

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded. Please retry after 45 seconds."
}
```

Response headers include:
- `Retry-After`: Seconds to wait before retrying
- `X-RateLimit-Reset`: Timestamp when limit resets

---

## Catalog API

### List Products

**GET** `/api/v1/headless/catalog/products`

**Scopes**: `catalog:read`

**Parameters**:
- `search` (optional): Search query
- `page` (optional): Page number (1-indexed, default: 1)
- `pageSize` (optional): Page size (max: 100, default: 20)

**Example**:

```bash
curl "https://yourstore.villagecompute.com/api/v1/headless/catalog/products?search=shoes&page=1&pageSize=20" \
  -H "Authorization: Basic $AUTH_HEADER"
```

**Response**:

```json
{
  "products": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Running Shoes",
      "slug": "running-shoes",
      "price": {
        "amount": "99.99",
        "currency": "USD"
      },
      "inStock": true,
      "imageUrl": "https://..."
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 45,
    "totalPages": 3
  }
}
```

### Get Product Details

**GET** `/api/v1/headless/catalog/products/{productId}`

**Scopes**: `catalog:read`

**Example**:

```bash
curl "https://yourstore.villagecompute.com/api/v1/headless/catalog/products/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Basic $AUTH_HEADER"
```

**Response**:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Running Shoes",
  "slug": "running-shoes",
  "longDescription": "High-performance running shoes...",
  "price": {
    "amount": "99.99",
    "currency": "USD"
  },
  "variants": [...],
  "images": [...],
  "categories": [...]
}
```

---

## Cart API

Cart operations require a **session identifier** passed via the `X-Session-Id` header. Generate a UUID v4 for each guest session.

For `POST`, `PATCH`, and `DELETE` requests you must also include an `X-Idempotency-Key` header (UUID v4 recommended). This protects against duplicate mutations when partners retry after a network failure—the server will replay the original response while the key remains valid (24 hours).

### Get Cart

**GET** `/api/v1/headless/cart`

**Scopes**: `cart:read`

**Headers**: `X-Session-Id: <uuid>`

**Example**:

```bash
SESSION_ID="2f605a0b-93c0-4cf2-a6ed-2f139b31e9de"

curl "https://yourstore.villagecompute.com/api/v1/headless/cart" \
  -H "Authorization: Basic $AUTH_HEADER" \
  -H "X-Session-Id: $SESSION_ID"
```

### Add Item to Cart

**POST** `/api/v1/headless/cart/items`

**Scopes**: `cart:write`

**Headers**: `X-Session-Id: <uuid>`

**Request Body**:

```json
{
  "variantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "quantity": 2
}
```

**Example**:

```bash
curl -X POST "https://yourstore.villagecompute.com/api/v1/headless/cart/items" \
  -H "Authorization: Basic $AUTH_HEADER" \
  -H "X-Session-Id: $SESSION_ID" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "variantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "quantity": 2
  }'
```

### Update Cart Item

**PATCH** `/api/v1/headless/cart/items/{itemId}`

**Scopes**: `cart:write`

**Headers**: `X-Session-Id: <uuid>`

**Request Body**:

```json
{
  "quantity": 3
}
```

### Remove Cart Item

**DELETE** `/api/v1/headless/cart/items/{itemId}`

**Scopes**: `cart:write`

**Headers**: `X-Session-Id: <uuid>`

### Clear Cart

**DELETE** `/api/v1/headless/cart`

**Scopes**: `cart:write`

**Headers**: `X-Session-Id: <uuid>`

---

## Caching

### Catalog Search Results

Search results are cached for **5 minutes** using Caffeine in-memory cache.

- **Cache Key**: Tenant ID + query hash + pagination params
- **Invalidation**: Automatic on product/variant updates and whenever carts reserve or release inventory so `inStock` flags stay fresh
- **Benefits**: Reduced database load, faster response times

### ETag Support (Future)

Planned: ETag headers for conditional requests (`If-None-Match`) to minimize bandwidth.

---

## Error Handling

All errors follow **RFC 7807 Problem Details** format:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Product not found: 550e8400-e29b-41d4-a716-446655440000"
}
```

### Common Error Codes

| Status | Title | Description |
|--------|-------|-------------|
| 400 | Bad Request | Invalid request (missing session, invalid quantity, etc.) |
| 401 | Unauthorized | Invalid or missing OAuth credentials |
| 403 | Forbidden | Missing required scope or feature disabled |
| 404 | Not Found | Resource not found |
| 409 | Conflict | Optimistic lock conflict (cart modified concurrently) |
| 429 | Too Many Requests | Rate limit exceeded |

---

## Code Examples

### JavaScript (Node.js)

```javascript
const axios = require('axios');

const BASE_URL = 'https://yourstore.villagecompute.com/api/v1/headless';
const CLIENT_ID = process.env.OAUTH_CLIENT_ID;
const CLIENT_SECRET = process.env.OAUTH_CLIENT_SECRET;

const authHeader = 'Basic ' + Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64');

async function listProducts(search = '', page = 1) {
  const response = await axios.get(`${BASE_URL}/catalog/products`, {
    headers: { 'Authorization': authHeader },
    params: { search, page, pageSize: 20 }
  });
  return response.data;
}

async function addToCart(sessionId, variantId, quantity) {
  const response = await axios.post(`${BASE_URL}/cart/items`,
    { variantId, quantity },
    {
      headers: {
        'Authorization': authHeader,
        'X-Session-Id': sessionId
      }
    }
  );
  return response.data;
}
```

### Python

```python
import os
import base64
import requests

BASE_URL = 'https://yourstore.villagecompute.com/api/v1/headless'
CLIENT_ID = os.getenv('OAUTH_CLIENT_ID')
CLIENT_SECRET = os.getenv('OAUTH_CLIENT_SECRET')

auth_bytes = f'{CLIENT_ID}:{CLIENT_SECRET}'.encode('ascii')
auth_header = 'Basic ' + base64.b64encode(auth_bytes).decode('ascii')

def list_products(search='', page=1):
    response = requests.get(
        f'{BASE_URL}/catalog/products',
        headers={'Authorization': auth_header},
        params={'search': search, 'page': page, 'pageSize': 20}
    )
    response.raise_for_status()
    return response.json()

def add_to_cart(session_id, variant_id, quantity):
    response = requests.post(
        f'{BASE_URL}/cart/items',
        headers={
            'Authorization': auth_header,
            'X-Session-Id': session_id
        },
        json={'variantId': variant_id, 'quantity': quantity}
    )
    response.raise_for_status()
    return response.json()
```

### cURL

```bash
#!/bin/bash

BASE_URL="https://yourstore.villagecompute.com/api/v1/headless"
CLIENT_ID="your-client-id"
CLIENT_SECRET="your-client-secret"
AUTH_HEADER=$(echo -n "$CLIENT_ID:$CLIENT_SECRET" | base64)

# List products
curl "$BASE_URL/catalog/products?search=shoes" \
  -H "Authorization: Basic $AUTH_HEADER"

# Get product details
curl "$BASE_URL/catalog/products/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Basic $AUTH_HEADER"

# Add to cart
SESSION_ID="2f605a0b-93c0-4cf2-a6ed-2f139b31e9de"
curl -X POST "$BASE_URL/cart/items" \
  -H "Authorization: Basic $AUTH_HEADER" \
  -H "X-Session-Id: $SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{"variantId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "quantity": 2}'
```

---

## Best Practices

1. **Store Secrets Securely**: Never hardcode `client_secret` in code. Use environment variables or secret managers.
2. **Handle Rate Limits**: Implement exponential backoff when receiving 429 responses.
3. **Session Management**: Generate a new UUID for each guest session and persist it client-side (localStorage, cookies).
4. **Error Handling**: Parse RFC 7807 Problem Details responses for user-friendly error messages.
5. **Caching**: Respect cache headers and implement client-side caching for frequently accessed resources.
6. **Idempotency**: Use `X-Idempotency-Key` header for cart mutations to safely retry failed requests.

---

## Support

For questions or issues:
- **Documentation**: https://docs.villagecompute.com
- **API Support**: api-support@villagecompute.com
- **GitHub Issues**: https://github.com/villagecompute/village-storefront/issues
