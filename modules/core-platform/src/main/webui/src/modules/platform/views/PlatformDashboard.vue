<template>
  <div class="platform-dashboard" data-test="platform-dashboard">
    <ImpersonationBanner />

    <header class="dashboard-header">
      <div>
        <h1>Platform Overview</h1>
        <p class="subtitle">Real-time metrics and operational status across all tenants</p>
      </div>
      <button
        class="refresh-btn"
        :disabled="loading"
        data-test="refresh-metrics"
        @click="refreshDashboard"
      >
        <i class="pi pi-refresh" />
        Refresh
      </button>
    </header>

    <div v-if="loading && !healthMetrics" class="loading-state">
      <i class="pi pi-spin pi-spinner" />
      Loading dashboard metrics...
    </div>

    <div v-else-if="error" class="error-state" data-test="dashboard-error">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>

    <div v-else class="dashboard-content">
      <!-- Overall Status Card -->
      <section class="status-section" data-test="platform-status">
        <article class="metric-card status-card" :class="healthMetrics?.status">
          <div class="status-header">
            <h2>Platform Status</h2>
            <span :class="['status-indicator', healthMetrics?.status]"></span>
          </div>
          <p class="status-value">{{ capitalizeStatus(healthMetrics?.status) }}</p>
          <small>Last updated {{ formattedTimestamp }}</small>
        </article>
      </section>

      <!-- KPI Grid -->
      <section class="kpi-grid" data-test="kpi-grid">
        <article class="metric-card" data-test="kpi-tenants">
          <div class="metric-header">
            <i class="pi pi-building metric-icon"></i>
            <h3>Tenants</h3>
          </div>
          <p class="metric-value">{{ healthMetrics?.activeTenantCount }}</p>
          <small>{{ healthMetrics?.tenantCount }} total stores</small>
        </article>

        <article class="metric-card" data-test="kpi-users">
          <div class="metric-header">
            <i class="pi pi-users metric-icon"></i>
            <h3>Active Users</h3>
          </div>
          <p class="metric-value">{{ healthMetrics?.totalUsers }}</p>
          <small>{{ healthMetrics?.activeSessions }} active sessions</small>
        </article>

        <article class="metric-card" data-test="kpi-queue">
          <div class="metric-header">
            <i class="pi pi-list metric-icon"></i>
            <h3>Job Queue</h3>
          </div>
          <p class="metric-value" :class="{ warning: queueDepthWarning }">
            {{ healthMetrics?.jobQueueDepth }}
          </p>
          <small>{{ healthMetrics?.failedJobs24h }} failures (24h)</small>
        </article>

        <article class="metric-card" data-test="kpi-latency">
          <div class="metric-header">
            <i class="pi pi-clock metric-icon"></i>
            <h3>Response Time</h3>
          </div>
          <p class="metric-value">{{ healthMetrics?.avgResponseTimeMs }}ms</p>
          <small>p95: {{ healthMetrics?.p95ResponseTimeMs }}ms</small>
        </article>

        <article class="metric-card" data-test="kpi-errors">
          <div class="metric-header">
            <i class="pi pi-exclamation-circle metric-icon"></i>
            <h3>Error Rate</h3>
          </div>
          <p class="metric-value" :class="{ warning: errorRateWarning }">
            {{ healthMetrics?.errorRatePercent }}%
          </p>
          <small>HTTP 5xx responses</small>
        </article>

        <article class="metric-card" data-test="kpi-database">
          <div class="metric-header">
            <i class="pi pi-database metric-icon"></i>
            <h3>Database</h3>
          </div>
          <p class="metric-value">{{ healthMetrics?.dbConnectionCount }}</p>
          <small>Active connections</small>
        </article>

        <article class="metric-card" data-test="kpi-disk">
          <div class="metric-header">
            <i class="pi pi-server metric-icon"></i>
            <h3>Disk Usage</h3>
          </div>
          <p class="metric-value" :class="{ warning: diskUsageWarning }">
            {{ healthMetrics?.diskUsagePercent }}%
          </p>
          <small>Filesystem capacity</small>
        </article>
      </section>

      <!-- Alert Feed Section -->
      <section class="alerts-section" data-test="alert-feed">
        <div class="section-header">
          <h2>
            <i class="pi pi-bell"></i>
            Recent Alerts
          </h2>
          <span v-if="alertCount > 0" class="alert-badge" data-test="alert-count">
            {{ alertCount }}
          </span>
        </div>
        <div v-if="alertCount === 0" class="empty-alerts">
          <i class="pi pi-check-circle"></i>
          <p>No active alerts. System operating normally.</p>
        </div>
        <div v-else class="alert-list">
          <article
            v-for="(alert, index) in recentAlerts"
            :key="index"
            class="alert-item"
            :class="alert.severity"
            data-test="alert-item"
          >
            <div class="alert-icon">
              <i
                :class="[
                  'pi',
                  alert.severity === 'critical'
                    ? 'pi-times-circle'
                    : alert.severity === 'warning'
                      ? 'pi-exclamation-triangle'
                      : 'pi-info-circle',
                ]"
              ></i>
            </div>
            <div class="alert-content">
              <strong>{{ alert.title }}</strong>
              <p>{{ alert.message }}</p>
              <small>{{ formatAlertTime(alert.timestamp) }}</small>
            </div>
          </article>
        </div>
      </section>

      <!-- Support Queue Integration -->
      <section class="support-queue-section" data-test="support-queue">
        <SupportQueuePanel
          :tickets="supportQueue.tickets"
          :stats="supportStats"
          :loading="supportQueueLoading"
          :error="supportQueueError"
          @view-ticket="handleSupportTicketView"
          @impersonate="openSupportImpersonation"
        />
      </section>

      <!-- Quick Actions -->
      <section class="actions-section" data-test="quick-actions">
        <h2>Quick Actions</h2>
        <div class="action-grid">
          <router-link to="/admin/platform/stores" class="action-card">
            <i class="pi pi-building"></i>
            <span>Manage Stores</span>
          </router-link>
          <router-link to="/admin/platform/impersonation" class="action-card">
            <i class="pi pi-user-edit"></i>
            <span>Impersonation Control</span>
          </router-link>
          <router-link to="/admin/platform/audit" class="action-card">
            <i class="pi pi-file"></i>
            <span>Audit Logs</span>
          </router-link>
          <router-link to="/admin/platform/health" class="action-card">
            <i class="pi pi-heart"></i>
            <span>System Health</span>
          </router-link>
          <router-link to="/admin/platform/feature-flags" class="action-card">
            <i class="pi pi-flag"></i>
            <span>Feature Flags</span>
          </router-link>
        </div>
      </section>
    </div>

    <!-- Support Queue Impersonation Dialog -->
    <dialog ref="supportImpersonateDialog" class="modal" data-test="support-impersonate-dialog">
      <div class="modal-content">
        <h2>Impersonate from Support Ticket</h2>
        <p v-if="selectedSupportTicket">
          Ticket <strong>#{{ selectedSupportTicket.reference }}</strong> ·
          {{ selectedSupportTicket.subject }}
        </p>
        <p v-if="selectedSupportTicket" class="ticket-tenant">
          Tenant: {{ selectedSupportTicket.tenantName }}
        </p>
        <div class="form-field">
          <label for="support-reason">Reason (required)</label>
          <textarea
            id="support-reason"
            v-model="supportImpersonateReason"
            rows="3"
            placeholder="Describe why impersonation is required..."
          ></textarea>
        </div>
        <div class="form-field">
          <label for="support-ticket">Support Ticket Number</label>
          <input
            id="support-ticket"
            v-model="supportImpersonateTicket"
            type="text"
            readonly
          />
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" type="button" @click="closeSupportImpersonation">
            Cancel
          </button>
          <button
            class="btn-primary"
            type="button"
            :disabled="!isSupportImpersonateValid"
            @click="startSupportImpersonation"
          >
            Start Impersonation
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'
import SupportQueuePanel from '../components/SupportQueuePanel.vue'
import type { SupportTicketSummary } from '../types'

