<template>
  <Sidebar
    :visible="isOpen"
    position="right"
    class="order-detail-panel"
    :show-close-icon="false"
    @hide="emit('close')"
  >
    <div class="panel-header">
      <div>
        <p class="panel-eyebrow">{{ t('orders.detailDrawer.orderLabel') }}</p>
        <h2 class="panel-title">{{ order?.orderNumber }}</h2>
      </div>
      <Button
        icon="pi pi-times"
        class="p-button-text"
        :aria-label="t('common.close')"
        @click="emit('close')"
      />
    </div>

    <div v-if="!order" class="panel-empty">
      <p>{{ t('orders.detailDrawer.empty') }}</p>
    </div>

    <div v-else class="panel-content">
      <section class="detail-section">
        <div class="detail-row">
          <span class="label">{{ t('orders.table.customer') }}</span>
          <span class="value">{{ order.customerName }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('orders.detailDrawer.status') }}</span>
          <Tag
            :value="t(`orders.status.${order.status.toLowerCase()}`)"
            :severity="statusTone(order.status)"
          />
        </div>
        <div class="detail-row">
          <span class="label">{{ t('orders.detailDrawer.total') }}</span>
          <span class="value">{{ formatMoney(order.total) }}</span>
        </div>
        <div class="detail-row">
          <span class="label">{{ t('orders.detailDrawer.paymentMethod') }}</span>
          <span class="value">{{ order.paymentMethod }}</span>
        </div>
      </section>

      <section class="detail-section">
        <header class="section-header">
          <h3>{{ t('orders.detailDrawer.lineItems') }}</h3>
          <span class="section-count">{{ order.lineItems.length }}</span>
        </header>
        <ul class="line-items">
          <li v-for="line in order.lineItems" :key="line.id" class="line-item">
            <div>
              <p class="line-name">{{ line.name }}</p>
              <p class="line-meta">
                SKU {{ line.sku }} · {{ line.quantity }} × {{ formatMoney(line.unitPrice) }}
              </p>
            </div>
            <span>{{ formatMoney(line.total) }}</span>
          </li>
        </ul>
      </section>

      <section v-if="order.timeline?.length" class="detail-section">
        <OrderTimeline :events="order.timeline" :show-actor="true" :show-filter="true" />
      </section>

      <section class="detail-section actions">
        <header class="section-header">
          <h3>{{ t('orders.detailDrawer.actions') }}</h3>
        </header>
        <div class="action-grid">
          <Button
            v-if="canEdit && canCapturePayment"
            icon="pi pi-wallet"
            class="p-button-success"
            :label="t('orders.actions.capturePayment')"
            @click="handleCapturePayment"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-check"
            class="p-button-success"
            :label="t('orders.actions.markProcessing')"
            @click="() => emit('updateStatus', 'PROCESSING')"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-send"
            class="p-button"
            :label="t('orders.actions.markShipped')"
            @click="() => emit('updateStatus', 'SHIPPED')"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-check-circle"
            class="p-button"
            :label="t('orders.actions.markDelivered')"
            @click="() => emit('updateStatus', 'DELIVERED')"
          />
          <Button
            v-if="canEdit && canRefund"
            icon="pi pi-replay"
            class="p-button-outlined p-button-warning"
            :label="t('orders.actions.refundOrder')"
            @click="showRefundDialog = true"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-ban"
            class="p-button-outlined p-button-danger"
            :label="t('orders.actions.cancelOrder')"
            @click="() => emit('cancel')"
          />
          <Button
            v-if="canEdit"
            icon="pi pi-comment"
            class="p-button-outlined"
            :label="t('orders.actions.addNote')"
            @click="showNoteDialog = true"
          />
        </div>
      </section>
    </div>

    <!-- Refund Dialog -->
    <RefundDialog
      :is-open="showRefundDialog"
      :order="order"
      :is-processing="isRefundProcessing"
      @close="showRefundDialog = false"
      @confirm="handleRefund"
    />

    <!-- Note Dialog -->
    <Dialog
      :visible="showNoteDialog"
      modal
      :header="t('orders.noteDialog.title')"
      :style="{ width: '24rem' }"
      @update:visible="(val) => (showNoteDialog = val)"
    >
      <div class="note-dialog-content">
        <textarea
          v-model="noteText"
          rows="4"
          class="note-textarea"
          :placeholder="t('orders.noteDialog.placeholder')"
          :maxlength="500"
        />
        <p class="note-hint">{{ noteText.length }}/500</p>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <Button :label="t('common.cancel')" text @click="showNoteDialog = false" />
          <Button
            :label="t('orders.noteDialog.addNote')"
            :disabled="!noteText.trim()"
            @click="handleAddNote"
          />
        </div>
      </template>
    </Dialog>
  </Sidebar>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import Sidebar from 'primevue/sidebar'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Dialog from 'primevue/dialog'
