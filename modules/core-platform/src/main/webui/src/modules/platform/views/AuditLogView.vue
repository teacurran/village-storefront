<template>
  <div class="audit-log">
    <ImpersonationBanner />
    <header class="audit-header">
      <div>
        <h1>Audit Log Viewer</h1>
        <p class="subtitle">Filter and paginate immutable platform actions</p>
      </div>
      <button class="refresh-btn" :disabled="loading" @click="refreshLogs">
        <i class="pi pi-refresh" />
        Refresh
      </button>
    </header>

    <section class="filters">
      <label>
        Actor ID
        <input v-model="actorId" type="text" placeholder="Platform admin UUID" />
      </label>
      <label>
        Action
        <select v-model="action">
          <option value="">All</option>
          <option value="impersonate_start">impersonate_start</option>
          <option value="impersonate_stop">impersonate_stop</option>
          <option value="suspend_store">suspend_store</option>
          <option value="reactivate_store">reactivate_store</option>
        </select>
      </label>
      <label>
        Target Type
        <input v-model="targetType" type="text" placeholder="tenant, user..." />
      </label>
      <label>
        Start Date
        <input v-model="startDate" type="datetime-local" />
      </label>
      <label>
        End Date
        <input v-model="endDate" type="datetime-local" />
      </label>
      <div class="filter-actions">
        <button class="btn-secondary" @click="clearFilters">Clear</button>
        <button class="btn-primary" :disabled="loading" @click="applyFilters">Apply Filters</button>
      </div>
    </section>

    <div v-if="loading" class="loading-state">
      <i class="pi pi-spin pi-spinner" /> Loading audit entries...
    </div>
    <div v-else-if="error" class="error-state">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>

    <div v-else class="audit-table">
      <table>
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Actor</th>
            <th>Action</th>
            <th>Target</th>
            <th>Reason</th>
            <th>Ticket</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in auditLogs" :key="entry.id">
            <td>{{ formatTimestamp(entry.occurredAt) }}</td>
            <td>
              <div class="actor-cell">
                <span class="actor-email">{{ entry.actorEmail || 'system' }}</span>
                <span class="actor-id" v-if="entry.actorId">{{ entry.actorId }}</span>
              </div>
            </td>
            <td>{{ entry.action }}</td>
            <td>{{ entry.targetType }} {{ entry.targetId }}</td>
            <td>{{ entry.reason || '—' }}</td>
            <td>{{ entry.ticketNumber || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="auditLogs.length === 0" class="empty-state">
        <i class="pi pi-inbox" />
        No audit entries match the current filters
      </div>
    </div>

    <footer class="pagination" v-if="auditLogs.length > 0">
      <button class="pagination-btn" :disabled="auditPagination.page === 0" @click="loadPreviousPage">
        <i class="pi pi-chevron-left" /> Previous
      </button>
      <span>
        Page {{ auditPagination.page + 1 }} of {{ totalPages }} ({{ auditPagination.total }} total entries)
      </span>
      <button class="pagination-btn" :disabled="!hasNextPage" @click="loadNextPage">
        Next <i class="pi pi-chevron-right" />
      </button>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'

const platformStore = usePlatformStore()
const { auditLogs, auditPagination, loading, error } = storeToRefs(platformStore)

const actorId = ref('')
const action = ref('')
const targetType = ref('')
const startDate = ref('')
const endDate = ref('')

const totalPages = computed(() =>
  Math.ceil(auditPagination.value.total / auditPagination.value.size) || 1,
)
const hasNextPage = computed(
  () => (auditPagination.value.page + 1) * auditPagination.value.size < auditPagination.value.total,
)

onMounted(async () => {
  await platformStore.loadAuditLogs()
})

async function refreshLogs() {
  await platformStore.loadAuditLogs(auditPagination.value.page)
}

function applyFilters() {
  platformStore.updateAuditFilters({
    actorId: actorId.value || undefined,
    action: action.value || undefined,
    targetType: targetType.value || undefined,
    startDate: startDate.value ? new Date(startDate.value).toISOString() : undefined,
    endDate: endDate.value ? new Date(endDate.value).toISOString() : undefined,
  })
  platformStore.loadAuditLogs(0)
}

function clearFilters() {
  actorId.value = ''
  action.value = ''
  targetType.value = ''
  startDate.value = ''
  endDate.value = ''
  platformStore.updateAuditFilters({})
  platformStore.loadAuditLogs(0)
}

function loadPreviousPage() {
  if (auditPagination.value.page > 0) {
    platformStore.loadAuditLogs(auditPagination.value.page - 1)
  }
}

function loadNextPage() {
  if (hasNextPage.value) {
    platformStore.loadAuditLogs(auditPagination.value.page + 1)
  }
}

function formatTimestamp(timestamp: string): string {
  return new Date(timestamp).toLocaleString()
}
</script>

<style scoped>
.audit-log {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.audit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.subtitle {
  color: #666;
  margin-top: 0.35rem;
}

.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
  background: #fff;
  padding: 1rem;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.filters label {
  display: flex;
  flex-direction: column;
  font-size: 0.9rem;
  color: #444;
}

.filters input,
.filters select {
  margin-top: 0.35rem;
  padding: 0.5rem;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.filter-actions {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
}

.btn-primary,
.btn-secondary {
  padding: 0.5rem 1rem;
  border-radius: 4px;
  border: none;
  cursor: pointer;
}

.btn-primary {
  background: #2563eb;
  color: white;
}

.btn-secondary {
  background: #e5e7eb;
  color: #111;
}

.audit-table {
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

.actor-cell {
  display: flex;
  flex-direction: column;
}

.actor-email {
  font-weight: 500;
}

.actor-id {
  font-size: 0.8rem;
  color: #666;
}

.pagination {
  margin-top: 1rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pagination-btn,
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border: 1px solid #ddd;
  background: white;
  padding: 0.5rem 1rem;
  border-radius: 4px;
  cursor: pointer;
}

.loading-state,
.error-state,
.empty-state {
  text-align: center;
  padding: 2rem;
  color: #555;
}
</style>
