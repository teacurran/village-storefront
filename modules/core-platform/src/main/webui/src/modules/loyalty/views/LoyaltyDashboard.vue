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
        <Button
          icon="pi pi-refresh"
          class="p-button-text"
          :label="t('common.refresh')"
          @click="loadProgram"
        />
      </div>
    </div>

    <InlineAlert
      v-if="!tenantStore.isFeatureEnabled('loyalty')"
      tone="info"
      :title="t('loyalty.featureDisabledTitle')"
      :description="t('loyalty.featureDisabledCopy')"
    />

    <div v-else class="space-y-6">
      <section v-if="loyaltyStore.program" class="program-card">
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

      <section v-if="pilotEnabled" class="card program-builder">
        <header class="card-header">
          <div>
            <h2>{{ t('loyalty.program.builderTitle') }}</h2>
            <p>{{ t('loyalty.program.builderSubtitle') }}</p>
          </div>
          <div class="toggle-row">
            <span>{{ t('loyalty.program.enabledLabel') }}</span>
            <InputSwitch v-model="programForm.enabled" />
          </div>
        </header>
        <div class="program-form-grid">
          <div class="form-control">
            <label>{{ t('loyalty.program.nameLabel') }}</label>
            <InputText v-model="programForm.name" />
          </div>
          <div class="form-control col-span-2">
            <label>{{ t('loyalty.program.descriptionLabel') }}</label>
            <Textarea v-model="programForm.description" rows="2" auto-resize />
          </div>
          <div class="form-control">
            <label>{{ t('loyalty.program.pointsPerDollar') }}</label>
            <InputNumber v-model.number="programForm.pointsPerDollar" mode="decimal" :minFractionDigits="2" :maxFractionDigits="2" :min="0.1" show-buttons />
          </div>
          <div class="form-control">
            <label>{{ t('loyalty.program.redemptionValue') }}</label>
            <InputNumber v-model.number="programForm.redemptionValuePerPoint" mode="decimal" :minFractionDigits="2" :maxFractionDigits="2" :min="0.01" show-buttons />
          </div>
          <div class="form-control">
            <label>{{ t('loyalty.program.minRedemption') }}</label>
            <InputNumber v-model.number="programForm.minRedemptionPoints" :min="1" show-buttons />
          </div>
          <div class="form-control">
            <label>{{ t('loyalty.program.maxRedemption') }}</label>
            <InputNumber v-model.number="programForm.maxRedemptionPoints" :min="programForm.minRedemptionPoints || 1" show-buttons />
          </div>
          <div class="form-control">
            <label>{{ t('loyalty.program.expiration') }}</label>
            <InputNumber v-model.number="programForm.pointsExpirationDays" :min="0" show-buttons />
          </div>
        </div>

        <div class="tier-builder">
          <div class="tier-row" v-for="(tier, index) in programForm.tiers" :key="index">
            <div>
              <label>{{ t('loyalty.table.tier') }}</label>
              <InputText v-model="tier.name" />
            </div>
            <div>
              <label>{{ t('loyalty.table.minPoints') }}</label>
              <InputNumber v-model.number="tier.minPoints" :min="0" show-buttons />
            </div>
            <div>
              <label>{{ t('loyalty.table.multiplier') }}</label>
              <InputNumber v-model.number="tier.multiplier" mode="decimal" :minFractionDigits="2" :maxFractionDigits="2" :min="1" show-buttons />
            </div>
            <Button
              icon="pi pi-trash"
              class="p-button-text p-button-danger remove-tier"
              :label="t('loyalty.program.removeTier')"
              @click="removeTier(index)"
            />
          </div>
          <Button
            icon="pi pi-plus"
            class="p-button-text"
            :label="t('loyalty.program.addTier')"
            @click="addTier"
          />
        </div>

        <div class="preview-header">
          <h3>{{ t('loyalty.program.previewTitle') }}</h3>
          <p>{{ t('loyalty.program.previewSubtitle') }}</p>
        </div>
        <div class="reward-preview-grid">
          <div class="preview-card" v-for="preview in rewardPreview" :key="preview.spend">
            <p class="eyebrow">{{ t('loyalty.program.previewSpend') }}</p>
            <h4>${{ preview.spend }}</h4>
            <p class="preview-body">
              {{ t('loyalty.program.previewEarn', { points: preview.earned }) }}
              ·
              {{ t('loyalty.program.previewRedeem', { value: '$' + preview.redeemValue }) }}
            </p>
          </div>
        </div>
        <div class="builder-actions">
          <div class="toggle-row">
            <span>{{ t('loyalty.program.pilotToggle') }}</span>
            <InputSwitch v-model="programForm.pilotOnly" />
          </div>
          <Button
            icon="pi pi-save"
            :label="t('loyalty.program.save')"
            :disabled="!isProgramDirty || loyaltyStore.savingProgram"
            :loading="loyaltyStore.savingProgram"
            @click="handleProgramSave"
          />
        </div>
      </section>

      <section v-else class="card pilot-guard">
        <InlineAlert
          tone="info"
          :title="t('loyalty.pilotGuardTitle')"
          :description="t('loyalty.pilotGuardCopy')"
        />
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
          <Button
            :label="t('loyalty.members.lookup')"
            :disabled="!lookupId"
            @click="handleLookup"
          />
        </div>

        <div v-if="loyaltyStore.member" class="member-card-container">
          <div class="member-card">
            <div class="member-summary">
              <div>
                <p class="eyebrow">{{ t('loyalty.members.pointsBalance') }}</p>
                <h3>{{ loyaltyStore.member.pointsBalance }}</h3>
              </div>
              <div>
                <p class="eyebrow">{{ t('loyalty.members.tier') }}</p>
                <p class="tier-label">{{ loyaltyStore.member.currentTier }}</p>
              </div>
              <div>
                <p class="eyebrow">{{ t('loyalty.members.lifetimePoints') }}</p>
                <h3>{{ loyaltyStore.member.lifetimePointsEarned }}</h3>
              </div>
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

          <div v-if="nextTier" class="tier-progress-card">
            <div class="tier-progress-header">
              <div>
                <p class="eyebrow">{{ t('loyalty.tier.progress') }}</p>
                <h4>{{ t('loyalty.tier.nextTier', { tier: nextTier.name }) }}</h4>
              </div>
              <p class="points-to-go">
                {{ pointsToNextTier }} {{ t('loyalty.tier.pointsRemaining') }}
              </p>
            </div>
            <ProgressBar
              :value="tierProgressPercent"
              :show-value="true"
              :pt="{
                root: { class: 'tier-progress-bar' },
                value: { style: 'background: var(--primary-color)' },
              }"
            />
            <p class="tier-progress-text">
              {{ loyaltyStore.member.lifetimePointsEarned.toLocaleString() }} /
              {{ nextTier.minPoints.toLocaleString() }} {{ t('loyalty.tier.pointsToNextTier') }}
            </p>
          </div>

          <div v-else class="tier-max-card">
            <div class="flex items-center gap-2">
              <i class="pi pi-crown text-2xl text-amber-500" />
              <div>
                <h4>{{ t('loyalty.tier.maxTierReached') }}</h4>
                <p class="text-sm text-neutral-600">{{ t('loyalty.tier.maxTierDescription') }}</p>
              </div>
            </div>
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

    <Dialog
      :visible="showAdjustDialog"
      modal
      :header="t('loyalty.actions.adjustPoints')"
      @hide="closeAdjustDialog"
    >
      <div class="dialog-body">
        <label>{{ t('loyalty.adjust.points') }}</label>
        <InputNumber v-model.number="adjustPointsValue" :min="-1000" :max="1000" show-buttons />
        <label>{{ t('loyalty.adjust.reason') }}</label>
        <Dropdown
          v-model="adjustReason"
          :options="reasonOptions"
          option-label="label"
          option-value="value"
        />
      </div>
      <template #footer>
        <Button class="p-button-text" :label="t('common.cancel')" @click="closeAdjustDialog" />
        <Button
          :label="t('common.confirm')"
          :disabled="!adjustPointsValue || !adjustReason"
          @click="handleAdjust"
        />
      </template>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, reactive, computed, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Dropdown from 'primevue/dropdown'
