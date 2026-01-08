<template>
  <div class="items-table">
    <div class="items-table-header">
      <h3 class="text-lg font-semibold text-neutral-900">
        {{ t('consignor.items.title') }}
      </h3>
      <div class="flex items-center gap-2">
        <button
          v-for="filter in statusFilters"
          :key="filter.value"
          class="filter-btn"
          :class="{ active: currentFilter === filter.value }"
          @click="currentFilter = filter.value"
        >
          {{ t(filter.label) }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="items-table-loading">
      <div class="spinner" />
      <p>{{ t('common.loading') }}</p>
    </div>

    <div v-else-if="filteredItems.length === 0" class="items-table-empty">
      <p class="text-neutral-600">{{ t('consignor.items.empty') }}</p>
    </div>

    <div v-else class="items-table-content">
      <table class="w-full">
        <thead>
          <tr class="table-header-row">
            <th class="table-header">{{ t('consignor.items.product') }}</th>
            <th class="table-header">{{ t('consignor.items.sku') }}</th>
            <th class="table-header">{{ t('consignor.items.price') }}</th>
            <th class="table-header">{{ t('consignor.items.commission') }}</th>
            <th class="table-header">{{ t('consignor.items.status') }}</th>
            <th class="table-header">{{ t('consignor.items.date') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredItems" :key="item.id" class="table-row">
            <td class="table-cell">
              <div class="product-cell">
                <p class="font-medium text-neutral-900">{{ item.productName }}</p>
                <p v-if="item.variantAttributes" class="text-sm text-neutral-600">
                  {{ formatVariantAttributes(item.variantAttributes) }}
                </p>
              </div>
            </td>
            <td class="table-cell">
              <code class="text-sm text-neutral-700">{{ item.variantSku }}</code>
            </td>
            <td class="table-cell">
              <span class="font-semibold">{{ formatCurrency(item.consignmentPrice) }}</span>
            </td>
            <td class="table-cell">
              <span class="text-neutral-700">{{ item.commissionRate }}%</span>
            </td>
            <td class="table-cell">
              <span class="status-badge" :class="`status-${item.status.toLowerCase()}`">
                {{ t(`consignor.items.status.${item.status.toLowerCase()}`) }}
              </span>
            </td>
            <td class="table-cell">
              <span class="text-sm text-neutral-600">
                {{ formatDate(item.soldAt || item.consignedAt) }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="hasMore" class="items-table-footer">
      <button class="btn-secondary" @click="emit('load-more')">
        {{ t('common.loadMore') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { ConsignmentItem } from '../types'

const props = defineProps<{
  items: ConsignmentItem[]
  loading?: boolean
  hasMore?: boolean
}>()

const emit = defineEmits<{
  'load-more': []
}>()

const { t, formatCurrency, formatDate } = useI18n()

const currentFilter = ref<string>('all')

const statusFilters = [
  { value: 'all', label: 'consignor.items.filter.all' },
  { value: 'AVAILABLE', label: 'consignor.items.filter.available' },
  { value: 'SOLD', label: 'consignor.items.filter.sold' },
  { value: 'RETURNED', label: 'consignor.items.filter.returned' },
]

const filteredItems = computed(() => {
  if (currentFilter.value === 'all') {
    return props.items
  }
  return props.items.filter((item) => item.status === currentFilter.value)
})

function formatVariantAttributes(attrs: Record<string, string>): string {
  return Object.entries(attrs)
    .map(([key, value]) => `${key}: ${value}`)
    .join(', ')
}
</script>

<style scoped>
.items-table {
  @apply bg-white rounded-lg shadow-soft overflow-hidden;
}

.items-table-header {
  @apply p-6 border-b border-neutral-200 flex items-center justify-between;
}

.filter-btn {
  @apply px-3 py-1.5 text-sm font-medium text-neutral-600 bg-neutral-100 rounded-md hover:bg-neutral-200 transition-colors;
}

.filter-btn.active {
  @apply bg-primary-600 text-white hover:bg-primary-700;
}

.items-table-loading {
  @apply p-12 flex flex-col items-center justify-center gap-4;
}

.spinner {
  @apply w-8 h-8 border-4 border-primary-200 border-t-primary-600 rounded-full animate-spin;
}

.items-table-empty {
  @apply p-12 text-center;
}

.items-table-content {
  @apply overflow-x-auto;
}

.table-header-row {
  @apply bg-neutral-50 border-b border-neutral-200;
}

.table-header {
  @apply px-6 py-3 text-left text-xs font-semibold text-neutral-700 uppercase tracking-wider;
}

.table-row {
  @apply border-b border-neutral-200 hover:bg-neutral-50 transition-colors;
}

.table-row:last-child {
  @apply border-b-0;
}

.table-cell {
  @apply px-6 py-4;
}

.product-cell {
  @apply max-w-xs;
}

.status-badge {
  @apply inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium;
}

.status-available {
  @apply bg-success-100 text-success-700;
}

.status-sold {
  @apply bg-primary-100 text-primary-700;
}

.status-returned {
  @apply bg-warning-100 text-warning-700;
}

.status-withdrawn {
  @apply bg-neutral-100 text-neutral-700;
}

.items-table-footer {
  @apply p-6 border-t border-neutral-200 text-center;
}

.btn-secondary {
  @apply px-4 py-2 bg-neutral-100 text-neutral-700 rounded-md font-medium hover:bg-neutral-200 transition-colors;
}
</style>
