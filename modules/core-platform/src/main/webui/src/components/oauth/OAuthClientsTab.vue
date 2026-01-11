<template>
  <div class="oauth-clients-section">
    <!-- Header -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h2 class="text-xl font-bold text-neutral-900">OAuth Clients</h2>
        <p class="text-sm text-neutral-600 mt-1">
          Manage OAuth client credentials for headless API access
        </p>
      </div>
      <BaseButton variant="primary" @click="showCreateDialog">
        <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M12 4v16m8-8H4"
          />
        </svg>
        Create Client
      </BaseButton>
    </div>

    <!-- Data Table -->
    <div class="bg-white rounded-lg border border-neutral-200 overflow-hidden">
      <DataTable
        :value="oauthClients"
        :loading="loading"
        striped-rows
        responsive-layout="scroll"
        data-cy="oauth-clients-table"
      >
        <template #empty>
          <div class="text-center py-8 text-neutral-500">
            <svg class="w-16 h-16 mx-auto mb-4 text-neutral-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"
              />
            </svg>
            <p class="text-lg font-medium">No OAuth clients found</p>
            <p class="text-sm mt-1">Create your first OAuth client to enable headless API access</p>
          </div>
        </template>

        <Column field="name" header="Name" sortable>
          <template #body="{ data }">
            <div>
              <div class="font-medium text-neutral-900">{{ data.name }}</div>
              <div v-if="data.description" class="text-sm text-neutral-500">{{ data.description }}</div>
            </div>
          </template>
        </Column>

        <Column field="clientId" header="Client ID">
          <template #body="{ data }">
            <div class="flex items-center gap-2">
              <code class="text-sm bg-neutral-100 px-2 py-1 rounded">{{ data.clientId }}</code>
              <button
                type="button"
                class="text-neutral-400 hover:text-neutral-600"
                @click="copyToClipboard(data.clientId)"
                title="Copy client ID"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                  />
                </svg>
              </button>
            </div>
          </template>
        </Column>

        <Column field="scopes" header="Scopes">
          <template #body="{ data }">
            <div class="flex flex-wrap gap-1">
              <span
                v-for="scope in data.scopes"
                :key="scope"
                class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-primary-100 text-primary-800"
              >
                {{ scope }}
              </span>
            </div>
          </template>
        </Column>

        <Column field="rateLimitPerMinute" header="Rate Limit">
          <template #body="{ data }">
            {{ data.rateLimitPerMinute }}/min
          </template>
        </Column>

        <Column field="active" header="Status">
          <template #body="{ data }">
            <span
              v-if="data.active"
              class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-success-100 text-success-800"
            >
              Active
            </span>
            <span
              v-else
              class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-danger-100 text-danger-800"
            >
              Revoked
            </span>
          </template>
        </Column>

        <Column field="lastUsedAt" header="Last Used">
          <template #body="{ data }">
            {{ data.lastUsedAt ? formatDate(data.lastUsedAt) : 'Never' }}
          </template>
        </Column>

        <Column header="Actions">
          <template #body="{ data }">
            <div class="flex gap-2">
              <BaseButton
                v-if="data.active"
                variant="danger"
                size="sm"
                @click="confirmRevoke(data)"
              >
                Revoke
              </BaseButton>
              <BaseButton
                variant="warning"
                size="sm"
                @click="confirmRegenerateSecret(data)"
              >
                Regenerate Secret
              </BaseButton>
            </div>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- Create Dialog -->
    <Dialog
      v-model:visible="createDialogVisible"
      header="Create OAuth Client"
      :modal="true"
      :style="{ width: '600px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label for="name" class="block text-sm font-medium text-neutral-700 mb-1">Name</label>
          <input
            id="name"
            v-model="createForm.name"
            type="text"
            placeholder="My API Client"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          />
        </div>

        <div>
          <label for="description" class="block text-sm font-medium text-neutral-700 mb-1">Description</label>
          <textarea
            id="description"
            v-model="createForm.description"
            rows="3"
            placeholder="Optional description"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          ></textarea>
        </div>

        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-2">Scopes</label>
          <div class="flex flex-col gap-2">
            <div v-for="scope in availableScopes" :key="scope.value" class="flex items-start">
              <input
                :id="scope.value"
                v-model="createForm.scopes"
                type="checkbox"
                :value="scope.value"
                class="mt-1 h-4 w-4 text-primary-600 focus:ring-primary-500 border-neutral-300 rounded"
              />
              <label :for="scope.value" class="ml-2 text-sm">
                <span class="font-medium text-neutral-900">{{ scope.label }}</span>
                <span class="text-neutral-600"> - {{ scope.description }}</span>
              </label>
            </div>
          </div>
        </div>

        <div>
          <label for="rateLimit" class="block text-sm font-medium text-neutral-700 mb-1">
            Rate Limit (requests/minute)
          </label>
          <input
            id="rateLimit"
            v-model.number="createForm.rateLimitPerMinute"
            type="number"
            min="100"
            max="50000"
            step="1000"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          />
          <small class="text-neutral-500">Default: 5000 requests/minute</small>
        </div>
      </div>

      <template #footer>
        <div class="flex gap-2 justify-end">
          <BaseButton variant="neutral" @click="createDialogVisible = false">Cancel</BaseButton>
          <BaseButton variant="primary" :disabled="creating" @click="createClient">
            {{ creating ? 'Creating...' : 'Create' }}
          </BaseButton>
        </div>
      </template>
    </Dialog>

    <!-- Secret Display Dialog -->
    <Dialog
      v-model:visible="secretDialogVisible"
      header="Client Secret"
      :modal="true"
      :closable="false"
      :style="{ width: '600px' }"
    >
      <div class="mb-4 p-4 bg-warning-50 border border-warning-200 rounded-md">
        <div class="flex">
          <svg class="w-5 h-5 text-warning-600 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
            <path
              fill-rule="evenodd"
              d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
              clip-rule="evenodd"
            />
          </svg>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-warning-800">Important: Save this secret now</h3>
            <p class="mt-1 text-sm text-warning-700">
              This secret will NOT be shown again. Copy and store it securely.
            </p>
          </div>
        </div>
      </div>

      <div class="mt-4">
        <label class="block text-sm font-bold text-neutral-900 mb-2">Client ID:</label>
        <div class="flex gap-2">
          <input
            v-model="newClientCredentials.clientId"
            readonly
            type="text"
            class="flex-1 px-3 py-2 border border-neutral-300 rounded-md bg-neutral-50"
          />
          <BaseButton variant="neutral" @click="copyToClipboard(newClientCredentials.clientId)">
            Copy
          </BaseButton>
        </div>
      </div>

      <div class="mt-4">
        <label class="block text-sm font-bold text-neutral-900 mb-2">Client Secret:</label>
        <div class="flex gap-2">
          <input
            v-model="newClientCredentials.clientSecret"
            readonly
            type="text"
            class="flex-1 px-3 py-2 border border-neutral-300 rounded-md bg-neutral-50 font-mono text-sm"
          />
          <BaseButton variant="neutral" @click="copyToClipboard(newClientCredentials.clientSecret)">
            Copy
          </BaseButton>
        </div>
      </div>

      <template #footer>
        <BaseButton variant="primary" @click="secretDialogVisible = false">
          I've Copied the Secret
        </BaseButton>
      </template>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useToast } from 'primevue/usetoast'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Dialog from 'primevue/dialog'
