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
        <div>
          <h2>Cart</h2>
          <p class="helper-text">Search by name, SKU, or barcode. Cached catalog works offline.</p>
        </div>
        <button
          class="btn-link danger"
          :disabled="cartItems.length === 0"
          aria-label="Clear cart"
          @click="clearCart"
        >
          Clear
        </button>
      </div>

      <div class="search-bar">
        <input
          v-model="searchQuery"
          placeholder="Scan barcode or search products..."
          aria-label="Search products"
          class="search-input"
          @input="handleSearchInput"
          @keydown.enter.prevent="handleSearchEnter"
        />
        <span v-if="isSearching" class="search-status">
          <i class="pi pi-spin pi-spinner"></i>
          Searching...
        </span>
        <span v-else-if="searchError" class="search-status error">{{ searchError }}</span>
      </div>

      <div v-if="searchResults.length > 0" class="search-results" data-test="search-results">
        <div
          v-for="product in searchResults"
          :key="product.variantId"
          class="search-result-item"
          role="button"
          tabindex="0"
          :aria-label="`Add ${product.productName} to cart`"
          @click="addProductToCart(product)"
          @keydown.enter.prevent="addProductToCart(product)"
        >
          <div>
            <span class="product-name">{{ product.productName }}</span>
            <span class="product-sku">{{ product.sku }}</span>
            <span v-if="product.barcode" class="product-barcode">#{{ product.barcode }}</span>
          </div>
          <div class="product-meta">
            <span class="inventory-badge">Stock {{ product.inventoryQuantity }}</span>
            <span class="product-price">{{ formatCurrency(product.price) }}</span>
          </div>
        </div>
      </div>

      <div v-else-if="searchQuery.length >= 2 && !isSearching && !searchError" class="search-empty">
        <p>No products match “{{ searchQuery }}”.</p>
      </div>

      <div v-if="cartItems.length > 0" class="cart-items">
        <div v-for="item in cartItems" :key="item.variantId" class="cart-item">
          <div class="item-details">
            <span class="item-name">{{ item.productName }}</span>
            <span class="item-sku">SKU {{ item.sku }}</span>
          </div>
          <div class="quantity-controls">
            <button aria-label="Decrease quantity" class="qty-btn" @click="decrementQty(item)">
              -
            </button>
            <span class="quantity">{{ item.quantity }}</span>
            <button aria-label="Increase quantity" class="qty-btn" @click="incrementQty(item)">
              +
            </button>
          </div>
          <span class="item-total">{{ formatCurrency(item.subtotal) }}</span>
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

      <div v-if="cartItems.length > 0" class="cart-breakdown">
        <div class="breakdown-row">
          <span>Subtotal</span>
          <strong>{{ formatCurrency(subtotal) }}</strong>
        </div>
        <div class="breakdown-row">
          <span>Discounts</span>
          <strong>-{{ formatCurrency(discountAmount) }}</strong>
        </div>
        <div class="breakdown-row">
          <span>Tax</span>
          <strong>{{ formatCurrency(tax) }}</strong>
        </div>
        <div class="breakdown-row total-row">
          <span>Total</span>
          <strong>{{ formatCurrency(total) }}</strong>
        </div>
      </div>
    </section>

    <section v-if="pairedDevice && cartItems.length > 0" class="card tender-card">
      <h2>Tender &amp; Split Payments</h2>

      <div class="tender-layout">
        <div class="tender-summary">
          <div class="summary-row">
            <span>Amount Due</span>
            <strong data-test="amount-due">{{ formatCurrency(amountDue) }}</strong>
          </div>
          <div class="summary-row">
            <span>Amount Tendered</span>
            <strong>{{ formatCurrency(amountTendered) }}</strong>
          </div>
          <div class="summary-row">
            <span>Change Due</span>
            <strong>{{ formatCurrency(changeDue) }}</strong>
          </div>
        </div>

        <div v-if="payments.length > 0" class="payment-list">
          <div v-for="payment in payments" :key="payment.id" class="payment-pill">
            <div>
              <span class="payment-method">{{ formatMethod(payment.method) }}</span>
              <span class="payment-amount">{{ formatCurrency(payment.amount) }}</span>
              <span v-if="payment.reference" class="payment-reference">Ref {{ payment.reference }}</span>
            </div>
            <button class="btn-link" aria-label="Remove payment" @click="removePayment(payment.id)">
              <i class="pi pi-times"></i>
            </button>
          </div>
        </div>
      </div>

      <form class="tender-form" @submit.prevent="recordPayment">
        <label for="payment-method">Tender Type</label>
        <select
          id="payment-method"
          v-model="paymentMethod"
          aria-label="Payment method"
          class="payment-select"
        >
          <option value="cash">Cash</option>
          <option value="card">Card</option>
          <option value="store_credit">Store Credit</option>
        </select>

        <label for="payment-amount">Amount</label>
        <input
          id="payment-amount"
          v-model="paymentAmount"
          type="number"
          min="0"
          step="0.01"
          class="payment-amount-input"
          @input="handlePaymentAmountInput"
        />

        <label for="payment-reference">
          {{ paymentMethod === 'cash' ? 'Notes (optional)' : 'Reference / Last 4' }}
        </label>
        <input
          id="payment-reference"
          v-model="paymentReference"
          type="text"
          maxlength="64"
          placeholder="Required for card and store credit"
        />

        <button id="add-payment" class="btn-secondary" type="submit">
          <i class="pi pi-plus-circle"></i>
          Record Payment
        </button>
      </form>

      <p class="helper-text tender-helper">
        Accept multiple tenders per order. Store credit verifies balance when syncing.
      </p>
      <p v-if="paymentError" class="error-text">{{ paymentError }}</p>

      <button
        class="btn-primary btn-large"
        :disabled="!canCompleteSale || isProcessing"
        aria-label="Complete sale"
        @click="completeSale"
      >
        {{ isProcessing ? 'Processing...' : 'Complete Sale' }}
      </button>
    </section>

    <section v-if="pairedDevice" class="card queue-card">
      <div class="queue-card-header">
        <h2>Offline Queue</h2>
        <button class="btn-secondary" @click="offlineStore.refreshQueueStats()">
          <i class="pi pi-refresh"></i>
          Refresh
        </button>
      </div>
      <OfflineQueuePanel />
    </section>

    <HardwareStatusFooter v-if="pairedDevice" class="hardware-footer" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import OfflineIndicator from '@/modules/pos/offline/OfflineIndicator.vue'
