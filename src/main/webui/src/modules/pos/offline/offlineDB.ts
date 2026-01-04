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
}

export interface DeviceKeys {
  deviceId: number
  encryptionKey: string // Base64-encoded CryptoKey (exportKey result)
  keyVersion: number
  pairedAt: string // ISO 8601
}

interface OfflineDBSchema extends DBSchema {
  queueEntries: {
    key: string
    value: QueueEntry
    indexes: {
      'by-status': string
      'by-timestamp': string
      'by-idempotency': string
    }
  }
  deviceKeys: {
    key: number // deviceId
    value: DeviceKeys
  }
}

const DB_NAME = 'pos-offline-db'
const DB_VERSION = 1

let dbInstance: IDBPDatabase<OfflineDBSchema> | null = null

/**
 * Open IndexedDB connection (singleton pattern).
 */
export async function getDB(): Promise<IDBPDatabase<OfflineDBSchema>> {
  if (dbInstance) {
    return dbInstance
  }

  dbInstance = await openDB<OfflineDBSchema>(DB_NAME, DB_VERSION, {
    upgrade(db) {
      // Queue entries store
      if (!db.objectStoreNames.contains('queueEntries')) {
        const queueStore = db.createObjectStore('queueEntries', { keyPath: 'id' })
        queueStore.createIndex('by-status', 'syncStatus')
        queueStore.createIndex('by-timestamp', 'createdAt')
        queueStore.createIndex('by-idempotency', 'idempotencyKey', { unique: true })
      }

      // Device keys store
      if (!db.objectStoreNames.contains('deviceKeys')) {
        db.createObjectStore('deviceKeys', { keyPath: 'deviceId' })
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
}
