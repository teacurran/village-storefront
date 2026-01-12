<template>
  <div class="storecredit-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">Store Credit</h1>
        <p class="dashboard-subtitle">Manage customer store credit accounts and adjustments</p>
      </div>
      <div class="header-actions">
        <Button
          icon="pi pi-refresh"
          class="p-button-text"
          label="Refresh"
          @click="loadAccounts"
        />
        <Button
          icon="pi pi-gift"
          label="Convert Gift Card"
          @click="showConvertDialog = true"
        />
      </div>
    </div>

    <div class="space-y-6">
      <!-- Filters -->
      <div class="filters-card">
        <div class="filter-row">
          <div class="form-control">
            <label>Status</label>
            <Select
              v-model="filters.status"
              :options="statusOptions"
              option-label="label"
              option-value="value"
              placeholder="All Statuses"
              @change="loadAccounts"
            />
          </div>
          <div class="form-control">
            <label>Search</label>
            <InputText
              v-model="filters.search"
              placeholder="Search by user email..."
              @input="debouncedSearch"
            />
          </div>
        </div>
      </div>

      <!-- Store Credit Accounts Table -->
      <div class="card">
        <DataTable
          :value="accounts"
          :loading="loading"
          striped-rows
          paginator
          :rows="20"
          :total-records="totalRecords"
          lazy
          @page="onPage"
        >
          <Column field="id" header="Account ID" />
          <Column field="userEmail" header="User">
            <template #body="{ data }">
              <div>
                <div class="font-medium">{{ data.userEmail || '—' }}</div>
                <div class="text-sm text-gray-500">{{ data.userName || data.userId || '—' }}</div>
                <div class="text-xs text-gray-400">ID: {{ data.userId || '—' }}</div>
              </div>
            </template>
          </Column>
          <Column field="balance" header="Balance">
            <template #body="{ data }">
              <span :class="balanceClass(data.balance)" class="font-bold">
                {{ formatCurrency(data.balance, data.currency) }}
              </span>
            </template>
          </Column>
          <Column field="status" header="Status">
            <template #body="{ data }">
              <Badge :value="data.status" :severity="statusSeverity(data.status)" />
            </template>
          </Column>
          <Column field="createdAt" header="Created">
            <template #body="{ data }">
              {{ formatDate(data.createdAt) }}
            </template>
          </Column>
          <Column field="updatedAt" header="Last Updated">
            <template #body="{ data }">
              {{ formatDate(data.updatedAt) }}
            </template>
          </Column>
          <Column header="Actions">
            <template #body="{ data }">
              <div class="flex gap-2">
                <Button
                  icon="pi pi-eye"
                  class="p-button-text p-button-sm"
                  @click="viewDetails(data)"
                />
                <Button
                  icon="pi pi-pencil"
                  class="p-button-text p-button-sm"
                  :disabled="data.status !== 'active'"
                  @click="openAdjustDialog(data)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </div>
    </div>

    <!-- Account Details Dialog -->
    <Dialog
      v-model:visible="showDetailsDialog"
      header="Store Credit Account Details"
      :modal="true"
      :style="{ width: '50rem' }"
    >
      <div v-if="selectedAccount" class="space-y-4">
        <div class="details-grid">
          <div>
            <label>Account ID</label>
            <p>{{ selectedAccount.id }}</p>
          </div>
          <div>
            <label>User Email</label>
            <p>{{ selectedAccount.userEmail || '—' }}</p>
          </div>
          <div>
            <label>User Name</label>
            <p>{{ selectedAccount.userName || '—' }}</p>
          </div>
          <div>
            <label>User ID</label>
            <p class="font-mono text-sm">{{ selectedAccount.userId || '—' }}</p>
          </div>
          <div>
            <label>Balance</label>
            <p class="font-bold text-xl" :class="balanceClass(selectedAccount.balance)">
              {{ formatCurrency(selectedAccount.balance, selectedAccount.currency) }}
            </p>
          </div>
          <div>
            <label>Status</label>
            <Badge :value="selectedAccount.status" :severity="statusSeverity(selectedAccount.status)" />
          </div>
          <div>
            <label>Created</label>
            <p>{{ formatDate(selectedAccount.createdAt) }}</p>
          </div>
          <div>
            <label>Last Updated</label>
            <p>{{ formatDate(selectedAccount.updatedAt) }}</p>
          </div>
        </div>

        <div class="transactions-section">
          <h3>Transaction History</h3>
          <DataTable :value="transactions" :loading="loadingTransactions" striped-rows>
            <Column field="transactionType" header="Type">
              <template #body="{ data }">
                <Badge :value="data.transactionType" />
              </template>
            </Column>
            <Column field="amount" header="Amount">
              <template #body="{ data }">
                <span :class="data.amount < 0 ? 'text-red-600 font-bold' : 'text-green-600 font-bold'">
                  {{ data.amount >= 0 ? '+' : '' }}{{ formatCurrency(data.amount, selectedAccount.currency) }}
                </span>
              </template>
            </Column>
            <Column field="balanceAfter" header="Balance After">
              <template #body="{ data }">
                {{ formatCurrency(data.balanceAfter, selectedAccount.currency) }}
              </template>
            </Column>
            <Column field="reason" header="Reason" />
            <Column field="orderId" header="Order ID">
              <template #body="{ data }">
                {{ data.orderId || '—' }}
              </template>
            </Column>
            <Column field="createdAt" header="Date">
              <template #body="{ data }">
                {{ formatDateTime(data.createdAt) }}
              </template>
            </Column>
          </DataTable>
        </div>
      </div>
    </Dialog>

    <!-- Adjust Balance Dialog -->
    <Dialog
      v-model:visible="showAdjustDialog"
      header="Adjust Store Credit Balance"
      :modal="true"
      :style="{ width: '30rem' }"
    >
      <div class="space-y-4">
        <div v-if="selectedAccount">
          <label class="text-sm text-gray-500">Current Balance</label>
          <p class="text-xl font-bold" :class="balanceClass(selectedAccount.balance)">
            {{ formatCurrency(selectedAccount.balance, selectedAccount.currency) }}
          </p>
        </div>
        <div class="form-control">
          <label>Adjustment Amount *</label>
          <InputNumber
            v-model.number="adjustForm.amount"
            mode="currency"
            currency="USD"
            show-buttons
            :min-fraction-digits="2"
            :max-fraction-digits="2"
          />
          <small class="text-gray-500">Use negative values to deduct credit</small>
        </div>
        <div class="form-control">
          <label>Reason *</label>
          <Textarea v-model="adjustForm.reason" rows="3" placeholder="Explain the reason for this adjustment..." />
        </div>
        <div v-if="adjustForm.amount" class="new-balance-preview">
          <label>New Balance</label>
          <p class="text-xl font-bold" :class="balanceClass(calculateNewBalance())">
            {{ formatCurrency(calculateNewBalance(), selectedAccount?.currency || 'USD') }}
          </p>
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" icon="pi pi-times" class="p-button-text" @click="showAdjustDialog = false" />
        <Button label="Apply Adjustment" icon="pi pi-check" :loading="adjusting" @click="adjustBalance" />
      </template>
    </Dialog>

    <!-- Convert Gift Card Dialog -->
    <Dialog
      v-model:visible="showConvertDialog"
      header="Convert Gift Card to Store Credit"
      :modal="true"
      :style="{ width: '35rem' }"
    >
      <div class="space-y-4">
        <div class="form-control">
          <label>Gift Card ID *</label>
          <InputNumber v-model.number="convertForm.giftCardId" placeholder="Enter gift card ID" />
        </div>
        <div class="form-control">
          <label>User ID *</label>
          <InputText v-model="convertForm.userId" placeholder="Enter user UUID" />
        </div>
        <div class="form-control">
          <label>Reason</label>
          <Textarea v-model="convertForm.reason" rows="3" placeholder="Optional reason for conversion..." />
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" icon="pi pi-times" class="p-button-text" @click="showConvertDialog = false" />
        <Button label="Convert" icon="pi pi-arrow-right" :loading="converting" @click="convertGiftCard" />
      </template>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useToast } from 'primevue/usetoast'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'
