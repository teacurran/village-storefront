<template>
  <div v-if="isOpen" class="modal-overlay" @click="$emit('close')">
    <div class="modal-content" @click.stop>
      <h2 class="modal-title">{{ t('orders.bulkActions.updateStatus') }}</h2>
      <p class="modal-description">
        {{ t('orders.bulkActions.updateDescription', { count: selectedCount }) }}
      </p>
      <select v-model="selectedStatus" class="status-select">
        <option value="CONFIRMED">{{ t('orders.status.confirmed') }}</option>
        <option value="PROCESSING">{{ t('orders.status.processing') }}</option>
        <option value="SHIPPED">{{ t('orders.status.shipped') }}</option>
        <option value="DELIVERED">{{ t('orders.status.delivered') }}</option>
        <option value="CANCELLED">{{ t('orders.status.cancelled') }}</option>
      </select>
      <div class="modal-actions">
        <button @click="$emit('close')" class="btn-secondary">{{ t('common.cancel') }}</button>
        <button @click="handleConfirm" class="btn-primary">{{ t('common.confirm') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '@/composables/useI18n'
import type { OrderStatus } from '../types'

defineProps<{
  isOpen: boolean
  selectedCount: number
}>()

const emit = defineEmits<{
  close: []
  confirm: [status: OrderStatus]
}>()

const { t } = useI18n()
const selectedStatus = ref<OrderStatus>('CONFIRMED')

function handleConfirm() {
  emit('confirm', selectedStatus.value)
}
</script>

<style scoped>
.modal-overlay {
  @apply fixed inset-0 z-50 bg-black bg-opacity-50 flex items-center justify-center p-4;
}

.modal-content {
  @apply bg-white rounded-lg p-6 max-w-md w-full space-y-4;
}

.modal-title {
  @apply text-xl font-bold text-neutral-900;
}

.modal-description {
  @apply text-neutral-600;
}

.status-select {
  @apply w-full px-4 py-2 border border-neutral-300 rounded-md;
}

.modal-actions {
  @apply flex justify-end gap-3;
}

.btn-primary {
  @apply px-4 py-2 bg-primary-600 text-white rounded-md font-medium hover:bg-primary-700;
}

.btn-secondary {
  @apply px-4 py-2 bg-neutral-100 text-neutral-700 rounded-md font-medium hover:bg-neutral-200;
}
</style>
