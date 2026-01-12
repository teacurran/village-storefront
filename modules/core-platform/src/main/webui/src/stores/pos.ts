/**
 * Pinia store for POS cart and tender management.
 *
 * Manages cart items, customer selection, split payments, and tender workflows.
 *
 * References:
 * - Architecture: §2.15 POS-Specific Components
 * - Task I4.T5: POS cart and split payment logic
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { CachedProduct } from '@/modules/pos/offline/offlineDB'

export interface CartLineItem {
  variantId: string
  productId: string
  productName: string
  sku: string
  price: number
  quantity: number
  subtotal: number
}

export interface PaymentEntry {
  id: string
  method: 'cash' | 'card' | 'store_credit' | 'gift_card'
  amount: number
  reference?: string // Card last 4, gift card number, etc.
  timestamp: string
}

export interface Customer {
  id: string
  name: string
  email?: string
  loyaltyPoints?: number
  storeCreditBalance?: number
}

export const usePOSStore = defineStore('pos', () => {
  // State
  const cart = ref<CartLineItem[]>([])
  const customer = ref<Customer | null>(null)
  const payments = ref<PaymentEntry[]>([])
  const discount = ref<{ type: 'percentage' | 'fixed'; value: number } | null>(null)

  // Computed
  const subtotal = computed(() => cart.value.reduce((sum, item) => sum + item.subtotal, 0))

  const discountAmount = computed(() => {
    if (!discount.value) return 0
    if (discount.value.type === 'percentage') {
      return subtotal.value * (discount.value.value / 100)
    }
    return discount.value.value
  })

  const tax = computed(() => {
    const taxableAmount = subtotal.value - discountAmount.value
    return taxableAmount * 0.08 // TODO: Get from tenant settings
  })

  const total = computed(() => subtotal.value - discountAmount.value + tax.value)

  const amountTendered = computed(() =>
    payments.value.reduce((sum, payment) => sum + payment.amount, 0)
  )

  const amountDue = computed(() => Math.max(0, total.value - amountTendered.value))

  const changeDue = computed(() => Math.max(0, amountTendered.value - total.value))

  const canComplete = computed(() => amountTendered.value >= total.value && cart.value.length > 0)

  // Actions

  /**
   * Add product to cart.
   */
  function addToCart(product: CachedProduct, quantity = 1) {
    const existing = cart.value.find((item) => item.variantId === product.variantId)

    if (existing) {
      existing.quantity += quantity
      existing.subtotal = existing.price * existing.quantity
    } else {
      cart.value.push({
        variantId: product.variantId,
        productId: product.productId,
        productName: product.productName,
        sku: product.sku,
        price: product.price,
        quantity,
        subtotal: product.price * quantity,
      })
    }
  }

  /**
   * Update line item quantity.
   */
  function updateQuantity(variantId: string, quantity: number) {
    const item = cart.value.find((i) => i.variantId === variantId)
    if (item) {
      item.quantity = Math.max(1, quantity)
      item.subtotal = item.price * item.quantity
    }
  }

  /**
   * Remove line item from cart.
   */
  function removeFromCart(variantId: string) {
    const index = cart.value.findIndex((item) => item.variantId === variantId)
    if (index > -1) {
      cart.value.splice(index, 1)
    }
  }

  /**
   * Clear entire cart.
   */
  function clearCart() {
    cart.value = []
    payments.value = []
    discount.value = null
    customer.value = null
  }

  /**
   * Set customer for transaction.
   */
  function setCustomer(customerData: Customer | null) {
    customer.value = customerData
  }

  /**
   * Apply discount to cart.
   */
  function applyDiscount(discountData: { type: 'percentage' | 'fixed'; value: number } | null) {
    discount.value = discountData
  }

  /**
   * Add payment to transaction (split tender support).
   */
  function addPayment(payment: Omit<PaymentEntry, 'id' | 'timestamp'>) {
    payments.value.push({
      ...payment,
      id: `payment-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
      timestamp: new Date().toISOString(),
    })
  }

  /**
   * Remove payment from transaction.
   */
  function removePayment(paymentId: string) {
    const index = payments.value.findIndex((p) => p.id === paymentId)
    if (index > -1) {
      payments.value.splice(index, 1)
    }
  }

  /**
   * Hold current transaction (save for later).
   */
  function holdTransaction(): string {
    const holdId = `hold-${Date.now()}`
    const holdData = {
      id: holdId,
      cart: cart.value,
      customer: customer.value,
      payments: payments.value,
      discount: discount.value,
      timestamp: new Date().toISOString(),
    }

    // Store in localStorage for now (could move to IndexedDB)
    const holds = JSON.parse(localStorage.getItem('pos.holds') || '[]')
    holds.push(holdData)
    localStorage.setItem('pos.holds', JSON.stringify(holds))

    clearCart()
    return holdId
  }

  /**
   * Retrieve held transaction.
   */
  function retrieveTransaction(holdId: string): boolean {
    const holds = JSON.parse(localStorage.getItem('pos.holds') || '[]')
    const hold = holds.find((h: any) => h.id === holdId)

    if (hold) {
      cart.value = hold.cart
      customer.value = hold.customer
      payments.value = hold.payments
      discount.value = hold.discount

      // Remove from holds
      const updatedHolds = holds.filter((h: any) => h.id !== holdId)
      localStorage.setItem('pos.holds', JSON.stringify(updatedHolds))

      return true
    }

    return false
  }

  /**
   * Get all held transactions.
   */
  function getHeldTransactions(): any[] {
    return JSON.parse(localStorage.getItem('pos.holds') || '[]')
  }

  return {
    // State
    cart,
    customer,
    payments,
    discount,

    // Computed
    subtotal,
    discountAmount,
    tax,
    total,
    amountTendered,
    amountDue,
    changeDue,
    canComplete,

    // Actions
    addToCart,
    updateQuantity,
    removeFromCart,
    clearCart,
    setCustomer,
    applyDiscount,
    addPayment,
    removePayment,
    holdTransaction,
    retrieveTransaction,
    getHeldTransactions,
  }
})