import Dialog from 'primevue/dialog'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import ProgressBar from 'primevue/progressbar'
import InputSwitch from 'primevue/inputswitch'
import Textarea from 'primevue/textarea'
import InlineAlert from '@/components/base/InlineAlert.vue'
import { useLoyaltyStore, type LoyaltyTierConfig } from '../store'
import type { LoyaltyProgramResponse, UpsertProgramPayload } from '../api'
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

const pilotEnabled = computed(() => tenantStore.isFeatureEnabled('loyalty-pilot'))

type EditableTier = LoyaltyTierConfig & { id?: string }

const programForm = reactive({
  name: '',
  description: '',
  enabled: true,
  pointsPerDollar: 1,
  redemptionValuePerPoint: 0.01,
  minRedemptionPoints: 100,
  maxRedemptionPoints: null as number | null,
  pointsExpirationDays: null as number | null,
  tiers: [] as EditableTier[],
  pilotOnly: false,
})
programForm.tiers = defaultTiers()

const payloadFingerprint = ref('')

const previewSpendLevels = [25, 50, 100]
const rewardPreview = computed(() =>
  previewSpendLevels.map((spend) => {
    const earnRate = Number(programForm.pointsPerDollar || 0)
    const redeemRate = Number(programForm.redemptionValuePerPoint || 0)
    const earned = Math.max(0, Math.floor(spend * earnRate))
    const redeemValue = (earned * redeemRate).toFixed(2)
    return { spend, earned, redeemValue }
  }),
)

