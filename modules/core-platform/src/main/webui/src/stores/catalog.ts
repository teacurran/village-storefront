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

      // Call admin catalog API endpoint
      const response = await apiClient.get<{
        items: ProductSummary[]
        pagination?: PaginationMetadata
      }>(`/admin/catalog/products?${params.toString()}`)

      // Transform ProductSummary to Product format
      products.value = (response.items || []).map((item) => ({
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

      const fallbackPagination: PaginationMetadata = {
        page: pagination.value.page,
        pageSize: pagination.value.pageSize,
        totalItems: response.items?.length ?? pagination.value.totalItems,
        totalPages: pagination.value.totalPages,
      }

      const paginationMeta = response.pagination ?? fallbackPagination

      pagination.value = {
        page: paginationMeta.page || 1,
        pageSize: paginationMeta.pageSize || 20,
        totalItems: paginationMeta.totalItems || 0,
        totalPages: paginationMeta.totalPages || 0,
      }
    } catch (error) {
      console.error('Failed to fetch products:', error)
      // Fallback to empty state on error
      products.value = []
      pagination.value = {
        page: 1,
        pageSize: 20,
        totalItems: 0,
        totalPages: 0,
      }
      throw error
    }
  }

  async function fetchCategories(): Promise<void> {
    try {
      const response = await apiClient.get<Category[]>('/admin/catalog/categories')
      categories.value = response || []
    } catch (error) {
      console.error('Failed to fetch categories:', error)
      categories.value = []
      throw error
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
