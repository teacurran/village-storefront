/**
 * Inventory Module Types
 *
 * Extends backend DTOs with UI-specific helpers and derived fields.
 *
 * References:
 * - InventoryAdminResource (locations/transfers/adjustments)
 * - ReportsResource inventory aging aggregates
 */

export interface InventoryLocation {
  id: string
  name: string
  code?: string
  address?: string
  type?: string
  active: boolean
  isDefault?: boolean
}

export interface InventoryRecord {
  id: string
  variantId: string
  sku: string
  productName: string
  locationId: string
  locationName: string
  quantity: number
  reserved: number
  available: number
  daysInStock: number
  firstReceivedAt?: string
  dataFreshnessTimestamp?: string
}

export interface InventoryFilters {
  locationId?: string
  search?: string
  maxDaysInStock?: number
  lowStockOnly?: boolean
}

export interface InventoryAdjustmentPayload {
  variantId: string
  locationId: string
  quantityChange: number
  reason: string
  notes?: string
}

export interface InventoryTransferSummary {
  transferId: string
  sourceLocationId: string
  destinationLocationId: string
  status: string
  initiatedBy?: string
  expectedArrivalDate?: string
  carrier?: string
  trackingNumber?: string
  createdAt: string
  lines: Array<{
    variantId: string
    quantity: number
    receivedQuantity?: number
  }>
}

export interface InventorySseEvent {
  eventType: 'inventory.updated' | 'transfer.completed' | 'transfer.created'
  variantId: string
  locationId: string
  quantity: number
  reserved: number
  timestamp: string
}
