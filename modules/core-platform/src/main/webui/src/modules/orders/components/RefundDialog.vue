<template>
  <Dialog
    :visible="isOpen"
    modal
    :header="t('orders.refundDialog.title')"
    :style="{ width: '32rem' }"
    @update:visible="handleClose"
  >
    <template #default>
      <div class="refund-dialog-content">
        <!-- Order Summary -->
        <div class="order-summary">
          <div class="summary-row">
            <span class="label">{{ t('orders.refundDialog.orderNumber') }}</span>
            <span class="value">{{ order?.orderNumber }}</span>
          </div>
          <div class="summary-row">
            <span class="label">{{ t('orders.refundDialog.orderTotal') }}</span>
            <span class="value">{{ formatMoney(order?.total) }}</span>
          </div>
          <div v-if="refundedAmount > 0" class="summary-row">
            <span class="label">{{ t('orders.refundDialog.alreadyRefunded') }}</span>
            <span class="value text-warning-600">-{{ formatAmount(refundedAmount) }}</span>
          </div>
          <div class="summary-row font-semibold">
            <span class="label">{{ t('orders.refundDialog.availableToRefund') }}</span>
            <span class="value">{{ formatAmount(availableAmount) }}</span>
          </div>
        </div>

        <!-- Refund Amount Input -->
        <div class="form-field">
          <label for="refund-amount" class="field-label">
            {{ t('orders.refundDialog.refundAmount') }}
            <span class="text-error-500">*</span>
          </label>
          <div class="amount-input-wrapper">
            <span class="currency-symbol">{{ currencySymbol }}</span>
            <input
              id="refund-amount"
              v-model.number="refundAmount"
              type="number"
              :min="0.01"
              :max="availableAmount / 100"
              step="0.01"
              class="amount-input"
              :class="{ error: amountError }"
              :aria-invalid="!!amountError"
              :aria-describedby="amountError ? 'amount-error' : undefined"
              @input="validateAmount"
            />
          </div>
          <p v-if="amountError" id="amount-error" class="field-error">{{ amountError }}</p>
          <div class="quick-amounts">
            <button
              v-if="availableAmount > 0"
              class="quick-amount-btn"
              @click="setQuickAmount(availableAmount)"
            >
              {{ t('orders.refundDialog.fullRefund') }}
            </button>
            <button
              v-if="availableAmount >= 2000"
              class="quick-amount-btn"
              @click="setQuickAmount(Math.floor(availableAmount / 2))"
            >
              50%
            </button>
          </div>
        </div>

        <!-- Refund Reason -->
        <div class="form-field">
          <label for="refund-reason" class="field-label">
            {{ t('orders.refundDialog.reason') }}
            <span class="text-error-500">*</span>
          </label>
          <select
            id="refund-reason"
            v-model="refundReason"
            class="field-select"
            :aria-invalid="!!reasonError"
            :aria-describedby="reasonError ? 'reason-error' : undefined"
            @change="validateReason"
          >
            <option value="">{{ t('orders.refundDialog.selectReason') }}</option>
            <option value="customer_request">
              {{ t('orders.refundDialog.reasons.customerRequest') }}
            </option>
            <option value="defective_product">
              {{ t('orders.refundDialog.reasons.defectiveProduct') }}
            </option>
            <option value="wrong_item">{{ t('orders.refundDialog.reasons.wrongItem') }}</option>
            <option value="not_as_described">
              {{ t('orders.refundDialog.reasons.notAsDescribed') }}
            </option>
            <option value="duplicate_order">
              {{ t('orders.refundDialog.reasons.duplicateOrder') }}
            </option>
            <option value="other">{{ t('orders.refundDialog.reasons.other') }}</option>
          </select>
          <p v-if="reasonError" id="reason-error" class="field-error">{{ reasonError }}</p>
        </div>

        <!-- Additional Notes -->
        <div class="form-field">
          <label for="refund-notes" class="field-label">
            {{ t('orders.refundDialog.notes') }}
            <span class="text-neutral-500 text-sm font-normal"> ({{ t('common.optional') }}) </span>
          </label>
          <textarea
            id="refund-notes"
            v-model="notes"
            rows="3"
            class="field-textarea"
            :placeholder="t('orders.refundDialog.notesPlaceholder')"
            :maxlength="500"
          />
          <p class="field-hint">{{ notes.length }}/500</p>
        </div>

        <!-- Warning Messages -->
        <InlineAlert
          v-if="isFullRefund"
          tone="warning"
          :title="t('orders.refundDialog.warnings.fullRefundTitle')"
          :description="t('orders.refundDialog.warnings.fullRefundMessage')"
        />
        <InlineAlert
          v-if="!canRefund"
          tone="error"
          :title="t('orders.refundDialog.errors.cannotRefund')"
          :description="cannotRefundReason"
        />
      </div>
    </template>

    <template #footer>
      <div class="dialog-footer">
        <Button :label="t('common.cancel')" text :disabled="isProcessing" @click="handleClose" />
        <Button
          :label="
            isProcessing
              ? t('orders.refundDialog.processing')
              : t('orders.refundDialog.confirmRefund')
          "
          :disabled="!isFormValid || isProcessing"
          :loading="isProcessing"
          severity="danger"
          @click="handleSubmit"
        />
      </div>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import { useI18n } from '@/composables/useI18n'
