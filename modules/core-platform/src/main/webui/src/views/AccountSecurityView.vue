<template>
  <div class="account-security-view">
    <h1 class="text-2xl font-bold text-neutral-900 mb-6">Account Security & Privacy</h1>

    <!-- Active Sessions Card -->
    <div class="bg-white rounded-lg border border-neutral-200 mb-6 overflow-hidden">
      <div class="px-6 py-4 border-b border-neutral-200">
        <h2 class="text-lg font-semibold text-neutral-900">Active Sessions</h2>
        <p class="text-sm text-neutral-600 mt-1">
          Manage where you're logged in. If you see a session you don't recognize, revoke it immediately and consider
          changing your password.
        </p>
      </div>

      <div class="p-6">
        <!-- Loading State -->
        <div v-if="sessionsLoading" class="flex items-center justify-center py-8">
          <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
        </div>

        <!-- Sessions List -->
        <div v-else-if="sessions.length > 0" class="space-y-4">
          <div
            v-for="session in sessions"
            :key="session.sessionId"
            class="flex items-start justify-between p-4 border border-neutral-200 rounded-lg hover:bg-neutral-50 transition-colors"
          >
            <div class="flex-1">
              <div class="flex items-center gap-3 mb-2">
                <h3 class="font-medium text-neutral-900">
                  {{ session.deviceName }} - {{ session.browser }}
                </h3>
                <span
                  v-if="session.isCurrentSession"
                  class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800"
                >
                  Current Session
                </span>
                <span
                  v-if="session.impersonated"
                  class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800"
                  :title="`Impersonated by ${session.impersonatedBy}`"
                >
                  Admin Access
                </span>
              </div>
              <div class="text-sm text-neutral-600 space-y-1">
                <p>Location: {{ session.approximateLocation }}</p>
                <p>Last activity: {{ formatDate(session.lastActivityAt) }}</p>
              </div>
            </div>

            <div class="flex gap-2 ml-4">
              <button
                v-if="!session.isCurrentSession"
                @click="revokeSession(session.sessionId)"
                class="px-3 py-2 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-md hover:bg-red-100 transition-colors"
              >
                Revoke
              </button>
              <button
                @click="reportSession(session.sessionId)"
                class="px-3 py-2 text-sm font-medium text-neutral-700 bg-neutral-50 border border-neutral-200 rounded-md hover:bg-neutral-100 transition-colors"
              >
                Report
              </button>
            </div>
          </div>
        </div>

        <!-- Empty State -->
        <div v-else class="text-center py-8">
          <p class="text-neutral-600">No active sessions found</p>
        </div>

        <!-- Revoke All Button -->
        <div v-if="sessions.length > 1" class="mt-6 pt-6 border-t border-neutral-200">
          <button
            @click="revokeAllSessions"
            class="w-full px-4 py-2 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-md hover:bg-red-100 transition-colors"
          >
            Revoke All Other Sessions
          </button>
        </div>
      </div>
    </div>

    <!-- Privacy Controls Card -->
    <div class="bg-white rounded-lg border border-neutral-200 overflow-hidden">
      <div class="px-6 py-4 border-b border-neutral-200">
        <h2 class="text-lg font-semibold text-neutral-900">Privacy Controls</h2>
        <p class="text-sm text-neutral-600 mt-1">
          Manage your personal data and exercise your privacy rights under GDPR/CCPA.
        </p>
      </div>

      <div class="p-6 space-y-6">
        <!-- Data Export Section -->
        <div class="border-b border-neutral-200 pb-6">
          <h3 class="font-medium text-neutral-900 mb-2">Download Your Data</h3>
          <p class="text-sm text-neutral-600 mb-4">
            Request a copy of all personal data we have about you. You'll receive a downloadable ZIP file containing your
            profile, orders, and activity history.
          </p>

          <!-- Export Status -->
          <div v-if="exportRequest" class="mb-4 p-4 rounded-lg" :class="getExportStatusClass()">
            <div class="flex items-center justify-between">
              <div>
                <p class="font-medium">{{ getExportStatusText() }}</p>
                <p class="text-sm mt-1">{{ exportRequest.message }}</p>
                <p v-if="exportRequest.downloadUrl" class="text-sm mt-2">
                  <a
                    :href="exportRequest.downloadUrl"
                    target="_blank"
                    class="text-primary-600 hover:text-primary-700 underline"
                  >
                    Download your data
                  </a>
                  (expires in 72 hours)
                </p>
              </div>
              <div v-if="exportRequest.status === 'processing'" class="animate-spin rounded-full h-6 w-6 border-b-2 border-primary-600"></div>
            </div>
          </div>

          <button
            @click="requestDataExport"
            :disabled="exportLoading || (exportRequest && exportRequest.status !== 'completed')"
            class="px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {{ exportLoading ? 'Requesting...' : 'Request Data Export' }}
          </button>
        </div>

        <!-- Account Deletion Section -->
        <div>
          <h3 class="font-medium text-neutral-900 mb-2">Delete Your Account</h3>
          <p class="text-sm text-neutral-600 mb-4">
            Permanently delete your account and all associated data. This action is irreversible. Your account will be
            deactivated immediately and permanently deleted after 90 days.
          </p>

          <!-- Deletion Status -->
          <div v-if="deleteRequest" class="mb-4 p-4 rounded-lg bg-red-50 border border-red-200">
            <div class="flex items-center justify-between">
              <div>
                <p class="font-medium text-red-900">{{ getDeleteStatusText() }}</p>
                <p class="text-sm mt-1 text-red-700">{{ deleteRequest.message }}</p>
              </div>
            </div>
          </div>

          <button
            v-if="!deleteRequest"
            @click="showDeleteConfirmation = true"
            class="px-4 py-2 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-md hover:bg-red-100 transition-colors"
          >
            Delete My Account
          </button>
        </div>
      </div>
    </div>

    <!-- Delete Confirmation Modal -->
    <div
      v-if="showDeleteConfirmation"
      class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      @click.self="showDeleteConfirmation = false"
    >
      <div class="bg-white rounded-lg shadow-xl max-w-md w-full mx-4">
        <div class="px-6 py-4 border-b border-neutral-200">
          <h3 class="text-lg font-semibold text-neutral-900">Confirm Account Deletion</h3>
        </div>
        <div class="px-6 py-4">
          <p class="text-neutral-700 mb-4">
            Are you absolutely sure you want to delete your account? This action cannot be undone.
          </p>
          <ul class="list-disc list-inside text-sm text-neutral-600 space-y-1 mb-4">
            <li>Your account will be deactivated immediately</li>
            <li>All personal data will be permanently deleted after 90 days</li>
            <li>You will lose access to all orders and history</li>
            <li>This action is irreversible</li>
          </ul>
          <p class="text-sm text-neutral-600">
            If you're having issues with your account, please
            <a href="/support" class="text-primary-600 hover:underline">contact support</a> instead.
          </p>
        </div>
        <div class="px-6 py-4 border-t border-neutral-200 flex justify-end gap-3">
          <button
            @click="showDeleteConfirmation = false"
            class="px-4 py-2 text-sm font-medium text-neutral-700 bg-white border border-neutral-300 rounded-md hover:bg-neutral-50 transition-colors"
          >
            Cancel
          </button>
          <button
            @click="confirmAccountDeletion"
            :disabled="deleteLoading"
            class="px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-md hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {{ deleteLoading ? 'Deleting...' : 'Yes, Delete My Account' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Report Session Modal -->
    <div
      v-if="showReportModal"
      class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      @click.self="showReportModal = false"
    >
      <div class="bg-white rounded-lg shadow-xl max-w-md w-full mx-4">
        <div class="px-6 py-4 border-b border-neutral-200">
          <h3 class="text-lg font-semibold text-neutral-900">Report Suspicious Activity</h3>
        </div>
        <div class="px-6 py-4">
          <p class="text-neutral-700 mb-4">
            Please describe why this session appears suspicious. Our security team will investigate.
          </p>
          <textarea
            v-model="reportDescription"
            rows="4"
            class="w-full px-3 py-2 border border-neutral-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
            placeholder="Example: I don't recognize this location or device..."
          ></textarea>
        </div>
        <div class="px-6 py-4 border-t border-neutral-200 flex justify-end gap-3">
          <button
            @click="showReportModal = false"
            class="px-4 py-2 text-sm font-medium text-neutral-700 bg-white border border-neutral-300 rounded-md hover:bg-neutral-50 transition-colors"
          >
            Cancel
          </button>
          <button
            @click="submitReport"
            :disabled="reportLoading || !reportDescription.trim()"
            class="px-4 py-2 text-sm font-medium text-white bg-primary-600 rounded-md hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {{ reportLoading ? 'Submitting...' : 'Submit Report' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

