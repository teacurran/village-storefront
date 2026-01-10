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
  type QueueEntry,
  type DeviceKeys,
} from './offlineDB'
import { encryptData, importKeyFromBase64 } from './encryption'

interface OfflineTransaction {
  localTransactionId: string
  totalAmount: number
  currency: string
  customerId?: string
  paymentMethodId: string
  items: Array<{
    productId: string
    variantId: string
    quantity: number
    price: number
  }>
}

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

    // Create queue entry
    const queueEntry: QueueEntry = {
      id: uuidv4(),
      localTransactionId: transaction.localTransactionId,
      encryptedPayload: encrypted.encryptedData,
      encryptionIv: encrypted.iv,
      encryptionKeyVersion: encrypted.keyVersion,
      transactionTimestamp: new Date().toISOString(),
      transactionAmount: transaction.totalAmount,
      idempotencyKey,
      staffUserId: staffUserId ?? authStore.user?.id ?? undefined,
      syncStatus: 'queued',
      createdAt: new Date().toISOString(),
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
      console.log(`[OfflineQueue] Sync complete: ${result.enqueued} enqueued, ${result.duplicates} duplicates`)

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

      // Mark all as failed
      for (const entry of processingEntries) {
        await markAsFailed(entry.id, syncError.value)
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
  }

  /**
   * Reset device context (used when unpairing locally).
   */
  function clearDeviceContext() {
    currentDeviceId.value = null
    encryptionKeyVersion.value = 1
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
  }
})
