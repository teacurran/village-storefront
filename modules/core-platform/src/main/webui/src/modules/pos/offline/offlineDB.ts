/**
 * IndexedDB wrapper for POS offline queue persistence.
 *
 * Stores encrypted transaction queue and device encryption keys locally.
 * Survives browser restarts and provides efficient querying.
 *
 * References:
 * - Architecture: §4.1 State Management (POS offline state in IndexedDB)
 * - Task I4.T7: IndexedDB offline queue storage
 */

import { openDB, type IDBPDatabase, type DBSchema } from 'idb'

export interface QueueEntry {
  id: string // UUID generated client-side
  localTransactionId: string
  encryptedPayload: string // Base64
  encryptionIv: string // Base64
  encryptionKeyVersion: number
  transactionTimestamp: string // ISO 8601
  transactionAmount: number
  idempotencyKey: string
  staffUserId?: string
  syncStatus: 'queued' | 'syncing' | 'synced' | 'failed'
  syncError?: string
  createdAt: string // ISO 8601
  syncedAt?: string // ISO 8601
  ttlMs: number // Time-to-live in milliseconds (default: 7 days)
  expiresAt: string // ISO 8601 - calculated as createdAt + ttlMs
  retryCount: number // Number of sync attempts
  nextRetryAt?: string // ISO 8601 - next retry time with exponential backoff
  lastAttemptAt?: string // ISO 8601 - last sync attempt timestamp
}

export interface DeviceKeys {
  deviceId: number
  encryptionKey: string // Base64-encoded CryptoKey (exportKey result)
  keyVersion: number
  pairedAt: string // ISO 8601
}

export interface CachedProduct {
  variantId: string
  productId: string
  productName: string
  sku: string
  barcode?: string
  price: number
  inventoryQuantity: number
  categoryId?: string
  cachedAt: string // ISO 8601
  expiresAt: string // ISO 8601
}

interface OfflineDBSchema extends DBSchema {
  queueEntries: {
    key: string
    value: QueueEntry
    indexes: {
      'by-status': string
      'by-timestamp': string
      'by-idempotency': string
      'by-expiration': string
      'by-next-retry': string
    }
  }
  deviceKeys: {
    key: number // deviceId
    value: DeviceKeys
  }
  catalogCache: {
    key: string // variantId
    value: CachedProduct
    indexes: {
      'by-sku': string
      'by-barcode': string
      'by-expiration': string
    }
  }
}

const DB_NAME = 'pos-offline-db'
const DB_VERSION = 2 // Incremented for schema changes

export const DEFAULT_TTL_MS = 7 * 24 * 60 * 60 * 1000 // 7 days
export const CATALOG_CACHE_TTL_MS = 24 * 60 * 60 * 1000 // 24 hours

let dbInstance: IDBPDatabase<OfflineDBSchema> | null = null

/**
 * Open IndexedDB connection (singleton pattern).
 */
export async function getDB(): Promise<IDBPDatabase<OfflineDBSchema>> {
  if (dbInstance) {
    return dbInstance
  }

  dbInstance = await openDB<OfflineDBSchema>(DB_NAME, DB_VERSION, {
    upgrade(db, oldVersion, newVersion, transaction) {
      // Queue entries store
      if (!db.objectStoreNames.contains('queueEntries')) {
        const queueStore = db.createObjectStore('queueEntries', { keyPath: 'id' })
        queueStore.createIndex('by-status', 'syncStatus')
        queueStore.createIndex('by-timestamp', 'createdAt')
        queueStore.createIndex('by-idempotency', 'idempotencyKey', { unique: true })
        queueStore.createIndex('by-expiration', 'expiresAt')
        queueStore.createIndex('by-next-retry', 'nextRetryAt')
      } else if (oldVersion < 2) {
        // Add new indexes for v2
        const queueStore = transaction.objectStore('queueEntries')
        if (!queueStore.indexNames.contains('by-expiration')) {
          queueStore.createIndex('by-expiration', 'expiresAt')
        }
        if (!queueStore.indexNames.contains('by-next-retry')) {
          queueStore.createIndex('by-next-retry', 'nextRetryAt')
        }
      }

      // Device keys store
      if (!db.objectStoreNames.contains('deviceKeys')) {
        db.createObjectStore('deviceKeys', { keyPath: 'deviceId' })
      }

      // Catalog cache store (v2)
      if (!db.objectStoreNames.contains('catalogCache')) {
        const catalogStore = db.createObjectStore('catalogCache', { keyPath: 'variantId' })
        catalogStore.createIndex('by-sku', 'sku')
        catalogStore.createIndex('by-barcode', 'barcode')
        catalogStore.createIndex('by-expiration', 'expiresAt')
      }
    },
  })

  return dbInstance
}

