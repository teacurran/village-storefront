<template>
  <div class="offline-queue-panel" data-test="offline-queue-panel">
    <div class="panel-header">
      <div>
        <h3>Offline Queue</h3>
        <p class="subtitle">{{ queueStats.total }} transactions · {{ formatNextRetry }}</p>
      </div>
      <div class="panel-actions">
        <button class="btn-icon" :disabled="isRefreshing" @click="refreshQueue" title="Refresh queue">
          <i class="pi pi-refresh" :class="{ 'pi-spin': isRefreshing }"></i>
        </button>
        <button class="btn-icon" @click="exportEncryptedQueue" title="Export encrypted backup">
          <i class="pi pi-shield"></i>
        </button>
        <button
          v-if="hasFailedEntries"
          class="btn-sm btn-warn"
          @click="retryAllFailed"
          title="Retry all failed"
        >
          <i class="pi pi-replay"></i>
          Retry Failed
        </button>
      </div>
    </div>

    <div v-if="queueEntries.length === 0" class="empty-state">
      <i class="pi pi-check-circle"></i>
      <p>All clear! No pending transactions.</p>
    </div>

    <div v-else class="queue-list">
      <div
        v-for="entry in queueEntries"
        :key="entry.id"
        class="queue-entry queue-item"
        :class="`status-${entry.syncStatus}`"
        data-test="queue-entry"
      >
        <div class="entry-main">
          <div class="entry-header">
            <div class="entry-id">
              <i :class="getStatusIcon(entry.syncStatus)"></i>
              <code>{{ entry.localTransactionId.substring(0, 8) }}</code>
            </div>
            <div class="entry-amount">{{ formatCurrency(entry.transactionAmount) }}</div>
          </div>

          <div class="entry-meta">
            <span class="meta-tag">
              <i class="pi pi-clock"></i>
              {{ formatTimestamp(entry.createdAt) }}
            </span>
            <span v-if="entry.staffUserId" class="meta-tag">
              <i class="pi pi-user"></i>
              Staff {{ entry.staffUserId.substring(0, 8) }}
            </span>
            <span v-if="entry.retryCount > 0" class="meta-tag">
              <i class="pi pi-replay"></i>
              {{ entry.retryCount }} retries
            </span>
            <span v-if="isExpiringSoon(entry)" class="meta-tag tag-warning">
              <i class="pi pi-exclamation-triangle"></i>
              Expires soon
            </span>
          </div>
        </div>

        <div v-if="entry.syncStatus === 'syncing'" class="entry-status status-syncing">
          <div class="spinner"></div>
          <span>Syncing to server...</span>
        </div>

        <div v-if="entry.syncStatus === 'failed'" class="entry-status status-failed">
          <div class="error-detail">
            <i class="pi pi-times-circle"></i>
            <span>{{ entry.syncError || 'Sync failed' }}</span>
          </div>
          <div v-if="entry.nextRetryAt" class="retry-info">
            Next retry: {{ formatTimestamp(entry.nextRetryAt) }}
          </div>
          <button class="btn-xs btn-retry" @click="retryEntry(entry)">
            <i class="pi pi-replay"></i>
            Retry Now
          </button>
        </div>

        <div v-if="entry.syncStatus === 'synced'" class="entry-status status-synced">
          <i class="pi pi-check-circle"></i>
          <span>Synced {{ formatTimestamp(entry.syncedAt!) }}</span>
        </div>
      </div>
    </div>

    <div v-if="queueStats.total > 0" class="panel-footer queue-summary">
      <div class="stat-group">
        <div class="stat">
          <div class="stat-value">{{ queueStats.queued }}</div>
          <div class="stat-label">Queued</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ queueStats.syncing }}</div>
          <div class="stat-label">Syncing</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ queueStats.synced }}</div>
          <div class="stat-label">Synced</div>
        </div>
        <div class="stat">
          <div class="stat-value status-failed">{{ queueStats.failed }}</div>
          <div class="stat-label">Failed</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useOfflineStore } from './offlineStore'
