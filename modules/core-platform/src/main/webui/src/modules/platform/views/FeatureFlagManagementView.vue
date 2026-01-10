<template>
  <div class="feature-flag-management">
    <ImpersonationBanner />
    <header class="flag-header">
      <div>
        <h1>Feature Flag Management</h1>
        <p class="subtitle">Platform-wide feature toggles and tenant overrides</p>
      </div>
      <div class="header-actions">
        <button class="refresh-btn" :disabled="loading" @click="refreshFlags">
          <i class="pi pi-refresh" />
          Refresh
        </button>
        <button class="stale-btn" @click="showStaleOnly = !showStaleOnly">
          <i class="pi pi-exclamation-circle" />
          {{ showStaleOnly ? 'Show All' : 'Show Stale' }}
          <span v-if="staleFlagCount > 0" class="badge">{{ staleFlagCount }}</span>
        </button>
      </div>
    </header>

    <div v-if="loading" class="loading-state">
      <i class="pi pi-spin pi-spinner" /> Loading feature flags...
    </div>
    <div v-else-if="error" class="error-state">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>

    <div v-else class="flag-table">
      <table>
        <thead>
          <tr>
            <th>Flag Key</th>
            <th>Description</th>
            <th>Default Value</th>
            <th>Overrides</th>
            <th>Last Reviewed</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="flag in displayedFlags" :key="flag.id">
            <td>
              <code>{{ flag.flagKey }}</code>
              <span v-if="flag.stale" class="stale-tag">Stale</span>
            </td>
            <td>{{ flag.description || '—' }}</td>
            <td>
              <span :class="['flag-value', flag.defaultValue ? 'enabled' : 'disabled']">
                {{ flag.defaultValue ? 'Enabled' : 'Disabled' }}
              </span>
            </td>
            <td>{{ flag.tenantOverrideCount || 0 }}</td>
            <td>{{ flag.lastReviewedAt ? formatDate(flag.lastReviewedAt) : 'Never' }}</td>
            <td>
              <button class="action-btn" @click="invalidateFlag(flag.id)">
                <i class="pi pi-trash" />
                Invalidate Cache
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="displayedFlags.length === 0" class="empty-state">
        <i class="pi pi-inbox" />
        No feature flags found
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'

const platformStore = usePlatformStore()
const { featureFlags, loading, error, staleFlagCount } = storeToRefs(platformStore)

const showStaleOnly = ref(false)

const displayedFlags = computed(() => {
  if (showStaleOnly.value) {
    return featureFlags.value.filter((f) => f.stale)
  }
  return featureFlags.value
})

onMounted(async () => {
  await platformStore.loadFeatureFlags()
})

async function refreshFlags() {
  await platformStore.loadFeatureFlags(showStaleOnly.value)
}

async function invalidateFlag(flagId: string) {
  if (!confirm('Are you sure you want to invalidate the cache for this feature flag?')) {
    return
  }

  try {
    // This would call an API to invalidate the flag's cache
    // For now, just refresh the list
    await refreshFlags()
  } catch (err) {
    console.error('Failed to invalidate flag cache:', err)
  }
}

function formatDate(value: string): string {
  const date = new Date(value)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Yesterday'
  if (diffDays < 30) return `${diffDays} days ago`
  if (diffDays < 365) return `${Math.floor(diffDays / 30)} months ago`
  return date.toLocaleDateString()
}
</script>

<style scoped>
.feature-flag-management {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.flag-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.subtitle {
  color: #666;
  margin-top: 0.35rem;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
}

.refresh-btn,
.stale-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border: 1px solid #ddd;
  background: white;
  padding: 0.5rem 1rem;
  border-radius: 4px;
  cursor: pointer;
}

.stale-btn {
  background: #fef3c7;
  border-color: #fbbf24;
  color: #92400e;
}

.badge {
  background: #ef4444;
  color: white;
  border-radius: 12px;
  padding: 0.1rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 600;
}

.flag-table {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th,
td {
  padding: 0.85rem;
  border-bottom: 1px solid #eee;
  text-align: left;
}

th {
  background: #f9fafb;
  font-weight: 600;
  color: #374151;
}

code {
  background: #f3f4f6;
  padding: 0.2rem 0.4rem;
  border-radius: 3px;
  font-size: 0.85rem;
  font-family: 'Courier New', monospace;
}

.stale-tag {
  display: inline-block;
  margin-left: 0.5rem;
  background: #fef3c7;
  color: #92400e;
  padding: 0.1rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 600;
}

.flag-value {
  padding: 0.25rem 0.6rem;
  border-radius: 4px;
  font-size: 0.85rem;
  font-weight: 500;
}

.flag-value.enabled {
  background: #d1fae5;
  color: #065f46;
}

.flag-value.disabled {
  background: #fee2e2;
  color: #991b1b;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.4rem 0.8rem;
  border: 1px solid #ddd;
  background: white;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.85rem;
}

.action-btn:hover {
  background: #f9fafb;
}

.loading-state,
.error-state,
.empty-state {
  text-align: center;
  padding: 2rem;
  color: #555;
}

.empty-state i {
  font-size: 3rem;
  margin-bottom: 1rem;
  color: #9ca3af;
}
</style>