interface Session {
  sessionId: string
  deviceName: string
  browser: string
  ipAddress: string
  approximateLocation: string
  lastActivityAt: string
  isCurrentSession: boolean
  impersonated: boolean
  impersonatedBy?: string
}

interface PrivacyRequest {
  requestId?: string
  status: string
  message: string
  downloadUrl?: string
}

const sessions = ref<Session[]>([])
const sessionsLoading = ref(false)

const exportRequest = ref<PrivacyRequest | null>(null)
const exportLoading = ref(false)

const deleteRequest = ref<PrivacyRequest | null>(null)
const deleteLoading = ref(false)
const showDeleteConfirmation = ref(false)

const showReportModal = ref(false)
const reportingSessionId = ref<string | null>(null)
const reportDescription = ref('')
const reportLoading = ref(false)

onMounted(() => {
  loadSessions()
  checkExistingRequests()
})

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const response = await fetch('/api/v1/sessions')
    if (response.ok) {
      const data = await response.json()
      sessions.value = data.sessions || []
    } else {
      console.error('Failed to load sessions:', response.statusText)
    }
  } catch (error) {
    console.error('Error loading sessions:', error)
  } finally {
    sessionsLoading.value = false
  }
}

async function checkExistingRequests() {
  // Check for existing export requests
  try {
    const response = await fetch('/api/v1/privacy/exports')
    if (response.ok) {
      const data = await response.json()
      if (data.exports && data.exports.length > 0) {
        // Get the most recent export request
        const latestExport = data.exports[0]
        const statusResponse = await fetch(`/api/v1/privacy/export/${latestExport.requestId}`)
        if (statusResponse.ok) {
          exportRequest.value = await statusResponse.json()
        }
      }
    }
  } catch (error) {
    console.error('Error checking export requests:', error)
  }
}

