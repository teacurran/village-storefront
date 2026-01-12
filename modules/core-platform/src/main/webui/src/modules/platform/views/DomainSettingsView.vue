<template>
  <div class="domain-settings" data-test="domain-settings">
    <ImpersonationBanner />

    <header class="settings-header">
      <div>
        <h1>Custom Domain Management</h1>
        <p class="subtitle">
          Configure custom domains and SSL certificates for your storefront
        </p>
      </div>
      <button
        class="add-domain-btn"
        :disabled="loading"
        data-test="add-domain-button"
        @click="openAddDomainDialog"
      >
        <i class="pi pi-plus" />
        Add Custom Domain
      </button>
    </header>

    <div v-if="loading && domains.length === 0" class="loading-state">
      <i class="pi pi-spin pi-spinner" />
      Loading domains...
    </div>

    <div v-else-if="error" class="error-state" data-test="domain-error">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>

    <div v-else class="domain-content">
      <!-- Domains List -->
      <section v-if="domains.length === 0" class="empty-domains">
        <i class="pi pi-globe"></i>
        <h2>No Custom Domains</h2>
        <p>
          Add a custom domain to allow customers to access your store at your own domain
          (e.g., shop.example.com)
        </p>
        <button class="btn-primary" @click="openAddDomainDialog">Add Your First Domain</button>
      </section>

      <section v-else class="domains-section" data-test="domains-list">
        <div
          v-for="domain in sortedDomains"
          :key="domain.id"
          class="domain-card"
          :data-test="`domain-${domain.domain}`"
        >
          <div class="domain-header">
            <div class="domain-info">
              <h3>{{ domain.domain }}</h3>
              <span :class="['status-badge', domain.status.toLowerCase()]" data-test="domain-status">
                {{ formatStatus(domain.status) }}
              </span>
            </div>
            <button
              class="remove-btn"
              :disabled="removing === domain.id"
              data-test="remove-domain"
              @click="removeDomain(domain)"
            >
              <i class="pi pi-trash" />
            </button>
          </div>

          <div class="domain-details">
            <!-- Pending State: DNS Instructions -->
            <div v-if="domain.status === 'PENDING'" class="dns-instructions" data-test="dns-instructions">
              <div class="instruction-header">
                <i class="pi pi-info-circle"></i>
                <strong>DNS Configuration Required</strong>
              </div>
              <p>Add the following TXT record to your DNS provider:</p>
              <div class="dns-record">
                <div class="record-row">
                  <span class="record-label">Type:</span>
                  <code>TXT</code>
                </div>
                <div class="record-row">
                  <span class="record-label">Name:</span>
                  <code>{{ domain.dnsChallenge?.name || '_acme-challenge' }}</code>
                  <button
                    class="copy-btn"
                    :data-test="`copy-dns-name-${domain.id}`"
                    @click="copyToClipboard(domain.dnsChallenge?.name)"
                  >
                    <i class="pi pi-copy"></i>
                  </button>
                </div>
                <div class="record-row">
                  <span class="record-label">Value:</span>
                  <code class="token-value">{{ domain.dnsChallenge?.value }}</code>
                  <button
                    class="copy-btn"
                    :data-test="`copy-dns-value-${domain.id}`"
                    @click="copyToClipboard(domain.dnsChallenge?.value)"
                  >
                    <i class="pi pi-copy"></i>
                  </button>
                </div>
              </div>
              <p class="verification-note">
                Verification runs hourly. DNS changes may take up to 24 hours to propagate.
              </p>
            </div>

            <!-- Failed State: Error Message -->
            <div
              v-if="domain.status === 'FAILED' && domain.verificationErrorMessage"
              class="error-message"
              data-test="verification-error"
            >
              <i class="pi pi-exclamation-triangle"></i>
              <div>
                <strong>Verification Failed</strong>
                <p>{{ domain.verificationErrorMessage }}</p>
              </div>
            </div>

            <!-- Active State: Certificate Info -->
            <div v-if="domain.status === 'ACTIVE'" class="certificate-info" data-test="certificate-info">
              <div class="cert-row">
                <i class="pi pi-check-circle cert-icon-success"></i>
                <span>SSL certificate active</span>
              </div>
              <div v-if="domain.certificateExpiry" class="cert-row">
                <i class="pi pi-calendar"></i>
                <span>
                  Expires {{ formatExpiryDate(domain.certificateExpiry) }}
                  <span
                    v-if="isExpiringSoon(domain.certificateExpiry)"
                    class="expiry-warning"
                    data-test="expiry-warning"
                  >
                    (Renewing soon)
                  </span>
                </span>
              </div>
            </div>

            <!-- Timestamps -->
            <div class="domain-meta">
              <small>Added {{ formatDate(domain.createdAt) }}</small>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- Add Domain Dialog -->
    <dialog ref="addDomainDialog" class="modal" data-test="add-domain-dialog">
      <div class="modal-content">
        <h2>Add Custom Domain</h2>
        <p class="dialog-description">
          Enter your custom domain name. You'll receive DNS configuration instructions after adding.
        </p>

        <div class="form-field">
          <label for="domain-input">Domain Name</label>
          <input
            id="domain-input"
            v-model="newDomainInput"
            type="text"
            placeholder="shop.example.com"
            :class="{ error: addDomainError }"
            data-test="domain-input"
            @keyup.enter="addDomain"
          />
          <small v-if="addDomainError" class="field-error" data-test="add-domain-error">
            {{ addDomainError }}
          </small>
          <small v-else class="field-hint">
            Enter a fully qualified domain name (e.g., shop.example.com)
          </small>
        </div>

        <div class="modal-actions">
          <button class="btn-secondary" type="button" @click="closeAddDomainDialog">Cancel</button>
          <button
            class="btn-primary"
            type="button"
            :disabled="!newDomainInput.trim() || adding"
            data-test="confirm-add-domain"
            @click="addDomain"
          >
            <i v-if="adding" class="pi pi-spin pi-spinner"></i>
            <span v-else>Add Domain</span>
          </button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ImpersonationBanner from '../components/ImpersonationBanner.vue'
