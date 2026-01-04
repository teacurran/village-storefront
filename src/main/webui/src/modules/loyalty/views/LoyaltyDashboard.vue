<template>
  <div class="loyalty-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">{{ t('loyalty.title') }}</h1>
        <p class="dashboard-subtitle">{{ t('loyalty.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <div class="sse-badge" :class="{ connected: loyaltyStore.sseConnected }">
          <span class="dot" />
          <span>{{ loyaltyStore.sseConnected ? t('common.live') : t('common.offline') }}</span>
        </div>
        <Button icon="pi pi-refresh" class="p-button-text" :label="t('common.refresh')" @click="loadProgram" />
      </div>
    </div>

    <InlineAlert
      v-if="!tenantStore.isFeatureEnabled('loyalty')"
      tone="info"
      :title="t('loyalty.featureDisabledTitle')"
      :description="t('loyalty.featureDisabledCopy')"
    />

    <div v-else class="space-y-6">
      <section class="program-card" v-if="loyaltyStore.program">
        <div>
          <p class="eyebrow">{{ t('loyalty.program.pointsPerDollar') }}</p>
          <h2>{{ loyaltyStore.program.pointsPerDollar }}</h2>
        </div>
        <div>
          <p class="eyebrow">{{ t('loyalty.program.redemptionValue') }}</p>
          <h2>{{ loyaltyStore.program.redemptionValuePerPoint }}</h2>
        </div>
        <div>
          <p class="eyebrow">{{ t('loyalty.program.expiration') }}</p>
          <h2>{{ loyaltyStore.program.pointsExpirationDays || '—' }}</h2>
        </div>
      </section>

      <section class="card">
        <header class="card-header">
          <div>
            <h2>{{ t('loyalty.tiers.title') }}</h2>
            <p>{{ t('loyalty.tiers.subtitle') }}</p>
          </div>
        </header>
        <DataTable :value="loyaltyStore.tiers" size="small">
          <Column field="name" :header="t('loyalty.table.tier')" />
          <Column field="minPoints" :header="t('loyalty.table.minPoints')" />
          <Column field="multiplier" :header="t('loyalty.table.multiplier')" />
        </DataTable>
      </section>

      <section class="card">
        <header class="card-header">
          <div>
            <h2>{{ t('loyalty.members.title') }}</h2>
            <p>{{ t('loyalty.members.subtitle') }}</p>
          </div>
        </header>
        <div class="lookup-form">
          <InputText
            v-model="lookupId"
            :placeholder="t('loyalty.members.lookupPlaceholder')"
            class="w-80"
          />
          <Button :label="t('loyalty.members.lookup')" @click="handleLookup" :disabled="!lookupId" />
        </div>

        <div v-if="loyaltyStore.member" class="member-card">
          <div class="member-summary">
            <p class="eyebrow">{{ t('loyalty.members.pointsBalance') }}</p>
            <h3>{{ loyaltyStore.member.pointsBalance }}</h3>
            <p class="eyebrow">{{ t('loyalty.members.tier') }}</p>
            <p class="tier-label">{{ loyaltyStore.member.currentTier }}</p>
          </div>
          <div class="member-actions">
            <Button
              v-if="authStore.hasRole('LOYALTY_ADMIN')"
              icon="pi pi-plus"
              :label="t('loyalty.actions.adjustPoints')"
              @click="showAdjustDialog = true"
            />
          </div>
        </div>

        <table v-if="loyaltyStore.transactions.length" class="transactions">
          <thead>
            <tr>
              <th>{{ t('loyalty.table.date') }}</th>
              <th>{{ t('loyalty.table.reason') }}</th>
              <th>{{ t('loyalty.table.points') }}</th>
              <th>{{ t('loyalty.table.balanceAfter') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="txn in loyaltyStore.transactions" :key="txn.id">
              <td>{{ new Date(txn.createdAt).toLocaleString() }}</td>
              <td>{{ txn.reason || txn.transactionType }}</td>
              <td :class="{ positive: txn.pointsDelta > 0, negative: txn.pointsDelta < 0 }">
                {{ txn.pointsDelta }}
              </td>
              <td>{{ txn.balanceAfter }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>

    <Dialog :visible="showAdjustDialog" modal :header="t('loyalty.actions.adjustPoints')" @hide="closeAdjustDialog">
      <div class="dialog-body">
        <label>{{ t('loyalty.adjust.points') }}</label>
        <InputNumber v-model.number="adjustPointsValue" :min="-1000" :max="1000" show-buttons />
        <label>{{ t('loyalty.adjust.reason') }}</label>
        <Dropdown v-model="adjustReason" :options="reasonOptions" option-label="label" option-value="value" />
      </div>
      <template #footer>
        <Button class="p-button-text" :label="t('common.cancel')" @click="closeAdjustDialog" />
        <Button :label="t('common.confirm')" :disabled="!adjustPointsValue || !adjustReason" @click="handleAdjust" />
      </template>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Dropdown from 'primevue/dropdown'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InlineAlert from '@/components/base/InlineAlert.vue'
import { useLoyaltyStore } from '../store'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import { useI18n } from '@/composables/useI18n'
import { useToast } from 'primevue/usetoast'

const loyaltyStore = useLoyaltyStore()
const authStore = useAuthStore()
const tenantStore = useTenantStore()
const { t } = useI18n()
const toast = useToast()

const lookupId = ref('')
const showAdjustDialog = ref(false)
const adjustPointsValue = ref<number | null>(null)
const adjustReason = ref<string | null>(null)

const reasonOptions = [
  { label: t('loyalty.adjust.reasonBonus'), value: 'bonus' },
  { label: t('loyalty.adjust.reasonCorrection'), value: 'correction' },
  { label: t('loyalty.adjust.reasonAppeasement'), value: 'appeasement' },
]

onMounted(async () => {
  authStore.restoreAuth()
  if (!authStore.hasRole('LOYALTY_ADMIN')) return

  if (!tenantStore.currentTenant) {
    await tenantStore.loadTenant()
  }

  if (!tenantStore.isFeatureEnabled('loyalty')) return

  await loadProgram()
  loyaltyStore.connectSSE()
})

onBeforeUnmount(() => {
  loyaltyStore.disconnectSSE()
})

async function loadProgram() {
  try {
    await loyaltyStore.loadProgram()
  } catch (error) {
    console.error('Failed to load loyalty program', error)
    toast.add({ severity: 'error', summary: t('loyalty.errors.loadFailed') })
  }
}

async function handleLookup() {
  if (!lookupId.value) return
  try {
    await loyaltyStore.lookupMember(lookupId.value)
  } catch (error) {
    toast.add({ severity: 'error', summary: t('loyalty.errors.lookupFailed') })
  }
}

function closeAdjustDialog() {
  showAdjustDialog.value = false
  adjustPointsValue.value = null
  adjustReason.value = null
}

async function handleAdjust() {
  if (!adjustPointsValue.value || !adjustReason.value) return
  try {
    await loyaltyStore.adjustPoints(adjustPointsValue.value, adjustReason.value)
    toast.add({ severity: 'success', summary: t('loyalty.messages.adjusted') })
  } catch (error) {
    toast.add({ severity: 'error', summary: t('loyalty.errors.adjustFailed') })
  } finally {
    closeAdjustDialog()
  }
}
</script>

<style scoped>
.loyalty-dashboard {
  @apply max-w-6xl mx-auto px-4 py-6 space-y-6;
}

.dashboard-header {
  @apply flex items-center justify-between;
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
  @apply flex items-center gap-2 px-3 py-1 rounded-full bg-neutral-100 text-neutral-600;
}

.sse-badge.connected {
  @apply bg-success-50 text-success-700;
}

.sse-badge .dot {
  @apply w-2 h-2 rounded-full bg-neutral-400;
}

.program-card {
  @apply grid grid-cols-1 md:grid-cols-3 gap-6 bg-white border border-neutral-200 rounded-lg p-4;
}

.eyebrow {
  @apply text-xs uppercase text-neutral-500;
}

.card {
  @apply bg-white border border-neutral-200 rounded-lg shadow-sm p-4 space-y-4;
}

.card-header {
  @apply flex items-start justify-between;
}

.lookup-form {
  @apply flex items-center gap-3;
}

.member-card {
  @apply flex items-center justify-between bg-neutral-50 border border-neutral-200 rounded-lg p-4;
}

.tier-label {
  @apply text-sm font-medium text-primary-600;
}

.transactions {
  @apply w-full text-sm;
}

.transactions th {
  @apply text-left text-xs uppercase text-neutral-500 border-b border-neutral-200 pb-2;
}

.transactions td {
  @apply py-2 border-b border-neutral-100;
}

.transactions .positive {
  @apply text-success-600;
}

.transactions .negative {
  @apply text-danger-600;
}

.dialog-body {
  @apply space-y-3;
}
</style>