const isProgramDirty = computed(() => {
  if (!pilotEnabled.value) return false
  return payloadFingerprint.value !== serializeProgramPayload()
})

// Computed: Next tier information
const nextTier = computed(() => {
  if (!loyaltyStore.tiers || !loyaltyStore.member) return null

  const currentPoints = loyaltyStore.member.lifetimePointsEarned
  const sorted = [...loyaltyStore.tiers].sort((a, b) => a.minPoints - b.minPoints)
  return sorted.find((tier) => tier.minPoints > currentPoints)
})

// Computed: Points remaining to next tier
const pointsToNextTier = computed(() => {
  if (!nextTier.value || !loyaltyStore.member) return 0
  return nextTier.value.minPoints - loyaltyStore.member.lifetimePointsEarned
})

// Computed: Tier progress percentage
const tierProgressPercent = computed(() => {
  if (!nextTier.value || !loyaltyStore.member || !loyaltyStore.tiers) return 100

  const currentPoints = loyaltyStore.member.lifetimePointsEarned
  const sorted = [...loyaltyStore.tiers].sort((a, b) => a.minPoints - b.minPoints)
  const currentTierObj = sorted.find((t) => t.name === loyaltyStore.member!.currentTier)
  const currentTierMin = currentTierObj?.minPoints || 0

  const progress =
    ((currentPoints - currentTierMin) / (nextTier.value.minPoints - currentTierMin)) * 100
  return Math.min(Math.round(progress), 100)
})

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

watch(
  () => loyaltyStore.program,
  (program) => {
    if (!program) return
    hydrateProgramForm(program)
    payloadFingerprint.value = serializeProgramPayload()
  },
  { immediate: true },
)

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

function hydrateProgramForm(program: LoyaltyProgramResponse | null) {
  if (!program) {
    programForm.name = ''
    programForm.description = ''
    programForm.enabled = true
    programForm.pointsPerDollar = 1
    programForm.redemptionValuePerPoint = 0.01
    programForm.minRedemptionPoints = 100
    programForm.maxRedemptionPoints = null
    programForm.pointsExpirationDays = null
    programForm.tiers = defaultTiers()
    programForm.pilotOnly = false
    return
  }

  programForm.name = program.name ?? ''
  programForm.description = program.description ?? ''
  programForm.enabled = program.enabled
  programForm.pointsPerDollar = Number(program.pointsPerDollar ?? 1)
  programForm.redemptionValuePerPoint = Number(program.redemptionValuePerPoint ?? 0.01)
  programForm.minRedemptionPoints = Number(program.minRedemptionPoints ?? 1)
  programForm.maxRedemptionPoints = program.maxRedemptionPoints ?? null
  programForm.pointsExpirationDays = program.pointsExpirationDays ?? null
  programForm.tiers =
    loyaltyStore.tiers.length > 0
      ? loyaltyStore.tiers.map((tier) => ({ ...tier }))
      : defaultTiers()
  programForm.pilotOnly = Boolean(program.metadata?.pilotOnly)
}

