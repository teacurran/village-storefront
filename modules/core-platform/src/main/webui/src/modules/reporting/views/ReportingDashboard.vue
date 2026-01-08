<template>
  <div class="reporting-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('reporting.title') }}</h1>
        <p class="dashboard-subtitle">{{ t('reporting.subtitle') }}</p>
      </div>
      <Button
        v-if="authStore.hasRole('REPORTS_EXPORT')"
        icon="pi pi-download"
        :label="t('reporting.actions.export')"
        @click="handleExport"
      />
    </div>

    <div class="filters-card">
      <label>
        {{ t('reporting.filters.startDate') }}
        <input type="date" v-model="startDate" />
      </label>
      <label>
        {{ t('reporting.filters.endDate') }}
        <input type="date" v-model="endDate" />
      </label>
      <Button class="p-button-text" :label="t('common.refresh')" @click="handleRefresh" />
    </div>

    <div class="metrics-grid">
      <MetricsCard
        :title="t('reporting.metrics.revenue')"
        :value="formatCurrency(totalRevenueMoney)"
        icon="💰"
        color="primary"
        :change="reportingStore.trend"
      />
      <MetricsCard
        :title="t('reporting.metrics.orders')"
        :value="reportingStore.metrics.orderCount.toString()"
        icon="📦"
        color="success"
      />
      <MetricsCard
        :title="t('reporting.metrics.avgOrderValue')"
        :value="formatCurrency(avgOrderValueMoney)"
        icon="📊"
        color="secondary"
      />
    </div>

    <div class="content-grid">
      <section class="card">
        <header class="card-header">
          <div>
            <h2>{{ t('reporting.slowMovers.title') }}</h2>
            <p>{{ t('reporting.slowMovers.subtitle') }}</p>
          </div>
        </header>
        <table>
          <thead>
            <tr>
              <th>{{ t('reporting.table.sku') }}</th>
              <th>{{ t('reporting.table.location') }}</th>
              <th>{{ t('reporting.table.quantity') }}</th>
              <th>{{ t('reporting.table.daysInStock') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in reportingStore.slowMovers" :key="item.id">
              <td>{{ item.variant?.sku }}</td>
              <td>{{ item.location?.name }}</td>
              <td>{{ item.quantity }}</td>
              <td>{{ item.daysInStock }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="card">
        <header class="card-header">
          <div>
            <h2>{{ t('reporting.exports.title') }}</h2>
            <p>{{ t('reporting.exports.subtitle') }}</p>
          </div>
          <Button class="p-button-text" icon="pi pi-refresh" @click="reportingStore.refreshExportJobs" />
        </header>
        <ul class="job-list">
          <li v-for="job in reportingStore.exportJobs" :key="job.jobId" class="job-item">
            <div>
              <p class="job-title">{{ job.reportType }}</p>
              <p class="job-meta">
                {{ job.status }} · {{ job.createdAt ? new Date(job.createdAt).toLocaleString() : '' }}
              </p>
            </div>
            <a v-if="job.downloadUrl" :href="job.downloadUrl" target="_blank">{{ t('reporting.actions.download') }}</a>
          </li>
          <li v-if="!reportingStore.exportJobs.length" class="job-empty">
            {{ t('reporting.exports.empty') }}
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import Button from 'primevue/button'
import MetricsCard from '@/components/base/MetricsCard.vue'
import { useReportingStore } from '../store'
import { useAuthStore } from '@/stores/auth'
import { useI18n } from '@/composables/useI18n'
import { useToast } from 'primevue/usetoast'

const reportingStore = useReportingStore()
const authStore = useAuthStore()
const { t, formatCurrency } = useI18n()
const toast = useToast()

const startDate = ref('')
const endDate = ref('')
const defaultCurrency = 'USD'

const totalRevenueMoney = computed(() => ({
  amount: Math.round(reportingStore.metrics.totalRevenue * 100),
  currency: defaultCurrency,
}))

const avgOrderValueMoney = computed(() => ({
  amount: Math.round(reportingStore.metrics.avgOrderValue * 100),
  currency: defaultCurrency,
}))

onMounted(async () => {
  authStore.restoreAuth()
  if (!authStore.hasRole('REPORTS_VIEW')) return
  await reportingStore.loadDashboard()
})

async function handleRefresh() {
  reportingStore.setDateRange({ start: startDate.value || undefined, end: endDate.value || undefined })
  try {
    await reportingStore.loadDashboard()
  } catch (error) {
    console.error(error)
    toast.add({ severity: 'error', summary: t('reporting.errors.loadFailed') })
  }
}

async function handleExport() {
  try {
    await reportingStore.exportReport('csv')
    toast.add({ severity: 'success', summary: t('reporting.messages.exportRequested') })
  } catch (error) {
    console.error(error)
    toast.add({ severity: 'error', summary: t('reporting.errors.exportFailed') })
  }
}
</script>

<style scoped>
.reporting-dashboard {
  @apply max-w-6xl mx-auto px-4 py-6 space-y-6;
}

.dashboard-header {
  @apply flex items-center justify-between;
}

.dashboard-title {
  @apply text-3xl font-bold text-neutral-900;
}

.filters-card {
  @apply flex items-end gap-4 bg-white border border-neutral-200 rounded-lg p-4;
}

.filters-card label {
  @apply flex flex-col text-sm text-neutral-600;
}

.filters-card input {
  @apply border border-neutral-300 rounded-md px-3 py-2;
}

.metrics-grid {
  @apply grid grid-cols-1 md:grid-cols-3 gap-4;
}

.content-grid {
  @apply grid grid-cols-1 md:grid-cols-2 gap-6;
}

.card {
  @apply bg-white border border-neutral-200 rounded-lg shadow-sm p-4;
}

.card-header {
  @apply flex justify-between items-start mb-4;
}

.job-list {
  @apply space-y-3;
}

.job-item {
  @apply flex items-center justify-between;
}

.job-title {
  @apply font-medium text-neutral-900;
}

.job-meta {
  @apply text-xs text-neutral-500;
}

.job-empty {
  @apply text-sm text-neutral-500;
}
</style>
