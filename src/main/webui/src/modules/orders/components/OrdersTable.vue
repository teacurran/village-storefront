<template>
  <div class="orders-table">
    <table class="w-full">
      <thead>
        <tr class="table-header">
          <th class="checkbox-cell">
            <input type="checkbox" @change="$emit('select-all')" />
          </th>
          <th>{{ t('orders.table.orderNumber') }}</th>
          <th>{{ t('orders.table.customer') }}</th>
          <th>{{ t('orders.table.status') }}</th>
          <th>{{ t('orders.table.total') }}</th>
          <th>{{ t('orders.table.date') }}</th>
          <th>{{ t('orders.table.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="order in orders"
          :key="order.id"
          class="table-row"
          @click="$emit('view-detail', order.id)"
        >
          <td class="checkbox-cell" @click.stop>
            <input
              type="checkbox"
              :checked="selectedIds.includes(order.id)"
              @change="$emit('select', order.id)"
            />
          </td>
          <td class="font-medium">{{ order.orderNumber }}</td>
          <td>{{ order.customerName }}</td>
          <td>
            <span :class="statusClass(order.status)" class="status-badge">
              {{ t(`orders.status.${order.status.toLowerCase()}`) }}
            </span>
          </td>
          <td>{{ formatMoney(order.total) }}</td>
          <td>{{ formatDate(order.createdAt) }}</td>
          <td @click.stop>
            <button class="action-btn">{{ t('common.view') }}</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="loading" class="loading-overlay">
      <div class="spinner" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from '@/composables/useI18n'
import type { OrderItem, OrderStatus, Money } from '../types'

defineProps<{
  orders: OrderItem[]
  loading: boolean
  selectedIds: string[]
}>()

defineEmits<{
  select: [id: string]
  selectAll: []
  viewDetail: [id: string]
}>()

const { t } = useI18n()

function statusClass(status: OrderStatus): string {
  const classes: Record<OrderStatus, string> = {
    PENDING: 'bg-yellow-100 text-yellow-800',
    CONFIRMED: 'bg-blue-100 text-blue-800',
    PROCESSING: 'bg-purple-100 text-purple-800',
    SHIPPED: 'bg-indigo-100 text-indigo-800',
    DELIVERED: 'bg-green-100 text-green-800',
    CANCELLED: 'bg-red-100 text-red-800',
    REFUNDED: 'bg-gray-100 text-gray-800',
  }
  return classes[status]
}

function formatMoney(money: Money): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: money.currency,
  }).format(money.amount / 100)
}

function formatDate(date: string): string {
  return new Date(date).toLocaleDateString()
}
</script>

<style scoped>
.orders-table {
  @apply relative;
}

.table-header {
  @apply bg-neutral-50 border-b border-neutral-200;
}

.table-header th {
  @apply px-4 py-3 text-left text-xs font-medium text-neutral-700 uppercase tracking-wider;
}

.table-row {
  @apply border-b border-neutral-200 hover:bg-neutral-50 cursor-pointer transition-colors;
}

.table-row td {
  @apply px-4 py-4 text-sm text-neutral-900;
}

.checkbox-cell {
  @apply w-12;
}

.status-badge {
  @apply px-2 py-1 text-xs font-medium rounded-full inline-block;
}

.action-btn {
  @apply text-primary-600 hover:text-primary-800 font-medium text-sm;
}

.loading-overlay {
  @apply absolute inset-0 bg-white bg-opacity-75 flex items-center justify-center;
}

.spinner {
  @apply w-8 h-8 border-4 border-primary-200 border-t-primary-600 rounded-full animate-spin;
}
</style>
