/**
 * Orders Module Types
 *
 * Type definitions for orders dashboard, extending generated OpenAPI types
 * with UI-specific state and filter models.
 *
 * References:
 * - Task I5.T1: Admin SPA completion
 * - OpenAPI spec: CartDto, OrderStatus
 */

import type { Money } from '@/api/types'
export type { Money }

export interface OrderItem {
  id: string
  orderNumber: string
  customerName: string
  customerEmail: string
  status: OrderStatus
  total: Money
  itemCount: number
  createdAt: string
  updatedAt: string
  shippedAt?: string
  deliveredAt?: string
  paymentMethod: string
  shippingAddress?: Address
}

export type OrderStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'REFUNDED'

export interface Address {
  line1: string
  line2?: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface OrderDetail extends OrderItem {
  lineItems: LineItem[]
  subtotal: Money
  taxAmount: Money
  shippingAmount: Money
  discountAmount: Money
  trackingNumber?: string
  notes?: string
  timeline: OrderEvent[]
}

export interface LineItem {
  id: string
  productId: string
  variantId?: string
  sku: string
  name: string
  quantity: number
  unitPrice: Money
  total: Money
  imageUrl?: string
}

export interface OrderEvent {
  id: string
  type: string
  description: string
  actor?: string
  timestamp: string
  metadata?: Record<string, any>
}

export interface OrderFilters {
  status?: OrderStatus[]
  dateFrom?: string
  dateTo?: string
  searchTerm?: string
  minAmount?: number
  maxAmount?: number
  paymentMethod?: string
}

export interface OrdersStats {
  totalOrders: number
  pendingOrders: number
  processingOrders: number
  shippedOrders: number
  revenue: Money
  avgOrderValue: Money
}

export interface PaginationState {
  page: number
  size: number
  total: number
  hasMore: boolean
}

export interface SSEOrderEvent {
  orderId: string
  orderNumber: string
  eventType: 'order.created' | 'order.updated' | 'order.shipped' | 'order.cancelled'
  status: OrderStatus
  timestamp: string
}
