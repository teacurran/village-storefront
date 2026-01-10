# Next.js Headless Storefront Example

This example demonstrates how to integrate Village Storefront Headless API with a Next.js application using OAuth client credentials authentication.

## Features

- Product listing with search and pagination
- Product detail pages with variants
- Add to cart functionality
- OAuth client credentials authentication with HTTP Basic Auth
- Rate limit handling with automatic retry logic
- Comprehensive error handling (401, 403, 429)
- TypeScript types for all API responses
- Server-side rendering with Next.js App Router

## Prerequisites

1. Village Storefront instance running (local or production)
2. OAuth client credentials (created via admin dashboard at `/admin/settings`)
3. Node.js 18+ installed

## Setup

### 1. Create OAuth Client

1. Log into your Village Storefront admin dashboard
2. Navigate to **Settings** → **OAuth Clients**
3. Click **Create Client**
4. Configure the client:
   - **Name**: Next.js Headless Example
   - **Description**: Sample Next.js integration
   - **Scopes**: Select all scopes (catalog:read, cart:read, cart:write, orders:read)
   - **Rate Limit**: 5000 requests/minute (default)
5. Click **Create** and copy the client ID and secret immediately (won't be shown again)

### 2. Configure Environment Variables

Create `.env.local` file in this directory:

```bash
STOREFRONT_URL=https://yourstore.villagecompute.com
OAUTH_CLIENT_ID=oauth_abc123xyz...
OAUTH_CLIENT_SECRET=your-secret-here...
```

**Important:** Never commit `.env.local` to version control. Add it to `.gitignore`.

### 3. Install Dependencies

```bash
npm install
```

### 4. Run Development Server

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser.

## Project Structure

```
nextjs-headless/
├── app/
│   ├── layout.tsx           # Root layout
│   ├── page.tsx             # Home page (product listing)
│   └── products/
│       └── [slug]/
│           └── page.tsx     # Product detail page
├── lib/
│   └── storefront-api.ts    # API client with OAuth auth
├── components/
│   ├── ProductCard.tsx      # Product list item
│   └── AddToCartButton.tsx  # Cart interaction
└── types/
    └── storefront.ts        # TypeScript types
```

## API Client Implementation

The `lib/storefront-api.ts` file provides a reusable API client with:

- **OAuth Authentication**: Automatic HTTP Basic Auth with client credentials
- **Rate Limit Handling**: Detects 429 responses and retries after delay
- **Request Caching**: GET requests cached for 60 seconds (Next.js App Router)
- **Error Handling**: Typed error responses with ProblemDetails format
- **TypeScript Types**: Fully typed request/response interfaces

### Example Usage

```typescript
import { getProducts, getProduct, addToCart } from '@/lib/storefront-api'

// List products
const result = await getProducts('shoes', 1, 20)
console.log(result.products)

// Get product details
const product = await getProduct('product-uuid')
console.log(product.name, product.price)

// Add to cart
const cartItem = await addToCart('session-id', 'variant-uuid', 2)
console.log('Added', cartItem.quantity, 'items')
```

## Rate Limiting

The API enforces rate limits per OAuth client (default: 5000 requests/minute). The API client automatically:

1. Detects 429 responses
2. Reads `Retry-After` header
3. Waits specified delay
4. Retries request once

Rate limit headers included in all responses:

```http
X-RateLimit-Limit: 5000
X-RateLimit-Remaining: 4998
X-RateLimit-Reset: 1704110460
```

## Error Handling

All errors follow RFC 7807 ProblemDetails format:

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded for client xyz. Retry after 45 seconds.",
  "instance": "/api/v1/headless/catalog/products"
}
```

Common error codes:

- **401 Unauthorized**: Invalid or expired OAuth credentials
- **403 Forbidden**: Missing required scope (e.g., cart:write)
- **404 Not Found**: Product or resource not found
- **429 Too Many Requests**: Rate limit exceeded

## Production Deployment

### Environment Variables

Set these in your production environment (Vercel, Netlify, etc.):

```bash
STOREFRONT_URL=https://yourstore.villagecompute.com
OAUTH_CLIENT_ID=oauth_...
OAUTH_CLIENT_SECRET=...
```

### Security Considerations

1. **Never expose client credentials in client-side code**: All API calls happen server-side
2. **Use environment variables**: Keep secrets out of source code
3. **Enable HTTPS**: Always use HTTPS in production
4. **Rotate secrets regularly**: Regenerate OAuth client secrets periodically
5. **Monitor rate limits**: Track usage to avoid hitting limits

## Next Steps

- Add checkout flow with orders API
- Implement customer authentication
- Add cart persistence with session management
- Integrate with your existing user system
- Add analytics and tracking

## Support

For issues or questions:

- Documentation: `/docs/headless/usage.md`
- API Reference: OpenAPI spec at `/api/v1/openapi.yaml`
- GitHub Issues: [village-storefront/issues](https://github.com/villagecompute/village-storefront/issues)

## License

MIT
