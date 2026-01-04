<template>
  <div class="orders-dashboard">
    <p class="sr-only" role="status" aria-live="polite">{{ liveRegionMessage }}</p>

    <!-- Header -->
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('orders.dashboard.title') }}</h1>
        <p class="dashboard-subtitle">{{ t('orders.dashboard.subtitle') }}</p>
      </div>
      <div class="dashboard-header-actions">
        <button
          v-if="authStore.hasRole('ORDERS_EXPORT')"
          class="btn-secondary"
          @click="handleExport"
          :aria-label="t('orders.actions.export')"
        >
          <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
            />
          </svg>
          {{ t('common.export') }}
        </button>
        <button class="btn-secondary" @click="handleRefresh" :aria-label="t('common.refresh')">
          <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
          {{ t('common.refresh') }}
        </button>
        <!-- SSE Connection Status -->
        <div class="sse-status" :class="{ connected: ordersStore.sseConnected }">
          <div class="sse-indicator" />
          <span class="text-sm">{{
            ordersStore.sseConnected ? t('common.live') : t('common.offline')
          }}</span>
        </div>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading && !ordersStore.orders.length" class="dashboard-loading" role="status" aria-live="polite">
      <div class="spinner" />
      <p>{{ t('common.loading') }}</p>
    </div>

    <!-- Error State -->
    <div v-else-if="ordersStore.error" class="dashboard-error">
      <InlineAlert
        tone="error"
        :title="t('orders.errors.loadFailed')"
        :description="ordersStore.error"
      >
        <button class="btn-primary" @click="handleRetry">{{ t('common.retry') }}</button>
      </InlineAlert>
    </div>

    <!-- Dashboard Content -->
    <div v-else class="dashboard-content">
      <!-- Stats Grid -->
      <div v-if="stats" class="stats-grid">
        <MetricsCard
          :title="t('orders.stats.totalOrders')"
          :value="stats.totalOrders.toString()"
          icon="📦"
          color="primary"
        />
        <MetricsCard
          :title="t('orders.stats.pendingOrders')"
          :value="stats.pendingOrders.toString()"
          icon="⏳"
          color="warning"
        />
        <MetricsCard
          :title="t('orders.stats.revenue')"
          :value="formatMoney(stats.revenue)"
          icon="💰"
          color="success"
        />
        <MetricsCard
          :title="t('orders.stats.avgOrderValue')"
          :value="formatMoney(stats.avgOrderValue)"
          icon="📊"
          color="secondary"
        />
      </div>

      <!-- Filters & Search -->
      <div class="filters-section">
        <div class="search-box">
          <input
            v-model="searchTerm"
            type="text"
            :placeholder="t('orders.search.placeholder')"
            class="search-input"
            @input="debouncedSearch"
          />
        </div>
        <div class="filter-controls">
          <select v-model="statusFilter" @change="handleFilterChange" class="filter-select">
            <option value="">{{ t('orders.filters.allStatuses') }}</option>
            <option value="PENDING">{{ t('orders.status.pending') }}</option>
            <option value="CONFIRMED">{{ t('orders.status.confirmed') }}</option>
            <option value="PROCESSING">{{ t('orders.status.processing') }}</option>
            <option value="SHIPPED">{{ t('orders.status.shipped') }}</option>
            <option value="DELIVERED">{{ t('orders.status.delivered') }}</option>
            <option value="CANCELLED">{{ t('orders.status.cancelled') }}</option>
            <option value="REFUNDED">{{ t('orders.status.refunded') }}</option>
          </select>
          <button v-if="hasActiveFilters" @click="handleClearFilters" class="btn-text">
            {{ t('orders.filters.clear') }}
          </button>
        </div>
      </div>

      <!-- Bulk Actions Bar -->
      <div v-if="ordersStore.hasSelection" class="bulk-actions-bar">
        <span class="selection-count">{{
          t('orders.bulkActions.selectedCount', { count: ordersStore.selectionCount })
        }}</span>
        <div class="bulk-action-buttons">
          <button
            v-if="authStore.hasRole('ORDERS_EDIT')"
            @click="showBulkUpdateModal = true"
            class="btn-secondary"
          >
            {{ t('orders.bulkActions.updateStatus') }}
          </button>
          <button @click="ordersStore.clearSelection()" class="btn-text">
            {{ t('common.cancel') }}
          </button>
        </div>
      </div>

      <!-- Orders Table -->
      <div class="dashboard-card">
        <OrdersTable
          :orders="ordersStore.orders"
          :loading="ordersStore.loading"
          :selected-ids="Array.from(ordersStore.selectedOrderIds)"
          @select="ordersStore.toggleOrderSelection"
          @select-all="ordersStore.selectAllOrders"
          @view-detail="handleViewDetail"
        />

        <!-- Pagination -->
        <div v-if="ordersStore.pagination.hasMore" class="pagination-controls">
          <button @click="handleLoadMore" class="btn-secondary">
            {{ t('common.loadMore') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Modals -->
    <BulkUpdateModal
      v-if="showBulkUpdateModal"
      :is-open="showBulkUpdateModal"
      :selected-count="ordersStore.selectionCount"
      @close="showBulkUpdateModal = false"
      @confirm="handleBulkUpdate"
    />

    <OrderDetailPanel
      :order="ordersStore.selectedOrder"
      :is-open="isDetailDrawerOpen"
      :can-edit="authStore.hasRole('ORDERS_EDIT')"
      @close="closeDetailPanel"
      @update-status="handleDetailStatusUpdate"
      @cancel="handleDetailCancel"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useOrdersStore } from '../store'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import { useI18n } from '@/composables/useI18n'
import { emitTelemetryEvent } from '@/telemetry'
import MetricsCard from '@/components/base/MetricsCard.vue'
import InlineAlert from '@/components/base/InlineAlert.vue'
import OrdersTable from '../components/OrdersTable.vue'
import BulkUpdateModal from '../components/BulkUpdateModal.vue'
import OrderDetailPanel from '../components/OrderDetailPanel.vue'
import type { OrderStatus } from '../types'
import type { Money } from '@/api/types'
import { useToast } from 'primevue/usetoast'

const ordersStore = useOrdersStore()
const authStore = useAuthStore()
const tenantStore = useTenantStore()
const { t } = useI18n()
const toast = useToast()

const isLoading = ref(false)
const searchTerm = ref('')
const statusFilter = ref('')
const showBulkUpdateModal = ref(false)
const liveRegionMessage = ref('')
const isDetailDrawerOpen = ref(false)

const stats = computed(() => ordersStore.stats)
const hasActiveFilters = computed(() => {
  return !!searchTerm.value || !!statusFilter.value
})

onMounted(async () => {
  // Restore auth state
  authStore.restoreAuth()

  // Check authorization
  if (!authStore.hasRole('ORDERS_VIEW')) {
    liveRegionMessage.value = t('orders.errors.unauthorized')
    return
  }

  // Load tenant if needed
  if (!tenantStore.currentTenant) {
    await tenantStore.loadTenant()
  }

  // Check feature flag
  if (!tenantStore.isFeatureEnabled('orders')) {
    liveRegionMessage.value = t('orders.errors.featureDisabled')
    return
  }

  await loadDashboard()
  ordersStore.connectSSE()

  emitTelemetryEvent('view_orders', {
    tenantId: tenantStore.tenantId,
    userId: authStore.user?.id,
  })
})

onBeforeUnmount(() => {
  ordersStore.disconnectSSE()
})

async function loadDashboard() {
  isLoading.value = true
  try {
    await Promise.all([ordersStore.loadOrders(), ordersStore.loadStats()])
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  } finally {
    isLoading.value = false
  }
}

async function handleRefresh() {
  await loadDashboard()
  liveRegionMessage.value = t('orders.messages.refreshed')
}

async function handleRetry() {
  ordersStore.clearError()
  await loadDashboard()
}

async function handleFilterChange() {
  const filters = {
    status: statusFilter.value ? [statusFilter.value as OrderStatus] : undefined,
    searchTerm: searchTerm.value || undefined,
  }
  await ordersStore.updateFilters(filters)
}

const debouncedSearch = debounce(() => {
  handleFilterChange()
}, 300)

async function handleClearFilters() {
  searchTerm.value = ''
  statusFilter.value = ''
  await ordersStore.clearFilters()
  liveRegionMessage.value = t('orders.messages.filtersCleared')
}

async function handleLoadMore() {
  await ordersStore.loadOrders(ordersStore.pagination.page + 1, false)
}

async function handleExport() {
  try {
    await ordersStore.exportOrdersCSV()
    liveRegionMessage.value = t('orders.messages.exported')
  } catch (error) {
    console.error('Export failed:', error)
    liveRegionMessage.value = t('orders.errors.exportFailed')
  }
}

async function handleBulkUpdate(status: OrderStatus) {
  try {
    await ordersStore.bulkUpdateStatus(status)
    showBulkUpdateModal.value = false
    liveRegionMessage.value = t('orders.messages.bulkUpdateSuccess')
  } catch (error) {
    console.error('Bulk update failed:', error)
    liveRegionMessage.value = t('orders.errors.bulkUpdateFailed')
  }
}

async function handleViewDetail(orderId: string) {
  try {
    await ordersStore.loadOrderDetail(orderId)
    isDetailDrawerOpen.value = true
  } catch (error) {
    console.error('Failed to load order detail', error)
    toast.add({
      severity: 'error',
      summary: t('orders.errors.detailFailed'),
    })
  }
}

function closeDetailPanel() {
  isDetailDrawerOpen.value = false
  ordersStore.clearSelectedOrder()
}

async function handleDetailStatusUpdate(status: OrderStatus) {
  if (!ordersStore.selectedOrder) return
  try {
    await ordersStore.updateOrderStatus(ordersStore.selectedOrder.id, status)
    toast.add({
      severity: 'success',
      summary: t('orders.messages.statusUpdated'),
    })
  } catch (error) {
    console.error('Failed to update order status', error)
    toast.add({
      severity: 'error',
      summary: t('orders.errors.bulkUpdateFailed'),
    })
  }
}

async function handleDetailCancel() {
  if (!ordersStore.selectedOrder) return
  try {
    await ordersStore.cancelOrder(ordersStore.selectedOrder.id, t('orders.actions.defaultCancelReason'))
    toast.add({
      severity: 'info',
      summary: t('orders.messages.orderCancelled'),
    })
  } catch (error) {
    console.error('Failed to cancel order', error)
    toast.add({
      severity: 'error',
      summary: t('orders.errors.cancelFailed'),
    })
  }
}

watch(
  () => ordersStore.selectedOrder,
  (next) => {
    if (!next) {
      isDetailDrawerOpen.value = false
    }
  }
)

function formatMoney(money: Money): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: money.currency,
  }).format(money.amount / 100)
}