import { emitTelemetryEvent } from '@/telemetry'

/**
 * Domain Settings View
 *
 * Admin interface for custom domain management with DNS verification instructions
 * and SSL certificate status.
 *
 * References:
 * - Task I5.T4: Custom Domain SSL Automation
 * - Backend: CustomDomainResource.java, DomainValidationJob.java
 */

interface CustomDomain {
  id: string
  domain: string
  status: 'PENDING' | 'ACTIVE' | 'FAILED'
  verified: boolean
  dnsChallenge?: {
    type: string
    name: string
    value: string
  }
  verificationErrorMessage?: string
  certificateExpiry?: string
  createdAt: string
}

const domains = ref<CustomDomain[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const removing = ref<string | null>(null)

const addDomainDialog = ref<HTMLDialogElement | null>(null)
const newDomainInput = ref('')
const adding = ref(false)
const addDomainError = ref<string | null>(null)

const sortedDomains = computed(() => {
  return [...domains.value].sort((a, b) => {
    // Sort by status priority: ACTIVE > PENDING > FAILED
    const statusPriority = { ACTIVE: 0, PENDING: 1, FAILED: 2 }
    const priorityDiff = statusPriority[a.status] - statusPriority[b.status]
    if (priorityDiff !== 0) return priorityDiff

    // Then by creation date (newest first)
    return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  })
})

onMounted(() => {
  fetchDomains()
})

async function fetchDomains() {
  loading.value = true
  error.value = null

  try {
    const response = await fetch('/api/v1/admin/custom-domains', {
      headers: { Accept: 'application/json' },
      credentials: 'include',
    })

    if (!response.ok) {
      throw new Error(`Failed to fetch domains: ${response.statusText}`)
    }

    domains.value = await response.json()
    emitTelemetryEvent('domain_settings.list_loaded', { count: domains.value.length })
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Unknown error'
    console.error('Failed to fetch custom domains:', err)
  } finally {
    loading.value = false
  }
}

function openAddDomainDialog() {
  newDomainInput.value = ''
  addDomainError.value = null
  addDomainDialog.value?.showModal()
}

function closeAddDomainDialog() {
  addDomainDialog.value?.close()
}

async function addDomain() {
  const domainInput = newDomainInput.value.trim().toLowerCase()

  if (!domainInput) {
    addDomainError.value = 'Domain name is required'
    return
  }

  // Basic client-side validation
  const domainPattern = /^[a-z0-9][a-z0-9-.]{0,251}[a-z0-9]$/i
  if (!domainPattern.test(domainInput)) {
    addDomainError.value = 'Invalid domain format'
    return
  }

  adding.value = true
  addDomainError.value = null

  try {
    const response = await fetch('/api/v1/admin/custom-domains', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify({ domain: domainInput }),
    })

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: response.statusText }))
      throw new Error(errorData.message || 'Failed to add domain')
    }

    const newDomain = await response.json()
    domains.value.unshift(newDomain)

    emitTelemetryEvent('domain_settings.domain_added', { domain: domainInput })
    closeAddDomainDialog()
  } catch (err) {
    addDomainError.value = err instanceof Error ? err.message : 'Unknown error'
    console.error('Failed to add domain:', err)
  } finally {
    adding.value = false
  }
}