/**
 * Add transaction to offline queue.
 */
export async function addToQueue(entry: QueueEntry): Promise<void> {
  const db = await getDB()
  await db.add('queueEntries', entry)
}

/**
 * Get all queued entries (pending upload).
 */
export async function getQueuedEntries(): Promise<QueueEntry[]> {
  const db = await getDB()
  return db.getAllFromIndex('queueEntries', 'by-status', 'queued')
}

/**
 * Get queue entry by ID.
 */
export async function getQueueEntry(id: string): Promise<QueueEntry | undefined> {
  const db = await getDB()
  return db.get('queueEntries', id)
}

/**
 * Update queue entry status.
 */
export async function updateQueueEntry(id: string, updates: Partial<QueueEntry>): Promise<void> {
  const db = await getDB()
  const entry = await db.get('queueEntries', id)
  if (!entry) {
    throw new Error(`Queue entry not found: ${id}`)
  }

  const updated = { ...entry, ...updates }
  await db.put('queueEntries', updated)
}

/**
 * Mark entry as syncing.
 */
export async function markAsSyncing(id: string): Promise<void> {
  await updateQueueEntry(id, { syncStatus: 'syncing' })
}

/**
 * Mark entry as synced.
 */
export async function markAsSynced(id: string): Promise<void> {
  await updateQueueEntry(id, {
    syncStatus: 'synced',
    syncedAt: new Date().toISOString(),
  })
}

/**
 * Mark entry as failed.
 */
export async function markAsFailed(id: string, error: string): Promise<void> {
  await updateQueueEntry(id, {
    syncStatus: 'failed',
    syncError: error,
  })
}

/**
 * Delete synced entries (cleanup).
 */
export async function deleteSyncedEntries(): Promise<number> {
  const db = await getDB()
  const synced = await db.getAllFromIndex('queueEntries', 'by-status', 'synced')
  for (const entry of synced) {
    await db.delete('queueEntries', entry.id)
  }
  return synced.length
}

/**
 * Get queue statistics.
 */
export async function getQueueStats(): Promise<{
  queued: number
  syncing: number
  synced: number
  failed: number
  total: number
}> {
  const db = await getDB()
  const all = await db.getAll('queueEntries')

  return {
    queued: all.filter((e) => e.syncStatus === 'queued').length,
    syncing: all.filter((e) => e.syncStatus === 'syncing').length,
    synced: all.filter((e) => e.syncStatus === 'synced').length,
    failed: all.filter((e) => e.syncStatus === 'failed').length,
    total: all.length,
  }
}

/**
 * Store device encryption keys.
 */
export async function storeDeviceKeys(keys: DeviceKeys): Promise<void> {
  const db = await getDB()
  await db.put('deviceKeys', keys)
}

/**
 * Get device encryption keys.
 */
export async function getDeviceKeys(deviceId: number): Promise<DeviceKeys | undefined> {
  const db = await getDB()
  return db.get('deviceKeys', deviceId)
}

/**
 * Delete device keys (on device unpair).
 */
export async function deleteDeviceKeys(deviceId: number): Promise<void> {
  const db = await getDB()
  await db.delete('deviceKeys', deviceId)
}

/**
 * Export entire offline queue as JSON (for support debugging).
 */
export async function exportQueue(): Promise<string> {
  const db = await getDB()
  const entries = await db.getAll('queueEntries')
  return JSON.stringify(entries, null, 2)
}

/**
 * Clear all offline data (use with caution!).
 */
export async function clearAllData(): Promise<void> {
  const db = await getDB()
  await db.clear('queueEntries')
  await db.clear('deviceKeys')
  await db.clear('catalogCache')
}

/**
 * Clean up expired queue entries (TTL enforcement).
 * @returns Number of entries deleted
 */
export async function cleanupExpiredEntries(): Promise<number> {
  const db = await getDB()
  const now = new Date().toISOString()
  const all = await db.getAll('queueEntries')

  let deletedCount = 0
  for (const entry of all) {
    if (entry.expiresAt && entry.expiresAt < now) {
      await db.delete('queueEntries', entry.id)
      deletedCount++
    }
  }

  return deletedCount
}

