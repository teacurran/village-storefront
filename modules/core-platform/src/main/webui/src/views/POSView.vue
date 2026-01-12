<template>
  <div class="pos-view">
    <header class="pos-header">
      <div>
        <h1>Point of Sale</h1>
        <p class="subtitle">
          Sell confidently even if the network drops — queued transactions sync automatically.
        </p>
      </div>
      <OfflineIndicator />
    </header>

    <section v-if="!pairedDevice" class="card pairing-card">
      <h2>Complete Device Pairing</h2>
      <p class="helper-text">
        Enter the pairing code generated from the admin dashboard to receive the encryption key for
        this POS device.
      </p>

      <form class="pairing-form" @submit.prevent="completePairing">
        <label for="pairing-code">Pairing Code</label>
        <input
          id="pairing-code"
          v-model="pairingCode"
          type="text"
          placeholder="e.g., ABCD1234"
          maxlength="8"
          required
        />
        <div class="pairing-actions">
          <button type="submit" class="btn-primary" :disabled="isPairing">
            {{ isPairing ? 'Pairing...' : 'Pair Device' }}
          </button>
        </div>
        <p v-if="pairingError" class="error-text">{{ pairingError }}</p>
      </form>

      <p class="helper-text">
        Need a pairing code? Ask an admin to add this device under
        <strong>Admin → POS → Devices</strong>.
      </p>
    </section>

    <section v-else class="card device-card">
      <div class="device-card-header">
        <div>
          <h2>{{ pairedDevice.deviceName }}</h2>
          <p class="helper-text">Paired {{ formatRelative(pairedDevice.pairedAt) }}</p>
        </div>
        <div class="device-actions">
          <button class="btn-secondary" @click="toggleHold">
            {{ isSyncOnHold ? 'Resume Sync' : 'Hold Sync' }}
          </button>
          <button class="btn-secondary" :disabled="!canSyncNow" @click="syncNow">
            <i class="pi pi-cloud-upload"></i>
            Sync Now
          </button>
          <button class="btn-link danger" @click="forgetDevice">
            <i class="pi pi-times"></i>
            Forget Device
          </button>
        </div>
      </div>

      <div class="device-meta">
        <div>
          <span class="label">Device ID</span>
          <strong>#{{ pairedDevice.deviceId }}</strong>
        </div>
        <div>
          <span class="label">Queue Depth</span>
          <strong>{{ queueStats.queued }} queued · {{ queueStats.failed }} failed</strong>
        </div>
        <div>
          <span class="label">Last Sync</span>
          <strong>{{ lastSyncLabel }}</strong>
        </div>
      </div>

      <div class="terminal-token">
        <div class="token-header">
          <div>
            <span class="label">Stripe Terminal Token</span>
            <p class="helper-text">Provide to the Stripe Terminal SDK when pairing a reader.</p>
          </div>
          <div class="token-actions">
            <button
              class="btn-secondary"
              :disabled="isTerminalLoading"
              @click="requestTerminalToken"
            >
              <i class="pi pi-refresh" :class="{ 'pi-spin': isTerminalLoading }"></i>
              Refresh
            </button>
            <button class="btn-secondary" :disabled="!terminalToken" @click="copyTerminalToken">
              <i class="pi pi-copy"></i>
              Copy
            </button>
          </div>
        </div>
        <code class="token-value">{{ terminalToken || 'Request a token to pair a reader' }}</code>
      </div>
    </section>

    <section v-if="pairedDevice" class="card cart-card">
      <div class="cart-header">
        <h2>Cart</h2>
        <button
          class="btn-link danger"
          :disabled="cartItems.length === 0"
          aria-label="Clear cart"
          @click="clearCart"
        >
          Clear
        </button>
      </div>

      <!-- Product search -->
      <div class="search-bar">
        <input
          v-model="searchQuery"
          placeholder="Search products by name or SKU..."
          aria-label="Search products"
          class="search-input"
          @input="searchProducts"
        />
      </div>

      <!-- Search results -->
      <div v-if="searchResults.length > 0" class="search-results">
        <div
          v-for="product in searchResults"
          :key="product.variantId"
          class="search-result-item"
          role="button"
          tabindex="0"
          aria-label="`Add ${product.productName} to cart`"
          @click="addToCart(product)"
          @keydown.enter="addToCart(product)"
        >
          <span class="product-name">{{ product.productName }}</span>
          <span class="product-price">${{ product.price.toFixed(2) }}</span>
        </div>
      </div>

      <!-- Cart items -->
      <div v-if="cartItems.length > 0" class="cart-items">
        <div v-for="item in cartItems" :key="item.variantId" class="cart-item">
          <span class="item-name">{{ item.productName }}</span>
          <div class="quantity-controls">
            <button aria-label="Decrease quantity" class="qty-btn" @click="decrementQty(item)">
              -
            </button>
            <span class="quantity">{{ item.quantity }}</span>
            <button aria-label="Increase quantity" class="qty-btn" @click="incrementQty(item)">
              +
            </button>
          </div>
          <span class="item-total">${{ (item.price * item.quantity).toFixed(2) }}</span>
          <button
            class="btn-link danger"
            aria-label="Remove item from cart"
            @click="removeItem(item)"
          >
            <i class="pi pi-times"></i>
          </button>
        </div>
      </div>

      <div v-else class="empty-cart">
        <p>Cart is empty. Search for products to add them.</p>
      </div>

      <div v-if="cartItems.length > 0" class="cart-total">
        <strong>Total:</strong> ${{ cartTotal.toFixed(2) }}
      </div>
    </section>

    <section v-if="pairedDevice && cartItems.length > 0" class="card tender-card">
      <h2>Tender</h2>
      <div class="tender-form">
        <label for="payment-method">Payment Method</label>
        <select
          id="payment-method"
          v-model="paymentMethodId"
          aria-label="Payment method"
          class="payment-select"
        >
          <option value="">Select payment method</option>
          <option value="cash">Cash</option>
          <option value="card">Card on File</option>
          <option value="terminal">Card Reader</option>
        </select>

        <button
          class="btn-primary btn-large"
          :disabled="!paymentMethodId || isProcessing"
          aria-label="Complete sale"
          @click="completeSale"
        >
          {{ isProcessing ? 'Processing...' : 'Complete Sale' }}
        </button>
      </div>
    </section>

    <section v-if="pairedDevice" class="card queue-card">
      <div class="queue-card-header">
        <h2>Offline Queue</h2>
        <button class="btn-secondary" @click="offlineStore.refreshQueueStats()">
          <i class="pi pi-refresh"></i>
          Refresh
        </button>
      </div>
      <OfflineQueueList />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import OfflineIndicator from '@/modules/pos/offline/OfflineIndicator.vue'
