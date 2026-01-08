/**
 * Inventory Store
 *
 * Responsible for fetching locations, inventory aging data, transfers,
 * and handling SSE updates for low stock alerts.
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { emitTelemetryEvent } from '@/telemetry'
import * as inventoryApi from './api'
import type {
  InventoryFilters,
  InventoryLocation,
  InventoryRecord,
  InventorySseEvent,
  InventoryTransferSummary,
} from './types'

export const useInventoryStore = defineStore('inventory', () => {
  const locations = ref<InventoryLocation[]>([])
  const inventory = ref<InventoryRecord[]>([])
  const transfers = ref<InventoryTransferSummary[]>([])
  const filters = ref<InventoryFilters>({
    lowStockOnly: false,
  })
  const selectedRecord = ref<InventoryRecord | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const sseConnected = ref(false)
  const sseSource = ref<EventSource | null>(null)

  const filteredInventory = computed(() => {
    let records = [...inventory.value]

    if (filters.value.locationId) {
      records = records.filter((record) => record.locationId === filters.value.locationId)
    }

    if (filters.value.search) {
      const query = filters.value.search.toLowerCase()
      records = records.filter(
        (record) =>
          record.productName.toLowerCase().includes(query) ||
          record.sku.toLowerCase().includes(query) ||
          record.locationName.toLowerCase().includes(query)
      )
    }

    if (filters.value.lowStockOnly) {
      records = records.filter((record) => record.available <= 5)
    }

    if (filters.value.maxDaysInStock) {
      records = records.filter((record) => record.daysInStock >= filters.value.maxDaysInStock!)
    }

    return records
  })

  const lowStockCount = computed(() => inventory.value.filter((record) => record.available <= 5).length)

  async function loadDashboard(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const [fetchedLocations, matrix, transferList] = await Promise.all([
        inventoryApi.getLocations(),
        inventoryApi.getInventoryMatrix(filters.value),
        inventoryApi.getTransfers(),
      ])

      locations.value = fetchedLocations
      inventory.value = matrix
      transfers.value = transferList

      emitTelemetryEvent('view_inventory', {
        recordCount: matrix.length,
        transferCount: transferList.length,
        lowStock: lowStockCount.value,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load inventory data'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function applyFilters(newFilters: InventoryFilters): Promise<void> {
    filters.value = { ...filters.value, ...newFilters }
    await loadInventory()

    emitTelemetryEvent('action_filter_inventory', {
      filters: filters.value,
    })
  }

  async function loadInventory(): Promise<void> {
    try {
      inventory.value = await inventoryApi.getInventoryMatrix(filters.value)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load inventory'
      throw err
    }
  }

  async function recordAdjustment(payload: { quantityChange: number; reason: string; notes?: string }) {
    if (!selectedRecord.value) return
    await inventoryApi.createAdjustment({
      variantId: selectedRecord.value.variantId,
      locationId: selectedRecord.value.locationId,
      quantityChange: payload.quantityChange,
      reason: payload.reason,
      notes: payload.notes,
    })

    await loadInventory()

    emitTelemetryEvent('action_inventory_adjustment', {
      variantId: selectedRecord.value.variantId,
      locationId: selectedRecord.value.locationId,
      delta: payload.quantityChange,
    })
  }

  async function refreshTransfers() {
    transfers.value = await inventoryApi.getTransfers()
  }

  function selectRecord(record: InventoryRecord | null) {
    selectedRecord.value = record
  }

  function connectSSE() {
    if (sseSource.value) return

    try {
      sseSource.value = inventoryApi.connectInventorySSE(handleSseEvent, handleSseError)
      sseConnected.value = true
      emitTelemetryEvent('sse_inventory_connected', { timestamp: new Date().toISOString() })
    } catch (err) {
      console.error('Failed to connect inventory SSE', err)
      sseConnected.value = false
    }
  }

  function disconnectSSE() {
    if (!sseSource.value) return
    sseSource.value.close()
    sseSource.value = null
    sseConnected.value = false
    emitTelemetryEvent('sse_inventory_disconnected', { timestamp: new Date().toISOString() })
  }

  function handleSseEvent(event: InventorySseEvent) {
    const record = inventory.value.find(
      (entry) => entry.variantId === event.variantId && entry.locationId === event.locationId
    )

    if (record) {
      record.quantity = event.quantity
      record.available = event.quantity - event.reserved
    } else {
      inventory.value.unshift({
        id: `${event.variantId}-${event.locationId}`,
        variantId: event.variantId,
        sku: event.variantId,
        productName: 'Unknown',
        locationId: event.locationId,
        locationName: 'Unknown',
        quantity: event.quantity,
        reserved: event.reserved,
        available: event.quantity - event.reserved,
        daysInStock: 0,
      })
    }

    emitTelemetryEvent('sse_inventory_event', {
      type: event.eventType,
      variantId: event.variantId,
      locationId: event.locationId,
    })
  }

  function handleSseError(error: Error) {
    console.error('Inventory SSE error', error)
    sseConnected.value = false
    setTimeout(() => {
      if (!sseConnected.value) {
        connectSSE()
      }
    }, 5000)
  }

  return {
    locations,
    inventory,
    transfers,
    filters,
    selectedRecord,
    loading,
    error,
    sseConnected,
    filteredInventory,
    lowStockCount,
    loadDashboard,
    loadInventory,
    applyFilters,
    recordAdjustment,
    refreshTransfers,
    selectRecord,
    connectSSE,
    disconnectSSE,
  }
})
