<template>
  <div class="store-directory">
    <ImpersonationBanner />
    <div class="directory-header">
      <h1>Store Directory</h1>
      <p class="subtitle">Manage all stores across the platform</p>
    </div>

    <div class="directory-filters">
      <div class="filter-row">
        <input
          v-model="searchQuery"
          type="text"
          placeholder="Search by subdomain or name..."
          class="search-input"
          @input="handleSearchChange"
        />
        <select v-model="statusFilter" @change="handleFilterChange" class="status-filter">
          <option value="">All Statuses</option>
          <option value="active">Active</option>
          <option value="suspended">Suspended</option>
          <option value="trial">Trial</option>
        </select>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <i class="pi pi-spin pi-spinner"></i> Loading stores...
    </div>

    <div v-else-if="error" class="error-state">
      <i class="pi pi-exclamation-triangle"></i>
      {{ error }}
    </div>

    <div v-else class="stores-table">
      <table>
        <thead>
          <tr>
            <th>Subdomain</th>
            <th>Name</th>
            <th>Status</th>
            <th>Users</th>
            <th>Plan</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="store in stores" :key="store.id" class="store-row">
            <td class="subdomain-cell">
              <code>{{ store.subdomain }}</code>
            </td>
            <td>{{ store.name }}</td>
            <td>
              <span :class="['status-badge', `status-${store.status}`]">
                {{ store.status }}
              </span>
            </td>
            <td>
              {{ store.activeUserCount }} / {{ store.userCount }}
            </td>
            <td>
              <span class="plan-badge">{{ store.plan }}</span>
            </td>
            <td>{{ formatDate(store.createdAt) }}</td>
            <td>
              <div class="action-buttons">
                <button
                  @click="viewStoreDetails(store.id)"
                  class="btn-secondary"
                  title="View Details"
                >
                  <i class="pi pi-eye"></i>
                </button>
                <button
                  v-if="store.status === 'active'"
                  @click="showSuspendDialog(store)"
                  class="btn-danger"
                  title="Suspend Store"
                >
                  <i class="pi pi-ban"></i>
                </button>
                <button
                  v-if="store.status === 'suspended'"
                  @click="handleReactivate(store.id)"
                  class="btn-success"
                  title="Reactivate Store"
                >
                  <i class="pi pi-check"></i>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="stores.length === 0" class="empty-state">
        <i class="pi pi-inbox"></i>
        <p>No stores found</p>
      </div>
    </div>

    <div v-if="!loading && stores.length > 0" class="pagination">
      <button
        @click="loadPreviousPage"
        :disabled="storePagination.page === 0"
        class="pagination-btn"
      >
        <i class="pi pi-chevron-left"></i> Previous
      </button>
      <span class="pagination-info">
        Page {{ storePagination.page + 1 }} of {{ totalPages }}
        ({{ storePagination.total }} total stores)
      </span>
      <button @click="loadNextPage" :disabled="!hasNextPage" class="pagination-btn">
        Next <i class="pi pi-chevron-right"></i>
      </button>
    </div>

    <!-- Suspend Dialog -->
    <dialog ref="suspendDialog" class="modal">
      <div class="modal-content">
        <h2>Suspend Store</h2>
        <p>Are you sure you want to suspend <strong>{{ selectedStore?.name }}</strong>?</p>
        <textarea
          v-model="suspendReason"
          placeholder="Enter suspension reason (required)..."
          class="reason-textarea"
          rows="4"
        ></textarea>
        <div class="modal-actions">
          <button @click="closeSuspendDialog" class="btn-secondary">Cancel</button>
          <button
            @click="handleSuspend"
            :disabled="suspendReason.trim().length < 10"
            class="btn-danger"
          >
            Suspend Store
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import type { StoreDirectoryEntry } from '../types'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'

const platformStore = usePlatformStore()
const { stores, storePagination, loading, error } = storeToRefs(platformStore)

const searchQuery = ref('')
const statusFilter = ref('')
const suspendDialog = ref<HTMLDialogElement | null>(null)
const selectedStore = ref<StoreDirectoryEntry | null>(null)
const suspendReason = ref('')