async function requestDataExport() {
  exportLoading.value = true
  try {
    const response = await fetch('/api/v1/privacy/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    })

    if (response.ok) {
      const data = await response.json()
      exportRequest.value = data

      // Start polling for status
      pollExportStatus(data.requestId)
    } else if (response.status === 409) {
      const error = await response.json()
      exportRequest.value = error
    } else {
      console.error('Failed to request export:', response.statusText)
      alert('Failed to request data export. Please try again later.')
    }
  } catch (error) {
    console.error('Error requesting export:', error)
    alert('An error occurred. Please try again later.')
  } finally {
    exportLoading.value = false
  }
}

async function pollExportStatus(requestId: string) {
  const pollInterval = setInterval(async () => {
    try {
      const response = await fetch(`/api/v1/privacy/export/${requestId}`)
      if (response.ok) {
        const data = await response.json()
        exportRequest.value = data

        if (data.status === 'completed' || data.status === 'failed') {
          clearInterval(pollInterval)
        }
      }
    } catch (error) {
      console.error('Error polling export status:', error)
      clearInterval(pollInterval)
    }
  }, 5000) // Poll every 5 seconds
}

async function confirmAccountDeletion() {
  deleteLoading.value = true
  try {
    const response = await fetch('/api/v1/privacy/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    })

    if (response.ok) {
      const data = await response.json()
      deleteRequest.value = data
      showDeleteConfirmation.value = false
    } else if (response.status === 409) {
      const error = await response.json()
      deleteRequest.value = error
      showDeleteConfirmation.value = false
    } else {
      console.error('Failed to request deletion:', response.statusText)
      alert('Failed to request account deletion. Please try again later.')
    }
  } catch (error) {
    console.error('Error requesting deletion:', error)
    alert('An error occurred. Please try again later.')
  } finally {
    deleteLoading.value = false
  }
}

async function revokeSession(sessionId: string) {
  if (!confirm('Are you sure you want to revoke this session? The device will be logged out.')) {
    return
  }

  try {
    const response = await fetch(`/api/v1/sessions/${sessionId}`, {
      method: 'DELETE',
    })

    if (response.ok) {
      // Refresh sessions list
      await loadSessions()
    } else {
      console.error('Failed to revoke session:', response.statusText)
      alert('Failed to revoke session. Please try again.')
    }
  } catch (error) {
    console.error('Error revoking session:', error)
    alert('An error occurred. Please try again.')
  }
}

async function revokeAllSessions() {
  if (
    !confirm(
      'Are you sure you want to revoke all other sessions? All other devices will be logged out, but you will remain logged in on this device.'
    )
  ) {
    return
  }

  try {
    const response = await fetch('/api/v1/sessions/revoke-all', {
      method: 'POST',
    })

    if (response.ok) {
      // Refresh sessions list
      await loadSessions()
    } else {
      console.error('Failed to revoke all sessions:', response.statusText)
      alert('Failed to revoke sessions. Please try again.')
    }
  } catch (error) {
    console.error('Error revoking all sessions:', error)
    alert('An error occurred. Please try again.')
  }
}

function reportSession(sessionId: string) {
  reportingSessionId.value = sessionId
  showReportModal.value = true
  reportDescription.value = ''
}

async function submitReport() {
  if (!reportingSessionId.value || !reportDescription.value.trim()) {
    return
  }

  reportLoading.value = true
  try {
    const response = await fetch(`/api/v1/sessions/${reportingSessionId.value}/report`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: reportDescription.value }),
    })

    if (response.ok) {
      alert('Thank you for your report. Our security team will investigate.')
      showReportModal.value = false
    } else {
      console.error('Failed to submit report:', response.statusText)
      alert('Failed to submit report. Please try again.')
    }
  } catch (error) {
    console.error('Error submitting report:', error)
    alert('An error occurred. Please try again.')
  } finally {
    reportLoading.value = false
  }
}