import OfflineQueueList from '@/modules/pos/offline/OfflineQueueList.vue'
import { useOfflineStore } from '@/modules/pos/offline/offlineStore'
import { useToast } from 'primevue/usetoast'
import { storeToRefs } from 'pinia'

interface PairedDevice {
  deviceId: number
  deviceName: string
  pairedAt: string
}

interface Product {
  productId: string
  variantId: string
  productName: string
  price: number
  quantity?: number
}

const offlineStore = useOfflineStore()
const toast = useToast()
const { queueStats, lastSyncAt, isSyncOnHold } = storeToRefs(offlineStore)

const pairingCode = ref('')
const isPairing = ref(false)
const pairingError = ref('')
const pairedDevice = ref<PairedDevice | null>(null)
const terminalToken = ref<string | null>(null)
const isTerminalLoading = ref(false)

// Cart state
const searchQuery = ref('')
const searchResults = ref<Product[]>([])
const cartItems = ref<Product[]>([])
const paymentMethodId = ref('')
const isProcessing = ref(false)

const cartTotal = computed(() =>
  cartItems.value.reduce((sum, item) => sum + item.price * (item.quantity || 1), 0)
)

const lastSyncLabel = computed(() => {
  if (!lastSyncAt.value) return 'Not yet synced'
  return formatRelative(lastSyncAt.value.toISOString())
})
const canSyncNow = computed(() => offlineStore.canSync)

