import { cache } from 'react'

const STOREFRONT_URL = process.env.STOREFRONT_URL
const CLIENT_ID = process.env.OAUTH_CLIENT_ID
const CLIENT_SECRET = process.env.OAUTH_CLIENT_SECRET

if (!STOREFRONT_URL || !CLIENT_ID || !CLIENT_SECRET) {
  throw new Error('Missing required environment variables: STOREFRONT_URL, OAUTH_CLIENT_ID, OAUTH_CLIENT_SECRET')
}

// Build HTTP Basic Auth header (Base64 encoded client_id:client_secret)
const authHeader = 'Basic ' + Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64')

interface ApiOptions {
  method?: string
  body?: any
  headers?: Record<string, string>
  retryOn429?: boolean
}

/**
 * Make authenticated API request to Village Storefront headless API.
 *
 * @param path - API path (e.g., /api/v1/headless/catalog/products)
 * @param options - Request options
 * @returns Parsed JSON response
 * @throws Error if request fails
 */
async function apiRequest<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    headers = {},
    retryOn429 = true
  } = options

  const url = `${STOREFRONT_URL}${path}`

  const response = await fetch(url, {
    method,
    headers: {
      'Authorization': authHeader,
      'Content-Type': 'application/json',
      ...headers
    },
    body: body ? JSON.stringify(body) : undefined,
    // Enable caching for GET requests (Next.js App Router)
    ...(method === 'GET' && { next: { revalidate: 60 } })
  })

  // Handle rate limiting with retry
  if (response.status === 429 && retryOn429) {
    const retryAfter = response.headers.get('Retry-After')
    const delay = retryAfter ? parseInt(retryAfter) * 1000 : 5000

    console.warn(`Rate limited. Retrying after ${delay}ms`)
    await new Promise(resolve => setTimeout(resolve, delay))

    // Retry once (disable further retries to avoid infinite loop)
    return apiRequest<T>(path, { ...options, retryOn429: false })
  }

  if (!response.ok) {
    const error = await response.json().catch(() => ({ detail: `API error: ${response.status}` }))
    throw new Error(error.detail || `API error: ${response.status}`)
  }

  return response.json()
}

// ========================================
// Catalog API
// ========================================

/**
 * List products with optional search and pagination.
 *
 * @param search - Search query (optional)
 * @param page - Page number (1-indexed)
 * @param pageSize - Items per page (max 100)
 * @returns Product list with pagination metadata
 */
export const getProducts = cache(async (search?: string, page = 1, pageSize = 20) => {
  const params = new URLSearchParams({
    page: page.toString(),
    pageSize: pageSize.toString(),
    ...(search && { search })
  })

  return apiRequest<{
    products: Product[]
    pagination: { page: number, pageSize: number, totalItems: number, totalPages: number }
  }>(`/api/v1/headless/catalog/products?${params}`)
})

/**
 * Get product details by ID.
 *
 * @param productId - Product UUID
 * @returns Product with variants
 */
export const getProduct = cache(async (productId: string) => {
  return apiRequest<Product>(`/api/v1/headless/catalog/products/${productId}`)
})

// ========================================
// Cart API
// ========================================

/**
 * Get cart contents.
 *
 * @param sessionId - Session identifier (UUID)
 * @returns Cart with items and totals
 */
export async function getCart(sessionId: string) {
  return apiRequest<Cart>('/api/v1/headless/cart', {
    headers: { 'X-Session-Id': sessionId }
  })
}

/**
 * Add item to cart.
 *
 * @param sessionId - Session identifier (UUID)
 * @param variantId - Product variant UUID
 * @param quantity - Quantity to add
 * @returns Created cart item
 */
export async function addToCart(sessionId: string, variantId: string, quantity: number) {
  return apiRequest<CartItem>('/api/v1/headless/cart/items', {
    method: 'POST',
    body: { variantId, quantity },
    headers: { 'X-Session-Id': sessionId }
  })
}

/**
 * Update cart item quantity.
 *
 * @param sessionId - Session identifier (UUID)
 * @param itemId - Cart item UUID
 * @param quantity - New quantity
 * @returns Updated cart item
 */
export async function updateCartItem(sessionId: string, itemId: string, quantity: number) {
  return apiRequest<CartItem>(`/api/v1/headless/cart/items/${itemId}`, {
    method: 'PATCH',
    body: { quantity },
    headers: { 'X-Session-Id': sessionId }
  })
}

/**
 * Remove item from cart.
 *
 * @param sessionId - Session identifier (UUID)
 * @param itemId - Cart item UUID
 */
export async function removeFromCart(sessionId: string, itemId: string) {
  return apiRequest<void>(`/api/v1/headless/cart/items/${itemId}`, {
    method: 'DELETE',
    headers: { 'X-Session-Id': sessionId }
  })
}

/**
 * Clear all items from cart.
 *
 * @param sessionId - Session identifier (UUID)
 */
export async function clearCart(sessionId: string) {
  return apiRequest<void>('/api/v1/headless/cart', {
    method: 'DELETE',
    headers: { 'X-Session-Id': sessionId }
  })
}

// ========================================
// TypeScript Types
// ========================================

export interface Product {
  id: string
  sku: string
  name: string
  slug: string
  description?: string
  price: string
  imageUrl?: string
  status: 'active' | 'draft' | 'archived'
  variants: ProductVariant[]
}

export interface ProductVariant {
  id: string
  sku: string
  name: string
  price: string
  inventoryQuantity: number
  status: 'active' | 'inactive'
}

export interface Cart {
  id: string
  items: CartItem[]
  subtotal: string
  tax: string
  total: string
}

export interface CartItem {
  id: string
  variantId: string
  productName: string
  variantName: string
  quantity: number
  price: string
  total: string
}

export interface ApiError {
  type: string
  title: string
  status: number
  detail: string
  instance: string
}