import Badge from 'primevue/badge'
import Select from 'primevue/select'
import axios from 'axios'

interface StoreCreditAccount {
  id: number
  userId: string | null
  userEmail?: string | null
  userName?: string | null
  balance: number | string
  currency: string
  status: string
  notes?: string | null
  metadata?: string | null
  createdAt: string
  updatedAt: string
}

interface StoreCreditTransaction {
  id: number
  accountId: number
  orderId?: string | null
  giftCardId?: number | null
  amount: number | string
  transactionType: string
  balanceAfter: number | string
  reason?: string | null
  createdAt: string
}

const toast = useToast()

const accounts = ref<StoreCreditAccount[]>([])
const transactions = ref<StoreCreditTransaction[]>([])
const loading = ref(false)
const loadingTransactions = ref(false)
const adjusting = ref(false)
const converting = ref(false)
const totalRecords = ref(0)
const showDetailsDialog = ref(false)
const showAdjustDialog = ref(false)
const showConvertDialog = ref(false)
const selectedAccount = ref<StoreCreditAccount | null>(null)

const filters = reactive({
  status: null as string | null,
  search: '',
  page: 0,
  size: 20
})

const adjustForm = reactive({
  amount: 0,
  reason: ''
})

const convertForm = reactive({
  giftCardId: null as number | null,
  userId: '',
  reason: ''
})

