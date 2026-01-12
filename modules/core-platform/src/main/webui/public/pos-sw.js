/**
 * POS Service Worker for offline queue background sync and asset caching.
 *
 * Handles background sync when network connectivity is restored and caches
 * critical assets for offline operation.
 *
 * References:
 * - Architecture: §3.4 POS Offline Flow UX (automatic retry behavior)
 * - Task I4.T5: Service Worker with cache management
 */

const CACHE_NAME = 'pos-offline-v2'
const SYNC_TAG = 'pos-offline-sync'

// Assets to cache for offline use
const CRITICAL_ASSETS = [
  '/',
  '/pos',
  '/assets/primeicons.woff2',
  '/assets/vendor.js',
  '/assets/main.js',
]

const API_CACHE_NAME = 'pos-api-v1'
const API_CACHE_PATTERNS = ['/api/catalog/products']

// Install event - cache critical assets
self.addEventListener('install', (event) => {
  console.log('[POS SW] Installing service worker...')
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[POS SW] Caching critical assets')
      return cache.addAll(CRITICAL_ASSETS).catch((error) => {
        console.warn('[POS SW] Failed to cache some assets:', error)
      })
    })
  )
  self.skipWaiting() // Activate immediately
})

// Activate event - cleanup old caches
self.addEventListener('activate', (event) => {
  console.log('[POS SW] Activating service worker...')
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== CACHE_NAME && cacheName !== API_CACHE_NAME) {
            console.log('[POS SW] Deleting old cache:', cacheName)
            return caches.delete(cacheName)
          }
        })
      )
    }).then(() => self.clients.claim())
  )
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

// Fetch event - implement caching strategies
self.addEventListener('fetch', (event) => {
  const { request } = event
  const url = new URL(request.url)

  // Skip cross-origin requests
  if (url.origin !== location.origin) {
    return
  }

  // API requests: Network-first with cache fallback
  if (API_CACHE_PATTERNS.some((pattern) => url.pathname.startsWith(pattern))) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          // Cache successful responses
          if (response.ok) {
            const responseClone = response.clone()
            caches.open(API_CACHE_NAME).then((cache) => {
              cache.put(request, responseClone)
            })
          }
          return response
        })
        .catch(() => {
          // Network failed, try cache
          return caches.match(request).then((cached) => {
            if (cached) {
              console.log('[POS SW] Serving cached API response:', url.pathname)
              return cached
            }
            // Return offline response
            return new Response(JSON.stringify({ error: 'Offline', items: [] }), {
              headers: { 'Content-Type': 'application/json' },
              status: 503,
            })
          })
        })
    )
    return
  }

  // Static assets: Cache-first
  if (
    request.method === 'GET' &&
    (url.pathname.startsWith('/assets/') ||
      url.pathname.endsWith('.js') ||
      url.pathname.endsWith('.css') ||
      url.pathname.endsWith('.woff2'))
  ) {
    event.respondWith(
      caches.match(request).then((cached) => {
        if (cached) {
          return cached
        }
        return fetch(request).then((response) => {
          if (response.ok) {
            const responseClone = response.clone()
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(request, responseClone)
            })
          }
          return response
        })
      })
    )
    return
  }

  // HTML pages: Network-first with cache fallback
  if (request.method === 'GET' && request.headers.get('accept')?.includes('text/html')) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const responseClone = response.clone()
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(request, responseClone)
            })
          }
          return response
        })
        .catch(() => {
          return caches.match(request).then((cached) => {
            if (cached) {
              console.log('[POS SW] Serving cached HTML:', url.pathname)
              return cached
            }
            return caches.match('/').then((fallback) => fallback || new Response('Offline'))
          })
        })
    )
  }
})

console.log('[POS SW] Service worker loaded')
