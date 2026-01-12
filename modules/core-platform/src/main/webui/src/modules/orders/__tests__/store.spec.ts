/**
 * Orders Store Unit Tests
 *
 * Tests for orders store actions, mutations, and state management.
 * Covers refund, capture, note operations and optimistic UI updates.
 *
 * References:
 * - Task I3.T7: Orders dashboard implementation
 * - Store: modules/orders/store.ts
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useOrdersStore } from '../store'
import * as ordersApi from '../api'
import type { OrderDetail, OrderItem, OrderStatus } from '../types'

// Mock the API module
vi.mock('../api', () => ({
  getOrders: vi.fn(),
  getOrdersStats: vi.fn(),
  getOrderDetail: vi.fn(),
  updateOrderStatus: vi.fn(),
  cancelOrder: vi.fn(),
  refundOrder: vi.fn(),
  capturePayment: vi.fn(),
  addOrderNote: vi.fn(),
  bulkUpdateOrders: vi.fn(),
  exportOrders: vi.fn(),
  connectOrdersSSE: vi.fn(),
}))

// Mock telemetry
vi.mock('@/telemetry', () => ({
  emitTelemetryEvent: vi.fn(),
}))

describe('Orders Store', () => {
  let store: ReturnType<typeof useOrdersStore>

  const mockOrder: OrderItem = {
    id: 'order-1',
    orderNumber: 'ORD-001',
    customerName: 'John Doe',
    customerEmail: 'john@example.com',
    status: 'CONFIRMED',
    total: { amount: 10000, currency: 'USD' },
    itemCount: 2,
    createdAt: '2026-01-10T12:00:00Z',
    updatedAt: '2026-01-10T12:00:00Z',
    paymentMethod: 'card',
  }

  const mockOrderDetail: OrderDetail = {
    ...mockOrder,
    lineItems: [
      {
        id: 'line-1',
        productId: 'prod-1',
        sku: 'SKU-001',
        name: 'Test Product',
        quantity: 2,
        unitPrice: { amount: 5000, currency: 'USD' },
        total: { amount: 10000, currency: 'USD' },
      },
    ],
    subtotal: { amount: 10000, currency: 'USD' },
    taxAmount: { amount: 0, currency: 'USD' },
    shippingAmount: { amount: 0, currency: 'USD' },
    discountAmount: { amount: 0, currency: 'USD' },
    timeline: [
      {
        id: 'evt-1',
        type: 'order.created',
        description: 'Order created',
        timestamp: '2026-01-10T12:00:00Z',
      },
    ],
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    store = useOrdersStore()
    vi.clearAllMocks()
  })

  describe('loadOrders', () => {
    it('loads orders and updates state', async () => {
      const mockResponse = { items: [mockOrder], total: 1 }
      vi.mocked(ordersApi.getOrders).mockResolvedValue(mockResponse)

      await store.loadOrders()

      expect(ordersApi.getOrders).toHaveBeenCalledWith(0, 20, store.filters)
      expect(store.orders).toEqual([mockOrder])
      expect(store.pagination.total).toBe(1)
      expect(store.loading).toBe(false)
      expect(store.error).toBeNull()
    })

    it('requests the provided page number when loading more', async () => {
      const mockResponse = { items: [mockOrder], total: 2 }
      vi.mocked(ordersApi.getOrders).mockResolvedValue(mockResponse)

      await store.loadOrders(1, false)

      expect(ordersApi.getOrders).toHaveBeenCalledWith(1, 20, store.filters)
      expect(store.pagination.page).toBe(1)
    })

    it('handles load errors', async () => {
      const errorMessage = 'Network error'
      vi.mocked(ordersApi.getOrders).mockRejectedValue(new Error(errorMessage))

      await expect(store.loadOrders()).rejects.toThrow(errorMessage)
      expect(store.error).toBe(errorMessage)
      expect(store.loading).toBe(false)
    })

    it('resets selection when resetSelection is true', async () => {
      const mockResponse = { items: [mockOrder], total: 1 }
      vi.mocked(ordersApi.getOrders).mockResolvedValue(mockResponse)

      store.selectedOrderIds.add('order-1')
      await store.loadOrders(0, true)

      expect(store.selectedOrderIds.size).toBe(0)
    })
  })

  describe('loadOrderDetail', () => {
    it('loads order detail and sets selectedOrder', async () => {
      vi.mocked(ordersApi.getOrderDetail).mockResolvedValue(mockOrderDetail)

      await store.loadOrderDetail('order-1')

      expect(store.selectedOrder).toEqual(mockOrderDetail)
      expect(store.loading).toBe(false)
    })
  })

  describe('refundOrder', () => {
    it('refunds order and updates state', async () => {
      const refundedOrder = { ...mockOrderDetail, status: 'REFUNDED' as OrderStatus }
      vi.mocked(ordersApi.refundOrder).mockResolvedValue(refundedOrder)

      store.orders = [mockOrder]
      store.selectedOrder = mockOrderDetail

      await store.refundOrder('order-1', 5000, 'customer_request', 'Requested refund')

      expect(ordersApi.refundOrder).toHaveBeenCalledWith(
        'order-1',
        5000,
        'customer_request',
        'Requested refund'
      )
      expect(store.orders[0].status).toBe('REFUNDED')
      expect(store.selectedOrder?.status).toBe('REFUNDED')
    })

    it('handles refund errors', async () => {
      const errorMessage = 'Refund failed'
      vi.mocked(ordersApi.refundOrder).mockRejectedValue(new Error(errorMessage))

      await expect(store.refundOrder('order-1', 5000, 'test', '')).rejects.toThrow(errorMessage)
      expect(store.error).toBe(errorMessage)
    })
  })

  describe('capturePayment', () => {
    it('captures payment and updates order status', async () => {
      const capturedOrder = { ...mockOrderDetail, status: 'CONFIRMED' as OrderStatus }
      vi.mocked(ordersApi.capturePayment).mockResolvedValue(capturedOrder)

      store.orders = [{ ...mockOrder, status: 'PENDING' }]
      store.selectedOrder = { ...mockOrderDetail, status: 'PENDING' }

      await store.capturePayment('order-1', 'pi_123')

      expect(ordersApi.capturePayment).toHaveBeenCalledWith('order-1', 'pi_123')
      expect(store.orders[0].status).toBe('CONFIRMED')
      expect(store.selectedOrder?.status).toBe('CONFIRMED')
    })

    it('handles capture errors', async () => {
      const errorMessage = 'Capture failed'
      vi.mocked(ordersApi.capturePayment).mockRejectedValue(new Error(errorMessage))

      await expect(store.capturePayment('order-1', 'pi_123')).rejects.toThrow(errorMessage)
      expect(store.error).toBe(errorMessage)
    })
  })

  describe('addOrderNote', () => {
    it('adds note and updates order detail', async () => {
      const updatedOrder = {
        ...mockOrderDetail,
        timeline: [
          ...mockOrderDetail.timeline,
          {
            id: 'evt-2',
            type: 'note.added',
            description: 'Note added',
            timestamp: '2026-01-10T13:00:00Z',
            metadata: { note: 'Test note' },
          },
        ],
      }
      vi.mocked(ordersApi.addOrderNote).mockResolvedValue(updatedOrder)

      store.selectedOrder = mockOrderDetail

      await store.addOrderNote('order-1', 'Test note')

      expect(ordersApi.addOrderNote).toHaveBeenCalledWith('order-1', 'Test note')
      expect(store.selectedOrder?.timeline.length).toBe(2)
    })

    it('handles note errors', async () => {
      const errorMessage = 'Failed to add note'
      vi.mocked(ordersApi.addOrderNote).mockRejectedValue(new Error(errorMessage))

      await expect(store.addOrderNote('order-1', 'Test')).rejects.toThrow(errorMessage)
      expect(store.error).toBe(errorMessage)
    })
  })

  describe('updateOrderStatus', () => {
    it('updates order status optimistically', async () => {
      const updatedOrder = { ...mockOrderDetail, status: 'PROCESSING' as OrderStatus }
      vi.mocked(ordersApi.updateOrderStatus).mockResolvedValue(updatedOrder)

      store.orders = [mockOrder]
      store.selectedOrder = mockOrderDetail

      await store.updateOrderStatus('order-1', 'PROCESSING')

      expect(store.orders[0].status).toBe('PROCESSING')
      expect(store.selectedOrder?.status).toBe('PROCESSING')
    })
  })

  describe('cancelOrder', () => {
    it('cancels order and sets status to CANCELLED', async () => {
      const cancelledOrder = { ...mockOrderDetail, status: 'CANCELLED' as OrderStatus }
      vi.mocked(ordersApi.cancelOrder).mockResolvedValue(cancelledOrder)

      store.orders = [mockOrder]
      store.selectedOrder = mockOrderDetail

      await store.cancelOrder('order-1', 'Customer requested cancellation')

      expect(ordersApi.cancelOrder).toHaveBeenCalledWith(
        'order-1',
        'Customer requested cancellation'
      )
      expect(store.orders[0].status).toBe('CANCELLED')
    })
  })

  describe('bulkUpdateStatus', () => {
    it('updates multiple orders and clears selection', async () => {
      const mockResponse = { updated: 2, errors: [] }
      vi.mocked(ordersApi.bulkUpdateOrders).mockResolvedValue(mockResponse)
      vi.mocked(ordersApi.getOrders).mockResolvedValue({ items: [], total: 0 })

      store.selectedOrderIds.add('order-1')
      store.selectedOrderIds.add('order-2')

      await store.bulkUpdateStatus('SHIPPED')

      expect(ordersApi.bulkUpdateOrders).toHaveBeenCalledWith(
        expect.arrayContaining(['order-1', 'order-2']),
        'SHIPPED'
      )
      expect(store.selectedOrderIds.size).toBe(0)
    })

    it('throws error when no orders selected', async () => {
      await expect(store.bulkUpdateStatus('SHIPPED')).rejects.toThrow('No orders selected')
    })
  })

  describe('selection management', () => {
    it('toggles order selection', () => {
      store.toggleOrderSelection('order-1')
      expect(store.selectedOrderIds.has('order-1')).toBe(true)

      store.toggleOrderSelection('order-1')
      expect(store.selectedOrderIds.has('order-1')).toBe(false)
    })

    it('selects all orders', () => {
      store.orders = [mockOrder, { ...mockOrder, id: 'order-2' }]
      store.selectAllOrders()

      expect(store.selectedOrderIds.size).toBe(2)
      expect(store.hasSelection).toBe(true)
    })

    it('clears selection', () => {
      store.selectedOrderIds.add('order-1')
      store.clearSelection()

      expect(store.selectedOrderIds.size).toBe(0)
      expect(store.hasSelection).toBe(false)
    })
  })

  describe('filters', () => {
    it('updates filters and reloads orders', async () => {
      const mockResponse = { items: [], total: 0 }
      vi.mocked(ordersApi.getOrders).mockResolvedValue(mockResponse)

      await store.updateFilters({ status: ['PENDING'] })

      expect(store.filters.status).toEqual(['PENDING'])
      expect(ordersApi.getOrders).toHaveBeenCalled()
    })

    it('clears filters', async () => {
      const mockResponse = { items: [], total: 0 }
      vi.mocked(ordersApi.getOrders).mockResolvedValue(mockResponse)

      store.filters = { status: ['PENDING'], searchTerm: 'test' }
      await store.clearFilters()

      expect(store.filters).toEqual({})
    })
  })

  describe('SSE connection', () => {
    it('connects to SSE stream', () => {
      const mockEventSource = {
        close: vi.fn(),
      } as any

      vi.mocked(ordersApi.connectOrdersSSE).mockReturnValue(mockEventSource)

      store.connectSSE()

      expect(ordersApi.connectOrdersSSE).toHaveBeenCalled()
      expect(store.sseConnected).toBe(true)
    })

    it('disconnects SSE stream', () => {
      const mockEventSource = {
        close: vi.fn(),
      } as any

      vi.mocked(ordersApi.connectOrdersSSE).mockReturnValue(mockEventSource)

      store.connectSSE()
      store.disconnectSSE()

      expect(mockEventSource.close).toHaveBeenCalled()
      expect(store.sseConnected).toBe(false)
    })
  })
})
