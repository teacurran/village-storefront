<template>
  <div class="consignor-dashboard">
    <p class="sr-only" role="status" aria-live="polite">{{ liveRegionMessage }}</p>
    <!-- Header -->
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('consignor.dashboard.title') }}</h1>
        <p class="dashboard-subtitle">
          {{ t('consignor.dashboard.welcome', { name: consignorStore.profile?.displayName || '' }) }}
        </p>
      </div>
      <div class="dashboard-header-actions">
        <button
          class="btn-secondary"
          @click="handleRefresh"
          :aria-label="t('common.refresh')"
        >
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
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="dashboard-loading" role="status" aria-live="polite">
      <div class="spinner" />
      <p>{{ t('common.loading') }}</p>
    </div>

    <!-- Error State -->
    <div v-else-if="consignorStore.error" class="dashboard-error">
      <p class="error-message">{{ consignorStore.error }}</p>
      <button class="btn-primary" @click="handleRetry">
        {{ t('common.retry') }}
      </button>
    </div>

    <!-- Dashboard Content -->
    <div v-else class="dashboard-content">
      <!-- Stats Grid -->
      <div class="stats-grid">
        <DashboardStatsCard
          icon="💰"
          :label="t('consignor.dashboard.stats.balanceOwed')"
          :value="displayMoney(stats?.balanceOwed)"
          color="primary"
        />
        <DashboardStatsCard
          icon="📦"
          :label="t('consignor.dashboard.stats.activeItems')"
          :value="stats?.activeItemCount || 0"
          color="success"
        />
        <DashboardStatsCard
          icon="📈"
          :label="t('consignor.dashboard.stats.soldThisMonth')"
          :value="stats?.soldThisMonth || 0"
          :subtitle="t('consignor.dashboard.stats.items')"
          color="secondary"
        />
        <DashboardStatsCard
          icon="💵"
          :label="t('consignor.dashboard.stats.lifetimeEarnings')"
          :value="displayMoney(stats?.lifetimeEarnings)"
          color="warning"
        />
      </div>

      <!-- Main Grid -->
      <div class="dashboard-grid">
        <!-- Balance Chart -->
        <div class="dashboard-card">
          <BalanceChart
            v-if="stats"
            :balance-owed="stats.balanceOwed"
            :lifetime-earnings="stats.lifetimeEarnings"
            :avg-commission-rate="stats.avgCommissionRate"
            :last-payout-date="stats.lastPayoutDate"
            :next-payout-eligible="stats.nextPayoutEligible"
            @request-payout="showPayoutModal = true"
          />
        </div>

        <!-- Notification Center -->
        <div class="dashboard-card">
          <NotificationCenter
            :notifications="consignorStore.notifications"
            :loading="false"
            :has-more="false"
            @load-more="handleLoadMoreNotifications"
            @mark-read="handleMarkNotificationRead"
          />
        </div>
      </div>

      <!-- Items Table -->
      <div class="dashboard-card full-width">
        <ConsignmentItemsTable
          :items="consignorStore.items"
          :loading="false"
          :has-more="false"
          @load-more="handleLoadMoreItems"
        />
      </div>
    </div>

    <!-- Payout Request Modal -->
    <PayoutRequestModal
      v-if="stats"
      :is-open="showPayoutModal"
      :available-balance="stats.balanceOwed"
      @close="showPayoutModal = false"
      @submit="handlePayoutRequest"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useConsignorStore } from '../store'
import { useI18n } from '../composables/useI18n'
import { emitTelemetryEvent } from '@/telemetry'
import DashboardStatsCard from '../components/DashboardStatsCard.vue'
import BalanceChart from '../components/BalanceChart.vue'
import ConsignmentItemsTable from '../components/ConsignmentItemsTable.vue'
import NotificationCenter from '../components/NotificationCenter.vue'
import PayoutRequestModal from '../components/PayoutRequestModal.vue'
import type { Money } from '@/api/types'
import { useTenantStore } from '@/stores/tenant'

const consignorStore = useConsignorStore()
const tenantStore = useTenantStore()
const { t, formatCurrency } = useI18n()

const isLoading = ref(false)
const showPayoutModal = ref(false)
const liveRegionMessage = ref('')

const stats = computed(() => consignorStore.dashboardStats)
const defaultCurrency = computed(() => stats.value?.balanceOwed.currency || 'USD')

onMounted(async () => {
  if (!tenantStore.currentTenant) {
    await tenantStore.loadTenant()
  }
  await loadDashboard()
})

async function loadDashboard() {
  isLoading.value = true
  try {
    await Promise.all([
      consignorStore.loadDashboardStats(),
      consignorStore.loadNotifications(0, 10),
    ])

    if (consignorStore.profile && consignorStore.dashboardStats) {
      emitTelemetryEvent('consignor:portal-loaded', {
        consignorId: consignorStore.profile.id,
        balanceOwed: consignorStore.dashboardStats.balanceOwed.amount,
        activeItemCount: consignorStore.dashboardStats.activeItemCount,
      })
    }
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  } finally {
    isLoading.value = false
  }
}

async function handleRefresh() {
  await loadDashboard()
}

async function handleRetry() {
  consignorStore.clearError()
  await loadDashboard()
}

async function handleLoadMoreItems() {
  const currentPage = Math.floor(consignorStore.items.length / 20)
  await consignorStore.loadItems(currentPage, 20)
}

async function handleLoadMoreNotifications() {
  const currentPage = Math.floor(consignorStore.notifications.length / 20)
  await consignorStore.loadNotifications(currentPage, 20)
}

async function handleMarkNotificationRead(notificationId: string) {
  await consignorStore.markNotificationRead(notificationId)
}

async function handlePayoutRequest(payload: {
  amountCents: number
  method: string
  notes?: string
}) {
  try {
    await consignorStore.requestPayout(
      {
        amount: payload.amountCents,
        currency: defaultCurrency.value,
      },
      payload.method as any,
      payload.notes
    )

    showPayoutModal.value = false
    liveRegionMessage.value = t('consignor.payout.successAnnouncement', {
      amount: formatCurrency({
        amount: payload.amountCents,
        currency: defaultCurrency.value,
      }),
    })
  } catch (error) {
    console.error('Failed to request payout:', error)
    liveRegionMessage.value = t('consignor.payout.failureAnnouncement')
  }
}

function displayMoney(money?: Money): string {
  return formatCurrency(
    money || {
      amount: 0,
      currency: defaultCurrency.value,
    }
  )
}
</script>

<style scoped>
.consignor-dashboard {
  @apply max-w-7xl mx-auto;
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

.dashboard-loading {
  @apply py-24 flex flex-col items-center justify-center gap-4;
}

.spinner {
  @apply w-12 h-12 border-4 border-primary-200 border-t-primary-600 rounded-full animate-spin;
}

.dashboard-error {
  @apply py-24 flex flex-col items-center justify-center gap-4;
}

.error-message {
  @apply text-error-600 font-medium;
}

.dashboard-content {
  @apply space-y-6;
}

.stats-grid {
  @apply grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6;
}

.dashboard-grid {
  @apply grid grid-cols-1 lg:grid-cols-2 gap-6;
}

.dashboard-card {
  @apply col-span-1;
}

.full-width {
  @apply lg:col-span-2;
}
</style>