import { getDB, exportQueue, updateQueueEntry, type QueueEntry } from './offlineDB'
import { useToast } from 'primevue/usetoast'

const offlineStore = useOfflineStore()
const toast = useToast()

const queueEntries = ref<QueueEntry[]>([])
const isRefreshing = ref(false)

const queueStats = computed(() => offlineStore.queueStats)
const hasFailedEntries = computed(() => offlineStore.hasSyncErrors)

const formatNextRetry = computed(() => {
  const failedWithRetry = queueEntries.value.filter(
    (e) => e.syncStatus === 'failed' && e.nextRetryAt
  )
  if (failedWithRetry.length === 0) return ''

  const nextRetry = failedWithRetry.reduce((earliest, entry) => {
    return entry.nextRetryAt! < earliest ? entry.nextRetryAt! : earliest
  }, failedWithRetry[0].nextRetryAt!)

  return `Next retry: ${formatTimestamp(nextRetry)}`
})

onMounted(() => {
  loadQueue()
})

watch(
  queueStats,
  () => {
    loadQueue()
  },
  { deep: true }
)

async function loadQueue() {
  const db = await getDB()
  const all = await db.getAll('queueEntries')
  queueEntries.value = all.sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )
}

async function refreshQueue() {
  isRefreshing.value = true
  try {
    await offlineStore.refreshQueueStats()
    await loadQueue()
  } finally {
    setTimeout(() => {
      isRefreshing.value = false
    }, 500)
  }
}

async function exportEncryptedQueue() {
  try {
    const jsonData = await exportQueue()
    const blob = new Blob([jsonData], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `encrypted-queue-${new Date().toISOString()}.json`
    a.click()
    URL.revokeObjectURL(url)

    toast.add({
      severity: 'success',
      summary: 'Queue Exported',
      detail: 'Encrypted queue saved. Handle with care - contains sensitive data.',
      life: 5000,
    })
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Export Failed',
      detail: 'Could not export queue',
      life: 4000,
    })
  }
}

async function retryEntry(entry: QueueEntry) {
  try {
    await updateQueueEntry(entry.id, { syncStatus: 'queued', syncError: undefined })
    await offlineStore.refreshQueueStats()
    await loadQueue()
    await offlineStore.syncQueue()

    toast.add({
      severity: 'info',
      summary: 'Retry Queued',
      detail: 'Transaction will be retried',
      life: 3000,
    })
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Retry Failed',
      detail: 'Could not retry transaction',
      life: 4000,
    })
  }
}

async function retryAllFailed() {
  const db = await getDB()
  const failed = await db.getAllFromIndex('queueEntries', 'by-status', 'failed')

  for (const entry of failed) {
    await updateQueueEntry(entry.id, { syncStatus: 'queued', syncError: undefined })
  }

  await offlineStore.refreshQueueStats()
  await loadQueue()
  await offlineStore.syncQueue()

  toast.add({
    severity: 'info',
    summary: 'Retrying Failed Transactions',
    detail: `${failed.length} transactions queued for retry`,
    life: 4000,
  })
}

function isExpiringSoon(entry: QueueEntry): boolean {
  const expiresAt = new Date(entry.expiresAt).getTime()
  const now = Date.now()
  const hoursUntilExpiry = (expiresAt - now) / (1000 * 60 * 60)
  return hoursUntilExpiry < 24 && hoursUntilExpiry > 0
}

function getStatusIcon(status: string): string {
  switch (status) {
    case 'queued':
      return 'pi pi-clock'
    case 'syncing':
      return 'pi pi-spin pi-spinner'
    case 'synced':
      return 'pi pi-check-circle'
    case 'failed':
      return 'pi pi-times-circle'
    default:
      return 'pi pi-circle'
  }
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
  }).format(amount)
}

