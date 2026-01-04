/**
 * Orders Store
 *
 * Manages state for orders dashboard including list, filters, stats, and SSE updates.
 *
 * References:
 * - Task I5.T1: Orders module
 * - Architecture Section 4.1: State Management
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  OrderItem,
  OrderDetail,
  OrderFilters,
  OrdersStats,
  PaginationState,
  SSEOrderEvent,
  OrderStatus,
} from './types'
import * as ordersApi from './api'
import { emitTelemetryEvent } from '@/telemetry'

export const useOrdersStore = defineStore('orders', () => {
  // State
  const orders = ref<OrderItem[]>([])
  const selectedOrder = ref<OrderDetail | null>(null)
  const stats = ref<OrdersStats | null>(null)
  const filters = ref<OrderFilters>({})
  const pagination = ref<PaginationState>({
    page: 0,
    size: 20,
    total: 0,
    hasMore: false,
  })
  const loading = ref(false)
  const error = ref<string | null>(null)
  const sseConnected = ref(false)
  const sseEventSource = ref<EventSource | null>(null)
  const selectedOrderIds = ref<Set<string>>(new Set())

  // Computed
  const filteredOrdersCount = computed(() => orders.value.length)
  const hasSelection = computed(() => selectedOrderIds.value.size > 0)
  const selectionCount = computed(() => selectedOrderIds.value.size)

  const ordersByStatus = computed(() => {
    const grouped: Record<OrderStatus, OrderItem[]> = {
      PENDING: [],
      CONFIRMED: [],
      PROCESSING: [],
      SHIPPED: [],
      DELIVERED: [],
      CANCELLED: [],
      REFUNDED: [],
    }

    orders.value.forEach((order) => {
      grouped[order.status].push(order)
    })

    return grouped
  })

  // Actions
  async function loadOrders(page = 0, resetSelection = true): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const result = await ordersApi.getOrders(pagination.value.size, page, filters.value)

      if (page === 0) {
        orders.value = result.items
      } else {
        orders.value.push(...result.items)
      }

      pagination.value.page = page
      pagination.value.total = result.total
      pagination.value.hasMore = orders.value.length < result.total

      if (resetSelection) {
        selectedOrderIds.value.clear()
      }

      emitTelemetryEvent('view_orders', {
        count: result.items.length,
        total: result.total,
        filters: filters.value,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load orders'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadStats(): Promise<void> {
    try {
      stats.value = await ordersApi.getOrdersStats()
    } catch (err) {
      console.error('Failed to load stats:', err)
    }
  }

  async function loadOrderDetail(orderId: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      selectedOrder.value = await ordersApi.getOrderDetail(orderId)

      emitTelemetryEvent('view_order_detail', {
        orderId,
        orderNumber: selectedOrder.value.orderNumber,
        status: selectedOrder.value.status,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load order detail'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function updateFilters(newFilters: OrderFilters): Promise<void> {
    filters.value = { ...newFilters }
    await loadOrders(0)

    emitTelemetryEvent('action_filter_orders', {
      filters: filters.value,
    })
  }

  async function clearFilters(): Promise<void> {
    filters.value = {}
    await loadOrders(0)
  }

  async function updateOrderStatus(
    orderId: string,
    status: OrderStatus,
    notes?: string
  ): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const updated = await ordersApi.updateOrderStatus(orderId, status, notes)

      // Update in list
      const index = orders.value.findIndex((o) => o.id === orderId)
      if (index !== -1) {
        orders.value[index] = { ...orders.value[index], status: updated.status }
      }

      // Update selected order if it's the same
      if (selectedOrder.value?.id === orderId) {
        selectedOrder.value = updated
      }

      // Reload stats
      await loadStats()

      emitTelemetryEvent('action_update_order_status', {
        orderId,
        status,
        notes,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to update order status'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function cancelOrder(orderId: string, reason: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const updated = await ordersApi.cancelOrder(orderId, reason)

      // Update in list
      const index = orders.value.findIndex((o) => o.id === orderId)
      if (index !== -1) {
        orders.value[index] = { ...orders.value[index], status: 'CANCELLED' }
      }

      // Update selected order if it's the same
      if (selectedOrder.value?.id === orderId) {
        selectedOrder.value = updated
      }

      await loadStats()

      emitTelemetryEvent('action_cancel_order', {
        orderId,
        reason,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to cancel order'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function bulkUpdateStatus(status: OrderStatus): Promise<void> {
    if (selectedOrderIds.value.size === 0) {
      throw new Error('No orders selected')
    }

    loading.value = true
    error.value = null

    try {
      const orderIds = Array.from(selectedOrderIds.value)
      const result = await ordersApi.bulkUpdateOrders(orderIds, status)

      // Reload orders to reflect changes
      await loadOrders(0, false)
      await loadStats()

      emitTelemetryEvent('action_bulk_update_orders', {
        count: result.updated,
        status,
        errors: result.errors.length,
      })

      selectedOrderIds.value.clear()
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to bulk update orders'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function exportOrdersCSV(): Promise<void> {
    try {
      const blob = await ordersApi.exportOrders(filters.value)
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `orders-${new Date().toISOString()}.csv`
      link.click()
      window.URL.revokeObjectURL(url)

      emitTelemetryEvent('action_export_orders', {
        filters: filters.value,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to export orders'
      throw err
    }
  }

  function toggleOrderSelection(orderId: string): void {
    if (selectedOrderIds.value.has(orderId)) {
      selectedOrderIds.value.delete(orderId)
    } else {
      selectedOrderIds.value.add(orderId)
    }
  }

  function selectAllOrders(): void {
    orders.value.forEach((order) => selectedOrderIds.value.add(order.id))
  }

  function clearSelection(): void {
    selectedOrderIds.value.clear()
  }

  function connectSSE(): void {
    if (sseEventSource.value) {
      return // Already connected
    }

    try {
      sseEventSource.value = ordersApi.connectOrdersSSE(
        handleSSEEvent,
        handleSSEError
      )
      sseConnected.value = true

      emitTelemetryEvent('sse_orders_connected', {
        timestamp: new Date().toISOString(),
      })
    } catch (err) {
      console.error('Failed to connect SSE:', err)
      sseConnected.value = false
    }
  }

  function disconnectSSE(): void {
    if (sseEventSource.value) {
      sseEventSource.value.close()
      sseEventSource.value = null
      sseConnected.value = false

      emitTelemetryEvent('sse_orders_disconnected', {
        timestamp: new Date().toISOString(),
      })
    }
  }

  function handleSSEEvent(event: SSEOrderEvent): void {
    // Update order in list if present
    const index = orders.value.findIndex((o) => o.id === event.orderId)
    if (index !== -1) {
      orders.value[index] = { ...orders.value[index], status: event.status }
    }

    // Update selected order if it's the same
    if (selectedOrder.value?.id === event.orderId) {
      // Reload full detail
      loadOrderDetail(event.orderId).catch(console.error)
    }

    // Reload stats when order status changes
    loadStats().catch(console.error)

    emitTelemetryEvent('sse_order_event', {
      orderId: event.orderId,
      eventType: event.eventType,
      status: event.status,
    })
  }

  function handleSSEError(error: Error): void {
    console.error('SSE error:', error)
    sseConnected.value = false

    emitTelemetryEvent('sse_orders_error', {
      error: error.message,
      timestamp: new Date().toISOString(),
    })

    // Attempt reconnection after delay
    setTimeout(() => {
      if (!sseConnected.value) {
        connectSSE()
      }
    }, 5000)
  }

  function clearError(): void {
    error.value = null
  }

  function clearSelectedOrder(): void {
    selectedOrder.value = null
  }

  return {
    // State
    orders,
    selectedOrder,
    stats,
    filters,
    pagination,
    loading,
    error,
    sseConnected,
    selectedOrderIds,

    // Computed
    filteredOrdersCount,
    hasSelection,
    selectionCount,
    ordersByStatus,

    // Actions
    loadOrders,
    loadStats,
    loadOrderDetail,
    updateFilters,
    clearFilters,
    updateOrderStatus,
    cancelOrder,
    bulkUpdateStatus,
    exportOrdersCSV,
    toggleOrderSelection,
    selectAllOrders,
    clearSelection,
    connectSSE,
    disconnectSSE,
    clearError,
    clearSelectedOrder,
  }
})
