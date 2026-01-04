/**
 * Orders API Client
 *
 * Wraps order management REST endpoints with typed interfaces.
 * All methods use the shared apiClient with automatic tenant context and auth.
 *
 * References:
 * - OrdersResource.java endpoints
 * - Task I5.T1: Orders module implementation
 */

import { apiClient } from '@/api/client'
import type {
  OrderItem,
  OrderDetail,
  OrderFilters,
  OrdersStats,
  OrderStatus,
  SSEOrderEvent,
} from './types'

/**
 * List orders with pagination and filters
 */
export async function getOrders(
  page = 0,
  size = 20,
  filters?: OrderFilters
): Promise<{ items: OrderItem[]; total: number }> {
  const params: Record<string, any> = { page, size }

  if (filters) {
    if (filters.status?.length) params.status = filters.status.join(',')
    if (filters.dateFrom) params.dateFrom = filters.dateFrom
    if (filters.dateTo) params.dateTo = filters.dateTo
    if (filters.searchTerm) params.q = filters.searchTerm
    if (filters.minAmount) params.minAmount = filters.minAmount
    if (filters.maxAmount) params.maxAmount = filters.maxAmount
    if (filters.paymentMethod) params.paymentMethod = filters.paymentMethod
  }

  return apiClient.get<{ items: OrderItem[]; total: number }>('/admin/orders', { params })
}

/**
 * Get single order detail
 */
export async function getOrderDetail(orderId: string): Promise<OrderDetail> {
  return apiClient.get<OrderDetail>(`/admin/orders/${orderId}`)
}

/**
 * Get orders dashboard statistics
 */
export async function getOrdersStats(): Promise<OrdersStats> {
  return apiClient.get<OrdersStats>('/admin/orders/stats')
}

/**
 * Update order status
 */
export async function updateOrderStatus(
  orderId: string,
  status: OrderStatus,
  notes?: string
): Promise<OrderDetail> {
  return apiClient.patch<OrderDetail>(`/admin/orders/${orderId}/status`, { status, notes })
}

/**
 * Cancel order
 */
export async function cancelOrder(orderId: string, reason: string): Promise<OrderDetail> {
  return apiClient.post<OrderDetail>(`/admin/orders/${orderId}/cancel`, { reason })
}

/**
 * Refund order
 */
export async function refundOrder(
  orderId: string,
  amount: number,
  reason: string
): Promise<OrderDetail> {
  return apiClient.post<OrderDetail>(`/admin/orders/${orderId}/refund`, { amount, reason })
}

/**
 * Bulk update order statuses
 */
export async function bulkUpdateOrders(
  orderIds: string[],
  status: OrderStatus
): Promise<{ updated: number; errors: any[] }> {
  return apiClient.post<{ updated: number; errors: any[] }>('/admin/orders/bulk-update', {
    orderIds,
    status,
  })
}

/**
 * Export orders to CSV
 */
export async function exportOrders(filters?: OrderFilters): Promise<Blob> {
  const params: Record<string, any> = {}

  if (filters) {
    if (filters.status?.length) params.status = filters.status.join(',')
    if (filters.dateFrom) params.dateFrom = filters.dateFrom
    if (filters.dateTo) params.dateTo = filters.dateTo
    if (filters.searchTerm) params.q = filters.searchTerm
  }

  const response = await apiClient.getClient().get('/admin/orders/export', {
    params,
    responseType: 'blob',
  })

  return response.data
}

/**
 * Connect to SSE stream for real-time order updates
 */
export function connectOrdersSSE(
  onEvent: (event: SSEOrderEvent) => void,
  onError?: (error: Error) => void
): EventSource {
  const eventSource = new EventSource('/api/v1/admin/orders/events')

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data) as SSEOrderEvent
      onEvent(data)
    } catch (error) {
      console.error('Failed to parse SSE event:', error)
      onError?.(error as Error)
    }
  }

  eventSource.onerror = (event) => {
    console.error('SSE connection error:', event)
    onError?.(new Error('SSE connection failed'))
  }

  return eventSource
}
