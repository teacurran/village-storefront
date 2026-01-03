<template>
  <div class="balance-chart">
    <div class="balance-chart-header">
      <h3 class="text-lg font-semibold text-neutral-900">{{ t('consignor.balance.title') }}</h3>
      <p class="text-sm text-neutral-600">{{ t('consignor.balance.subtitle') }}</p>
    </div>

    <div class="balance-chart-amount">
      <p class="text-sm font-medium text-neutral-600 mb-1">
        {{ t('consignor.balance.currentBalance') }}
      </p>
      <p class="text-4xl font-bold text-primary-600">{{ formatCurrency(balanceOwed) }}</p>
    </div>

    <div class="balance-chart-details">
      <div class="detail-row">
        <span class="detail-label">{{ t('consignor.balance.lifetimeEarnings') }}</span>
        <span class="detail-value">{{ formatCurrency(lifetimeEarnings) }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">{{ t('consignor.balance.avgCommissionRate') }}</span>
        <span class="detail-value">{{ avgCommissionRate }}%</span>
      </div>
      <div v-if="lastPayoutDate" class="detail-row">
        <span class="detail-label">{{ t('consignor.balance.lastPayout') }}</span>
        <span class="detail-value">{{ formatDate(lastPayoutDate) }}</span>
      </div>
    </div>

    <div v-if="nextPayoutEligible" class="balance-chart-cta">
      <button
        class="btn-primary w-full"
        @click="emit('request-payout')"
        :aria-label="t('consignor.balance.requestPayoutAriaLabel')"
      >
        {{ t('consignor.balance.requestPayout') }}
      </button>
    </div>
    <div v-else class="balance-chart-info">
      <p class="text-sm text-neutral-600">
        {{ t('consignor.balance.minimumThreshold', { amount: minimumThreshold }) }}
      </p>
    </div>

    <p class="timezone-note">
      {{ t('consignor.balance.timezoneNotice', { timezone: timeZone }) }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { Money } from '@/api/types'
import { MIN_PAYOUT_CENTS } from '../constants'

const props = defineProps<{
  balanceOwed: Money
  lifetimeEarnings: Money
  avgCommissionRate: number
  lastPayoutDate?: string
  nextPayoutEligible: boolean
}>()

const emit = defineEmits<{
  'request-payout': []
}>()

const { t, formatCurrency, formatDate, timeZone } = useI18n()

const minimumThreshold = computed(() =>
  formatCurrency({
    amount: MIN_PAYOUT_CENTS,
    currency: props.balanceOwed.currency,
  })
)
</script>

<style scoped>
.balance-chart {
  @apply bg-white rounded-lg shadow-soft p-6;
}

.balance-chart-header {
  @apply mb-6;
}

.balance-chart-amount {
  @apply mb-6 pb-6 border-b border-neutral-200;
}

.balance-chart-details {
  @apply space-y-3 mb-6;
}

.detail-row {
  @apply flex items-center justify-between;
}

.detail-label {
  @apply text-sm text-neutral-600;
}

.detail-value {
  @apply text-sm font-semibold text-neutral-900;
}

.balance-chart-cta {
  @apply pt-4 border-t border-neutral-200;
}

.balance-chart-info {
  @apply pt-4 border-t border-neutral-200 text-center;
}

.timezone-note {
  @apply text-xs text-neutral-500 mt-4 text-center;
}

.btn-primary {
  @apply px-4 py-2 bg-primary-600 text-white rounded-md font-medium hover:bg-primary-700 transition-colors;
}
</style>
