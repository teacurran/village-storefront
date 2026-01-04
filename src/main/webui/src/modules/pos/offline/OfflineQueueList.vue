<template>
  <div class="offline-queue-list">
    <div class="queue-header">
      <h3>Offline Transaction Queue</h3>
      <div class="queue-actions">
        <button @click="refreshQueue" class="btn-secondary" :disabled="isRefreshing">
          <i class="pi pi-refresh" :class="{ 'pi-spin': isRefreshing }"></i>
          Refresh
        </button>
        <button @click="exportQueue" class="btn-secondary">
          <i class="pi pi-download"></i>
          Export
        </button>
      </div>
    </div>

    <div v-if="queueEntries.length === 0" class="empty-state">
      <i class="pi pi-check-circle"></i>
      <p>No offline transactions</p>
    </div>

    <div v-else class="queue-items">
      <div
        v-for="entry in queueEntries"
        :key="entry.id"
        class="queue-item"
        :class="'status-' + entry.syncStatus"
      >
        <div class="item-header">
          <div class="item-id">
            <i :class="getStatusIcon(entry.syncStatus)"></i>
            <span class="tx-id">{{ entry.localTransactionId.substring(0, 8) }}</span>
          </div>
          <div class="item-amount">{{ formatAmount(entry.transactionAmount) }}</div>
        </div>

        <div class="item-meta">
          <span class="meta-item">
            <i class="pi pi-clock"></i>
            {{ formatTimestamp(entry.transactionTimestamp) }}
          </span>
          <span v-if="entry.staffUserId" class="meta-item">
            <i class="pi pi-user"></i>
            Staff
          </span>
        </div>

        <div v-if="entry.syncStatus === 'syncing'" class="item-progress">
          <div class="progress-spinner"></div>
          <span>Syncing...</span>
        </div>

        <div v-if="entry.syncStatus === 'failed'" class="item-error">
          <i class="pi pi-exclamation-triangle"></i>
          <span>{{ entry.syncError || 'Sync failed' }}</span>
        </div>

        <div v-if="entry.syncStatus === 'synced'" class="item-success">
          <i class="pi pi-check"></i>
          <span>Synced {{ formatTimestamp(entry.syncedAt!) }}</span>
        </div>
      </div>
    </div>

    <div v-if="queueStats.total > 0" class="queue-summary">
      <div class="summary-stat">
        <span class="stat-label">Queued:</span>
        <span class="stat-value">{{ queueStats.queued }}</span>
      </div>
      <div class="summary-stat">
        <span class="stat-label">Syncing:</span>
        <span class="stat-value">{{ queueStats.syncing }}</span>
      </div>
      <div class="summary-stat">
        <span class="stat-label">Synced:</span>
        <span class="stat-value">{{ queueStats.synced }}</span>
      </div>
      <div class="summary-stat">
        <span class="stat-label">Failed:</span>
        <span class="stat-value status-failed">{{ queueStats.failed }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useOfflineStore } from './offlineStore'
import { getDB, exportQueue as exportQueueData } from './offlineDB'
import type { QueueEntry } from './offlineDB'

const offlineStore = useOfflineStore()
const queueEntries = ref<QueueEntry[]>([])
const isRefreshing = ref(false)

const queueStats = computed(() => offlineStore.queueStats)

onMounted(() => {
  loadQueue()
})

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

async function exportQueue() {
  const jsonData = await exportQueueData()
  const blob = new Blob([jsonData], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `offline-queue-${new Date().toISOString()}.json`
  a.click()
  URL.revokeObjectURL(url)
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

function formatAmount(amount: number): string {
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

  if (diffMins < 1) return 'Just now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffMins < 1440) return `${Math.floor(diffMins / 60)}h ago`
  return date.toLocaleDateString()
}
</script>

<style scoped>
.offline-queue-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.queue-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.queue-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.queue-actions {
  display: flex;
  gap: 0.5rem;
}

.btn-secondary {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: var(--surface-100);
  border: 1px solid var(--surface-300);
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover:not(:disabled) {
  background: var(--surface-200);
}

.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem;
  color: var(--text-color-secondary);
  text-align: center;
}

.empty-state i {
  font-size: 3rem;
  margin-bottom: 1rem;
  color: var(--green-500);
}

.queue-items {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.queue-item {
  padding: 1rem;
  background: var(--surface-0);
  border: 1px solid var(--surface-200);
  border-radius: 0.5rem;
  transition: all 0.2s;
}

.queue-item.status-queued {
  border-left: 3px solid var(--orange-500);
}

.queue-item.status-syncing {
  border-left: 3px solid var(--blue-500);
}

.queue-item.status-synced {
  border-left: 3px solid var(--green-500);
  opacity: 0.7;
}

.queue-item.status-failed {
  border-left: 3px solid var(--red-500);
}

.item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.item-id {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-family: monospace;
  font-size: 0.875rem;
}

.item-amount {
  font-weight: 600;
  font-size: 1.125rem;
}

.item-meta {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-color-secondary);
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.item-progress,
.item-error,
.item-success {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding: 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
}

.item-progress {
  background: var(--blue-50);
  color: var(--blue-700);
}

.item-error {
  background: var(--red-50);
  color: var(--red-700);
}

.item-success {
  background: var(--green-50);
  color: var(--green-700);
}

.progress-spinner {
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

.queue-summary {
  display: flex;
  gap: 1.5rem;
  padding: 1rem;
  background: var(--surface-50);
  border-radius: 0.5rem;
}

.summary-stat {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--text-color-secondary);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
}

.stat-value.status-failed {
  color: var(--red-600);
}
</style>