import BaseButton from '@/components/base/BaseButton.vue'

interface OAuthClient {
  id: string
  clientId: string
  name: string
  description?: string
  scopes: string[]
  active: boolean
  rateLimitPerMinute: number
  createdAt: string
  lastUsedAt?: string
}

interface CreateOAuthClientRequest {
  name: string
  description: string
  scopes: string[]
  rateLimitPerMinute: number
}

const toast = useToast()

const loading = ref(false)
const oauthClients = ref<OAuthClient[]>([])
const createDialogVisible = ref(false)
const secretDialogVisible = ref(false)
const creating = ref(false)

const createForm = ref<CreateOAuthClientRequest>({
  name: '',
  description: '',
  scopes: [],
  rateLimitPerMinute: 5000
})

const newClientCredentials = ref({
  clientId: '',
  clientSecret: ''
})

const availableScopes = [
  { value: 'catalog:read', label: 'Catalog Read', description: 'Read product catalog' },
  { value: 'cart:read', label: 'Cart Read', description: 'Read cart contents' },
  { value: 'cart:write', label: 'Cart Write', description: 'Modify cart (add/update/remove items)' },
  { value: 'orders:read', label: 'Orders Read', description: 'Read order history' },
  { value: 'orders:write', label: 'Orders Write', description: 'Create and update orders' }
]

