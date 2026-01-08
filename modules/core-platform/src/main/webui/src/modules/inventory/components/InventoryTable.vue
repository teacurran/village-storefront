<template>
  <div class="inventory-table">
    <DataTable
      :value="records"
      data-key="id"
      size="small"
      scrollable
      scroll-height="500px"
      class="p-datatable-sm"
      :loading="loading"
      selection-mode="single"
      :meta-key-selection="false"
      @row-click="(e) => emit('select', e.data)"
    >
      <template #empty>
        <div class="empty-state">
          <p>{{ t('inventory.emptyState') }}</p>
        </div>
      </template>

      <Column field="sku" :header="t('inventory.table.sku')" sortable>
        <template #body="{ data }">
          <span class="mono">{{ data.sku }}</span>
        </template>
      </Column>

      <Column field="productName" :header="t('inventory.table.product')" sortable>
        <template #body="{ data }">
          <div>
            <p class="product-name">{{ data.productName }}</p>
            <p class="location-text">{{ data.locationName }}</p>
          </div>
        </template>
      </Column>

      <Column field="available" :header="t('inventory.table.available')" sortable align="center">
        <template #body="{ data }">
          <Tag
            :severity="data.available <= lowStockThreshold ? 'danger' : 'success'"
            :value="data.available"
            rounded
          />
        </template>
      </Column>

      <Column field="reserved" :header="t('inventory.table.reserved')" sortable align="center" />
      <Column field="quantity" :header="t('inventory.table.onHand')" sortable align="center" />

      <Column field="daysInStock" :header="t('inventory.table.daysInStock')" sortable align="center">
        <template #body="{ data }">
          <span :class="{ 'text-warning-600': data.daysInStock > 30 }">{{ data.daysInStock }}</span>
        </template>
      </Column>
    </DataTable>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import type { InventoryRecord } from '../types'
import { useI18n } from '@/composables/useI18n'

const props = defineProps<{
  records: InventoryRecord[]
  loading: boolean
  lowStockThreshold?: number
}>()

const emit = defineEmits<{
  select: [record: InventoryRecord]
}>()

const { t } = useI18n()

const lowStockThreshold = computed(() => props.lowStockThreshold ?? 5)
</script>

<style scoped>
.inventory-table {
  @apply bg-white border border-neutral-200 rounded-lg shadow-xs;
}

.empty-state {
  @apply text-center py-6 text-neutral-500;
}

.product-name {
  @apply font-medium text-neutral-900;
}

.location-text {
  @apply text-xs text-neutral-500;
}

.mono {
  @apply font-mono text-sm;
}
</style>
