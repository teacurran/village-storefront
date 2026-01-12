/**
 * Pinia store for POS offline queue state management.
 *
 * Manages offline transaction queue, sync state, and provides actions for
 * enqueueing transactions and uploading batches when online.
 *
 * References:
 * - Architecture: §4.1 State Management (Pinia stores)
 * - Task I4.T7: Offline queue state management
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import { useTenantStore } from '@/stores/tenant'
import { useAuthStore } from '@/stores/auth'
import type { PaymentEntry } from '@/stores/pos'
import {
  addToQueue,
  getQueuedEntries,
  getQueueStats,
  markAsSyncing,
  markAsSynced,
  markAsFailed,
  deleteSyncedEntries,
  getDeviceKeys,
  storeDeviceKeys,
  cleanupExpiredEntries,
  cleanupExpiredCatalogCache,
  getEntriesEligibleForRetry,
  calculateNextRetryTime,
  bulkCacheProducts,
  searchCachedProducts,
  type QueueEntry,
  type DeviceKeys,
  type CachedProduct,
  DEFAULT_TTL_MS,
  updateQueueEntry,
} from './offlineDB'
import { encryptData, importKeyFromBase64 } from './encryption'

interface OfflineTransaction {
  localTransactionId: string
  totalAmount: number
  currency: string
  customerId?: string
  customer?: {
    id: string
    name: string
    email?: string
  }
  paymentMethodId?: string
  payments: PaymentEntry[]
  amountTendered: number
  amountDue: number
  changeDue: number
  taxAmount?: number
  discountAmount?: number
  items: Array<{
    productId: string
    variantId: string
    quantity: number
    price: number
  }>
}

const FALLBACK_CATALOG: Omit<CachedProduct, 'cachedAt' | 'expiresAt'>[] = [
  {
    variantId: 'demo-widget-pro',
    productId: 'demo-1',
    productName: 'Widget Pro',
    sku: 'WIDG-PRO',
    barcode: '123456789012',
    price: 29.99,
    inventoryQuantity: 25,
    categoryId: 'widgets',
  },
  {
    variantId: 'demo-gadget-plus',
    productId: 'demo-2',
    productName: 'Gadget Plus',
    sku: 'GADT-PLUS',
    barcode: '987654321098',
    price: 49.99,
    inventoryQuantity: 18,
    categoryId: 'gadgets',
  },
  {
    variantId: 'demo-device-elite',
    productId: 'demo-3',
    productName: 'Device Elite',
    sku: 'DEVC-ELTE',
    barcode: '135791357913',
    price: 99.99,
    inventoryQuantity: 6,
    categoryId: 'devices',
  },
]
let fallbackCatalogSeeded = false

export const useOfflineStore = defineStore('pos-offline', () => {
  const tenantStore = useTenantStore()
  const authStore = useAuthStore()

  // State
  const isOnline = ref(navigator.onLine)
  const isSyncing = ref(false)
  const isSyncOnHold = ref(false)
  const queueStats = ref({
    queued: 0,
    syncing: 0,
    synced: 0,
    failed: 0,
    total: 0,
  })
  const currentDeviceId = ref<number | null>(null)
  const encryptionKeyVersion = ref(1)
  const lastSyncAt = ref<Date | null>(null)
  const syncError = ref<string | null>(null)
  let serviceWorkerRegistered = false
  let serviceWorkerRegistration: ServiceWorkerRegistration | null = null
  let processingEntries: QueueEntry[] = []
  let cleanupIntervalId: number | null = null
  let retryIntervalId: number | null = null

  // Computed
  const hasQueuedTransactions = computed(() => queueStats.value.queued > 0)
  const hasSyncErrors = computed(() => queueStats.value.failed > 0)
  const isOfflineMode = computed(() => !isOnline.value)
  const canSync = computed(
    () => isOnline.value && !isSyncing.value && hasQueuedTransactions.value && !isSyncOnHold.value
  )

  // Actions

  /**
   * Initialize offline store (load queue stats, setup listeners).
   */
  async function initialize(deviceId: number) {
    currentDeviceId.value = deviceId
    await refreshQueueStats()

    // Listen for online/offline events
    if (typeof window !== 'undefined') {
      window.addEventListener('online', handleOnline)
      window.addEventListener('offline', handleOffline)
    }

    await ensureServiceWorker()

    // Start periodic cleanup jobs
    startPeriodicCleanup()
    startRetryMonitor()

    // Attempt sync if online
    if (isOnline.value && hasQueuedTransactions.value && !isSyncOnHold.value) {
      await syncQueue()
    }
  }

  /**
   * Add transaction to offline queue.
   */
  async function enqueueTransaction(transaction: OfflineTransaction, staffUserId?: string) {
    if (!currentDeviceId.value) {
      throw new Error('Device not initialized')
    }

    const deviceKeys = await getDeviceKeys(currentDeviceId.value)
    if (!deviceKeys) {
      throw new Error('Device encryption keys not found')
    }

    // Import encryption key
    const cryptoKey = await importKeyFromBase64(deviceKeys.encryptionKey)

    // Encrypt transaction payload
    const encrypted = await encryptData(transaction, cryptoKey, deviceKeys.keyVersion)

    // Generate idempotency key
    const tenantId = tenantStore.tenantId?.value ?? 'unknown-tenant'
    const idempotencyKey = `${tenantId}:${currentDeviceId.value}:${transaction.localTransactionId}`

    // Create queue entry with TTL and retry metadata
    const now = new Date()
    const queueEntry: QueueEntry = {
      id: uuidv4(),
      localTransactionId: transaction.localTransactionId,
      encryptedPayload: encrypted.encryptedData,
      encryptionIv: encrypted.iv,
      encryptionKeyVersion: encrypted.keyVersion,
      transactionTimestamp: now.toISOString(),
      transactionAmount: transaction.totalAmount,
      idempotencyKey,
      staffUserId: staffUserId ?? authStore.user?.id ?? undefined,
      syncStatus: 'queued',
      createdAt: now.toISOString(),
      ttlMs: DEFAULT_TTL_MS,
      expiresAt: new Date(now.getTime() + DEFAULT_TTL_MS).toISOString(),
      retryCount: 0,
    }

    await addToQueue(queueEntry)
    await refreshQueueStats()

    console.log(`[OfflineQueue] Enqueued transaction: ${transaction.localTransactionId}`)

    // Auto-sync if online
    if (isOnline.value && !isSyncOnHold.value) {
      setTimeout(() => syncQueue(), 500) // Debounce
    }

    await requestBackgroundSync()
  }

  /**
   * Sync offline queue to server.
   */
  async function syncQueue() {
    if (isSyncing.value || !currentDeviceId.value || isSyncOnHold.value) {
      return
    }

    isSyncing.value = true
    syncError.value = null

    try {
      const queued = await getQueuedEntries()
      if (queued.length === 0) {
        console.log('[OfflineQueue] No transactions to sync')
        return
      }

      console.log(`[OfflineQueue] Syncing ${queued.length} transactions...`)
      processingEntries = [...queued]

      // Mark all as syncing
      for (const entry of processingEntries) {
        await markAsSyncing(entry.id)
      }

      // Upload batch to server
      const response = await fetch('/api/pos/offline/upload', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceId: currentDeviceId.value,
          firmwareVersion: '1.0.0', // TODO: Get from device config
          transactions: processingEntries.map((e) => ({
            localTransactionId: e.localTransactionId,
            encryptedPayload: e.encryptedPayload,
            encryptionIv: e.encryptionIv,
            encryptionKeyVersion: e.encryptionKeyVersion,
            transactionTimestamp: e.transactionTimestamp,
            transactionAmount: e.transactionAmount,
            idempotencyKey: e.idempotencyKey,
            priority: e.syncStatus === 'failed' ? 'CRITICAL' : 'HIGH',
          })),
        }),
      })

      if (!response.ok) {
        throw new Error(`Sync failed: ${response.statusText}`)
      }

      const result = await response.json()
      console.log(
        `[OfflineQueue] Sync complete: ${result.enqueued} enqueued, ${result.duplicates} duplicates`
      )

      // Mark all as synced
      for (const entry of processingEntries) {
        await markAsSynced(entry.id)
      }

      lastSyncAt.value = new Date()

      // Cleanup synced entries after 5 minutes
      setTimeout(() => deleteSyncedEntries(), 5 * 60 * 1000)
      await refreshQueueStats()
      await requestBackgroundSync()
    } catch (error) {
      console.error('[OfflineQueue] Sync error:', error)
      syncError.value = error instanceof Error ? error.message : 'Unknown error'

      // Mark all as failed with retry backoff
      for (const entry of processingEntries) {
        const newRetryCount = entry.retryCount + 1
        const nextRetryAt = calculateNextRetryTime(newRetryCount)

        await updateQueueEntry(entry.id, {
          syncStatus: 'failed',
          syncError: syncError.value,
          retryCount: newRetryCount,
          nextRetryAt: nextRetryAt.toISOString(),
          lastAttemptAt: new Date().toISOString(),
        })
      }
      await refreshQueueStats()
    } finally {
      isSyncing.value = false
      processingEntries = []
    }
  }

  /**
   * Refresh queue statistics.
   */
  async function refreshQueueStats() {
    queueStats.value = await getQueueStats()
  }

  /**
   * Store device pairing keys.
   */
  async function storePairingKeys(deviceId: number, encryptionKey: string, keyVersion: number) {
    const keys: DeviceKeys = {
      deviceId,
      encryptionKey,
      keyVersion,
      pairedAt: new Date().toISOString(),
    }
    await storeDeviceKeys(keys)
    currentDeviceId.value = deviceId
    encryptionKeyVersion.value = keyVersion
  }

  /**
   * Pause automatic syncing (staff initiated hold).
   */
  function holdSync() {
    isSyncOnHold.value = true
  }

  /**
   * Resume syncing after hold.
   */
  function resumeSync() {
    if (!isSyncOnHold.value) return
    isSyncOnHold.value = false
    if (isOnline.value && hasQueuedTransactions.value) {
      syncQueue()
    }
  }

  /**
   * Handle online event.
   */
  function handleOnline() {
    console.log('[OfflineQueue] Network online - attempting sync')
    isOnline.value = true
    if (!isSyncOnHold.value) {
      syncQueue()
    }
  }

  /**
   * Handle offline event.
   */
  function handleOffline() {
    console.warn('[OfflineQueue] Network offline - transactions will be queued')
    isOnline.value = false
  }

  /**
   * Ensure service worker is registered so background sync can run.
   */
  async function ensureServiceWorker() {
    if (serviceWorkerRegistered) return
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
      return
    }

    try {
      serviceWorkerRegistration = await navigator.serviceWorker.register('/pos-sw.js')
      navigator.serviceWorker.addEventListener('message', handleServiceWorkerMessage)
      serviceWorkerRegistered = true
      console.log('[OfflineQueue] POS service worker registered')
    } catch (error) {
      console.warn('[OfflineQueue] Failed to register service worker', error)
    }
  }

  async function requestBackgroundSync() {
    if (!serviceWorkerRegistration || !('sync' in serviceWorkerRegistration)) {
      return
    }
    try {
      await serviceWorkerRegistration.sync.register('pos-offline-sync')
    } catch (error) {
      console.warn('[OfflineQueue] Background sync registration failed', error)
    }
  }

  function handleServiceWorkerMessage(event: MessageEvent) {
    const data = event.data
    if (!data || typeof data.type !== 'string') {
      return
    }

    if (data.type === 'TRIGGER_SYNC' && !isSyncOnHold.value) {
      syncQueue()
    }
  }

  /**
   * Start periodic cleanup of expired entries and cache.
   */
  function startPeriodicCleanup() {
    if (cleanupIntervalId) return

    // Run cleanup every 10 minutes
    cleanupIntervalId = window.setInterval(
      async () => {
        const expiredEntries = await cleanupExpiredEntries()
        const expiredCache = await cleanupExpiredCatalogCache()
        if (expiredEntries > 0 || expiredCache > 0) {
          console.log(
            `[OfflineQueue] Cleanup: ${expiredEntries} expired entries, ${expiredCache} expired cache items`
          )
          await refreshQueueStats()
        }
      },
      10 * 60 * 1000
    ) // 10 minutes
  }

  /**
   * Start periodic monitoring for retry-eligible failed entries.
   */
  function startRetryMonitor() {
    if (retryIntervalId) return

    // Check for retry-eligible entries every 30 seconds
    retryIntervalId = window.setInterval(async () => {
      if (isSyncing.value || !isOnline.value || isSyncOnHold.value) {
        return
      }

      const eligibleEntries = await getEntriesEligibleForRetry()
      if (eligibleEntries.length > 0) {
        console.log(`[OfflineQueue] ${eligibleEntries.length} entries eligible for retry`)

        // Reset status to queued so syncQueue will pick them up
        for (const entry of eligibleEntries) {
          await updateQueueEntry(entry.id, { syncStatus: 'queued' })
        }
        await refreshQueueStats()
        await syncQueue()
      }
    }, 30 * 1000) // 30 seconds
  }

  /**
   * Prime catalog cache by fetching frequently used products.
   */
  async function primeCatalogCache() {
    if (!isOnline.value) {
      console.warn('[OfflineQueue] Cannot reach catalog API while offline - using fallback data')
      await ensureFallbackCatalogCached()
      return
    }

    try {
      // Fetch active products from API
      const response = await fetch('/api/catalog/products?status=active&limit=200', {
        credentials: 'include',
      })

      if (!response.ok) {
        throw new Error(`Failed to fetch catalog: ${response.statusText}`)
      }

      const data = await response.json()
      const products: Omit<CachedProduct, 'cachedAt' | 'expiresAt'>[] = data.items.map(
        (item: any) => ({
          variantId: item.variantId,
          productId: item.productId,
          productName: item.name,
          sku: item.sku,
          barcode: item.barcode,
          price: item.price,
          inventoryQuantity: item.inventoryQuantity || 0,
          categoryId: item.categoryId,
        })
      )

      if (products.length > 0) {
        await bulkCacheProducts(products)
        console.log(`[OfflineQueue] Cached ${products.length} products from API`)
      }
    } catch (error) {
      console.error('[OfflineQueue] Failed to prime catalog cache:', error)
      await ensureFallbackCatalogCached()
    }
  }

  /**
   * Search products (online or cached).
   */
  async function searchProducts(query: string): Promise<CachedProduct[]> {
    const trimmedQuery = query.trim()
    if (trimmedQuery.length < 2) {
      return []
    }

    // Try cached search first (works offline)
    const cached = await searchCachedProducts(trimmedQuery)
    if (cached.length > 0) {
      return cached
    }

    // If online and no cached results, fetch from API
    if (isOnline.value) {
      try {
        const response = await fetch(
          `/api/catalog/products/search?q=${encodeURIComponent(trimmedQuery)}&limit=20`,
          {
            credentials: 'include',
          }
        )

        if (!response.ok) {
          return []
        }

        const data = await response.json()
        const products: Omit<CachedProduct, 'cachedAt' | 'expiresAt'>[] = data.items.map(
          (item: any) => ({
            variantId: item.variantId,
            productId: item.productId,
            productName: item.name,
            sku: item.sku,
            barcode: item.barcode,
            price: item.price,
            inventoryQuantity: item.inventoryQuantity || 0,
            categoryId: item.categoryId,
          })
        )

        if (products.length > 0) {
          await bulkCacheProducts(products)
          return searchCachedProducts(trimmedQuery)
        }
      } catch (error) {
        console.error('[OfflineQueue] Product search failed:', error)
      }
    }

    // Fall back to demo catalog to keep POS usable when offline
    await ensureFallbackCatalogCached()
    return searchCachedProducts(trimmedQuery)
  }

  /**
   * Cleanup listeners on unmount.
   */
  function dispose() {
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }

    if (serviceWorkerRegistered && navigator.serviceWorker) {
      navigator.serviceWorker.removeEventListener('message', handleServiceWorkerMessage)
    }

    if (cleanupIntervalId) {
      clearInterval(cleanupIntervalId)
      cleanupIntervalId = null
    }

    if (retryIntervalId) {
      clearInterval(retryIntervalId)
      retryIntervalId = null
    }
  }

  /**
   * Reset device context (used when unpairing locally).
   */
  function clearDeviceContext() {
    currentDeviceId.value = null
    encryptionKeyVersion.value = 1
  }

  async function ensureFallbackCatalogCached() {
    if (fallbackCatalogSeeded) {
      return
    }

    await bulkCacheProducts(FALLBACK_CATALOG)
    fallbackCatalogSeeded = true
  }

  return {
    // State
    isOnline,
    isSyncing,
    isSyncOnHold,
    queueStats,
    currentDeviceId,
    encryptionKeyVersion,
    lastSyncAt,
    syncError,

    // Computed
    hasQueuedTransactions,
    hasSyncErrors,
    isOfflineMode,
    canSync,

    // Actions
    initialize,
    enqueueTransaction,
    syncQueue,
    refreshQueueStats,
    storePairingKeys,
    holdSync,
    resumeSync,
    clearDeviceContext,
    dispose,
    primeCatalogCache,
    searchProducts,
  }
})