onMounted(() => {
  loadCachedDevice()
})

onBeforeUnmount(() => {
  offlineStore.dispose()
})

async function loadCachedDevice() {
  const stored = localStorage.getItem('pos.offline.device')
  if (!stored) {
    return
  }

  try {
    const parsed = JSON.parse(stored) as PairedDevice
    pairedDevice.value = parsed
    await offlineStore.initialize(parsed.deviceId)
    await offlineStore.refreshQueueStats()
    await requestTerminalToken()
  } catch (error) {
    console.warn('Failed to restore POS device context', error)
    localStorage.removeItem('pos.offline.device')
  }
}

async function completePairing() {
  if (!pairingCode.value) return
  pairingError.value = ''
  isPairing.value = true

  try {
    const response = await fetch('/api/pos/devices/complete-pairing', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ pairingCode: pairingCode.value.trim() }),
    })

    if (!response.ok) {
      const message = await response.text()
      pairingError.value = message || 'Failed to complete pairing'
      return
    }

    const result = await response.json()
    await offlineStore.storePairingKeys(
      result.deviceId,
      result.encryptionKey,
      result.encryptionKeyVersion
    )
    await offlineStore.initialize(result.deviceId)
    await offlineStore.refreshQueueStats()

    pairedDevice.value = {
      deviceId: result.deviceId,
      deviceName: result.deviceName,
      pairedAt: new Date().toISOString(),
    }
    localStorage.setItem('pos.offline.device', JSON.stringify(pairedDevice.value))
    terminalToken.value = result.stripeConnectionToken

    toast.add({
      severity: 'success',
      summary: 'Device Paired',
      detail: `${result.deviceName} is ready for offline mode`,
      life: 4000,
    })
    pairingCode.value = ''
  } catch (error) {
    pairingError.value = 'Unexpected error completing pairing'
    console.error(error)
  } finally {
    isPairing.value = false
  }
}

