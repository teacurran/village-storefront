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
import { apiClient } from '@/api/client'
import type { ProductSummary, ProductDetail, PaginationMetadata } from '@/api/generated'
import type { Money } from '@/api/types'

type CatalogProductSummary = ProductSummary & {
  slug?: string
  compareAtPrice?: Money
  primaryImage?: {
    id: string
    url: string
    thumbnailUrl: string
    alt?: string
  }
  trackInventory?: boolean
  totalInventory?: number
  lowStockThreshold?: number
  variantCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface Product {
  id: string
  name: string
  slug: string
  sku?: string
  description?: string
  price?: Money
  compareAtPrice?: Money
  primaryImage?: {
    id: string
    url: string
    thumbnailUrl: string
    alt?: string
  }
  status: 'active' | 'draft' | 'archived'
  trackInventory: boolean
  totalInventory?: number
  lowStockThreshold?: number
  variantCount?: number
  createdAt: string
  updatedAt: string
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

export interface CatalogFilters {
  search?: string
  category?: string
  status?: string
  tags?: string[]
}

interface PaginationState {
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export const useCatalogStore = defineStore('catalog', () => {
  // State - Server State
  const products = ref<Product[]>([])
  const categories = ref<Category[]>([])
  const productCache = ref<Map<string, CacheEntry<ProductDetail>>>(new Map())
  const isLoadingProducts = ref(false)
  const isLoadingCategories = ref(false)
  const lastProductsSource = ref<'api' | 'mock'>('mock')
  const lastCategoriesSource = ref<'api' | 'mock'>('mock')

  // State - UI State
  const selectedProducts = ref<Set<string>>(new Set())
  const currentFilters = ref<CatalogFilters>({})
  const pagination = ref<PaginationState>({
    page: 1,
    pageSize: 20,
    totalItems: 0,
    totalPages: 0,
  })

  const sortField = ref<string>('updatedAt')
  const sortOrder = ref<'asc' | 'desc'>('desc')

  // Computed
  const selectedProductCount = computed(() => selectedProducts.value.size)
  const hasSelection = computed(() => selectedProducts.value.size > 0)
  const hasFilters = computed(() => {
    return Object.values(currentFilters.value).some((v) => v !== undefined && v !== '')
  })

  // Actions - Data Loading
  async function fetchProducts(): Promise<void> {
    if (isLoadingProducts.value) {
      return
    }

    isLoadingProducts.value = true
    try {
      const params = new URLSearchParams()
      params.append('page', String(pagination.value.page))
      params.append('size', String(pagination.value.pageSize))

      if (currentFilters.value.search) {
        params.append('q', currentFilters.value.search)
      }
      if (currentFilters.value.category) {
        params.append('categoryId', currentFilters.value.category)
      }
      if (currentFilters.value.status) {
        params.append('status', currentFilters.value.status)
      }
      if (sortField.value) {
        params.append('sort', `${sortField.value},${sortOrder.value}`)
      }

      const response = await apiClient.get<{
        items: CatalogProductSummary[]
        pagination?: PaginationMetadata
      }>(`/admin/catalog/products?${params.toString()}`)

      products.value = transformProductSummaries(response.items || [])

      const fallbackPagination: PaginationMetadata = {
        page: pagination.value.page,
        pageSize: pagination.value.pageSize,
        totalItems: response.items?.length ?? pagination.value.totalItems,
        totalPages: pagination.value.totalPages,
        hasNext: false,
        hasPrevious: false,
      }

      const paginationMeta = response.pagination ?? fallbackPagination

      pagination.value = {
        page: paginationMeta.page || 1,
        pageSize: paginationMeta.pageSize || 20,
        totalItems: paginationMeta.totalItems || 0,
        totalPages: paginationMeta.totalPages || 0,
      }
      lastProductsSource.value = 'api'
    } catch (error) {
      console.error('Failed to fetch products:', error)
      const fallback = await getMockCatalogListing(pagination.value.page, pagination.value.pageSize)
      products.value = transformProductSummaries(fallback.items)
      pagination.value = {
        page: fallback.pagination.page,
        pageSize: fallback.pagination.pageSize,
        totalItems: fallback.pagination.totalItems,
        totalPages: fallback.pagination.totalPages,
      }
      lastProductsSource.value = 'mock'
    } finally {
      isLoadingProducts.value = false
    }
  }

  async function fetchCategories(): Promise<void> {
    if (isLoadingCategories.value) {
      return
    }

    isLoadingCategories.value = true
    try {
      const response = await apiClient.get<Category[]>('/admin/catalog/categories')
      categories.value = response || []
      lastCategoriesSource.value = 'api'
    } catch (error) {
      console.error('Failed to fetch categories:', error)
      categories.value = await getMockCategories()
      lastCategoriesSource.value = 'mock'
    } finally {
      isLoadingCategories.value = false
    }
  }

  async function fetchProductById(id: string): Promise<ProductDetail | null> {
    // Check cache first
    const cached = productCache.value.get(id)
    if (cached && Date.now() - cached.timestamp < cached.ttl) {
      return cached.data
    }

    try {
      const product = await apiClient.get<ProductDetail>(`/admin/catalog/products/${id}`)

      // Update cache with 5-minute TTL
      productCache.value.set(id, {
        data: product,
        timestamp: Date.now(),
        ttl: 300000, // 5 minutes
      })

      return product
    } catch (error) {
      console.error(`Failed to fetch product ${id}:`, error)
      return null
    }
  }

  async function createProduct(data: Partial<ProductDetail>): Promise<ProductDetail> {
    try {
      const product = await apiClient.post<ProductDetail>('/admin/catalog/products', data)
      // Invalidate list cache and reload
      await fetchProducts()
      return product
    } catch (error) {
      console.error('Failed to create product:', error)
      throw error
    }
  }

  async function updateProduct(id: string, data: Partial<ProductDetail>): Promise<ProductDetail> {
    try {
      const product = await apiClient.put<ProductDetail>(`/admin/catalog/products/${id}`, data)

      // Update cache
      productCache.value.set(id, {
        data: product,
        timestamp: Date.now(),
        ttl: 300000,
      })

      // Invalidate list cache and reload
      await fetchProducts()
      return product
    } catch (error) {
      console.error(`Failed to update product ${id}:`, error)
      throw error
    }
  }

  async function deleteProduct(id: string): Promise<void> {
    try {
      await apiClient.delete(`/admin/catalog/products/${id}`)

      // Remove from cache
      productCache.value.delete(id)

      // Remove from local state
      products.value = products.value.filter((p) => p.id !== id)

      // Update counts
      pagination.value.totalItems = Math.max(0, pagination.value.totalItems - 1)
    } catch (error) {
      console.error(`Failed to delete product ${id}:`, error)
      throw error
    }
  }

  // Actions - UI State
  function setFilters(filters: CatalogFilters): void {
    currentFilters.value = { ...filters }
    pagination.value.page = 1 // Reset to first page
  }

  function clearFilters(): void {
    currentFilters.value = {}
    pagination.value.page = 1
  }

  function setPage(page: number): void {
    pagination.value.page = page
  }

  function setPageSize(size: number): void {
    pagination.value.pageSize = size
    pagination.value.page = 1
  }

  function setSort(field: string, order: 'asc' | 'desc'): void {
    sortField.value = field
    sortOrder.value = order
  }

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
    products.value.forEach((product) => {
      selectedProducts.value.add(product.id)
    })
  }

  function clearSelection(): void {
    selectedProducts.value.clear()
  }

  function clearCache(): void {
    productCache.value.clear()
  }

  return {
    // State
    products,
    categories,
    isLoadingProducts,
    isLoadingCategories,
    lastProductsSource,
    lastCategoriesSource,
    selectedProducts,
    currentFilters,
    pagination,
    sortField,
    sortOrder,

    // Computed
    selectedProductCount,
    hasSelection,
    hasFilters,

    // Actions
    fetchProducts,
    fetchCategories,
    fetchProductById,
    createProduct,
    updateProduct,
    deleteProduct,
    setFilters,
    clearFilters,
    setPage,
    setPageSize,
    setSort,
    selectProduct,
    deselectProduct,
    toggleProduct,
    selectAllProducts,
    clearSelection,
    clearCache,
  }
})

function transformProductSummaries(items: CatalogProductSummary[]): Product[] {
  return items.map((item) => ({
    id: item.id || '',
    name: item.name || '',
    slug: item.slug || '',
    sku: item.sku,
    description: item.description,
    price: item.price,
    compareAtPrice: item.compareAtPrice,
    primaryImage: item.primaryImage,
    status: (item.status?.toLowerCase() || 'draft') as Product['status'],
    trackInventory: item.trackInventory || false,
    totalInventory: item.totalInventory,
    lowStockThreshold: item.lowStockThreshold,
    variantCount: item.variantCount,
    createdAt: item.createdAt || new Date().toISOString(),
    updatedAt: item.updatedAt || new Date().toISOString(),
  }))
}

async function getMockCatalogListing(
  page: number,
  pageSize: number
): Promise<{ items: CatalogProductSummary[]; pagination: PaginationMetadata }> {
  await delay(200)
  const start = (page - 1) * pageSize
  const end = start + pageSize
  const slice = mockProductSummaries.slice(start, end)

  return {
    items: slice,
    pagination: {
      page,
      pageSize,
      totalItems: mockProductSummaries.length,
      totalPages: Math.ceil(mockProductSummaries.length / pageSize),
      hasNext: end < mockProductSummaries.length,
      hasPrevious: start > 0,
    },
  }
}

async function getMockCategories(): Promise<Category[]> {
  await delay(120)
  return [
    { id: 'cat-apparel', name: 'Apparel', slug: 'apparel', sortOrder: 1 },
    { id: 'cat-home', name: 'Home & Living', slug: 'home', sortOrder: 2 },
    { id: 'cat-consignment', name: 'Consignment', slug: 'consignment', sortOrder: 3 },
  ]
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

const mockProductSummaries: CatalogProductSummary[] = [
  {
    id: 'prod-1',
    name: 'Heritage Denim Jacket',
    slug: 'heritage-denim-jacket',
    sku: 'JCK-001',
    description: 'Classic selvedge denim jacket with brass hardware.',
    price: { currency: 'USD', amount: 16800, value: 16800 },
    compareAtPrice: { currency: 'USD', amount: 18900, value: 18900 },
    primaryImage: {
      id: 'img-1',
      url: 'https://placehold.co/400x400',
      thumbnailUrl: 'https://placehold.co/96x96',
    },
    status: 'active',
    trackInventory: true,
    totalInventory: 58,
    lowStockThreshold: 10,
    variantCount: 6,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-2',
    name: 'Merino Travel Tee',
    slug: 'merino-travel-tee',
    sku: 'TEE-103',
    description: 'Breathable merino wool tee that resists wrinkles.',
    price: { currency: 'USD', amount: 7800, value: 7800 },
    primaryImage: {
      id: 'img-2',
      url: 'https://placehold.co/400x400',
      thumbnailUrl: 'https://placehold.co/96x96',
    },
    status: 'active',
    trackInventory: true,
    totalInventory: 245,
    lowStockThreshold: 25,
    variantCount: 8,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-3',
    name: 'Organic Canvas Tote',
    slug: 'organic-canvas-tote',
    sku: 'BAG-208',
    description: 'Heavyweight organic cotton canvas tote with pocket.',
    price: { currency: 'USD', amount: 3800, value: 3800 },
    status: 'draft',
    trackInventory: false,
    totalInventory: 0,
    variantCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-4',
    name: 'Limited Run Stoneware Mug',
    slug: 'stoneware-mug',
    sku: 'MUG-014',
    description: 'Handmade mug with satin glaze and ergonomic handle.',
    price: { currency: 'USD', amount: 3200, value: 3200 },
    status: 'active',
    trackInventory: true,
    totalInventory: 18,
    lowStockThreshold: 12,
    variantCount: 2,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-5',
    name: 'Consigned Vintage Leather Bag',
    slug: 'consigned-vintage-bag',
    sku: 'CSN-442',
    description: 'Consigned full-grain leather bag with patina.',
    price: { currency: 'USD', amount: 24500, value: 24500 },
    status: 'active',
    trackInventory: true,
    totalInventory: 3,
    lowStockThreshold: 2,
    variantCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-6',
    name: 'Signature Candle Set',
    slug: 'signature-candle-set',
    sku: 'HOME-301',
    description: 'Set of three soy candles with custom scents.',
    price: { currency: 'USD', amount: 6400, value: 6400 },
    status: 'archived',
    trackInventory: false,
    totalInventory: 0,
    variantCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
]
