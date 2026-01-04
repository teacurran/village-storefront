/**
 * POS Service Worker for offline queue background sync.
 *
 * Handles background sync when network connectivity is restored.
 * Registered from POS module initialization.
 *
 * References:
 * - Architecture: §3.4 POS Offline Flow UX (automatic retry behavior)
 * - Task I4.T7: Service Worker for offline sync
 */

const CACHE_NAME = 'pos-offline-v1'
const SYNC_TAG = 'pos-offline-sync'

// Install event
self.addEventListener('install', (event) => {
  console.log('[POS SW] Installing service worker...')
  self.skipWaiting() // Activate immediately
})

// Activate event
self.addEventListener('activate', (event) => {
  console.log('[POS SW] Activating service worker...')
  event.waitUntil(self.clients.claim()) // Take control immediately
})

// Background Sync event
self.addEventListener('sync', (event) => {
  console.log('[POS SW] Background sync event:', event.tag)

  if (event.tag === SYNC_TAG) {
    event.waitUntil(syncOfflineQueue())
  }
})

/**
 * Sync offline queue with server.
 */
async function syncOfflineQueue() {
  try {
    console.log('[POS SW] Starting background sync...')

    // Notify all clients to trigger sync
    const clients = await self.clients.matchAll({ type: 'window' })
    for (const client of clients) {
      client.postMessage({
        type: 'TRIGGER_SYNC',
        timestamp: Date.now(),
      })
    }

    console.log('[POS SW] Background sync completed')
    return Promise.resolve()
  } catch (error) {
    console.error('[POS SW] Background sync failed:', error)
    return Promise.reject(error)
  }
}

// Message handler from clients
self.addEventListener('message', (event) => {
  console.log('[POS SW] Message received:', event.data)

  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting()
  }

  if (event.data && event.data.type === 'REGISTER_SYNC') {
    // Client requests background sync registration
    if (self.registration.sync) {
      self.registration.sync
        .register(SYNC_TAG)
        .then(() => {
          console.log('[POS SW] Background sync registered')
          if (event.ports && event.ports[0]) {
            event.ports[0].postMessage({ success: true })
          }
        })
        .catch((error) => {
          console.error('[POS SW] Background sync registration failed:', error)
          if (event.ports && event.ports[0]) {
            event.ports[0].postMessage({ success: false, error: error.message })
          }
        })
    } else {
      console.warn('[POS SW] Background sync not supported')
      if (event.ports && event.ports[0]) {
        event.ports[0].postMessage({ success: false, error: 'Background sync not supported' })
      }
    }
  }
})

console.log('[POS SW] Service worker loaded')
