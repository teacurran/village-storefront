<template>
  <div class="health-dashboard">
    <ImpersonationBanner />
    <header class="dashboard-header">
      <div>
        <h1>System Health</h1>
        <p class="subtitle">Prometheus-backed metrics across all tenants</p>
      </div>
      <button class="refresh-btn" :disabled="loading" @click="refreshHealth">
        <i class="pi pi-refresh" />
        Refresh
      </button>
    </header>

    <div v-if="loading" class="loading-state">
      <i class="pi pi-spin pi-spinner" />
      Loading health metrics...
    </div>
    <div v-else-if="error" class="error-state">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>

    <section v-else class="metrics-grid">
      <article class="metric-card status-card" :class="healthMetrics?.status">
        <h2>Overall Status</h2>
        <p class="status-value">{{ healthMetrics?.status }}</p>
        <small>Last updated {{ formattedTimestamp }}</small>
      </article>

      <article class="metric-card">
        <h3>Tenants</h3>
        <p class="metric-value">{{ healthMetrics?.activeTenantCount }} / {{ healthMetrics?.tenantCount }}</p>
        <small>Active / Total</small>
      </article>

      <article class="metric-card">
        <h3>Users & Sessions</h3>
        <p class="metric-value">{{ healthMetrics?.totalUsers }} users</p>
        <small>{{ healthMetrics?.activeSessions }} active sessions</small>
      </article>

      <article class="metric-card">
        <h3>HTTP Latency</h3>
        <p class="metric-value">{{ healthMetrics?.avgResponseTimeMs }} ms avg</p>
        <small>p95: {{ healthMetrics?.p95ResponseTimeMs }} ms</small>
      </article>

      <article class="metric-card">
        <h3>Error Rate</h3>
        <p class="metric-value">{{ healthMetrics?.errorRatePercent }}%</p>
        <small>Prometheus http.server.requests</small>
      </article>

      <article class="metric-card">
        <h3>Disk Usage</h3>
        <p class="metric-value">{{ healthMetrics?.diskUsagePercent }}%</p>
        <small>Filesystems in use</small>
      </article>

      <article class="metric-card">
        <h3>DB Connections</h3>
        <p class="metric-value">{{ healthMetrics?.dbConnectionCount }}</p>
        <small>Vert.x pool in use</small>
      </article>

      <article class="metric-card">
        <h3>Jobs</h3>
        <p class="metric-value">{{ healthMetrics?.jobQueueDepth }} queued</p>
        <small>{{ healthMetrics?.failedJobs24h }} failures (24h)</small>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'

const platformStore = usePlatformStore()
const { healthMetrics, loading, error } = storeToRefs(platformStore)

const formattedTimestamp = computed(() =>
  healthMetrics.value ? new Date(healthMetrics.value.timestamp).toLocaleString() : '',
)

onMounted(async () => {
  await platformStore.loadHealthMetrics()
})

async function refreshHealth() {
  await platformStore.loadHealthMetrics()
}
</script>

<style scoped>
.health-dashboard {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.subtitle {
  color: #666;
  margin-top: 0.35rem;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1.25rem;
}

.metric-card {
  background: white;
  padding: 1.25rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.metric-card h3,
.metric-card h2 {
  margin-bottom: 0.5rem;
  font-weight: 600;
}

.metric-value {
  font-size: 1.75rem;
  font-weight: 600;
  color: #111;
}

.status-card {
  grid-column: span 2;
}

.status-card.healthy {
  border-left: 4px solid #22c55e;
}

.status-card.degraded {
  border-left: 4px solid #f97316;
}

.status-card.critical {
  border-left: 4px solid #ef4444;
}

.status-value {
  text-transform: capitalize;
  font-size: 2rem;
  font-weight: 700;
}

.refresh-btn {
  border: 1px solid #ddd;
  background: white;
  padding: 0.5rem 1rem;
  border-radius: 4px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.loading-state,
.error-state {
  padding: 2rem;
  text-align: center;
  color: #555;
}
</style>