const statusOptions = [
  { label: 'All Statuses', value: null },
  { label: 'Active', value: 'active' },
  { label: 'Suspended', value: 'suspended' },
  { label: 'Closed', value: 'closed' }
]

const loadAccounts = async () => {
  loading.value = true
  try {
    const params: Record<string, any> = {
      page: filters.page,
      size: filters.size
    }
    if (filters.status) {
      params.status = filters.status
    }
    if (filters.search && filters.search.trim().length > 0) {
      params.search = filters.search.trim()
    }

    const response = await axios.get('/api/v1/admin/store-credit/accounts', { params })
    accounts.value = response.data?.data ?? []
    totalRecords.value = response.data?.pagination?.total ?? accounts.value.length
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to load store credit accounts', life: 3000 })
  } finally {
    loading.value = false
  }
}

const viewDetails = async (account: StoreCreditAccount) => {
  selectedAccount.value = account
  showDetailsDialog.value = true
  if (account.userId) {
    await loadTransactions(account.userId)
  } else {
    transactions.value = []
  }
}

const loadTransactions = async (userId: string) => {
  if (!userId) {
    transactions.value = []
    return
  }
  loadingTransactions.value = true
  try {
    const response = await axios.get(`/api/v1/store-credit/transactions/${userId}`)
    transactions.value = response.data ?? []
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to load transactions', life: 3000 })
  } finally {
    loadingTransactions.value = false
  }
}

const openAdjustDialog = (account: StoreCreditAccount) => {
  if (!account.userId) {
    toast.add({
      severity: 'warn',
      summary: 'Unavailable',
      detail: 'Cannot adjust store credit for an account without a user ID',
      life: 3000
    })
    return
  }
  selectedAccount.value = account
  adjustForm.amount = 0
  adjustForm.reason = ''
  showAdjustDialog.value = true
}

const adjustBalance = async () => {
  if (!adjustForm.amount || adjustForm.amount === 0) {
    toast.add({ severity: 'warn', summary: 'Validation Error', detail: 'Amount cannot be zero', life: 3000 })
    return
  }
  if (!adjustForm.reason || adjustForm.reason.trim().length === 0) {
    toast.add({ severity: 'warn', summary: 'Validation Error', detail: 'Reason is required', life: 3000 })
    return
  }
  const userId = selectedAccount.value?.userId
  if (!userId) {
    toast.add({ severity: 'warn', summary: 'Selection Error', detail: 'Select an account before adjusting', life: 3000 })
    return
  }

  adjusting.value = true
  try {
    await axios.post(`/api/v1/admin/store-credit/adjust/${userId}`, {
      amount: adjustForm.amount,
      reason: adjustForm.reason
    })
    toast.add({ severity: 'success', summary: 'Success', detail: 'Store credit balance adjusted successfully', life: 3000 })
    showAdjustDialog.value = false
    loadAccounts()
  } catch (error: any) {
    const errorMsg = error.response?.data?.error || 'Failed to adjust balance'
    toast.add({ severity: 'error', summary: 'Error', detail: errorMsg, life: 3000 })
  } finally {
    adjusting.value = false
  }
}

