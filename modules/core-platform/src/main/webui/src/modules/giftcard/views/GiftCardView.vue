<template>
  <div class="giftcard-dashboard">
    <div class="dashboard-header">
      <div>
        <h1 class="dashboard-title">Gift Cards</h1>
        <p class="dashboard-subtitle">Manage gift card issuance, redemption, and balances</p>
      </div>
      <div class="header-actions">
        <Button
          icon="pi pi-refresh"
          class="p-button-text"
          label="Refresh"
          @click="loadGiftCards"
        />
        <Button
          icon="pi pi-plus"
          label="Issue Gift Card"
          @click="showIssueDialog = true"
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
              @change="loadGiftCards"
            />
          </div>
          <div class="form-control">
            <label>Search</label>
            <InputText
              v-model="filters.search"
              placeholder="Search by recipient email..."
              @input="debouncedSearch"
            />
          </div>
        </div>
      </div>

      <!-- Gift Cards Table -->
      <div class="card">
        <DataTable
          :value="giftCards"
          :loading="loading"
          striped-rows
          paginator
          :rows="20"
          :total-records="totalRecords"
          lazy
          @page="onPage"
        >
          <Column field="id" header="ID" />
          <Column field="recipientEmail" header="Recipient">
            <template #body="{ data }">
              <div>
                <div class="font-medium">{{ data.recipientName || '—' }}</div>
                <div class="text-sm text-gray-500">{{ data.recipientEmail || '—' }}</div>
              </div>
            </template>
          </Column>
          <Column field="initialBalance" header="Initial Balance">
            <template #body="{ data }">
              {{ formatCurrency(data.initialBalance, data.currency) }}
            </template>
          </Column>
          <Column field="currentBalance" header="Current Balance">
            <template #body="{ data }">
              <span :class="balanceClass(data.currentBalance)">
                {{ formatCurrency(data.currentBalance, data.currency) }}
              </span>
            </template>
          </Column>
          <Column field="status" header="Status">
            <template #body="{ data }">
              <Badge :value="data.status" :severity="statusSeverity(data.status)" />
            </template>
          </Column>
          <Column field="createdAt" header="Issued">
            <template #body="{ data }">
              {{ formatDate(data.createdAt) }}
            </template>
          </Column>
          <Column field="expiresAt" header="Expires">
            <template #body="{ data }">
              {{ data.expiresAt ? formatDate(data.expiresAt) : 'Never' }}
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
                  icon="pi pi-send"
                  class="p-button-text p-button-sm"
                  :disabled="data.status !== 'active'"
                  @click="openResendDialog(data)"
                />
                <Button
                  icon="pi pi-ban"
                  class="p-button-text p-button-sm p-button-danger"
                  :disabled="data.status !== 'active'"
                  @click="cancelGiftCard(data)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </div>
    </div>

    <!-- Issue Gift Card Dialog -->
    <Dialog
      v-model:visible="showIssueDialog"
      header="Issue Gift Card"
      :modal="true"
      :style="{ width: '40rem' }"
    >
      <div class="space-y-4">
        <div class="form-control">
          <label>Initial Balance *</label>
          <InputNumber
            v-model.number="issueForm.initialBalance"
            mode="currency"
            currency="USD"
            :min="0.01"
            show-buttons
          />
        </div>
        <div class="form-control">
          <label>Currency</label>
          <InputText v-model="issueForm.currency" placeholder="USD" />
        </div>
        <div class="form-control">
          <label>Recipient Email</label>
          <InputText v-model="issueForm.recipientEmail" type="email" placeholder="recipient@example.com" />
        </div>
        <div class="form-control">
          <label>Recipient Name</label>
          <InputText v-model="issueForm.recipientName" placeholder="Jane Doe" />
        </div>
        <div class="form-control">
          <label>Personal Message</label>
          <Textarea v-model="issueForm.personalMessage" rows="3" auto-resize />
        </div>
        <div class="form-control">
          <label>Expiration Date</label>
          <Calendar v-model="issueForm.expiresAt" show-icon date-format="yy-mm-dd" />
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" icon="pi pi-times" class="p-button-text" @click="showIssueDialog = false" />
        <Button label="Issue" icon="pi pi-check" :loading="issuing" @click="issueGiftCard" />
      </template>
    </Dialog>

    <!-- Gift Card Details Dialog -->
    <Dialog
      v-model:visible="showDetailsDialog"
      header="Gift Card Details"
      :modal="true"
      :style="{ width: '50rem' }"
    >
      <div v-if="selectedGiftCard" class="space-y-4">
        <div class="details-grid">
          <div>
            <label>Gift Card ID</label>
            <p>{{ selectedGiftCard.id }}</p>
          </div>
          <div>
            <label>Status</label>
            <Badge :value="selectedGiftCard.status" :severity="statusSeverity(selectedGiftCard.status)" />
          </div>
          <div>
            <label>Initial Balance</label>
            <p>{{ formatCurrency(selectedGiftCard.initialBalance, selectedGiftCard.currency) }}</p>
          </div>
          <div>
            <label>Current Balance</label>
            <p class="font-bold" :class="balanceClass(selectedGiftCard.currentBalance)">
              {{ formatCurrency(selectedGiftCard.currentBalance, selectedGiftCard.currency) }}
            </p>
          </div>
          <div>
            <label>Recipient</label>
            <p>{{ selectedGiftCard.recipientName || '—' }}</p>
            <p class="text-sm text-gray-500">{{ selectedGiftCard.recipientEmail || '—' }}</p>
          </div>
          <div>
            <label>Purchaser</label>
            <p>{{ selectedGiftCard.purchaserEmail || '—' }}</p>
          </div>
          <div>
            <label>Issued</label>
            <p>{{ formatDate(selectedGiftCard.createdAt) }}</p>
          </div>
          <div>
            <label>Expires</label>
            <p>{{ selectedGiftCard.expiresAt ? formatDate(selectedGiftCard.expiresAt) : 'Never' }}</p>
          </div>
        </div>

        <div v-if="selectedGiftCard.personalMessage" class="message-card">
          <label>Personal Message</label>
          <p>{{ selectedGiftCard.personalMessage }}</p>
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
                <span :class="data.amount < 0 ? 'text-red-600' : 'text-green-600'">
                  {{ formatCurrency(data.amount, selectedGiftCard.currency) }}
                </span>
              </template>
            </Column>
            <Column field="balanceAfter" header="Balance After">
              <template #body="{ data }">
                {{ formatCurrency(data.balanceAfter, selectedGiftCard.currency) }}
              </template>
            </Column>
            <Column field="reason" header="Reason" />
            <Column field="createdAt" header="Date">
              <template #body="{ data }">
                {{ formatDate(data.createdAt) }}
              </template>
            </Column>
          </DataTable>
        </div>
      </div>
    </Dialog>

    <!-- Resend Dialog -->
    <Dialog
      v-model:visible="showResendDialog"
      header="Resend Gift Card"
      :modal="true"
      :style="{ width: '30rem' }"
    >
      <div class="form-control">
        <label>Recipient Email *</label>
        <InputText v-model="resendForm.recipientEmail" type="email" placeholder="recipient@example.com" />
      </div>
      <template #footer>
        <Button label="Cancel" icon="pi pi-times" class="p-button-text" @click="showResendDialog = false" />
        <Button label="Resend" icon="pi pi-send" :loading="resending" @click="resendGiftCard" />
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
import Calendar from 'primevue/calendar'
import Badge from 'primevue/badge'
import Select from 'primevue/select'
import axios from 'axios'