function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)

  if (diffMins < 0) {
    // Future time (for nextRetryAt)
    const futureMins = Math.abs(diffMins)
    if (futureMins < 60) return `in ${futureMins}m`
    if (futureMins < 1440) return `in ${Math.floor(futureMins / 60)}h`
    return date.toLocaleDateString()
  }

  if (diffMins < 1) return 'just now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffMins < 1440) return `${Math.floor(diffMins / 60)}h ago`
  return date.toLocaleDateString()
}
</script>

<style scoped>
.offline-queue-panel {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.panel-header h3 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}

.subtitle {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
  color: var(--text-color-secondary);
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.btn-icon,
.btn-sm,
.btn-xs {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-icon {
  padding: 0.5rem;
  background: transparent;
  color: var(--text-color);
  border: 1px solid var(--surface-border);
}

.btn-icon:hover:not(:disabled) {
  background: var(--surface-100);
}

.btn-sm {
  padding: 0.5rem 0.875rem;
  font-size: 0.875rem;
}

.btn-xs {
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
}

.btn-warn {
  background: var(--orange-500);
  color: white;
}

.btn-warn:hover:not(:disabled) {
  background: var(--orange-600);
}

.btn-retry {
  background: var(--primary-color);
  color: white;
}

.btn-retry:hover:not(:disabled) {
  opacity: 0.9;
}

button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  text-align: center;
  color: var(--text-color-secondary);
}

.empty-state i {
  font-size: 3rem;
  color: var(--green-500);
  margin-bottom: 1rem;
}

.queue-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-height: 500px;
  overflow-y: auto;
}

.queue-entry {
  padding: 1rem;
  background: var(--surface-0);
  border: 1px solid var(--surface-border);
  border-radius: 0.5rem;
  transition: all 0.2s;
}

.queue-entry.status-queued {
  border-left: 4px solid var(--orange-500);
}

.queue-entry.status-syncing {
  border-left: 4px solid var(--blue-500);
}

.queue-entry.status-synced {
  border-left: 4px solid var(--green-500);
  opacity: 0.6;
}

.queue-entry.status-failed {
  border-left: 4px solid var(--red-500);
}

.entry-main {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.entry-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.entry-id {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-family: 'Courier New', monospace;
  font-size: 0.875rem;
}

.entry-amount {
  font-size: 1.125rem;
  font-weight: 700;
}

.entry-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  font-size: 0.75rem;
}

.meta-tag {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  background: var(--surface-100);
  border-radius: 0.25rem;
  color: var(--text-color-secondary);
}

.meta-tag.tag-warning {
  background: var(--orange-100);
  color: var(--orange-700);
}

.entry-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
  padding: 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
}

.entry-status.status-syncing {
  background: var(--blue-50);
  color: var(--blue-700);
}

.entry-status.status-failed {
  background: var(--red-50);
  color: var(--red-700);
  flex-direction: column;
  align-items: flex-start;
}

.entry-status.status-synced {
  background: var(--green-50);
  color: var(--green-700);
}

.error-detail {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 500;
}

.retry-info {
  font-size: 0.75rem;
  opacity: 0.8;
}

.spinner {
  width: 1rem;
  height: 1rem;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.panel-footer {
  padding: 1rem;
  background: var(--surface-50);
  border-radius: 0.5rem;
}

.stat-group {
  display: flex;
  gap: 2rem;
  justify-content: space-around;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
}

.stat-value {
  font-size: 1.75rem;
  font-weight: 700;
  line-height: 1;
}

.stat-value.status-failed {
  color: var(--red-600);
}

.stat-label {
  font-size: 0.75rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-color-secondary);
}

@media (max-width: 768px) {
  .panel-header {
    flex-direction: column;
  }

  .panel-actions {
    width: 100%;
    justify-content: space-between;
  }

  .stat-group {
    gap: 1rem;
  }
}
</style>