import OfflineQueuePanel from '@/modules/pos/offline/OfflineQueuePanel.vue'
import HardwareStatusFooter from '@/modules/pos/offline/HardwareStatusFooter.vue'
import { useOfflineStore } from '@/modules/pos/offline/offlineStore'
import { usePOSStore } from '@/stores/pos'
import { useToast } from 'primevue/usetoast'
import { storeToRefs } from 'pinia'
import type { CachedProduct } from '@/modules/pos/offline/offlineDB'

interface PairedDevice {
  deviceId: number
  deviceName: string
  pairedAt: string
}

const offlineStore = useOfflineStore()
const posStore = usePOSStore()

const toast = useToast()
const { queueStats, lastSyncAt, isSyncOnHold } = storeToRefs(offlineStore)
const {
  cart: cartItems,
  subtotal,
  total,
  tax,
  discountAmount,
  payments,
  amountTendered,
  amountDue,
  changeDue,
  canComplete,
  customer,
} = storeToRefs(posStore)

const pairingCode = ref('')
const isPairing = ref(false)
const pairingError = ref('')
const pairedDevice = ref<PairedDevice | null>(null)
const terminalToken = ref<string | null>(null)
const isTerminalLoading = ref(false)
const isProcessing = ref(false)

// Search + cart state
const searchQuery = ref('')
const searchResults = ref<CachedProduct[]>([])
const searchError = ref('')
const isSearching = ref(false)
let searchTimeout: number | null = null

// Payment form
const paymentMethod = ref<'cash' | 'card' | 'store_credit'>('cash')
const paymentAmount = ref('')
const paymentReference = ref('')
const paymentError = ref('')
const paymentAmountEdited = ref(false)

const cartHasItems = computed(() => cartItems.value.length > 0)
const canCompleteSale = computed(() => canComplete.value && payments.value.length > 0)

const lastSyncLabel = computed(() => {
  if (!lastSyncAt.value) return 'Not yet synced'
  return formatRelative(lastSyncAt.value.toISOString())
})
const canSyncNow = computed(() => offlineStore.canSync)