function debounce<T extends (...args: any[]) => any>(func: T, wait: number) {
  let timeout: NodeJS.Timeout
  return function (this: any, ...args: Parameters<T>) {
    clearTimeout(timeout)
    timeout = setTimeout(() => func.apply(this, args), wait)
  }
}
</script>

<style scoped>
.orders-dashboard {
  @apply max-w-7xl mx-auto px-4 py-6;
}

.dashboard-header {
  @apply mb-8 flex items-start justify-between;
}

.dashboard-title {
  @apply text-3xl font-bold text-neutral-900 mb-2;
}

.dashboard-subtitle {
  @apply text-neutral-600;
}

.dashboard-header-actions {
  @apply flex items-center gap-3;
}

.btn-primary {
  @apply px-4 py-2 bg-primary-600 text-white rounded-md font-medium hover:bg-primary-700 transition-colors flex items-center;
}

.btn-secondary {
  @apply px-4 py-2 bg-neutral-100 text-neutral-700 rounded-md font-medium hover:bg-neutral-200 transition-colors flex items-center;
}

.btn-text {
  @apply px-3 py-1 text-neutral-600 hover:text-neutral-900 transition-colors;
}

.sse-status {
  @apply flex items-center gap-2 px-3 py-2 bg-neutral-100 rounded-md;
}