const convertGiftCard = async () => {
  if (!convertForm.giftCardId || !convertForm.userId) {
    toast.add({
      severity: 'warn',
      summary: 'Validation Error',
      detail: 'Gift Card ID and User ID are required',
      life: 3000
    })
    return
  }

  converting.value = true
  try {
    const response = await axios.post('/api/v1/admin/store-credit/convert-gift-card', {
      giftCardId: convertForm.giftCardId,
      userId: convertForm.userId,
      reason: convertForm.reason || 'Admin conversion'
    })

    const amount = response.data.storeCreditTransaction.amount
    toast.add({
      severity: 'success',
      summary: 'Success',
      detail: `Gift card converted: ${formatCurrency(amount, selectedAccount.value?.currency || 'USD')} added to store credit`,
      life: 5000
    })
    showConvertDialog.value = false
    resetConvertForm()
    loadAccounts()
  } catch (error: any) {
    const errorMsg = error.response?.data?.error || 'Failed to convert gift card'
    toast.add({ severity: 'error', summary: 'Error', detail: errorMsg, life: 3000 })
  } finally {
    converting.value = false
  }
}

const calculateNewBalance = () => {
  if (!selectedAccount.value) return 0
  return toNumber(selectedAccount.value.balance) + toNumber(adjustForm.amount)
}

const onPage = (event: any) => {
  filters.page = event.page
  filters.size = event.rows
  loadAccounts()
}

const debouncedSearch = (() => {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return () => {
    if (timeout) {
      clearTimeout(timeout)
    }
    filters.page = 0
    timeout = setTimeout(() => loadAccounts(), 500)
  }
})()

const resetConvertForm = () => {
  convertForm.giftCardId = null
  convertForm.userId = ''
  convertForm.reason = ''
}

const toNumber = (value: number | string | null | undefined) => {
  if (value === null || value === undefined) {
    return 0
  }
  if (typeof value === 'number') {
    return value
  }
  const parsed = Number(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

const formatCurrency = (amount: number | string, currency: string = 'USD') => {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(toNumber(amount))
}

const formatDate = (date?: string) => {
  if (!date) {
    return '—'
  }
  return new Date(date).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

const formatDateTime = (date?: string) => {
  if (!date) {
    return '—'
  }
  return new Date(date).toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const statusSeverity = (status?: string) => {
  const severityMap: Record<string, string> = {
    active: 'success',
    suspended: 'warn',
    closed: 'danger'
  }
  if (!status) {
    return 'secondary'
  }
  return severityMap[status.toLowerCase()] || 'secondary'
}

const balanceClass = (balance: number | string | null | undefined) => {
  const numericBalance = toNumber(balance)
  if (numericBalance === 0) return 'text-gray-500'
  if (numericBalance < 0) return 'text-red-600'
  if (numericBalance < 10) return 'text-yellow-600'
  return 'text-green-600'
}

onMounted(() => {
  loadAccounts()
})
</script>

<style scoped>
.storecredit-dashboard {
  padding: 2rem;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2rem;
}

.dashboard-title {
  font-size: 2rem;
  font-weight: 700;
  margin: 0;
}

.dashboard-subtitle {
  color: #6b7280;
  margin: 0.5rem 0 0;
}

.header-actions {
  display: flex;
  gap: 0.75rem;
}

.space-y-6 > * + * {
  margin-top: 1.5rem;
}

.space-y-4 > * + * {
  margin-top: 1rem;
}

.filters-card {
  background: white;
  padding: 1.5rem;
  border-radius: 0.5rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.filter-row {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 1rem;
}

.form-control {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.form-control label {
  font-weight: 600;
  font-size: 0.875rem;
}

.card {
  background: white;
  padding: 1.5rem;
  border-radius: 0.5rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.details-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
}

.details-grid label {
  font-weight: 600;
  font-size: 0.875rem;
  color: #6b7280;
  display: block;
  margin-bottom: 0.25rem;
}

.transactions-section {
  margin-top: 1.5rem;
}

.transactions-section h3 {
  margin-bottom: 1rem;
  font-size: 1.125rem;
  font-weight: 600;
}

.new-balance-preview {
  padding: 1rem;
  background: #f0f9ff;
  border-radius: 0.375rem;
  border: 1px solid #0ea5e9;
}

.new-balance-preview label {
  font-weight: 600;
  font-size: 0.875rem;
  color: #0369a1;
  display: block;
  margin-bottom: 0.5rem;
}
</style>