/**
 * Platform Dashboard View
 *
 * Main landing page for platform admins showing KPIs, alerts, queue metrics,
 * and quick action links. Integrates with SSE for real-time updates.
 *
 * References:
 * - Task I5.T2: Platform admin console UI
 * - Architecture: 06_UI_UX_Architecture.md Section 2.4
 */

const platformStore = usePlatformStore()
const {
  healthMetrics,
  loading,
  error,
  sseConnected,
  supportQueue,
  supportQueueLoading,
  supportQueueError,
} = storeToRefs(platformStore)

const supportImpersonateDialog = ref<HTMLDialogElement | null>(null)
const selectedSupportTicket = ref<SupportTicketSummary | null>(null)
const supportImpersonateReason = ref('')
const supportImpersonateTicket = ref('')

const formattedTimestamp = computed(() =>
  healthMetrics.value ? new Date(healthMetrics.value.timestamp).toLocaleTimeString() : ''
)

const supportStats = computed(() => ({
  totalOpen: supportQueue.value.totalOpen,
  urgentCount: supportQueue.value.urgentCount,
  awaitingReplyCount: supportQueue.value.awaitingReplyCount,
}))

const isSupportImpersonateValid = computed(() => {
  return (
    supportImpersonateReason.value.trim().length >= 10 &&
    supportImpersonateTicket.value.trim().length >= 3
  )
})

