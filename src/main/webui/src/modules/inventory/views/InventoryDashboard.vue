<template>
  <div class="inventory-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('inventory.title') }}</h1>
        <p class="dashboard-subtitle">{{ t('inventory.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <div class="sse-badge" :class="{ connected: inventoryStore.sseConnected }">
          <span class="dot" />
          <span>{{ inventoryStore.sseConnected ? t('common.live') : t('common.offline') }}</span>
        </div>
        <Button
          icon="pi pi-refresh"
          class="p-button-text"
          :label="t('common.refresh')"
          @click="handleRefresh"
        />
      </div>
    </div>

    <InlineAlert
      v-if="!tenantStore.isFeatureEnabled('inventory')"
      tone="info"
      :title="t('inventory.upgradeTitle')"
      :description="t('inventory.upgradeCopy')"
    />

    <div v-else>
      <div class="filters-card">
        <Dropdown
          class="w-64"
          :options="locationOptions"
          option-label="name"
          option-value="id"
          :placeholder="t('inventory.filters.location')"
          v-model="selectedLocationId"
          show-clear
        />
        <span class="divider" />
        <span class="filter-field">
          <i class="pi pi-search" />
          <InputText v-model="search" :placeholder="t('inventory.filters.search')" />
        </span>
        <span class="divider" />
        <div class="toggle-field">
          <label>{{ t('inventory.filters.lowStockOnly') }}</label>
          <InputSwitch v-model="lowStockOnly" />
        </div>
      </div>

      <div class="content-grid">
        <section class="table-card">
          <header class="card-header">
            <div>
              <p class="card-eyebrow">{{ t('inventory.metrics.totalSkus') }}</p>
              <h2>{{ inventoryStore.filteredInventory.length }}</h2>
            </div>
            <div>
              <p class="card-eyebrow">{{ t('inventory.metrics.lowStock') }}</p>
              <h2>{{ inventoryStore.lowStockCount }}</h2>
            </div>
          </header>

          <InventoryTable
            :records="inventoryStore.filteredInventory"
            :loading="inventoryStore.loading"
            @select="inventoryStore.selectRecord"
          />
        </section>

        <section>
          <InventoryDetailPanel
            :record="inventoryStore.selectedRecord"
            :transfers="inventoryStore.transfers"
            :visible="!!inventoryStore.selectedRecord"
            @close="inventoryStore.selectRecord(null)"
            @adjust-request="showAdjustmentDialog = true"
            @refresh-transfers="inventoryStore.refreshTransfers"
          />
        </section>
      </div>
    </div>

    <InventoryAdjustmentDialog
      :visible="showAdjustmentDialog"
      @close="showAdjustmentDialog = false"
      @save="handleAdjustmentSave"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Dropdown from 'primevue/dropdown'
import InputText from 'primevue/inputtext'
import InputSwitch from 'primevue/inputswitch'
import InlineAlert from '@/components/base/InlineAlert.vue'
import { useInventoryStore } from '../store'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import { useI18n } from '@/composables/useI18n'
import InventoryTable from '../components/InventoryTable.vue'
import InventoryDetailPanel from '../components/InventoryDetailPanel.vue'
import InventoryAdjustmentDialog from '../components/InventoryAdjustmentDialog.vue'
import { useToast } from 'primevue/usetoast'

const inventoryStore = useInventoryStore()
const authStore = useAuthStore()
const tenantStore = useTenantStore()
const { t } = useI18n()
const toast = useToast()

const selectedLocationId = ref<string | null>(null)
const search = ref('')
const lowStockOnly = ref(false)
const showAdjustmentDialog = ref(false)

const locationOptions = computed(() => inventoryStore.locations)

onMounted(async () => {
  authStore.restoreAuth()
  if (!authStore.hasRole('INVENTORY_VIEW')) return

  if (!tenantStore.currentTenant) {
    await tenantStore.loadTenant()
  }

  if (!tenantStore.isFeatureEnabled('inventory')) return

  await inventoryStore.loadDashboard()
  inventoryStore.connectSSE()
})

onBeforeUnmount(() => {
  inventoryStore.disconnectSSE()
})

watch([selectedLocationId, search, lowStockOnly], () => {
  inventoryStore.applyFilters({
    locationId: selectedLocationId.value || undefined,
    search: search.value || undefined,
    lowStockOnly: lowStockOnly.value,
  })
})

async function handleRefresh() {
  try {
    await inventoryStore.loadDashboard()
    toast.add({ severity: 'success', summary: t('inventory.messages.refreshed') })
  } catch (error) {
    console.error('Failed to refresh inventory', error)
    toast.add({ severity: 'error', summary: t('inventory.errors.loadFailed') })
  }
}

async function handleAdjustmentSave(payload: { quantityChange: number; reason: string; notes?: string }) {
  try {
    await inventoryStore.recordAdjustment(payload)
    showAdjustmentDialog.value = false
    toast.add({ severity: 'success', summary: t('inventory.messages.adjusted') })
  } catch (error) {
    console.error('Adjustment failed', error)
    toast.add({ severity: 'error', summary: t('inventory.errors.adjustFailed') })
  }
}
</script>

<style scoped>
.inventory-dashboard {
  @apply max-w-7xl mx-auto px-4 py-6 space-y-6;
}

.dashboard-header {
  @apply flex items-start justify-between;
}

.dashboard-title {
  @apply text-3xl font-bold text-neutral-900;
}

.dashboard-subtitle {
  @apply text-neutral-600;
}

.header-actions {
  @apply flex items-center gap-3;
}

.sse-badge {
  @apply flex items-center gap-2 px-3 py-1 rounded-full text-sm bg-neutral-100 text-neutral-700;
}

.sse-badge.connected {
  @apply bg-success-50 text-success-700;
}

.sse-badge .dot {
  @apply w-2 h-2 rounded-full bg-neutral-400;
}

.sse-badge.connected .dot {
  @apply bg-success-500;
}

.filters-card {
  @apply flex items-center gap-4 bg-white border border-neutral-200 rounded-lg p-4 shadow-xs;
}

.filter-field {
  @apply flex items-center gap-2 text-neutral-500;
}

.divider {
  @apply w-px h-8 bg-neutral-200;
}

.toggle-field {
  @apply flex items-center gap-2;
}

.content-grid {
  @apply grid grid-cols-1 lg:grid-cols-4 gap-6;
}

.table-card {
  @apply lg:col-span-3 bg-white border border-neutral-200 rounded-lg shadow-sm p-4 space-y-4;
}

.card-header {
  @apply flex items-center justify-between;
}

.card-eyebrow {
  @apply text-xs uppercase text-neutral-500;
}
</style>
