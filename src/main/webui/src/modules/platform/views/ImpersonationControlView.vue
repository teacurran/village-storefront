<template>
  <div class="impersonation-control">
    <ImpersonationBanner />
    <header class="control-header">
      <div>
        <h1>Impersonation Control</h1>
        <p class="subtitle">Start or end impersonation sessions with ticket accountability</p>
      </div>
    </header>

    <section class="form-card">
      <form @submit.prevent="handleStart">
        <label>
          Target Tenant ID
          <input
            v-model="tenantId"
            type="text"
            required
            placeholder="UUID of tenant to impersonate"
          />
        </label>

        <label>
          Target User ID (optional)
          <input
            v-model="userId"
            type="text"
            placeholder="UUID of specific user"
          />
        </label>

        <label>
          Reason
          <textarea
            v-model="reason"
            rows="3"
            required
            placeholder="Describe why impersonation is required (min 10 characters)"
          />
        </label>

        <label>
          Support Ticket Number
          <input
            v-model="ticketNumber"
            type="text"
            required
            placeholder="e.g. TICKET-12345"
          />
        </label>

        <div class="form-actions">
          <button class="btn-primary" type="submit" :disabled="!canStart">
            <i class="pi pi-user-edit" />
            Start impersonation
          </button>

          <button
            class="btn-danger"
            type="button"
            :disabled="!impersonation || loading"
            @click="handleEnd"
          >
            <i class="pi pi-times" />
            End impersonation
          </button>
        </div>
      </form>
    </section>

    <section v-if="impersonation" class="session-details">
      <h2>Active Session</h2>
      <dl>
        <div>
          <dt>Session ID</dt>
          <dd>{{ impersonation.sessionId }}</dd>
        </div>
        <div>
          <dt>Tenant</dt>
          <dd>{{ impersonation.targetTenantName }} ({{ impersonation.targetTenantId }})</dd>
        </div>
        <div v-if="impersonation.targetUserEmail">
          <dt>User</dt>
          <dd>{{ impersonation.targetUserEmail }}</dd>
        </div>
        <div>
          <dt>Reason</dt>
          <dd>{{ impersonation.reason }}</dd>
        </div>
        <div>
          <dt>Ticket</dt>
          <dd>{{ impersonation.ticketNumber }}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd>{{ formatTimestamp(impersonation.startedAt) }}</dd>
        </div>
      </dl>
    </section>

    <p v-if="error" class="error-state">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { usePlatformStore } from '../store'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'

const platformStore = usePlatformStore()
const { impersonation, loading, error } = storeToRefs(platformStore)

const tenantId = ref('')
const userId = ref('')
const reason = ref('')
const ticketNumber = ref('')

const canStart = computed(() => {
  return (
    tenantId.value.trim().length > 0 &&
    reason.value.trim().length >= 10 &&
    ticketNumber.value.trim().length >= 3 &&
    !loading.value
  )
})

async function handleStart() {
  if (!canStart.value) return
  try {
    await platformStore.startImpersonation({
      targetTenantId: tenantId.value.trim(),
      targetUserId: userId.value.trim() || undefined,
      reason: reason.value.trim(),
      ticketNumber: ticketNumber.value.trim(),
    })
    userId.value = ''
    reason.value = ''
    ticketNumber.value = ''
  } catch {
    // error handled in store
  }
}

async function handleEnd() {
  try {
    await platformStore.endImpersonation()
  } catch {
    // error handled in store
  }
}

function formatTimestamp(value: string) {
  return new Date(value).toLocaleString()
}
</script>

<style scoped>
.impersonation-control {
  max-width: 900px;
  margin: 0 auto;
  padding: 2rem;
}

.control-header {
  margin-bottom: 1.5rem;
}

.subtitle {
  color: #666;
}

.form-card {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  padding: 1.5rem;
}

form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

label {
  display: flex;
  flex-direction: column;
  font-size: 0.9rem;
  color: #444;
}

input,
textarea {
  margin-top: 0.35rem;
  padding: 0.6rem;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 1rem;
}

.form-actions {
  display: flex;
  gap: 1rem;
}

.btn-primary,
.btn-danger {
  border: none;
  border-radius: 4px;
  padding: 0.65rem 1.25rem;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.btn-primary {
  background: #2563eb;
  color: white;
}

.btn-danger {
  background: #dc2626;
  color: white;
}

.session-details {
  margin-top: 2rem;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 1.5rem;
}

dl {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1rem;
}

dt {
  font-size: 0.85rem;
  color: #666;
}

dd {
  font-weight: 600;
  color: #111;
}

.error-state {
  margin-top: 1rem;
  color: #b91c1c;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