// Warning thresholds
const queueDepthWarning = computed(() => (healthMetrics.value?.jobQueueDepth ?? 0) > 100)
const errorRateWarning = computed(() => (healthMetrics.value?.errorRatePercent ?? 0) > 1.0)
const diskUsageWarning = computed(() => (healthMetrics.value?.diskUsagePercent ?? 0) > 80)

// Mock alerts - in production, these would come from healthMetrics or separate endpoint
const recentAlerts = computed(() => {
  const alerts = []

  // Generate alerts based on metrics
  if (queueDepthWarning.value) {
    alerts.push({
      severity: 'warning',
      title: 'High Queue Depth',
      message: `Job queue has ${healthMetrics.value?.jobQueueDepth} pending items`,
      timestamp: new Date().toISOString(),
    })
  }

  if (errorRateWarning.value) {
    alerts.push({
      severity: 'critical',
      title: 'Elevated Error Rate',
      message: `Error rate at ${healthMetrics.value?.errorRatePercent}% - investigate immediately`,
      timestamp: new Date().toISOString(),
    })
  }

  if (diskUsageWarning.value) {
    alerts.push({
      severity: 'warning',
      title: 'Disk Usage High',
      message: `Disk usage at ${healthMetrics.value?.diskUsagePercent}% capacity`,
      timestamp: new Date().toISOString(),
    })
  }

  if ((healthMetrics.value?.failedJobs24h ?? 0) > 10) {
    alerts.push({
      severity: 'warning',
      title: 'Job Failures Detected',
      message: `${healthMetrics.value?.failedJobs24h} jobs failed in the last 24 hours`,
      timestamp: new Date().toISOString(),
    })
  }

  return alerts
})

const alertCount = computed(() => recentAlerts.value.length)

onMounted(async () => {
  // Load initial metrics
  await Promise.all([platformStore.loadHealthMetrics(), platformStore.loadSupportQueue()])

  // Establish SSE connection for real-time updates
  platformStore.connectSSE()
})

onBeforeUnmount(() => {
  // Clean up SSE connection when leaving dashboard
  platformStore.disconnectSSE()
})

async function refreshDashboard() {
  await Promise.all([platformStore.loadHealthMetrics(), platformStore.loadSupportQueue()])
}

function capitalizeStatus(status?: string): string {
  if (!status) return 'Unknown'
  return status.charAt(0).toUpperCase() + status.slice(1)
}

function formatAlertTime(timestamp: string): string {
  const date = new Date(timestamp)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / (1000 * 60))

  if (diffMins < 1) return 'Just now'
  if (diffMins === 1) return '1 minute ago'
  if (diffMins < 60) return `${diffMins} minutes ago`
  if (diffMins < 120) return '1 hour ago'
  if (diffMins < 1440) return `${Math.floor(diffMins / 60)} hours ago`
  return date.toLocaleString()
}

function handleSupportTicketView(ticket: SupportTicketSummary) {
  const ticketUrl = `https://support.villagecompute.com/tickets/${ticket.reference}`
  window.open(ticketUrl, '_blank', 'noopener')
}

function openSupportImpersonation(ticket: SupportTicketSummary) {
  selectedSupportTicket.value = ticket
  supportImpersonateReason.value = `Support ticket ${ticket.reference}: ${ticket.subject}`
  supportImpersonateTicket.value = ticket.reference
  supportImpersonateDialog.value?.showModal()
}

function closeSupportImpersonation() {
  supportImpersonateDialog.value?.close()
  selectedSupportTicket.value = null
  supportImpersonateReason.value = ''
  supportImpersonateTicket.value = ''
}

async function startSupportImpersonation() {
  if (!selectedSupportTicket.value || !isSupportImpersonateValid.value) return
  try {
    await platformStore.startImpersonation({
      targetTenantId: selectedSupportTicket.value.tenantId,
      reason: supportImpersonateReason.value.trim(),
      ticketNumber: supportImpersonateTicket.value.trim(),
    })
    closeSupportImpersonation()
  } catch (err) {
    console.error('Failed to start impersonation from support ticket:', err)
  }
}
</script>

<style scoped>
.platform-dashboard {
  padding: 2rem;
  max-width: 1600px;
  margin: 0 auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2rem;
}