watch(
  amountDue,
  (due) => {
    if (!paymentAmountEdited.value) {
      paymentAmount.value = due > 0 ? due.toFixed(2) : ''
    }
  },
  { immediate: true }
)

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
    await offlineStore.primeCatalogCache()
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
    await offlineStore.primeCatalogCache()

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

// Search helpers
function handleSearchInput() {
  scheduleSearch(false)
}

function handleSearchEnter() {
  scheduleSearch(true)
}

function scheduleSearch(autoAdd: boolean) {
  if (searchTimeout) {
    clearTimeout(searchTimeout)
  }

  if (!searchQuery.value || searchQuery.value.trim().length < 2) {
    searchResults.value = []
    searchError.value = ''
    return
  }

  searchTimeout = window.setTimeout(() => {
    executeSearch(autoAdd)
  }, autoAdd ? 0 : 300)
}

async function executeSearch(autoAdd: boolean) {
  const query = searchQuery.value.trim()
  if (query.length < 2) {
    searchResults.value = []
    return
  }

  isSearching.value = true
  searchError.value = ''

  try {
    const results = await offlineStore.searchProducts(query)
    searchResults.value = results

    if (results.length === 0) {
      searchError.value = 'No matching products found'
    }

    if (autoAdd) {
      const exact = findExactMatch(query, results)
      if (exact) {
        addProductToCart(exact)
        searchResults.value = []
        searchQuery.value = ''
        searchError.value = ''
      }
    }
  } catch (error) {
    console.error('Product search failed', error)
    searchError.value = 'Unable to search products right now'
  } finally {
    isSearching.value = false
  }
}

function findExactMatch(query: string, results: CachedProduct[]) {
  const normalized = query.trim().toUpperCase()
  return results.find(
    (product) =>
      product.sku?.toUpperCase() === normalized ||
      product.barcode?.toUpperCase() === normalized ||
      product.productName.toUpperCase() === normalized
  )
}

function addProductToCart(product: CachedProduct) {
  posStore.addToCart(product, 1)
  toast.add({
    severity: 'success',
    summary: 'Added to Cart',
    detail: product.productName,
    life: 2000,
  })
  searchResults.value = []
  searchQuery.value = ''
  searchError.value = ''
}

function incrementQty(item: (typeof cartItems.value)[number]) {
  posStore.updateQuantity(item.variantId, item.quantity + 1)
}

function decrementQty(item: (typeof cartItems.value)[number]) {
  if (item.quantity > 1) {
    posStore.updateQuantity(item.variantId, item.quantity - 1)
  }
}

function removeItem(item: (typeof cartItems.value)[number]) {
  posStore.removeFromCart(item.variantId)
}

function clearCart() {
  posStore.clearCart()
  paymentAmountEdited.value = false
  paymentAmount.value = ''
  paymentReference.value = ''
  paymentMethod.value = 'cash'
  paymentError.value = ''
}

function handlePaymentAmountInput() {
  paymentAmountEdited.value = true
}

function formatMethod(method: string) {
  switch (method) {
    case 'card':
      return 'Card'
    case 'store_credit':
      return 'Store Credit'
    case 'gift_card':
      return 'Gift Card'
    default:
      return 'Cash'
  }
}

function validatePayment(amount: number) {
  if (amountDue.value <= 0) {
    paymentError.value = 'Balance already settled'
    return false
  }

  if (Number.isNaN(amount) || amount <= 0) {
    paymentError.value = 'Enter a valid payment amount'
    return false
  }

  const outstanding = amountDue.value
  if (paymentMethod.value !== 'cash' && amount > outstanding) {
    paymentError.value = 'This tender cannot exceed the amount due'
    return false
  }

  if (paymentMethod.value !== 'cash' && !paymentReference.value.trim()) {
    paymentError.value = 'Reference is required for this tender type'
    return false
  }

  if (paymentMethod.value === 'store_credit' && !customer.value) {
    paymentError.value = 'Lookup customer before applying store credit'
    return false
  }

  return true
}