const toast = useToast()

const giftCards = ref([])
const transactions = ref([])
const loading = ref(false)
const loadingTransactions = ref(false)
const issuing = ref(false)
const resending = ref(false)
const totalRecords = ref(0)
const showIssueDialog = ref(false)
const showDetailsDialog = ref(false)
const showResendDialog = ref(false)
const selectedGiftCard = ref(null)

const filters = reactive({
  status: null,
  search: '',
  page: 0,
  size: 20
})

const issueForm = reactive({
  initialBalance: 100.00,
  currency: 'USD',
  recipientEmail: '',
  recipientName: '',
  personalMessage: '',
  expiresAt: null
})

const resendForm = reactive({
  recipientEmail: ''
})

const statusOptions = [
  { label: 'All Statuses', value: null },
  { label: 'Active', value: 'active' },
  { label: 'Redeemed', value: 'redeemed' },
  { label: 'Expired', value: 'expired' },
  { label: 'Cancelled', value: 'cancelled' }
]

const loadGiftCards = async () => {
  loading.value = true
  try {
    const params: any = {
      page: filters.page,
      size: filters.size
    }
    if (filters.status) {
      params.status = filters.status
    }

    const response = await axios.get('/api/v1/admin/gift-cards', { params })
    giftCards.value = response.data.data
    totalRecords.value = response.data.pagination.total
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to load gift cards', life: 3000 })
  } finally {
    loading.value = false
  }
}