import InlineAlert from '@/components/base/InlineAlert.vue'
import type { OrderDetail, Money } from '../types'

const props = defineProps<{
  isOpen: boolean
  order: OrderDetail | null
  isProcessing?: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [amount: number, reason: string, notes: string]
}>()

const { t } = useI18n()

// Form state
const refundAmount = ref(0)
const refundReason = ref('')
const notes = ref('')
const amountError = ref('')
const reasonError = ref('')

// Computed
const refundedAmount = computed(() => {
  // Calculate total already refunded from timeline/metadata
  if (!props.order?.timeline) return 0

  return props.order.timeline
    .filter((event) => event.type.includes('refund'))
    .reduce((sum, event) => {
      const amount = event.metadata?.refundAmount || 0
      return sum + amount
    }, 0)
})

const availableAmount = computed(() => {
  if (!props.order?.total) return 0
  const totalCents = props.order.total.amount
  return Math.max(0, totalCents - refundedAmount.value)
})

const currencySymbol = computed(() => {
  const currency = props.order?.total?.currency || 'USD'
  return (
    new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
    })
      .formatToParts(0)
      .find((part) => part.type === 'currency')?.value || '$'
  )
})

const isFullRefund = computed(() => {
  if (!refundAmount.value || !availableAmount.value) return false
  const amountCents = Math.round(refundAmount.value * 100)
  return amountCents === availableAmount.value
})

const canRefund = computed(() => {
  if (!props.order) return false
  if (availableAmount.value <= 0) return false
  if (props.order.status === 'CANCELLED' && refundedAmount.value === 0) return false
  return true
})

const cannotRefundReason = computed(() => {
  if (!props.order) return t('orders.refundDialog.errors.noOrder')
  if (availableAmount.value <= 0) return t('orders.refundDialog.errors.alreadyFullyRefunded')
  if (props.order.status === 'CANCELLED') return t('orders.refundDialog.errors.orderCancelled')
  return ''
})

const isFormValid = computed(() => {
  return (
    canRefund.value &&
    refundAmount.value > 0 &&
    !amountError.value &&
    refundReason.value &&
    !reasonError.value
  )
})

// Methods
function formatMoney(money: Money | undefined): string {
  if (!money) return '-'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: money.currency,
  }).format(money.amount / 100)
}

function formatAmount(cents: number): string {
  const currency = props.order?.total?.currency || 'USD'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
  }).format(cents / 100)
}

function setQuickAmount(cents: number): void {
  refundAmount.value = cents / 100
  validateAmount()
}

function validateAmount(): void {
  amountError.value = ''

  if (!refundAmount.value || refundAmount.value <= 0) {
    amountError.value = t('orders.refundDialog.errors.amountRequired')
    return
  }

  const amountCents = Math.round(refundAmount.value * 100)

  if (amountCents > availableAmount.value) {
    amountError.value = t('orders.refundDialog.errors.amountTooHigh', {
      max: formatAmount(availableAmount.value),
    })
    return
  }

  if (amountCents < 50) {
    // Stripe minimum is $0.50
    amountError.value = t('orders.refundDialog.errors.amountTooLow')
    return
  }
}

function validateReason(): void {
  reasonError.value = ''
  if (!refundReason.value) {
    reasonError.value = t('orders.refundDialog.errors.reasonRequired')
  }
}

function handleClose(): void {
  emit('close')
}

function handleSubmit(): void {
  // Final validation
  validateAmount()
  validateReason()

  if (!isFormValid.value) {
    return
  }

  const amountCents = Math.round(refundAmount.value * 100)
  emit('confirm', amountCents, refundReason.value, notes.value)
}

// Reset form when dialog opens
watch(
  () => props.isOpen,
  (isOpen) => {
    if (isOpen && props.order) {
      refundAmount.value = 0
      refundReason.value = ''
      notes.value = ''
      amountError.value = ''
      reasonError.value = ''
    }
  }
)
</script>

<style scoped>
.refund-dialog-content {
  @apply space-y-6;
}

.order-summary {
  @apply bg-neutral-50 rounded-lg p-4 border border-neutral-200 space-y-2;
}

.summary-row {
  @apply flex justify-between text-sm;
}

.summary-row .label {
  @apply text-neutral-600;
}

.summary-row .value {
  @apply font-medium text-neutral-900;
}

.form-field {
  @apply space-y-2;
}

.field-label {
  @apply block text-sm font-medium text-neutral-700;
}

.amount-input-wrapper {
  @apply relative;
}

.currency-symbol {
  @apply absolute left-3 top-1/2 -translate-y-1/2 text-neutral-500;
}

.amount-input {
  @apply w-full pl-8 pr-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent;
}

.amount-input.error {
  @apply border-error-500 focus:ring-error-500;
}

.quick-amounts {
  @apply flex gap-2 mt-2;
}

.quick-amount-btn {
  @apply px-3 py-1.5 text-sm text-primary-700 bg-primary-50 hover:bg-primary-100 rounded border border-primary-200 transition-colors;
}

.field-select {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500;
}

.field-textarea {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 resize-none;
}

.field-error {
  @apply text-sm text-error-600;
}

.field-hint {
  @apply text-xs text-neutral-500;
}

.dialog-footer {
  @apply flex justify-end gap-3;
}
</style>