async function requestTerminalToken() {
  if (!pairedDevice.value) return
  isTerminalLoading.value = true
  try {
    const response = await fetch(`/api/pos/devices/${pairedDevice.value.deviceId}/terminal/token`, {
      method: 'POST',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error('Unable to request connection token')
    }
    const data = await response.json()
    terminalToken.value = data.connectionToken
  } catch (error) {
    toast.add({
      severity: 'warn',
      summary: 'Terminal Token',
      detail: 'Failed to request a new connection token',
      life: 4000,
    })
    console.error(error)
  } finally {
    isTerminalLoading.value = false
  }
}

function copyTerminalToken() {
  if (!terminalToken.value) return
  navigator.clipboard.writeText(terminalToken.value).then(() => {
    toast.add({
      severity: 'info',
      summary: 'Copied',
      detail: 'Connection token copied',
      life: 2500,
    })
  })
}

function toggleHold() {
  if (isSyncOnHold.value) {
    offlineStore.resumeSync()
    toast.add({
      severity: 'info',
      summary: 'Sync Resumed',
      detail: 'Offline queue sync resumed',
      life: 3000,
    })
  } else {
    offlineStore.holdSync()
    toast.add({ severity: 'warn', summary: 'Sync Paused', detail: 'Sync is on hold', life: 3000 })
  }
}

function syncNow() {
  offlineStore.syncQueue()
}

function forgetDevice() {
  offlineStore.dispose()
  offlineStore.clearDeviceContext()
  pairedDevice.value = null
  terminalToken.value = null
  localStorage.removeItem('pos.offline.device')
  toast.add({
    severity: 'warn',
    summary: 'Device Removed',
    detail: 'Device pairing cleared locally',
    life: 3000,
  })
}

function formatRelative(dateString: string) {
  const date = new Date(dateString)
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  const diffMinutes = Math.floor((Date.now() - date.getTime()) / 60000)
  if (Math.abs(diffMinutes) < 60) {
    return formatter.format(-diffMinutes, 'minute')
  }
  const diffHours = Math.floor(diffMinutes / 60)
  if (Math.abs(diffHours) < 24) {
    return formatter.format(-diffHours, 'hour')
  }
  const diffDays = Math.floor(diffHours / 24)
  return formatter.format(-diffDays, 'day')
}

// Cart management functions
let searchTimeout: number | null = null

function searchProducts() {
  if (searchTimeout) {
    clearTimeout(searchTimeout)
  }

  if (!searchQuery.value || searchQuery.value.length < 2) {
    searchResults.value = []
    return
  }

  searchTimeout = window.setTimeout(async () => {
    // Mock product search for demo - replace with actual API call
    // TODO: Call /api/catalog/products/search?q=${searchQuery.value}
    const mockProducts: Product[] = [
      {
        productId: '1',
        variantId: '1-default',
        productName: 'Widget Pro',
        price: 29.99,
      },
      {
        productId: '2',
        variantId: '2-default',
        productName: 'Gadget Plus',
        price: 49.99,
      },
      {
        productId: '3',
        variantId: '3-default',
        productName: 'Device Elite',
        price: 99.99,
      },
    ]

    searchResults.value = mockProducts.filter((p) =>
      p.productName.toLowerCase().includes(searchQuery.value.toLowerCase())
    )
  }, 300)
}

function addToCart(product: Product) {
  const existing = cartItems.value.find((i) => i.variantId === product.variantId)
  if (existing) {
    existing.quantity = (existing.quantity || 1) + 1
  } else {
    cartItems.value.push({ ...product, quantity: 1 })
  }

  toast.add({
    severity: 'success',
    summary: 'Added to Cart',
    detail: product.productName,
    life: 2000,
  })

  searchQuery.value = ''
  searchResults.value = []
}

function incrementQty(item: Product) {
  item.quantity = (item.quantity || 1) + 1
}

function decrementQty(item: Product) {
  if ((item.quantity || 1) > 1) {
    item.quantity = (item.quantity || 1) - 1
  }
}

function removeItem(item: Product) {
  const idx = cartItems.value.indexOf(item)
  if (idx > -1) {
    cartItems.value.splice(idx, 1)
  }
}

function clearCart() {
  cartItems.value = []
  paymentMethodId.value = ''
}

async function completeSale() {
  if (!pairedDevice.value || cartItems.value.length === 0 || !paymentMethodId.value) {
    return
  }

  isProcessing.value = true

  try {
    const transaction = {
      localTransactionId: uuidv4(),
      totalAmount: cartTotal.value,
      currency: 'USD',
      paymentMethodId: paymentMethodId.value,
      items: cartItems.value.map((i) => ({
        productId: i.productId,
        variantId: i.variantId,
        quantity: i.quantity || 1,
        price: i.price,
      })),
    }

    await offlineStore.enqueueTransaction(transaction)

    toast.add({
      severity: 'success',
      summary: 'Sale Queued',
      detail: offlineStore.isOnline
        ? 'Transaction syncing to server...'
        : 'Transaction will sync when online',
      life: 4000,
    })

    clearCart()
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to queue transaction',
      life: 4000,
    })
    console.error(error)
  } finally {
    isProcessing.value = false
  }
}
</script>

<style scoped>
.pos-view {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding-bottom: 2rem;
}

.pos-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 2rem;
}

.pos-header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
}

.subtitle {
  margin: 0.25rem 0 0;
  color: var(--text-color-secondary);
}