function recordPayment() {
  paymentError.value = ''
  const amount = Number.parseFloat(paymentAmount.value)
  if (!validatePayment(amount)) {
    return
  }

  posStore.addPayment({
    method: paymentMethod.value,
    amount,
    reference: paymentReference.value.trim() || undefined,
  })

  paymentReference.value = ''
  paymentAmountEdited.value = false
  paymentAmount.value = amountDue.value > 0 ? amountDue.value.toFixed(2) : ''
}

function removePayment(paymentId: string) {
  posStore.removePayment(paymentId)
  paymentAmountEdited.value = false
  paymentAmount.value = amountDue.value > 0 ? amountDue.value.toFixed(2) : ''
}

async function completeSale() {
  if (!pairedDevice.value || !cartHasItems.value || !canCompleteSale.value) {
    return
  }

  isProcessing.value = true

  try {
    const transaction = {
      localTransactionId: uuidv4(),
      totalAmount: total.value,
      currency: 'USD',
      customerId: customer.value?.id,
      customer: customer.value ?? undefined,
      paymentMethodId: payments.value[0]?.method,
      payments: payments.value.map((payment) => ({ ...payment })),
      amountTendered: amountTendered.value,
      amountDue: amountDue.value,
      changeDue: changeDue.value,
      taxAmount: tax.value,
      discountAmount: discountAmount.value,
      items: cartItems.value.map((i) => ({
        productId: i.productId,
        variantId: i.variantId,
        quantity: i.quantity,
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

    posStore.clearCart()
    paymentAmountEdited.value = false
    paymentAmount.value = ''
    paymentReference.value = ''
    paymentMethod.value = 'cash'
    paymentError.value = ''
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

function formatCurrency(amount: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
  }).format(amount || 0)
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

.tender-helper {
  margin-top: 1rem;
}

.error-text {
  color: var(--red-600, #dc2626);
  margin: 0.25rem 0;
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

.hardware-footer {
  border: 1px solid var(--surface-border);
  border-radius: 0.75rem;
  background: var(--surface-card);
}

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
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.search-input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  font-size: 1rem;
}

.search-status {
  font-size: 0.85rem;
  color: var(--text-color-secondary);
  display: inline-flex;
  gap: 0.4rem;
  align-items: center;
}

.search-status.error {
  color: var(--red-600);
}

.search-results {
  max-height: 220px;
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
  font-weight: 600;
  display: block;
}

.product-sku,
.product-barcode {
  display: block;
  font-size: 0.75rem;
  color: var(--text-color-secondary);
}

.product-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.25rem;
  text-align: right;
}

.inventory-badge {
  background: var(--surface-100);
  border-radius: 9999px;
  padding: 0.125rem 0.5rem;
  font-size: 0.75rem;
}

.product-price {
  color: var(--primary-color, #2563eb);
  font-weight: 600;
}

.search-empty {
  padding: 1rem;
  border-radius: 0.5rem;
  background: var(--surface-100);
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

.item-details {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.item-name {
  font-weight: 600;
}

.item-sku {
  font-size: 0.8rem;
  color: var(--text-color-secondary);
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

.cart-breakdown {
  margin-top: 1rem;
  border-top: 1px solid var(--surface-border);
  padding-top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.breakdown-row {
  display: flex;
  justify-content: space-between;
  font-size: 1rem;
}

.total-row {
  font-size: 1.25rem;
  font-weight: 700;
}

.tender-layout {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.tender-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.summary-row {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.75rem;
  border: 1px solid var(--surface-border);
  border-radius: 0.5rem;
  background: var(--surface-0);
}

.payment-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.payment-pill {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  border-radius: 9999px;
  background: var(--surface-100);
  border: 1px solid var(--surface-border);
}

.payment-method {
  font-weight: 600;
  margin-right: 0.5rem;
}

.payment-reference {
  margin-left: 0.75rem;
  font-size: 0.75rem;
  color: var(--text-color-secondary);
}

.tender-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
  margin: 1rem 0;
}

.tender-form label {
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.payment-select,
.tender-form input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--surface-border, #d1d5db);
  border-radius: 0.5rem;
  font-size: 1rem;
}

.payment-amount-input {
  font-variant-numeric: tabular-nums;
}

.btn-large {
  width: 100%;
  min-height: 56px;
  font-size: 1.125rem;
  font-weight: 700;
}

button {
  min-height: 48px;
}

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

  .tender-form {
    grid-template-columns: 1fr;
  }
}
</style>
