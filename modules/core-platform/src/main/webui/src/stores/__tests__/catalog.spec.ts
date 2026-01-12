/**
 * Catalog Store Tests
 *
 * Unit tests for the catalog store covering product management,
 * filtering, pagination, and caching logic.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useCatalogStore } from '../catalog'
import type { ProductSummary, PaginationMetadata } from '@/api/generated'

// Mock the API client
vi.mock('@/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import { apiClient } from '@/api/client'

describe('Catalog Store', () => {
  beforeEach(() => {
    // Create a fresh pinia instance for each test
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('fetchProducts', () => {
    it('should fetch and set products from API', async () => {
      const mockResponse = {
        items: [
          {
            id: '1',
            name: 'Test Product',
            slug: 'test-product',
            status: 'ACTIVE',
            price: { value: 1999, currency: 'USD' },
            trackInventory: true,
            totalInventory: 50,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:00Z',
          },
        ] as ProductSummary[],
        pagination: {
          page: 1,
          pageSize: 20,
          totalItems: 1,
          totalPages: 1,
        } as PaginationMetadata,
      }

      vi.mocked(apiClient.get).mockResolvedValueOnce(mockResponse)

      const store = useCatalogStore()
      await store.fetchProducts()

      expect(apiClient.get).toHaveBeenCalledWith(expect.stringContaining('/admin/catalog/products'))
      expect(store.products).toHaveLength(1)
      expect(store.products[0].name).toBe('Test Product')
      expect(store.pagination.totalItems).toBe(1)
    })

    it('should handle API errors gracefully', async () => {
      vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Network error'))

      const store = useCatalogStore()
      await expect(store.fetchProducts()).rejects.toThrow('Network error')

      expect(store.products).toHaveLength(0)
      expect(store.pagination.totalItems).toBe(0)
    })

    it('should include filters in API request', async () => {
      const mockResponse = {
        items: [],
        pagination: { page: 1, pageSize: 20, totalItems: 0, totalPages: 0 },
      }

      vi.mocked(apiClient.get).mockResolvedValueOnce(mockResponse)

      const store = useCatalogStore()
      store.setFilters({ search: 'test', status: 'active', category: 'cat-1' })
      await store.fetchProducts()

      expect(apiClient.get).toHaveBeenCalledWith(expect.stringContaining('q=test'))
      expect(apiClient.get).toHaveBeenCalledWith(expect.stringContaining('status=active'))
      expect(apiClient.get).toHaveBeenCalledWith(expect.stringContaining('categoryId=cat-1'))
    })
  })

  describe('deleteProduct', () => {
    it('should delete product and update state', async () => {
      vi.mocked(apiClient.delete).mockResolvedValueOnce(undefined)

      const store = useCatalogStore()
      store.products = [
        {
          id: '1',
          name: 'Test Product',
          slug: 'test',
          status: 'active',
          trackInventory: true,
          createdAt: '',
          updatedAt: '',
        },
      ]
      store.pagination.totalItems = 1

      await store.deleteProduct('1')

      expect(apiClient.delete).toHaveBeenCalledWith('/admin/catalog/products/1')
      expect(store.products).toHaveLength(0)
      expect(store.pagination.totalItems).toBe(0)
    })
  })

  describe('filters and pagination', () => {
    it('should set filters and reset to page 1', () => {
      const store = useCatalogStore()
      store.pagination.page = 3

      store.setFilters({ search: 'test' })

      expect(store.currentFilters.search).toBe('test')
      expect(store.pagination.page).toBe(1)
    })

    it('should clear filters', () => {
      const store = useCatalogStore()
      store.currentFilters = { search: 'test', status: 'active' }

      store.clearFilters()

      expect(store.currentFilters).toEqual({})
      expect(store.pagination.page).toBe(1)
    })

    it('should update page size and reset to page 1', () => {
      const store = useCatalogStore()
      store.pagination.page = 3

      store.setPageSize(50)

      expect(store.pagination.pageSize).toBe(50)
      expect(store.pagination.page).toBe(1)
    })

    it('should set sort field and order', () => {
      const store = useCatalogStore()

      store.setSort('name', 'asc')

      expect(store.sortField).toBe('name')
      expect(store.sortOrder).toBe('asc')
    })
  })

  describe('selection', () => {
    it('should select and deselect products', () => {
      const store = useCatalogStore()

      store.selectProduct('1')
      expect(store.selectedProducts.has('1')).toBe(true)

      store.deselectProduct('1')
      expect(store.selectedProducts.has('1')).toBe(false)
    })

    it('should toggle product selection', () => {
      const store = useCatalogStore()

      store.toggleProduct('1')
      expect(store.selectedProducts.has('1')).toBe(true)

      store.toggleProduct('1')
      expect(store.selectedProducts.has('1')).toBe(false)
    })

    it('should select all products', () => {
      const store = useCatalogStore()
      store.products = [
        {
          id: '1',
          name: 'P1',
          slug: 's1',
          status: 'active',
          trackInventory: true,
          createdAt: '',
          updatedAt: '',
        },
        {
          id: '2',
          name: 'P2',
          slug: 's2',
          status: 'active',
          trackInventory: true,
          createdAt: '',
          updatedAt: '',
        },
      ]

      store.selectAllProducts()

      expect(store.selectedProducts.size).toBe(2)
      expect(store.hasSelection).toBe(true)
    })

    it('should clear selection', () => {
      const store = useCatalogStore()
      store.selectProduct('1')
      store.selectProduct('2')

      store.clearSelection()

      expect(store.selectedProducts.size).toBe(0)
      expect(store.hasSelection).toBe(false)
    })
  })

  describe('computed properties', () => {
    it('should calculate selectedProductCount', () => {
      const store = useCatalogStore()
      store.selectProduct('1')
      store.selectProduct('2')

      expect(store.selectedProductCount).toBe(2)
    })

    it('should determine hasFilters', () => {
      const store = useCatalogStore()

      expect(store.hasFilters).toBe(false)

      store.setFilters({ search: 'test' })
      expect(store.hasFilters).toBe(true)
    })
  })

  describe('caching', () => {
    it('should cache product details with TTL', async () => {
      const mockProduct = {
        id: '1',
        name: 'Test Product',
        slug: 'test-product',
        status: 'ACTIVE',
        trackInventory: true,
      }

      vi.mocked(apiClient.get).mockResolvedValueOnce(mockProduct)

      const store = useCatalogStore()
      const product1 = await store.fetchProductById('1')

      expect(apiClient.get).toHaveBeenCalledTimes(1)
      expect(product1?.name).toBe('Test Product')

      // Second call should use cache
      const product2 = await store.fetchProductById('1')
      expect(apiClient.get).toHaveBeenCalledTimes(1) // Still 1, not called again
      expect(product2?.name).toBe('Test Product')
    })

    it('should clear cache', async () => {
      const store = useCatalogStore()
      const mockProduct = { id: '1', name: 'Test' }

      vi.mocked(apiClient.get).mockResolvedValueOnce(mockProduct)
      await store.fetchProductById('1')

      store.clearCache()

      // After clearing cache, should fetch again
      vi.mocked(apiClient.get).mockResolvedValueOnce(mockProduct)
      await store.fetchProductById('1')

      expect(apiClient.get).toHaveBeenCalledTimes(2)
    })
  })
})