function formatDate(dateString: string): string {
  const date = new Date(dateString)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)

  if (diffMins < 1) return 'Just now'
  if (diffMins < 60) return `${diffMins} minute${diffMins > 1 ? 's' : ''} ago`

  const diffHours = Math.floor(diffMins / 60)
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`

  const diffDays = Math.floor(diffHours / 24)
  if (diffDays < 7) return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`

  return date.toLocaleDateString()
}

function getExportStatusClass(): string {
  if (!exportRequest.value) return ''

  switch (exportRequest.value.status) {
    case 'completed':
      return 'bg-green-50 border border-green-200'
    case 'processing':
      return 'bg-blue-50 border border-blue-200'
    case 'failed':
      return 'bg-red-50 border border-red-200'
    default:
      return 'bg-neutral-50 border border-neutral-200'
  }
}

function getExportStatusText(): string {
  if (!exportRequest.value) return ''

  switch (exportRequest.value.status) {
    case 'completed':
      return 'Export Ready'
    case 'processing':
      return 'Processing Export'
    case 'failed':
      return 'Export Failed'
    default:
      return 'Export Requested'
  }
}

function getDeleteStatusText(): string {
  if (!deleteRequest.value) return ''

  switch (deleteRequest.value.status) {
    case 'completed':
      return 'Account Deleted'
    case 'awaiting_purge':
      return 'Account Deactivated'
    case 'processing':
      return 'Processing Deletion'
    case 'failed':
      return 'Deletion Failed'
    default:
      return 'Deletion Requested'
  }
}
</script>