import { useI18n } from '@/composables/useI18n'
import OrderTimeline from './OrderTimeline.vue'
import RefundDialog from './RefundDialog.vue'
import type { OrderDetail, OrderStatus } from '../types'
import type { Money } from '@/api/types'

const props = defineProps<{
  order: OrderDetail | null
  isOpen: boolean
  canEdit: boolean
}>()

const emit = defineEmits<{
  close: []
  updateStatus: [status: OrderStatus]
  cancel: []
  refund: [amount: number, reason: string, notes: string]
  capturePayment: [paymentIntentId: string]
  addNote: [note: string]
}>()

const { t } = useI18n()

const showRefundDialog = ref(false)
const showNoteDialog = ref(false)
const noteText = ref('')
const isRefundProcessing = ref(false)

const canRefund = computed(() => {
  if (!props.order) return false
  // Can refund if order is confirmed/processing/shipped/delivered and not fully refunded
  return (
    ['CONFIRMED', 'PROCESSING', 'SHIPPED', 'DELIVERED'].includes(props.order.status) &&
    props.order.total.amount > 0
  )
})

const canCapturePayment = computed(() => {
  if (!props.order) return false
  // Check if order has an uncaptured payment intent
  const hasUncapturedPayment = props.order.timeline?.some(
    (event) =>
      event.type === 'payment.authorized' &&
      !props.order?.timeline?.some((e) => e.type === 'payment.captured')
  )
  return hasUncapturedPayment && props.order.status === 'PENDING'
})

function formatMoney(money: Money | undefined) {
  if (!money) return '-'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: money.currency,
  }).format(money.amount / 100)
}

function statusTone(status: OrderStatus) {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'CONFIRMED':
    case 'PROCESSING':
      return 'info'
    case 'SHIPPED':
      return 'info'
    case 'DELIVERED':
      return 'success'
    case 'CANCELLED':
    case 'REFUNDED':
      return 'danger'
    default:
      return 'info'
  }
}

function formatDate(date: string) {
  return new Date(date).toLocaleString()
}

async function handleRefund(amount: number, reason: string, notes: string) {
  isRefundProcessing.value = true
  try {
    emit('refund', amount, reason, notes)
    showRefundDialog.value = false
  } finally {
    isRefundProcessing.value = false
  }
}

function handleCapturePayment() {
  if (!props.order) return
  // Find payment intent ID from order metadata or timeline
  const paymentIntentId = props.order.timeline?.find((e) => e.type === 'payment.authorized')
    ?.metadata?.paymentIntentId

  if (paymentIntentId) {
    emit('capturePayment', paymentIntentId)
  }
}

function handleAddNote() {
  if (!noteText.value.trim()) return
  emit('addNote', noteText.value.trim())
  noteText.value = ''
  showNoteDialog.value = false
}

const isOpen = computed(() => props.isOpen)
</script>

<style scoped>
.order-detail-panel {
  --p-sidebar-width: min(480px, 100%);
}

.panel-header {
  @apply flex items-start justify-between mb-6;
}

.panel-eyebrow {
  @apply text-xs uppercase tracking-wide text-neutral-500;
}

.panel-title {
  @apply text-2xl font-semibold text-neutral-900;
}

.panel-empty {
  @apply text-neutral-500;
}

.panel-content {
  @apply space-y-6;
}

.detail-section {
  @apply bg-neutral-50 rounded-lg p-4 border border-neutral-200;
}

.detail-row {
  @apply flex justify-between text-sm mb-3;
}

.detail-row .label {
  @apply text-neutral-500;
}

.detail-row .value {
  @apply font-medium text-neutral-900;
}

.section-header {
  @apply flex items-center justify-between mb-3;
}

.section-count {
  @apply text-xs text-neutral-500;
}

.line-items {
  @apply divide-y divide-neutral-200;
}

.line-item {
  @apply py-3 flex items-center justify-between;
}

.line-name {
  @apply font-medium text-neutral-900;
}

.line-meta {
  @apply text-xs text-neutral-500;
}

.timeline {
  @apply space-y-4;
}

.timeline-entry {
  @apply flex gap-3;
}

.timeline-marker {
  @apply w-2 h-2 rounded-full bg-primary-500 mt-2;
}

.timeline-title {
  @apply text-sm font-medium text-neutral-900;
}

.timeline-meta {
  @apply text-xs text-neutral-500;
}

.action-grid {
  @apply grid gap-3;
}

.note-dialog-content {
  @apply space-y-3;
}

.note-textarea {
  @apply w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 resize-none;
}

.note-hint {
  @apply text-xs text-neutral-500;
}

.dialog-footer {
  @apply flex justify-end gap-3;
}
</style>