/**
 * Get entries eligible for retry (past nextRetryAt time).
 */
export async function getEntriesEligibleForRetry(): Promise<QueueEntry[]> {
  const db = await getDB()
  const now = new Date().toISOString()
  const failed = await db.getAllFromIndex('queueEntries', 'by-status', 'failed')

  return failed.filter((entry) => !entry.nextRetryAt || entry.nextRetryAt <= now)
}

/**
 * Calculate next retry time with exponential backoff.
 * Base: 30s, Max: 1 hour, with jitter.
 */
export function calculateNextRetryTime(retryCount: number): Date {
  const baseDelayMs = 30 * 1000 // 30 seconds
  const maxDelayMs = 60 * 60 * 1000 // 1 hour
  const exponentialDelay = baseDelayMs * Math.pow(2, retryCount)
  const cappedDelay = Math.min(exponentialDelay, maxDelayMs)

  // Add jitter (±20%)
  const jitter = cappedDelay * 0.2 * (Math.random() - 0.5)
  const delayWithJitter = cappedDelay + jitter

  return new Date(Date.now() + delayWithJitter)
}

/**
 * Cache product for offline use.
 */
export async function cacheProduct(product: Omit<CachedProduct, 'cachedAt' | 'expiresAt'>): Promise<void> {
  const db = await getDB()
  const now = new Date()
  const expiresAt = new Date(now.getTime() + CATALOG_CACHE_TTL_MS)

  const cached: CachedProduct = {
    ...product,
    cachedAt: now.toISOString(),
    expiresAt: expiresAt.toISOString(),
  }

  await db.put('catalogCache', cached)
}

/**
 * Get cached product by variant ID.
 */
export async function getCachedProduct(variantId: string): Promise<CachedProduct | undefined> {
  const db = await getDB()
  const product = await db.get('catalogCache', variantId)

  // Check expiration
  if (product && product.expiresAt < new Date().toISOString()) {
    await db.delete('catalogCache', variantId)
    return undefined
  }

  return product
}

/**
 * Search cached products by SKU or barcode.
 */
export async function searchCachedProducts(query: string): Promise<CachedProduct[]> {
  const db = await getDB()
  const now = new Date().toISOString()

  // Try exact SKU match first
  const bySku = await db.getAllFromIndex('catalogCache', 'by-sku', query.toUpperCase())
  const validBySku = bySku.filter((p) => p.expiresAt >= now)
  if (validBySku.length > 0) {
    return validBySku
  }

  // Try exact barcode match
  if (query.trim()) {
    const byBarcode = await db.getAllFromIndex('catalogCache', 'by-barcode', query)
    const validByBarcode = byBarcode.filter((p) => p.expiresAt >= now)
    if (validByBarcode.length > 0) {
      return validByBarcode
    }
  }

  // Fallback to partial name match
  const all = await db.getAll('catalogCache')
  const lowerQuery = query.toLowerCase()
  return all.filter(
    (p) => p.expiresAt >= now && p.productName.toLowerCase().includes(lowerQuery)
  ).slice(0, 20) // Limit results
}

/**
 * Clear expired catalog cache entries.
 */
export async function cleanupExpiredCatalogCache(): Promise<number> {
  const db = await getDB()
  const now = new Date().toISOString()
  const all = await db.getAll('catalogCache')

  let deletedCount = 0
  for (const product of all) {
    if (product.expiresAt < now) {
      await db.delete('catalogCache', product.variantId)
      deletedCount++
    }
  }

  return deletedCount
}

/**
 * Bulk cache products.
 */
export async function bulkCacheProducts(products: Omit<CachedProduct, 'cachedAt' | 'expiresAt'>[]): Promise<void> {
  const db = await getDB()
  const now = new Date()
  const expiresAt = new Date(now.getTime() + CATALOG_CACHE_TTL_MS)

  const tx = db.transaction('catalogCache', 'readwrite')
  const store = tx.objectStore('catalogCache')

  for (const product of products) {
    const cached: CachedProduct = {
      ...product,
      cachedAt: now.toISOString(),
      expiresAt: expiresAt.toISOString(),
    }
    await store.put(cached)
  }

  await tx.done
}
