<template>
  <div class="offline-indicator" :class="indicatorClass">
    <div class="indicator-badge" :title="tooltipText">
      <i :class="iconClass"></i>
      <span class="indicator-text">{{ statusText }}</span>
      <span v-if="queueCount > 0" class="queue-count">{{ queueCount }}</span>
    </div>

    <!-- Sync progress -->
    <div v-if="isSyncing" class="sync-progress">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: syncProgress + '%' }"></div>
      </div>
      <span class="progress-text">Syncing {{ syncProgress }}%</span>
    </div>

    <!-- Error message -->
    <div v-if="hasError" class="error-message">
      <i class="pi pi-exclamation-triangle"></i>
      <span>{{ errorMessage }}</span>
      <button @click="retrySync" class="retry-btn">Retry</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useOfflineStore } from './offlineStore'

const offlineStore = useOfflineStore()

const isOffline = computed(() => offlineStore.isOfflineMode)
const isSyncing = computed(() => offlineStore.isSyncing)
const queueCount = computed(
  () => offlineStore.queueStats.queued + offlineStore.queueStats.syncing + offlineStore.queueStats.failed
)
const hasError = computed(() => offlineStore.hasSyncErrors)
const errorMessage = computed(() => offlineStore.syncError || 'Sync failed')
const isSyncOnHold = computed(() => offlineStore.isSyncOnHold)

const statusText = computed(() => {
  if (isSyncOnHold.value) return 'On Hold'
  if (isSyncing.value) return 'Syncing...'
  if (isOffline.value) return 'Offline'
  if (queueCount.value > 0) return 'Pending'
  return 'Online'
})

const indicatorClass = computed(() => ({
  'offline': isOffline.value,
  'syncing': isSyncing.value,
  'error': hasError.value,
  'hold': isSyncOnHold.value,
  'online': !isOffline.value && queueCount.value === 0 && !isSyncOnHold.value,
}))

const iconClass = computed(() => ({
  'pi': true,
  'pi-cloud-upload': isSyncing.value,
  'pi-wifi': !isOffline.value,
  'pi-exclamation-circle': isOffline.value || hasError.value,
}))

const tooltipText = computed(() => {
  if (isSyncOnHold.value) return 'Sync paused - resume when ready'
  if (isSyncing.value) return 'Syncing offline transactions...'
  if (isOffline.value) return 'No connection - transactions will be queued'
  if (queueCount.value > 0) return `${queueCount.value} transactions pending sync`
  return 'Connected and synced'
})

const syncProgress = computed(() => {
  const total = offlineStore.queueStats.total
  const synced = offlineStore.queueStats.synced
  if (total === 0) return 0
  return Math.round((synced / total) * 100)
})

function retrySync() {
  offlineStore.syncQueue()
}
</script>

<style scoped>
.offline-indicator {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  transition: all 0.3s ease;
}

.indicator-badge {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
}

.queue-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.5rem;
  height: 1.5rem;
  padding: 0 0.5rem;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 700;
}

/* Status-specific styles */
.offline-indicator.online {
  background: var(--green-100);
  color: var(--green-700);
}

.offline-indicator.offline {
  background: var(--orange-100);
  color: var(--orange-700);
  animation: pulse 2s ease-in-out infinite;
}

.offline-indicator.syncing {
  background: var(--blue-100);
  color: var(--blue-700);
}

.offline-indicator.hold {
  background: var(--yellow-100);
  color: var(--yellow-800);
}

.offline-indicator.error {
  background: var(--red-100);
  color: var(--red-700);
}

/* Pulse animation for offline state */
@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.7;
  }
}

.sync-progress {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.progress-bar {
  width: 100%;
  height: 0.5rem;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 9999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: currentColor;
  border-radius: 9999px;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 0.75rem;
  opacity: 0.8;
}

.error-message {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 0.25rem;
  font-size: 0.75rem;
}

.retry-btn {
  margin-left: auto;
  padding: 0.25rem 0.75rem;
  background: currentColor;
  color: white;
  border: none;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
  cursor: pointer;
  opacity: 0.9;
  transition: opacity 0.2s;
}

.retry-btn:hover {
  opacity: 1;
}
</style>