.sse-status.connected {
  @apply bg-success-50;
}

.sse-indicator {
  @apply w-2 h-2 rounded-full bg-neutral-400;
}

.sse-status.connected .sse-indicator {
  @apply bg-success-500 animate-pulse;
}

.dashboard-loading {
  @apply py-24 flex flex-col items-center justify-center gap-4;
}

.spinner {
  @apply w-12 h-12 border-4 border-primary-200 border-t-primary-600 rounded-full animate-spin;
}

.dashboard-error {
  @apply py-12;
}

.dashboard-content {
  @apply space-y-6;
}

.stats-grid {
  @apply grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6;
}

.filters-section {
  @apply flex flex-col md:flex-row gap-4 items-start md:items-center justify-between bg-white p-4 rounded-lg border border-neutral-200;
}

.search-box {
  @apply flex-1;
}

.search-input {
  @apply w-full px-4 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent;
}

.filter-controls {
  @apply flex items-center gap-3;
}

.filter-select {
  @apply px-4 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500;
}

.bulk-actions-bar {
  @apply sticky top-0 z-10 bg-primary-50 border border-primary-200 rounded-lg p-4 flex items-center justify-between;
}

.selection-count {
  @apply font-medium text-primary-900;
}

.bulk-action-buttons {
  @apply flex items-center gap-3;
}

.dashboard-card {
  @apply bg-white rounded-lg border border-neutral-200 overflow-hidden;
}

.pagination-controls {
  @apply p-4 border-t border-neutral-200 flex justify-center;
}
</style>
