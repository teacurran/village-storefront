<template>
  <div
    v-if="stripeInfo && stripeInfo.requiresOnboarding"
    class="stripe-connect-banner"
    role="region"
    :aria-label="t('consignor.stripe.bannerLabel')"
  >
    <div class="banner-icon" aria-hidden="true">
      <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
        />
      </svg>
    </div>

    <div class="banner-content">
      <h3 class="banner-title">{{ t('consignor.stripe.setupRequired') }}</h3>
      <p class="banner-message">
        {{ stripeInfo.onboardingMessage }}
      </p>
      <ul v-if="!stripeInfo.accountId" class="banner-checklist">
        <li>{{ t('consignor.stripe.benefit1') }}</li>
        <li>{{ t('consignor.stripe.benefit2') }}</li>
        <li>{{ t('consignor.stripe.benefit3') }}</li>
      </ul>
    </div>

    <div class="banner-action">
      <button
        class="btn-stripe-connect"
        :disabled="!stripeInfo.onboardingUrl && !mockMode"
        :aria-label="t('consignor.stripe.startOnboardingLabel')"
        @click="handleStartOnboarding"
      >
        <span v-if="loading" class="spinner-small" aria-hidden="true" />
        <svg v-else class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M13 7l5 5m0 0l-5 5m5-5H6"
          />
        </svg>
        {{ t('consignor.stripe.completeSetup') }}
      </button>
      <p v-if="mockMode" class="mock-notice">
        {{ t('consignor.stripe.mockModeNotice') }}
      </p>
    </div>
  </div>

  <div
    v-else-if="stripeInfo && !stripeInfo.requiresOnboarding"
    class="stripe-verified-badge"
    role="status"
    :aria-label="t('consignor.stripe.verifiedLabel')"
  >
    <svg class="w-5 h-5 text-success-600" fill="currentColor" viewBox="0 0 20 20">
      <path
        fill-rule="evenodd"
        d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
        clip-rule="evenodd"
      />
    </svg>
    <span class="verified-text">{{ t('consignor.stripe.verified') }}</span>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { StripeConnectInfo } from '../types'
import { emitTelemetryEvent } from '@/telemetry'

interface Props {
  stripeInfo: StripeConnectInfo | null
  consignorId?: string
  mockMode?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  mockMode: false,
})

const emit = defineEmits<{
  'onboarding-started': []
}>()

const { t } = useI18n()
const loading = ref(false)

const handleStartOnboarding = async () => {
  loading.value = true

  try {
    if (props.mockMode) {
      // Mock mode: simulate navigation
      alert(t('consignor.stripe.mockRedirect'))
      emit('onboarding-started')
    } else if (props.stripeInfo?.onboardingUrl) {
      // Real mode: redirect to Stripe
      if (props.consignorId) {
        emitTelemetryEvent('consignor:stripe-onboarding-clicked', {
          consignorId: props.consignorId,
          accountId: props.stripeInfo.accountId,
        })
      }

      window.location.href = props.stripeInfo.onboardingUrl
      emit('onboarding-started')
    } else {
      console.error('No onboarding URL available')
    }
  } finally {
    setTimeout(() => {
      loading.value = false
    }, 1000)
  }
}
</script>

<style scoped>
.stripe-connect-banner {
  @apply bg-gradient-to-r from-primary-50 to-primary-100 border-2 border-primary-300 rounded-lg p-6 flex items-start gap-6 shadow-sm;
}

.banner-icon {
  @apply flex-shrink-0 w-12 h-12 flex items-center justify-center bg-primary-500 text-white rounded-full;
}

.banner-content {
  @apply flex-1;
}

.banner-title {
  @apply text-xl font-bold text-neutral-900 mb-2;
}

.banner-message {
  @apply text-neutral-700 mb-3;
}

.banner-checklist {
  @apply list-disc list-inside text-neutral-600 text-sm space-y-1;
}

.banner-action {
  @apply flex-shrink-0 flex flex-col items-end gap-2;
}

.btn-stripe-connect {
  @apply px-6 py-3 bg-primary-600 text-white rounded-md font-semibold hover:bg-primary-700 transition-colors flex items-center shadow-md disabled:opacity-50 disabled:cursor-not-allowed;
}

.btn-stripe-connect:focus {
  @apply outline-none ring-2 ring-offset-2 ring-primary-500;
}

.spinner-small {
  @apply w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin mr-2;
}

.mock-notice {
  @apply text-xs text-neutral-500 italic;
}

.stripe-verified-badge {
  @apply inline-flex items-center gap-2 px-4 py-2 bg-success-50 border border-success-300 rounded-md;
}

.verified-text {
  @apply text-success-800 font-medium;
}
</style>
