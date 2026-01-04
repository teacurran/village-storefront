<template>
  <div v-if="isOpen" class="modal-overlay" @click.self="emit('close')">
    <div
      class="modal-content"
      ref="modalRef"
      role="dialog"
      aria-labelledby="modal-title"
      aria-modal="true"
    >
      <div class="modal-header">
        <h2 id="modal-title" class="modal-title">
          {{ t('consignor.payout.requestTitle') }}
        </h2>
        <button
          class="modal-close-btn"
          @click="emit('close')"
          :aria-label="t('common.close')"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      </div>

      <form @submit.prevent="handleSubmit" class="modal-body">
        <div class="form-group">
          <label for="amount" class="form-label">
            {{ t('consignor.payout.amount') }}
            <span class="text-error-600">*</span>
          </label>
          <div class="amount-input-wrapper">
            <span class="amount-currency">$</span>
            <input
              id="amount"
              ref="amountInputRef"
              v-model.number="formData.amountDollars"
              type="number"
              step="0.01"
              :min="minAmountDollars"
              :max="maxAmount"
              required
              class="form-input pl-8"
              :aria-describedby="amountError ? 'amount-error' : undefined"
            />
          </div>
          <p v-if="amountError" id="amount-error" class="form-error">
            {{ amountError }}
          </p>
          <p class="form-hint">
            {{ t('consignor.payout.availableBalance', { amount: availableBalanceLabel }) }}
          </p>
        </div>

        <div class="form-group">
          <label for="method" class="form-label">
            {{ t('consignor.payout.method') }}
            <span class="text-error-600">*</span>
          </label>
          <select id="method" v-model="formData.method" required class="form-select">
            <option value="">{{ t('consignor.payout.selectMethod') }}</option>
            <option value="CHECK">{{ t('consignor.payout.methods.check') }}</option>
            <option value="ACH">{{ t('consignor.payout.methods.ach') }}</option>
            <option value="PAYPAL">{{ t('consignor.payout.methods.paypal') }}</option>
            <option value="STORE_CREDIT">{{ t('consignor.payout.methods.storeCredit') }}</option>
          </select>
        </div>

        <div class="form-group">
          <label for="notes" class="form-label">
            {{ t('consignor.payout.notes') }}
          </label>
          <textarea
            id="notes"
            v-model="formData.notes"
            rows="3"
            class="form-textarea"
            :placeholder="t('consignor.payout.notesPlaceholder')"
          />
        </div>

        <div class="form-summary">
          <div class="summary-row">
            <span class="summary-label">{{ t('consignor.payout.requestAmount') }}</span>
            <span class="summary-value">{{ formattedAmount }}</span>
          </div>
          <div class="summary-row">
            <span class="summary-label">{{ t('consignor.payout.method') }}</span>
            <span class="summary-value">{{ formData.method || '—' }}</span>
          </div>
        </div>

        <div class="modal-footer">
          <button type="button" class="btn-secondary" @click="emit('close')">
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            class="btn-primary"
            :disabled="!isFormValid || submitting"
          >
            <span v-if="submitting" class="inline-flex items-center gap-2">
              <div class="spinner-sm" />
              {{ t('consignor.payout.submitting') }}
            </span>
            <span v-else>{{ t('consignor.payout.submit') }}</span>
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { Money } from '@/api/types'
import { MIN_PAYOUT_CENTS } from '../constants'

const props = defineProps<{
  isOpen: boolean
  availableBalance: Money
}>()

const emit = defineEmits<{
  close: []
  submit: [payload: { amountCents: number; method: string; notes?: string }]
}>()

const { t, formatCurrency } = useI18n()

const formData = ref({
  amountDollars: 0,
  method: '',
  notes: '',
})

const submitting = ref(false)
const modalRef = ref<HTMLDivElement | null>(null)
const amountInputRef = ref<HTMLInputElement | null>(null)

const minAmountDollars = MIN_PAYOUT_CENTS / 100
const maxAmount = computed(() => props.availableBalance.amount / 100)

const formattedAmount = computed(() => {
  if (!formData.value.amountDollars) {
    return formatCurrency({
      amount: 0,
      currency: props.availableBalance.currency,
    })
  }
  return formatCurrency({
    amount: Math.round(formData.value.amountDollars * 100),
    currency: props.availableBalance.currency,
  })
})