async function removeDomain(domain: CustomDomain) {
  if (!confirm(`Remove custom domain ${domain.domain}? This action cannot be undone.`)) {
    return
  }

  removing.value = domain.id

  try {
    const response = await fetch(`/api/v1/admin/custom-domains/${domain.id}`, {
      method: 'DELETE',
      credentials: 'include',
    })

    if (!response.ok) {
      throw new Error(`Failed to remove domain: ${response.statusText}`)
    }

    domains.value = domains.value.filter((d) => d.id !== domain.id)
    emitTelemetryEvent('domain_settings.domain_removed', { domain: domain.domain })
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'Unknown error'
    console.error('Failed to remove domain:', err)
  } finally {
    removing.value = null
  }
}

async function copyToClipboard(text?: string) {
  if (!text) return

  try {
    await navigator.clipboard.writeText(text)
    // TODO: Show toast notification
    console.log('Copied to clipboard:', text)
  } catch (err) {
    console.error('Failed to copy to clipboard:', err)
  }
}

function formatStatus(status: string): string {
  const statusLabels: Record<string, string> = {
    PENDING: 'Pending Verification',
    ACTIVE: 'Active',
    FAILED: 'Verification Failed',
  }
  return statusLabels[status] || status
}

function formatDate(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function formatExpiryDate(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function isExpiringSoon(isoString: string): boolean {
  const expiryDate = new Date(isoString)
  const daysUntilExpiry = (expiryDate.getTime() - Date.now()) / (1000 * 60 * 60 * 24)
  return daysUntilExpiry <= 30
}
</script>

<style scoped>
.domain-settings {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2rem;
}

.settings-header h1 {
  margin: 0 0 0.5rem 0;
  font-size: 2rem;
  color: var(--color-heading);
}

.subtitle {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.95rem;
}

.add-domain-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  background: var(--color-primary);
  color: white;
  border: none;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

.add-domain-btn:hover:not(:disabled) {
  background: var(--color-primary-hover);
}

.add-domain-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.loading-state,
.error-state {
  text-align: center;
  padding: 3rem;
  color: var(--color-text-secondary);
}

.error-state {
  color: var(--color-error);
}

.empty-domains {
  text-align: center;
  padding: 4rem 2rem;
  background: var(--color-surface);
  border-radius: 8px;
  border: 2px dashed var(--color-border);
}

.empty-domains i {
  font-size: 3rem;
  color: var(--color-text-secondary);
  margin-bottom: 1rem;
}

.empty-domains h2 {
  margin: 1rem 0 0.5rem;
}

.domains-section {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.domain-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
  transition: box-shadow 0.2s;
}

.domain-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.domain-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.domain-info {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.domain-info h3 {
  margin: 0;
  font-size: 1.25rem;
  font-family: 'Courier New', monospace;
}

.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 4px;
  font-size: 0.85rem;
  font-weight: 500;
  text-transform: uppercase;
}

.status-badge.pending {
  background: var(--color-warning-light, #fff3cd);
  color: var(--color-warning-dark, #856404);
}

.status-badge.active {
  background: var(--color-success-light, #d4edda);
  color: var(--color-success-dark, #155724);
}

.status-badge.failed {
  background: var(--color-error-light, #f8d7da);
  color: var(--color-error-dark, #721c24);
}

.remove-btn {
  padding: 0.5rem;
  background: transparent;
  color: var(--color-error);
  border: 1px solid var(--color-error);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.remove-btn:hover:not(:disabled) {
  background: var(--color-error);
  color: white;
}

.dns-instructions {
  background: var(--color-info-light, #d1ecf1);
  border: 1px solid var(--color-info-border, #bee5eb);
  border-radius: 6px;
  padding: 1rem;
  margin-bottom: 1rem;
}

.instruction-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.dns-record {
  background: var(--color-background);
  border-radius: 4px;
  padding: 1rem;
  margin: 0.75rem 0;
  font-family: 'Courier New', monospace;
  font-size: 0.9rem;
}

.record-row {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.5rem;
}

.record-label {
  font-weight: 600;
  min-width: 60px;
}

code {
  background: rgba(0, 0, 0, 0.05);
  padding: 0.25rem 0.5rem;
  border-radius: 3px;
  flex: 1;
}

.token-value {
  word-break: break-all;
}

.copy-btn {
  padding: 0.25rem 0.5rem;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: 3px;
  cursor: pointer;
  transition: background 0.2s;
}

.copy-btn:hover {
  background: var(--color-hover);
}

.verification-note {
  margin: 0.75rem 0 0;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.error-message {
  display: flex;
  gap: 0.75rem;
  padding: 1rem;
  background: var(--color-error-light);
  border: 1px solid var(--color-error-border, #f5c6cb);
  border-radius: 6px;
  margin-bottom: 1rem;
}

.error-message i {
  color: var(--color-error);
  margin-top: 0.25rem;
}

.certificate-info {
  padding: 1rem;
  background: var(--color-success-light);
  border: 1px solid var(--color-success-border, #c3e6cb);
  border-radius: 6px;
  margin-bottom: 1rem;
}

.cert-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.cert-row:last-child {
  margin-bottom: 0;
}

.cert-icon-success {
  color: var(--color-success);
}

.expiry-warning {
  color: var(--color-warning);
  font-weight: 500;
}

.domain-meta {
  color: var(--color-text-secondary);
  font-size: 0.85rem;
}

/* Modal Styles */
.modal {
  border: none;
  border-radius: 8px;
  padding: 0;
  max-width: 500px;
  width: 90%;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

.modal::backdrop {
  background: rgba(0, 0, 0, 0.5);
}

.modal-content {
  padding: 2rem;
}

.modal-content h2 {
  margin: 0 0 0.5rem;
}

.dialog-description {
  margin: 0 0 1.5rem;
  color: var(--color-text-secondary);
}

.form-field {
  margin-bottom: 1.5rem;
}

.form-field label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
}

.form-field input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  font-size: 1rem;
}

.form-field input.error {
  border-color: var(--color-error);
}

.field-error {
  display: block;
  margin-top: 0.5rem;
  color: var(--color-error);
}

.field-hint {
  display: block;
  margin-top: 0.5rem;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 1rem;
  margin-top: 2rem;
}

.btn-primary,
.btn-secondary {
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 4px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-primary {
  background: var(--color-primary);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary-hover);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  background: transparent;
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover {
  background: var(--color-hover);
}
</style>
