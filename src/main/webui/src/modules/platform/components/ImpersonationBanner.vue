<template>
  <div v-if="isImpersonating" class="impersonation-banner" role="alert">
    <div class="banner-content">
      <div class="banner-icon">
        <i class="pi pi-user-edit"></i>
      </div>
      <div class="banner-text">
        <strong>Impersonating:</strong>
        {{ impersonation.targetTenantName }}
        <span v-if="impersonation.targetUserEmail"> ({{ impersonation.targetUserEmail }})</span>
        <span class="banner-reason">- {{ impersonation.reason }}</span>
        <span v-if="impersonation.ticketNumber" class="banner-ticket">
          [Ticket: {{ impersonation.ticketNumber }}]
        </span>
      </div>
      <div class="banner-actions">
        <button class="end-impersonation-btn" @click="handleEndImpersonation" :disabled="loading">
          <i class="pi pi-times"></i>
          End Impersonation
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'

/**
 * Impersonation Banner Component
 *
 * Visual indicator that platform admin is currently impersonating a tenant/user.
 * Prominently displayed across all pages during an active impersonation session.
 *
 * References:
 * - Task I5.T2: Platform admin console (impersonation banner)
 * - Rationale: 05_Rationale_and_Future.md Section 4.3.7 (visual indicators)
 */

const platformStore = usePlatformStore()
const { impersonation, loading } = storeToRefs(platformStore)

const isImpersonating = computed(() => impersonation.value !== null)

async function handleEndImpersonation() {
  try {
    await platformStore.endImpersonation()
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

.banner-actions {
  display: flex;
  gap: 0.5rem;
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