const totalPages = computed(() => Math.ceil(storePagination.value.total / storePagination.value.size))
const hasNextPage = computed(
  () => (storePagination.value.page + 1) * storePagination.value.size < storePagination.value.total,
)

onMounted(() => {
  platformStore.loadStores()
})

function handleSearchChange() {
  platformStore.updateStoreFilters({ status: statusFilter.value, search: searchQuery.value })
  platformStore.loadStores(0)
}

function handleFilterChange() {
  platformStore.updateStoreFilters({ status: statusFilter.value, search: searchQuery.value })
  platformStore.loadStores(0)
}

function loadPreviousPage() {
  if (storePagination.value.page > 0) {
    platformStore.loadStores(storePagination.value.page - 1, false)
  }
}

function loadNextPage() {
  if (hasNextPage.value) {
    platformStore.loadStores(storePagination.value.page + 1, false)
  }
}

function viewStoreDetails(storeId: string) {
  // Navigate to store details view
  console.log('View store details:', storeId)
}

function showSuspendDialog(store: StoreDirectoryEntry) {
  selectedStore.value = store
  suspendReason.value = ''
  suspendDialog.value?.showModal()
}

function closeSuspendDialog() {
  suspendDialog.value?.close()
  selectedStore.value = null
}

async function handleSuspend() {
  if (!selectedStore.value || suspendReason.value.trim().length < 10) return

  try {
    await platformStore.suspendStore(selectedStore.value.id, suspendReason.value)
    closeSuspendDialog()
  } catch (err) {
    console.error('Failed to suspend store:', err)
  }
}

async function handleReactivate(storeId: string) {
  if (!confirm('Are you sure you want to reactivate this store?')) return

  try {
    await platformStore.reactivateStore(storeId)
  } catch (err) {
    console.error('Failed to reactivate store:', err)
  }
}

function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<style scoped>
.store-directory {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.directory-header {
  margin-bottom: 2rem;
}

.directory-header h1 {
  font-size: 2rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.subtitle {
  color: #666;
  font-size: 1rem;
}

.directory-filters {
  margin-bottom: 2rem;
}

.filter-row {
  display: flex;
  gap: 1rem;
}

.search-input,
.status-filter {
  padding: 0.75rem 1rem;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 1rem;
}

.search-input {
  flex: 1;
}

.stores-table {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead {
  background: #f5f5f5;
}

th {
  text-align: left;
  padding: 1rem;
  font-weight: 600;
  color: #333;
}

td {
  padding: 1rem;
  border-top: 1px solid #e0e0e0;
}

.subdomain-cell code {
  background: #f0f0f0;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-family: monospace;
}

.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
  font-size: 0.85rem;
  font-weight: 500;
  text-transform: capitalize;
}

.status-active {
  background: #d4edda;
  color: #155724;
}

.status-suspended {
  background: #f8d7da;
  color: #721c24;
}

.status-trial {
  background: #fff3cd;
  color: #856404;
}

.plan-badge {
  text-transform: capitalize;
  font-weight: 500;
  color: #666;
}

.action-buttons {
  display: flex;
  gap: 0.5rem;
}

.action-buttons button {
  padding: 0.5rem;
  border-radius: 4px;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary {
  background: #6c757d;
  color: white;
}

.btn-danger {
  background: #dc3545;
  color: white;
}

.btn-success {
  background: #28a745;
  color: white;
}

.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 1rem;
  padding: 1rem;
}

.pagination-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border: 1px solid #ddd;
  background: white;
  border-radius: 4px;
  cursor: pointer;
}

.pagination-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal {
  border: none;
  border-radius: 8px;
  padding: 2rem;
  max-width: 500px;
}

.modal::backdrop {
  background: rgba(0, 0, 0, 0.5);
}

.modal-content h2 {
  margin-top: 0;
}

.reason-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-family: inherit;
  margin: 1rem 0;
}

.modal-actions {
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
}

.loading-state,
.error-state,
.empty-state {
  text-align: center;
  padding: 3rem;
  color: #666;
}
</style>
