<template>
  <div
    v-if="isImpersonating"
    class="impersonation-banner"
    role="alert"
    data-test="impersonation-banner"
  >
    <div class="banner-content">
      <div class="banner-icon">
        <i class="pi pi-user-edit"></i>
      </div>
      <div class="banner-text">
        <strong>Impersonating:</strong>
        <span data-test="impersonated-tenant">{{ impersonation.targetTenantName }}</span>
        <span v-if="impersonation.targetUserEmail" data-test="impersonated-user">
          ({{ impersonation.targetUserEmail }})
        </span>
        <span class="banner-reason" data-test="impersonation-reason">
          - {{ impersonation.reason }}
        </span>
        <span v-if="impersonation.ticketNumber" class="banner-ticket" data-test="ticket-number">
          [Ticket: {{ impersonation.ticketNumber }}]
        </span>
        <span v-if="elapsedTime" class="banner-timer" data-test="impersonation-timer">
          {{ elapsedTime }}
        </span>
        <span
          v-if="destructiveDisabled"
          class="banner-warning"
          data-test="impersonation-warning"
        >
          <i class="pi pi-exclamation-triangle"></i>
          Destructive actions disabled until reason and ticket are recorded.
        </span>
      </div>
      <div class="banner-actions">
        <button
          class="end-impersonation-btn"
          :disabled="loading"
          data-test="end-impersonation"
          @click="handleEndImpersonation"
        >
          <i class="pi pi-times"></i>
          End Impersonation
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'

/**
 * Impersonation Banner Component
 *
 * Visual indicator that platform admin is currently impersonating a tenant/user.
 * Prominently displayed across all pages during an active impersonation session.
 * Includes elapsed time timer and safety controls.
 *
 * References:
 * - Task I5.T2: Platform admin console (impersonation banner)
 * - Rationale: 05_Rationale_and_Future.md Section 4.3.7 (visual indicators)
 */

const platformStore = usePlatformStore()
const { impersonation, loading, canPerformDestructiveActions } = storeToRefs(platformStore)

const isImpersonating = computed(() => impersonation.value !== null)
const elapsedTime = ref<string>('')
const destructiveDisabled = computed(
  () => isImpersonating.value && !canPerformDestructiveActions.value
)

let timerInterval: number | null = null

onMounted(() => {
  startTimer()
})

onBeforeUnmount(() => {
  stopTimer()
})

function startTimer() {
  // Update timer immediately
  updateElapsedTime()

  // Update every second
  timerInterval = window.setInterval(() => {
    updateElapsedTime()
  }, 1000)
}

function stopTimer() {
  if (timerInterval) {
    clearInterval(timerInterval)
    timerInterval = null
  }
}

function updateElapsedTime() {
  if (!impersonation.value) {
    elapsedTime.value = ''
    return
  }

  const startTime = new Date(impersonation.value.startedAt)
  const now = new Date()
  const diffMs = now.getTime() - startTime.getTime()
  const diffSecs = Math.floor(diffMs / 1000)
  const diffMins = Math.floor(diffSecs / 60)
  const diffHours = Math.floor(diffMins / 60)

  const hours = diffHours
  const mins = diffMins % 60
  const secs = diffSecs % 60

  if (hours > 0) {
    elapsedTime.value = `${hours}h ${mins}m ${secs}s`
  } else if (mins > 0) {
    elapsedTime.value = `${mins}m ${secs}s`
  } else {
    elapsedTime.value = `${secs}s`
  }
}

async function handleEndImpersonation() {
  try {
    await platformStore.endImpersonation()
    stopTimer()
    elapsedTime.value = ''
  } catch (error) {
    console.error('Failed to end impersonation:', error)
    // Error is already set in store
  }
}
</script>

<style scoped>
.impersonation-banner {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: linear-gradient(135deg, #ff6b6b 0%, #ee5a6f 100%);
  color: white;
  padding: 0.75rem 1rem;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  border-bottom: 2px solid #d63031;
  animation: slideDown 0.3s ease-out;
}

@keyframes slideDown {
  from {
    transform: translateY(-100%);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.banner-content {
  display: flex;
  align-items: center;
  gap: 1rem;
  max-width: 1400px;
  margin: 0 auto;
}

.banner-icon {
  font-size: 1.5rem;
  display: flex;
  align-items: center;
}

.banner-text {
  flex: 1;
  font-size: 0.95rem;
}

.banner-text strong {
  font-weight: 600;
}

.banner-reason {
  font-style: italic;
  opacity: 0.9;
  margin-left: 0.5rem;
}

.banner-ticket {
  margin-left: 0.5rem;
  font-size: 0.85rem;
  opacity: 0.85;
}

.banner-timer {
  margin-left: 0.75rem;
  padding: 0.25rem 0.5rem;
  background: rgba(255, 255, 255, 0.25);
  border-radius: 4px;
  font-family: monospace;
  font-size: 0.9rem;
  font-weight: 600;
}

.banner-actions {
  display: flex;
  gap: 0.5rem;
}

.banner-warning {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  margin-left: 0.75rem;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.25);
  font-size: 0.85rem;
  font-weight: 600;
}

.banner-warning i {
  font-size: 0.9rem;
}

.end-impersonation-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.4);
  color: white;
  border-radius: 4px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s ease;
}

.end-impersonation-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.3);
  border-color: rgba(255, 255, 255, 0.6);
}

.end-impersonation-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.end-impersonation-btn i {
  font-size: 1rem;
}
</style>