const amountError = computed(() => {
  const amount = formData.value.amountDollars
  if (!amount) return null

  const amountInCents = Math.round(amount * 100)
  if (amountInCents < MIN_PAYOUT_CENTS) {
    return t('consignor.payout.errors.minimumAmount', {
      amount: formatCurrency({
        amount: MIN_PAYOUT_CENTS,
        currency: props.availableBalance.currency,
      }),
    })
  }
  if (amountInCents > props.availableBalance.amount) {
    return t('consignor.payout.errors.exceedsBalance', {
      amount: formatCurrency(props.availableBalance),
    })
  }

  return null
})

const isFormValid = computed(() => {
  return (
    formData.value.amountDollars * 100 >= MIN_PAYOUT_CENTS &&
    formData.value.amountDollars <= maxAmount.value &&
    formData.value.method !== '' &&
    !amountError.value
  )
})

const availableBalanceLabel = computed(() => formatCurrency(props.availableBalance))

watch(
  () => props.isOpen,
  async (isOpen) => {
    if (isOpen) {
      await nextTick()
      amountInputRef.value?.focus()
      if (typeof window !== 'undefined') {
        window.addEventListener('keydown', handleKeydown)
      }
    } else if (typeof window !== 'undefined') {
      window.removeEventListener('keydown', handleKeydown)
    }
  }
)

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', handleKeydown)
  }
})

function handleKeydown(event: KeyboardEvent) {
  if (!props.isOpen) return

  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }

  if (event.key === 'Tab') {
    trapFocus(event)
  }
}

function trapFocus(event: KeyboardEvent) {
  if (!modalRef.value || typeof document === 'undefined') return
  const focusable = modalRef.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])'
  )
  if (focusable.length === 0) return

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const activeElement = document.activeElement as HTMLElement | null

  if (event.shiftKey && activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function handleSubmit() {
  if (!isFormValid.value || submitting.value) return

  submitting.value = true

  try {
    emit('submit', {
      amountCents: Math.round(formData.value.amountDollars * 100),
      method: formData.value.method,
      notes: formData.value.notes || undefined,
    })

    // Reset form
    formData.value = {
      amountDollars: 0,
      method: '',
      notes: '',
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.modal-overlay {
  @apply fixed inset-0 bg-neutral-900 bg-opacity-50 flex items-center justify-center z-50 p-4;
}

.modal-content {
  @apply bg-white rounded-lg shadow-strong max-w-lg w-full max-h-[90vh] overflow-y-auto;
}

.modal-header {
  @apply p-6 border-b border-neutral-200 flex items-center justify-between;
}

.modal-title {
  @apply text-xl font-semibold text-neutral-900;
}

.modal-close-btn {
  @apply p-1 text-neutral-400 hover:text-neutral-600 rounded-md hover:bg-neutral-100 transition-colors;
}

.modal-body {
  @apply p-6 space-y-6;
}

.form-group {
  @apply space-y-2;
}

.form-label {
  @apply block text-sm font-medium text-neutral-700;
}

.amount-input-wrapper {
  @apply relative;
}

.amount-currency {
  @apply absolute left-3 top-1/2 -translate-y-1/2 text-neutral-600 font-medium;
}

.form-input {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-primary-500;
}

.form-select {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-primary-500;
}

.form-textarea {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-primary-500 resize-none;
}

.form-hint {
  @apply text-sm text-neutral-600;
}

.form-error {
  @apply text-sm text-error-600;
}

.form-summary {
  @apply bg-neutral-50 rounded-md p-4 space-y-2 border border-neutral-200;
}

.summary-row {
  @apply flex items-center justify-between;
}

.summary-label {
  @apply text-sm font-medium text-neutral-700;
}

.summary-value {
  @apply text-sm font-semibold text-neutral-900;
}

.modal-footer {
  @apply flex items-center justify-end gap-3;
}

.btn-primary {
  @apply px-4 py-2 bg-primary-600 text-white rounded-md font-medium hover:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed;
}

.btn-secondary {
  @apply px-4 py-2 bg-neutral-100 text-neutral-700 rounded-md font-medium hover:bg-neutral-200 transition-colors;
}

.spinner-sm {
  @apply w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin;
}
</style>
