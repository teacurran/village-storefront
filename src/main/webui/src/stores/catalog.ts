/**
 * Catalog Store
 *
 * Manages product catalog state including products, categories, variants,
 * and inventory. Implements server state caching with TTL and ETag support.
 *
 * References:
 * - Architecture Section 4.1.1: State Patterns
 * - UI/UX Section 4.1: State Management
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { PaginatedResponse, Money } from '@/api/types'

export interface Product {
  id: string
  name: string
  slug: string
  description?: string
  price: Money
  compareAtPrice?: Money
  images: ProductImage[]
  variants: ProductVariant[]
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  createdAt: string
  updatedAt: string
}

export interface ProductImage {
  id: string
  url: string
  alt?: string
  sortOrder: number
}

export interface ProductVariant {
  id: string
  sku: string
  name: string
  price: Money
  inventoryQuantity: number
  options: Record<string, string>
}

export interface Category {
  id: string
  name: string
  slug: string
  parentId?: string
  sortOrder: number
}

interface CacheEntry<T> {
  data: T
  timestamp: number
  etag?: string
  ttl: number
}

export const useCatalogStore = defineStore('catalog', () => {
  // State - Server State
  const products = ref<Map<string, Product>>(new Map())
  const categories = ref<Map<string, Category>>(new Map())
  const productCache = ref<Map<string, CacheEntry<Product>>>(new Map())

  // State - UI State
  const selectedProducts = ref<Set<string>>(new Set())
  const filters = ref({
    search: '',
    status: 'ACTIVE' as Product['status'] | 'ALL',
    category: null as string | null,
    sort: 'updatedAt:desc',
  })
  const currentPage = ref(1)
  const pageSize = ref(20)
  const totalProducts = ref(0)

  // Computed
  const filteredProducts = computed(() => {
    let result = Array.from(products.value.values())

    // Apply search filter
    if (filters.value.search) {
      const search = filters.value.search.toLowerCase()
      result = result.filter(
        (p) =>
          p.name.toLowerCase().includes(search) ||
          p.description?.toLowerCase().includes(search)
      )
    }

    // Apply status filter
    if (filters.value.status !== 'ALL') {
      result = result.filter((p) => p.status === filters.value.status)
    }

    // Apply category filter
    if (filters.value.category) {
      // This would need to check product-category relationships
      // Stub for now
    }

    return result
  })

  const selectedProductCount = computed(() => selectedProducts.value.size)

  const hasSelection = computed(() => selectedProducts.value.size > 0)

  // Actions - Data Loading
  async function loadProducts(page = 1): Promise<void> {
    // Mock implementation - replace with actual API call
    // const cacheKey = `products:${page}:${pageSize.value}:${JSON.stringify(filters.value)}`
    // Check cache first...

    const mockProducts: Product[] = [
      {
        id: '1',
        name: 'Sample Product',
        slug: 'sample-product',
        description: 'A sample product for testing',
        price: { amount: 29.99, currency: 'USD' },
        images: [],
        variants: [],
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]

    // Update products map
    mockProducts.forEach((product) => {
      products.value.set(product.id, product)
    })

    currentPage.value = page
    totalProducts.value = mockProducts.length
  }

  async function loadProduct(id: string): Promise<Product | null> {
    // Check cache first
    const cached = productCache.value.get(id)
    if (cached && Date.now() - cached.timestamp < cached.ttl) {
      return cached.data
    }

    // Mock implementation - replace with actual API call
    // const response = await apiClient.get<Product>(`/products/${id}`)

    const product = products.value.get(id) || null

    // Update cache
    if (product) {
      productCache.value.set(id, {
        data: product,
        timestamp: Date.now(),
        ttl: 60000, // 1 minute
      })
    }

    return product
  }

  async function loadCategories(): Promise<void> {
    // Mock implementation - replace with actual API call
    const mockCategories: Category[] = [
      {
        id: '1',
        name: 'Clothing',
        slug: 'clothing',
        sortOrder: 1,
      },
      {
        id: '2',
        name: 'Accessories',
        slug: 'accessories',
        sortOrder: 2,
      },
    ]

    categories.value.clear()
    mockCategories.forEach((category) => {
      categories.value.set(category.id, category)
    })
  }

  // Actions - UI State
  function selectProduct(id: string): void {
    selectedProducts.value.add(id)
  }

  function deselectProduct(id: string): void {
    selectedProducts.value.delete(id)
  }

  function toggleProduct(id: string): void {
    if (selectedProducts.value.has(id)) {
      selectedProducts.value.delete(id)
    } else {
      selectedProducts.value.add(id)
    }
  }

  function selectAllProducts(): void {
    filteredProducts.value.forEach((product) => {
      selectedProducts.value.add(product.id)
    })
  }

  function clearSelection(): void {
    selectedProducts.value.clear()
  }

  function setFilters(newFilters: Partial<typeof filters.value>): void {
    filters.value = { ...filters.value, ...newFilters }
    // Trigger reload
    loadProducts(1)
  }

  function clearCache(): void {
    productCache.value.clear()
  }

  return {
    // State
    products,
    categories,
    selectedProducts,
    filters,
    currentPage,
    pageSize,
    totalProducts,

    // Computed
    filteredProducts,
    selectedProductCount,
    hasSelection,

    // Actions
    loadProducts,
    loadProduct,
    loadCategories,
    selectProduct,
    deselectProduct,
    toggleProduct,
    selectAllProducts,
    clearSelection,
    setFilters,
    clearCache,
  }
})