async function loadClients() {
  loading.value = true
  try {
    const response = await fetch('/api/v1/admin/oauth-clients')
    if (!response.ok) {
      throw new Error('Failed to load OAuth clients')
    }
    oauthClients.value = await response.json()
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to load OAuth clients',
      life: 5000
    })
  } finally {
    loading.value = false
  }
}

function showCreateDialog() {
  createForm.value = {
    name: '',
    description: '',
    scopes: [],
    rateLimitPerMinute: 5000
  }
  createDialogVisible.value = true
}

async function createClient() {
  if (!createForm.value.name || createForm.value.scopes.length === 0) {
    toast.add({
      severity: 'warn',
      summary: 'Validation Error',
      detail: 'Name and at least one scope are required',
      life: 5000
    })
    return
  }

  creating.value = true
  try {
    const response = await fetch('/api/v1/admin/oauth-clients', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(createForm.value)
    })

    if (!response.ok) {
      throw new Error('Failed to create OAuth client')
    }

    const result = await response.json()

    newClientCredentials.value = {
      clientId: result.clientId,
      clientSecret: result.clientSecret
    }

    createDialogVisible.value = false
    secretDialogVisible.value = true
    await loadClients()

    toast.add({
      severity: 'success',
      summary: 'Client Created',
      detail: 'OAuth client created successfully',
      life: 3000
    })
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to create OAuth client',
      life: 5000
    })
  } finally {
    creating.value = false
  }
}

async function confirmRevoke(client: OAuthClient) {
  if (!confirm(`Are you sure you want to revoke "${client.name}"? This action cannot be undone.`)) {
    return
  }

  try {
    const response = await fetch(`/api/v1/admin/oauth-clients/${client.clientId}/revoke`, {
      method: 'POST'
    })

    if (!response.ok) {
      throw new Error('Failed to revoke OAuth client')
    }

    await loadClients()

    toast.add({
      severity: 'success',
      summary: 'Client Revoked',
      detail: 'OAuth client revoked successfully',
      life: 3000
    })
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to revoke OAuth client',
      life: 5000
    })
  }
}

async function confirmRegenerateSecret(client: OAuthClient) {
  if (!confirm(`Are you sure you want to regenerate the secret for "${client.name}"? The old secret will stop working immediately.`)) {
    return
  }

  try {
    const response = await fetch(`/api/v1/admin/oauth-clients/${client.clientId}/regenerate-secret`, {
      method: 'POST'
    })

    if (!response.ok) {
      throw new Error('Failed to regenerate secret')
    }

    const result = await response.json()

    newClientCredentials.value = {
      clientId: result.clientId,
      clientSecret: result.clientSecret
    }

    secretDialogVisible.value = true

    toast.add({
      severity: 'success',
      summary: 'Secret Regenerated',
      detail: 'New secret generated. Copy it now!',
      life: 5000
    })
  } catch (error) {
    toast.add({
      severity: 'error',
      summary: 'Error',
      detail: 'Failed to regenerate secret',
      life: 5000
    })
  }
}

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text)
  toast.add({
    severity: 'info',
    summary: 'Copied',
    detail: 'Copied to clipboard',
    life: 2000
  })
}

function formatDate(dateString: string): string {
  const date = new Date(dateString)
  return date.toLocaleString()
}

onMounted(() => {
  loadClients()
})
</script>

<style scoped>
.success-100 {
  background-color: #d1fae5;
}
.success-800 {
  color: #065f46;
}
.danger-100 {
  background-color: #fee2e2;
}
.danger-800 {
  color: #991b1b;
}
.warning-50 {
  background-color: #fffbeb;
}
.warning-200 {
  border-color: #fde68a;
}
.warning-600 {
  color: #d97706;
}
.warning-700 {
  color: #b45309;
}
.warning-800 {
  color: #92400e;
}
</style>