.card {
  background: var(--surface-card, #fff);
  border: 1px solid var(--surface-border, #e5e7eb);
  border-radius: 0.75rem;
  padding: 1.5rem;
  box-shadow: var(--surface-shadow, 0 1px 2px rgba(0, 0, 0, 0.05));
}

.pairing-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-top: 1rem;
}

.pairing-form input {
  padding: 0.75rem;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  font-size: 1rem;
}

.pairing-actions {
  display: flex;
  gap: 0.75rem;
}

.btn-primary,
.btn-secondary,
.btn-link {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.6rem 1rem;
  border-radius: 0.5rem;
  border: none;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.2s ease;
}

.btn-primary {
  background: var(--primary-color, #2563eb);
  color: white;
}

.btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.btn-secondary {
  background: var(--surface-100, #f3f4f6);
  border: 1px solid var(--surface-border, #d1d5db);
  color: var(--text-color);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--surface-200, #e5e7eb);
}

.btn-link {
  background: transparent;
  border: none;
  color: var(--primary-color, #2563eb);
}

.btn-link.danger {
  color: var(--red-600, #dc2626);
}

.device-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.device-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.device-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
  margin: 1.5rem 0;
}

.label {
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-size: 0.7rem;
  color: var(--text-color-secondary);
}

.helper-text {
  margin: 0.5rem 0;
  color: var(--text-color-secondary);
}

.error-text {
  color: var(--red-600, #dc2626);
  margin: 0;
}

.terminal-token {
  border: 1px dashed var(--surface-border, #d1d5db);
  border-radius: 0.75rem;
  padding: 1rem;
}

.token-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.token-actions {
  display: flex;
  gap: 0.5rem;
}

.token-value {
  display: block;
  margin-top: 0.75rem;
  padding: 0.75rem;
  border-radius: 0.5rem;
  background: var(--surface-100, #f3f4f6);
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.85rem;
  color: var(--text-color);
  word-break: break-all;
}

.queue-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

/* Cart and Tender Styles */
.cart-card,
.tender-card {
  margin-top: 1.5rem;
}

.cart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.search-bar {
  margin-bottom: 1rem;
}

.search-input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  font-size: 1rem;
}

.search-results {
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  margin-bottom: 1rem;
}

.search-result-item {
  display: flex;
  justify-content: space-between;
  padding: 0.75rem;
  cursor: pointer;
  transition: background 0.2s;
}

.search-result-item:hover {
  background: var(--surface-100, #f3f4f6);
}

.product-name {
  font-weight: 500;
}

.product-price {
  color: var(--primary-color, #2563eb);
  font-weight: 600;
}

.cart-items {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 1rem 0;
}

.cart-item {
  display: grid;
  grid-template-columns: 1fr auto auto auto;
  gap: 1rem;
  align-items: center;
  padding: 0.75rem;
  background: var(--surface-100, #f3f4f6);
  border-radius: 0.5rem;
}

.item-name {
  font-weight: 500;
}

.quantity-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.qty-btn {
  width: 48px;
  height: 48px;
  min-height: 48px;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  background: white;
  cursor: pointer;
  font-size: 1.25rem;
  font-weight: 700;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.qty-btn:hover:not(:disabled) {
  background: var(--surface-100, #f3f4f6);
}

.quantity {
  min-width: 2rem;
  text-align: center;
  font-weight: 600;
}

.item-total {
  font-weight: 700;
  color: var(--text-color);
}

.empty-cart {
  text-align: center;
  padding: 2rem;
  color: var(--text-color-secondary);
}

.cart-total {
  text-align: right;
  font-size: 1.5rem;
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 2px solid var(--surface-border, #d1d5db);
}

.tender-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.tender-form label {
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.payment-select {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  font-size: 1rem;
}

.btn-large {
  width: 100%;
  min-height: 56px;
  font-size: 1.125rem;
  font-weight: 700;
}

/* Accessibility: Ensure all buttons meet touch target size */
button {
  min-height: 48px;
}

/* Accessibility: Focus states for keyboard navigation */
button:focus-visible,
input:focus-visible,
select:focus-visible {
  outline: 2px solid var(--primary-color, #2563eb);
  outline-offset: 2px;
}

.search-result-item:focus-visible {
  outline: 2px solid var(--primary-color, #2563eb);
  outline-offset: -2px;
}

@media (max-width: 768px) {
  .pos-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .device-card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .device-actions {
    justify-content: flex-start;
  }

  .cart-item {
    grid-template-columns: 1fr;
    gap: 0.5rem;
  }

  .quantity-controls {
    justify-content: center;
  }
}
</style>