const issueGiftCard = async () => {
  if (!issueForm.initialBalance || issueForm.initialBalance < 0.01) {
    toast.add({ severity: 'warn', summary: 'Validation Error', detail: 'Initial balance must be at least 0.01', life: 3000 })
    return
  }

  issuing.value = true
  try {
    const payload = {
      initialBalance: issueForm.initialBalance,
      currency: issueForm.currency || 'USD',
      recipientEmail: issueForm.recipientEmail || null,
      recipientName: issueForm.recipientName || null,
      personalMessage: issueForm.personalMessage || null,
      expiresAt: issueForm.expiresAt ? issueForm.expiresAt.toISOString() : null
    }

    await axios.post('/api/v1/admin/gift-cards', payload)
    toast.add({ severity: 'success', summary: 'Success', detail: 'Gift card issued successfully', life: 3000 })
    showIssueDialog.value = false
    resetIssueForm()
    loadGiftCards()
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to issue gift card', life: 3000 })
  } finally {
    issuing.value = false
  }
}

const viewDetails = async (giftCard: any) => {
  selectedGiftCard.value = giftCard
  showDetailsDialog.value = true
  await loadTransactions(giftCard.id)
}

const loadTransactions = async (giftCardId: number) => {
  loadingTransactions.value = true
  try {
    const response = await axios.get(`/api/v1/gift-cards/transactions/${giftCardId}`)
    transactions.value = response.data
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to load transactions', life: 3000 })
  } finally {
    loadingTransactions.value = false
  }
}

const openResendDialog = (giftCard: any) => {
  selectedGiftCard.value = giftCard
  resendForm.recipientEmail = giftCard.recipientEmail || ''
  showResendDialog.value = true
}

const resendGiftCard = async () => {
  if (!resendForm.recipientEmail) {
    toast.add({ severity: 'warn', summary: 'Validation Error', detail: 'Recipient email is required', life: 3000 })
    return
  }

  resending.value = true
  try {
    await axios.post(`/api/v1/admin/gift-cards/${selectedGiftCard.value.id}/resend`, {
      recipientEmail: resendForm.recipientEmail
    })
    toast.add({ severity: 'success', summary: 'Success', detail: 'Gift card code resent successfully', life: 3000 })
    showResendDialog.value = false
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to resend gift card', life: 3000 })
  } finally {
    resending.value = false
  }
}

const cancelGiftCard = async (giftCard: any) => {
  if (!confirm('Are you sure you want to cancel this gift card? This action cannot be undone.')) {
    return
  }

  try {
    await axios.put(`/api/v1/admin/gift-cards/${giftCard.id}`, {
      status: 'cancelled',
      reason: 'Admin cancelled'
    })
    toast.add({ severity: 'success', summary: 'Success', detail: 'Gift card cancelled successfully', life: 3000 })
    loadGiftCards()
  } catch (error) {
    toast.add({ severity: 'error', summary: 'Error', detail: 'Failed to cancel gift card', life: 3000 })
  }
}

const onPage = (event: any) => {
  filters.page = event.page
  filters.size = event.rows
  loadGiftCards()
}

const debouncedSearch = (() => {
  let timeout: any
  return () => {
    clearTimeout(timeout)
    timeout = setTimeout(() => loadGiftCards(), 500)
  }
})()

const resetIssueForm = () => {
  issueForm.initialBalance = 100.00
  issueForm.currency = 'USD'
  issueForm.recipientEmail = ''
  issueForm.recipientName = ''
  issueForm.personalMessage = ''
  issueForm.expiresAt = null
}

const formatCurrency = (amount: number, currency: string = 'USD') => {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
}

const formatDate = (date: string) => {
  return new Date(date).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

const statusSeverity = (status: string) => {
  const severityMap: Record<string, any> = {
    active: 'success',
    redeemed: 'info',
    expired: 'warn',
    cancelled: 'danger'
  }
  return severityMap[status] || 'secondary'
}

const balanceClass = (balance: number) => {
  if (balance === 0) return 'text-gray-500'
  if (balance < 10) return 'text-yellow-600'
  return 'text-green-600'
}

onMounted(() => {
  loadGiftCards()
})
</script>

<style scoped>
.giftcard-dashboard {
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

.message-card {
  padding: 1rem;
  background: #f9fafb;
  border-radius: 0.375rem;
}

.transactions-section {
  margin-top: 1.5rem;
}

.transactions-section h3 {
  margin-bottom: 1rem;
  font-size: 1.125rem;
  font-weight: 600;
}
</style>
