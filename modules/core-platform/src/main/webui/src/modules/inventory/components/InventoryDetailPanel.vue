<template>
  <Sidebar
    :visible="visible"
    position="right"
    class="inventory-detail-panel"
    :show-close-icon="false"
    @hide="emit('close')"
  >
    <div class="panel-header">
      <div>
        <p class="panel-eyebrow">{{ t('inventory.detailDrawer.subtitle') }}</p>
        <h2 class="panel-title">{{ record?.productName }}</h2>
      </div>
      <Button icon="pi pi-times" class="p-button-text" @click="emit('close')" />
    </div>

    <div v-if="!record" class="panel-empty">
      <p>{{ t('inventory.detailDrawer.empty') }}</p>
    </div>

    <div v-else class="panel-content">
      <section class="detail-section">
        <div class="detail-row">
          <span>{{ t('inventory.table.sku') }}</span>
          <strong>{{ record.sku }}</strong>
        </div>
        <div class="detail-row">
          <span>{{ t('inventory.detailDrawer.location') }}</span>
          <strong>{{ record.locationName }}</strong>
        </div>
        <div class="detail-grid">
          <div>
            <p class="label">{{ t('inventory.table.onHand') }}</p>
            <p class="value">{{ record.quantity }}</p>
          </div>
          <div>
            <p class="label">{{ t('inventory.table.reserved') }}</p>
            <p class="value">{{ record.reserved }}</p>
          </div>
          <div>
            <p class="label">{{ t('inventory.table.available') }}</p>
            <p class="value">{{ record.available }}</p>
          </div>
        </div>
        <div class="detail-row">
          <span>{{ t('inventory.table.daysInStock') }}</span>
          <strong>{{ record.daysInStock }}</strong>
        </div>
        <div v-if="record.dataFreshnessTimestamp" class="detail-row">
          <span>{{ t('inventory.detailDrawer.lastUpdated') }}</span>
          <strong>{{ formatDate(record.dataFreshnessTimestamp) }}</strong>
        </div>
      </section>

      <section class="detail-section">
        <header class="section-header">
          <h3>{{ t('inventory.detailDrawer.transfers') }}</h3>
          <Button
            icon="pi pi-refresh"
            class="p-button-text"
            :label="t('common.refresh')"
            @click="emit('refreshTransfers')"
          />
        </header>
        <ul class="transfer-list">
          <li v-for="transfer in relatedTransfers" :key="transfer.transferId" class="transfer-item">
            <div>
              <p class="transfer-title">#{{ transfer.transferId.slice(0, 8) }}</p>
              <p class="transfer-meta">
                {{ transfer.status }} · {{ transfer.createdAt ? formatDate(transfer.createdAt) : '' }}
              </p>
            </div>
            <span>{{ transfer.lines.reduce((sum, line) => sum + (line.quantity || 0), 0) }}</span>
          </li>
          <li v-if="!relatedTransfers.length" class="transfer-empty">
            {{ t('inventory.detailDrawer.noTransfers') }}
          </li>
        </ul>
      </section>

      <section class="detail-section">
        <header class="section-header">
          <h3>{{ t('inventory.detailDrawer.actions') }}</h3>
        </header>
        <div class="actions">
          <Button
            icon="pi pi-plus"
            class="p-button"
            :label="t('inventory.detailDrawer.adjust')"
            @click="emit('adjustRequest')"
          />
        </div>
      </section>
    </div>
  </Sidebar>
</template>

<script setup lang="ts">
import Sidebar from 'primevue/sidebar'
import Button from 'primevue/button'
import type { InventoryRecord, InventoryTransferSummary } from '../types'
import { computed } from 'vue'
import { useI18n } from '@/composables/useI18n'

const props = defineProps<{
  record: InventoryRecord | null
  transfers: InventoryTransferSummary[]
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
  adjustRequest: []
  refreshTransfers: []
}>()

const { t } = useI18n()

const relatedTransfers = computed(() => {
  if (!props.record) return []
  return props.transfers.filter((transfer) =>
    transfer.lines?.some((line) => line.variantId === props.record?.variantId)
  )
})

function formatDate(timestamp: string) {
  return new Date(timestamp).toLocaleString()
}
</script>

<style scoped>
.inventory-detail-panel {
  --p-sidebar-width: min(420px, 100%);
}

.panel-header {
  @apply flex items-start justify-between mb-6;
}

.panel-eyebrow {
  @apply uppercase text-xs text-neutral-500;
}

.panel-title {
  @apply text-2xl font-semibold text-neutral-900;
}

.panel-empty {
  @apply text-neutral-500;
}

.panel-content {
  @apply space-y-6;
}

.detail-section {
  @apply bg-neutral-50 border border-neutral-200 rounded-lg p-4;
}

.detail-row {
  @apply flex justify-between text-sm mb-2;
}

.detail-grid {
  @apply grid grid-cols-3 gap-4 my-3;
}

.label {
  @apply text-xs text-neutral-500;
}

.value {
  @apply text-lg font-semibold text-neutral-900;
}

.transfer-list {
  @apply space-y-3;
}

.transfer-item {
  @apply flex items-center justify-between text-sm;
}

.transfer-title {
  @apply font-medium text-neutral-900;
}

.transfer-meta {
  @apply text-xs text-neutral-500;
}

.transfer-empty {
  @apply text-sm text-neutral-500;
}

.actions {
  @apply flex gap-3;
}
</style>
