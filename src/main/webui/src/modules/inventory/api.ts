import { apiClient } from '@/api/client'
import type {
  InventoryAdjustmentPayload,
  InventoryFilters,
  InventoryLocation,
  InventoryRecord,
  InventorySseEvent,
  InventoryTransferSummary,
} from './types'

interface InventoryAgingAggregateResponse {
  id: string
  variant: {
    id: string
    sku: string
    name: string
  }
  location: {
    id: string
    name: string
    code?: string
  }
  quantity: number
  daysInStock: number
  dataFreshnessTimestamp?: string
  firstReceivedAt?: string
}

interface InventoryLocationResponse {
  id: string
  name: string
  code?: string
  address?: string
  type?: string
  active: boolean
  defaultLocation?: boolean
}

interface InventoryTransferResponse {
  transferId: string
  sourceLocationId: string
  destinationLocationId: string
  status: string
  initiatedBy?: string
  expectedArrivalDate?: string
  carrier?: string
  trackingNumber?: string
  createdAt: string
  updatedAt?: string
  lines: Array<{
    variantId: string
    quantity: number
    receivedQuantity?: number
  }>
}

export async function getLocations(): Promise<InventoryLocation[]> {
  const raw = await apiClient.get<InventoryLocationResponse[]>('/admin/inventory/locations')
  return raw.map((loc) => ({
    id: loc.id,
    name: loc.name,
    code: loc.code,
    address: loc.address,
    type: loc.type,
    active: loc.active,
    isDefault: loc.defaultLocation,
  }))
}

export async function getInventoryMatrix(filters: InventoryFilters): Promise<InventoryRecord[]> {
  const params: Record<string, string | number | boolean> = {}
  if (filters.locationId) params.locationId = filters.locationId
  if (filters.maxDaysInStock) params.minDays = filters.maxDaysInStock
  const aggregates = await apiClient.get<InventoryAgingAggregateResponse[]>(
    '/admin/reports/aggregates/inventory-aging',
    { params }
  )

  return aggregates
    .filter((agg) => {
      if (filters.lowStockOnly) {
        return agg.quantity <= 5
      }
      return true
    })
    .filter((agg) => {
      if (!filters.search) return true
      const query = filters.search.toLowerCase()
      return (
        agg.variant.name.toLowerCase().includes(query) || agg.variant.sku.toLowerCase().includes(query)
      )
    })
    .map((agg) => ({
      id: agg.id,
      variantId: agg.variant.id,
      sku: agg.variant.sku,
      productName: agg.variant.name,
      locationId: agg.location.id,
      locationName: agg.location.name,
      quantity: agg.quantity,
      reserved: 0,
      available: agg.quantity,
      daysInStock: agg.daysInStock,
      dataFreshnessTimestamp: agg.dataFreshnessTimestamp,
      firstReceivedAt: agg.firstReceivedAt,
    }))
}

export async function getTransfers(): Promise<InventoryTransferSummary[]> {
  const transfers = await apiClient.get<InventoryTransferResponse[]>('/admin/inventory/transfers')
  return transfers.map((transfer) => ({
    transferId: transfer.transferId,
    sourceLocationId: transfer.sourceLocationId,
    destinationLocationId: transfer.destinationLocationId,
    status: transfer.status,
    initiatedBy: transfer.initiatedBy,
    expectedArrivalDate: transfer.expectedArrivalDate,
    carrier: transfer.carrier,
    trackingNumber: transfer.trackingNumber,
    createdAt: transfer.createdAt,
    lines: transfer.lines || [],
  }))
}

export async function receiveTransfer(transferId: string) {
  return apiClient.post(`/admin/inventory/transfers/${transferId}/receive`)
}

export async function createAdjustment(payload: InventoryAdjustmentPayload) {
  return apiClient.post('/admin/inventory/adjustments', payload)
}

export function connectInventorySSE(
  onEvent: (event: InventorySseEvent) => void,
  onError?: (error: Error) => void
): EventSource {
  const source = new EventSource('/api/v1/admin/inventory/events')

  source.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data) as InventorySseEvent
      onEvent(data)
    } catch (err) {
      console.error('Failed to parse inventory SSE event', err)
      onError?.(err as Error)
    }
  }

  source.onerror = () => {
    onError?.(new Error('Inventory SSE disconnected'))
  }

  return source
}