.dashboard-header h1 {
  font-size: 2rem;
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.subtitle {
  color: #666;
  font-size: 0.95rem;
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1.25rem;
  border: 1px solid #ddd;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #bbb;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.dashboard-content {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

/* Status Section */
.status-section {
  display: grid;
  grid-template-columns: 1fr;
}

.status-card {
  background: white;
  padding: 1.5rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.status-card.healthy {
  border-left: 5px solid #22c55e;
}

.status-card.degraded {
  border-left: 5px solid #f97316;
}

.status-card.critical {
  border-left: 5px solid #ef4444;
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.status-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
}

.status-indicator {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

.status-indicator.healthy {
  background: #22c55e;
}

.status-indicator.degraded {
  background: #f97316;
}

.status-indicator.critical {
  background: #ef4444;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}

.status-value {
  font-size: 2rem;
  font-weight: 700;
  color: #111;
  margin: 0.5rem 0;
}

/* KPI Grid */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 1.25rem;
}

.metric-card {
  background: white;
  padding: 1.25rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: transform 0.2s, box-shadow 0.2s;
}

.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.12);
}

.metric-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.metric-icon {
  font-size: 1.5rem;
  color: #6366f1;
}

.metric-header h3 {
  font-size: 1rem;
  font-weight: 600;
  color: #374151;
}

.metric-value {
  font-size: 2rem;
  font-weight: 700;
  color: #111;
  margin-bottom: 0.25rem;
}

.metric-value.warning {
  color: #f97316;
}

.metric-card small {
  color: #6b7280;
  font-size: 0.875rem;
}

/* Alerts Section */
.alerts-section {
  background: white;
  padding: 1.5rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.section-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.section-header h2 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.25rem;
  font-weight: 600;
}

.alert-badge {
  background: #ef4444;
  color: white;
  padding: 0.25rem 0.625rem;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 600;
}

.empty-alerts {
  text-align: center;
  padding: 2rem;
  color: #22c55e;
}

.empty-alerts i {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.empty-alerts p {
  font-size: 1rem;
  color: #6b7280;
}

.alert-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.alert-item {
  display: flex;
  gap: 1rem;
  padding: 1rem;
  border-radius: 6px;
  border-left: 4px solid;
}

.alert-item.critical {
  background: #fef2f2;
  border-left-color: #ef4444;
}

.alert-item.warning {
  background: #fffbeb;
  border-left-color: #f59e0b;
}

.alert-item.info {
  background: #eff6ff;
  border-left-color: #3b82f6;
}

.alert-icon {
  font-size: 1.5rem;
}

.alert-item.critical .alert-icon {
  color: #ef4444;
}

.alert-item.warning .alert-icon {
  color: #f59e0b;
}

.alert-item.info .alert-icon {
  color: #3b82f6;
}

.alert-content {
  flex: 1;
}

.alert-content strong {
  display: block;
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.alert-content p {
  margin: 0.25rem 0;
  color: #374151;
}

.alert-content small {
  color: #6b7280;
  font-size: 0.875rem;
}

/* Actions Section */
.actions-section {
  background: white;
  padding: 1.5rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.actions-section h2 {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 1rem;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.action-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 1.25rem;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  text-decoration: none;
  color: #374151;
  transition: all 0.2s;
}

.action-card:hover {
  background: #f3f4f6;
  border-color: #6366f1;
  color: #6366f1;
  transform: translateY(-2px);
}

.action-card i {
  font-size: 2rem;
}

.action-card span {
  font-weight: 500;
  text-align: center;
}

.support-queue-section {
  display: grid;
}

.modal {
  border: none;
  border-radius: 10px;
  padding: 0;
  width: min(480px, 90vw);
}

.modal::backdrop {
  background: rgba(15, 23, 42, 0.6);
}

.modal-content {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.modal-content h2 {
  margin: 0;
}

.ticket-tenant {
  margin: 0;
  color: #6b7280;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.form-field label {
  font-size: 0.9rem;
  color: #374151;
  font-weight: 600;
}

.form-field textarea,
.form-field input {
  padding: 0.65rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-family: inherit;
  font-size: 0.95rem;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.6rem 1.25rem;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  font-weight: 500;
}

.btn-primary {
  background: #4f46e5;
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  background: #f3f4f6;
  color: #111827;
  border: 1px solid #d1d5db;
}

/* Loading & Error States */
.loading-state,
.error-state {
  text-align: center;
  padding: 3rem;
  color: #6b7280;
}

.loading-state i,
.error-state i {
  font-size: 2rem;
  margin-bottom: 0.75rem;
}
</style>