function defaultTiers(): EditableTier[] {
  return [
    { name: 'Bronze', minPoints: 0, multiplier: 1 },
    { name: 'Silver', minPoints: 500, multiplier: 1.25 },
    { name: 'Gold', minPoints: 1500, multiplier: 1.5 },
  ]
}

function addTier() {
  const nextIndex = programForm.tiers.length + 1
  const last = programForm.tiers[programForm.tiers.length - 1]
  const suggestedMin = last ? last.minPoints + 500 : 0
  programForm.tiers.push({
    name: `Tier ${nextIndex}`,
    minPoints: suggestedMin,
    multiplier: 1,
  })
}

function removeTier(index: number) {
  programForm.tiers.splice(index, 1)
}

function serializeProgramPayload() {
  const payload = buildProgramPayload()
  return JSON.stringify(payload)
}

function buildProgramPayload(): UpsertProgramPayload {
  const tiers = programForm.tiers.map((tier) => ({
    name: tier.name,
    minPoints: Number(tier.minPoints ?? 0),
    multiplier: Number(tier.multiplier ?? 1),
  }))
  tiers.sort((a, b) => a.minPoints - b.minPoints)

  const metadata = { ...(loyaltyStore.program?.metadata || {}) }
  if (programForm.pilotOnly) {
    metadata.pilotOnly = true
  } else {
    delete metadata.pilotOnly
  }
  const metadataPayload = Object.keys(metadata).length ? metadata : undefined

  return {
    name: programForm.name || loyaltyStore.program?.name || 'Loyalty Program',
    description: programForm.description || loyaltyStore.program?.description,
    enabled: programForm.enabled,
    pointsPerDollar: Number(programForm.pointsPerDollar || 0),
    redemptionValuePerPoint: Number(programForm.redemptionValuePerPoint || 0),
    minRedemptionPoints: Number(programForm.minRedemptionPoints || 1),
    maxRedemptionPoints: programForm.maxRedemptionPoints ?? undefined,
    pointsExpirationDays: programForm.pointsExpirationDays ?? undefined,
    tiers,
    metadata: metadataPayload,
  }
}

async function handleProgramSave() {
  const payload = buildProgramPayload()
  try {
    await loyaltyStore.saveProgram(payload)
    payloadFingerprint.value = JSON.stringify(payload)
    toast.add({ severity: 'success', summary: t('loyalty.messages.saved') })
  } catch (error) {
    console.error('Failed to save program', error)
    toast.add({ severity: 'error', summary: t('loyalty.errors.saveFailed') })
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

.member-card-container {
  @apply space-y-4;
}

.member-card {
  @apply flex items-center justify-between bg-neutral-50 border border-neutral-200 rounded-lg p-4;
}

.member-summary {
  @apply flex items-start gap-8;
}

.tier-label {
  @apply text-sm font-medium text-primary-600;
}

.tier-progress-card {
  @apply bg-primary-50 border border-primary-200 rounded-lg p-4 space-y-3;
}

.tier-progress-header {
  @apply flex items-center justify-between;
}

.points-to-go {
  @apply text-sm font-semibold text-primary-700;
}

.tier-progress-text {
  @apply text-xs text-neutral-600 text-center;
}

.tier-max-card {
  @apply bg-amber-50 border border-amber-200 rounded-lg p-4;
}

.program-builder {
  @apply space-y-4;
}

.program-form-grid {
  @apply grid gap-4 md:grid-cols-3;
}

.form-control label {
  @apply block text-xs font-semibold text-neutral-500 mb-1 uppercase;
}

.col-span-2 {
  @apply md:col-span-2;
}

.tier-builder {
  @apply space-y-3;
}

.tier-row {
  @apply grid gap-3 md:grid-cols-4;
}

.remove-tier {
  @apply justify-self-end;
}

.preview-header {
  @apply flex flex-col gap-1;
}

.reward-preview-grid {
  @apply grid gap-3 md:grid-cols-3;
}

.preview-card {
  @apply border border-neutral-200 rounded-lg p-3 bg-neutral-50;
}

.preview-body {
  @apply text-sm text-neutral-600;
}

.builder-actions {
  @apply flex flex-col md:flex-row md:items-center md:justify-between gap-4;
}

.toggle-row {
  @apply flex items-center gap-3;
}

.pilot-guard {
  @apply bg-neutral-50;
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
