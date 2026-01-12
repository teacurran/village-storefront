<template>
  <div class="reports-view">
    <h1 class="text-2xl font-bold text-neutral-900 mb-6">Reports & Exports</h1>

    <div class="mb-8">
      <InlineAlert
        tone="info"
        title="Report Exports"
        description="Request exports of sales, consignment payouts, and inventory data. Downloads expire after 24 hours."
        :dismissible="false"
      />
    </div>

    <!-- Export Request Form -->
    <div class="bg-white rounded-lg border border-neutral-200 p-6 mb-8">
      <h2 class="text-lg font-semibold text-neutral-900 mb-4">Request New Export</h2>

      <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-4">
        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Report Type</label>
          <select
            v-model="exportForm.reportType"
            class="w-full border border-neutral-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option value="sales_by_period">Sales by Period</option>
            <option value="consignment_payout">Consignment Payouts</option>
            <option value="inventory_aging">Inventory Aging</option>
            <option value="loyalty_summary">Loyalty Summary</option>
          </select>
        </div>

        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Format</label>
          <select
            v-model="exportForm.format"
            class="w-full border border-neutral-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option value="csv">CSV</option>
            <option value="json">JSON</option>
          </select>
        </div>

        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">Start Date</label>
          <input
            v-model="exportForm.startDate"
            type="date"
            class="w-full border border-neutral-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>

        <div>
          <label class="block text-sm font-medium text-neutral-700 mb-1">End Date</label>
          <input
            v-model="exportForm.endDate"
            type="date"
            class="w-full border border-neutral-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
      </div>

      <button
        @click="requestExport"
        :disabled="requesting"
        class="px-4 py-2 bg-primary-600 text-white rounded-md hover:bg-primary-700 disabled:bg-neutral-300 disabled:cursor-not-allowed transition-colors"
      >
        {{ requesting ? 'Requesting...' : 'Request Export' }}
      </button>
    </div>

    <!-- Recent Exports -->
    <div class="bg-white rounded-lg border border-neutral-200 p-6">
      <h2 class="text-lg font-semibold text-neutral-900 mb-4">Recent Exports</h2>

      <div v-if="loading" class="text-center py-8 text-neutral-500">Loading exports...</div>

      <div v-else-if="jobs.length === 0" class="text-center py-8 text-neutral-500">
        No exports yet. Request one above to get started.
      </div>

      <div v-else class="space-y-3">
        <div
          v-for="job in jobs"
          :key="job.jobId"
          class="border border-neutral-200 rounded-md p-4 hover:bg-neutral-50 transition-colors"
        >
          <div class="flex items-start justify-between">
            <div class="flex-1">
              <div class="flex items-center gap-3 mb-2">
                <h3 class="font-medium text-neutral-900">
                  {{ formatReportType(job.reportType) }}
                </h3>
                <StatusBadge :status="job.status" />
              </div>

              <div class="text-sm text-neutral-600 space-y-1">
                <div>
                  <span class="font-medium">Requested:</span>
                  {{ formatDateTime(job.createdAt) }}
                </div>
                <div v-if="job.completedAt">
                  <span class="font-medium">Completed:</span>
                  {{ formatDateTime(job.completedAt) }}
                </div>
                <div v-if="job.urlExpiresAt">
                  <span class="font-medium">Expires:</span>
                  {{ formatDateTime(job.urlExpiresAt) }}
                  <span
                    v-if="job.status === 'completed' && job.urlExpiresAt && !job.downloadReady"
                    class="ml-1 text-xs text-red-600"
                  >
                    Link expired
                  </span>
                </div>
                <div v-if="job.manifest && job.manifest.requestedRange">
                  <span class="font-medium">Range:</span>
                  {{ formatRange(job.manifest.requestedRange) }}
                  <span
                    v-if="job.manifest.archivalLookupRequired"
                    class="ml-2 inline-flex items-center gap-1 text-xs text-amber-600 font-semibold"
                  >
                    <span class="h-2 w-2 rounded-full bg-amber-500"></span>
                    Includes Archive
                  </span>
                </div>
                <div v-if="job.error" class="text-red-600">
                  <span class="font-medium">Error:</span>
                  {{ job.error }}
                </div>
              </div>
            </div>

            <div class="flex items-center gap-2">
              <button
                v-if="job.downloadReady && job.downloadUrl"
                @click="downloadReport(job.downloadUrl)"
                class="px-3 py-1.5 bg-primary-600 text-white text-sm rounded hover:bg-primary-700 transition-colors"
              >
                Download
              </button>

              <button
                v-if="job.status === 'pending' || job.status === 'running'"
                @click="cancelJob(job.jobId)"
                class="px-3 py-1.5 bg-red-600 text-white text-sm rounded hover:bg-red-700 transition-colors"
              >
                Cancel
              </button>

              <button
                v-if="job.status === 'pending' || job.status === 'running'"
                @click="refreshJobStatus(job.jobId)"
                class="px-3 py-1.5 bg-neutral-200 text-neutral-700 text-sm rounded hover:bg-neutral-300 transition-colors"
              >
                Refresh
              </button>
            </div>
          </div>

          <!-- Progress indicator for running jobs -->
          <div v-if="job.status === 'running'" class="mt-3">
            <div class="w-full bg-neutral-200 rounded-full h-2">
              <div class="bg-primary-600 h-2 rounded-full animate-pulse" style="width: 60%"></div>
            </div>
            <p class="text-xs text-neutral-500 mt-1">Processing export...</p>
          </div>
        </div>
      </div>

      <!-- Pagination -->
      <div v-if="totalCount > pageSize" class="mt-6 flex items-center justify-between">
        <p class="text-sm text-neutral-600">
          Showing {{ (page * pageSize) + 1 }}-{{ Math.min((page + 1) * pageSize, totalCount) }} of
          {{ totalCount }}
        </p>
        <div class="flex gap-2">
          <button
            @click="prevPage"
            :disabled="page === 0"
            class="px-3 py-1 border border-neutral-300 rounded text-sm disabled:opacity-50 disabled:cursor-not-allowed hover:bg-neutral-50"
          >
            Previous
          </button>
          <button
            @click="nextPage"
            :disabled="(page + 1) * pageSize >= totalCount"
            class="px-3 py-1 border border-neutral-300 rounded text-sm disabled:opacity-50 disabled:cursor-not-allowed hover:bg-neutral-50"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import InlineAlert from '@/components/base/InlineAlert.vue'

interface ManifestRange {
  startDate: string
  endDate: string
}

interface ManifestMetadata {
  requestedRange?: ManifestRange
  hotStorageRange?: ManifestRange
  archivedRange?: ManifestRange
  archivalLookupRequired?: boolean
  archiveOnly?: boolean
  dataSource?: string
  downloadExpiresAt?: string
}

interface ExportJob {
  jobId: string
  reportType: string
  status: string
  createdAt: string
  startedAt?: string
  completedAt?: string
  downloadUrl?: string
  downloadReady: boolean
  urlExpiresAt?: string
  manifestMetadata?: string
  manifest: ManifestMetadata | null
  error?: string
}

type ApiJob = Omit<ExportJob, 'manifest' | 'downloadReady'> & {
  downloadReady?: boolean
  manifestMetadata?: string | ManifestMetadata
}

const parseManifest = (manifest: string | ManifestMetadata | undefined | null): ManifestMetadata | null => {
  if (!manifest) return null
  if (typeof manifest !== 'string') {
    return manifest as ManifestMetadata
  }
  try {
    return JSON.parse(manifest) as ManifestMetadata
  } catch (error) {
    console.warn('Failed to parse manifest metadata', error)
    return null
  }
}

const hydrateJob = (job: ApiJob): ExportJob => {
  const manifest = parseManifest(job.manifestMetadata)
  const manifestPayload =
    typeof job.manifestMetadata === 'string' || job.manifestMetadata === undefined
      ? job.manifestMetadata
      : JSON.stringify(job.manifestMetadata)

  return {
    jobId: job.jobId,
    reportType: job.reportType,
    status: job.status,
    createdAt: job.createdAt,
    startedAt: job.startedAt,
    completedAt: job.completedAt,
    downloadUrl: job.downloadUrl,
    downloadReady: Boolean(job.downloadReady),
    urlExpiresAt: job.urlExpiresAt,
    manifestMetadata: manifestPayload,
    manifest,
    error: job.error
  }
}

const exportForm = ref({
  reportType: 'sales_by_period',
  startDate: '',
  endDate: '',
  format: 'csv'
})

const jobs = ref<ExportJob[]>([])
const loading = ref(true)
const requesting = ref(false)
const page = ref(0)
const pageSize = ref(20)
const totalCount = ref(0)

const StatusBadge = (props: { status: string }) => {
  const colors: Record<string, string> = {
    pending: 'bg-yellow-100 text-yellow-800',
    running: 'bg-blue-100 text-blue-800',
    completed: 'bg-green-100 text-green-800',
    failed: 'bg-red-100 text-red-800',
    cancelled: 'bg-neutral-100 text-neutral-800'
  }

  return {
    render() {
      return (
        <span class={`px-2 py-1 text-xs font-medium rounded ${colors[props.status] || 'bg-neutral-100 text-neutral-800'}`}>
          {props.status}
        </span>
      )
    }
  }
}

const formatReportType = (type: string): string => {
  const names: Record<string, string> = {
    sales_by_period: 'Sales by Period',
    consignment_payout: 'Consignment Payouts',
    inventory_aging: 'Inventory Aging',
    loyalty_summary: 'Loyalty Summary'
  }
  return names[type] || type
}

const formatRange = (range?: ManifestRange | null): string => {
  if (!range) return ''
  return `${range.startDate} → ${range.endDate}`
}

const formatDateTime = (isoString?: string): string => {
  if (!isoString) return ''
  return new Date(isoString).toLocaleString()
}

const loadJobs = async () => {
  loading.value = true
  try {
    const response = await fetch(
      `/api/v1/admin/reports/jobs?page=${page.value}&size=${pageSize.value}`
    )
    if (!response.ok) throw new Error('Failed to load jobs')

    const data = await response.json()
    jobs.value = (data.jobs as ApiJob[]).map((job) => hydrateJob(job))
    totalCount.value = data.totalCount
  } catch (error) {
    console.error('Failed to load export jobs:', error)
  } finally {
    loading.value = false
  }
}

const requestExport = async () => {
  if (!exportForm.value.startDate || !exportForm.value.endDate) {
    alert('Please select start and end dates')
    return
  }

  requesting.value = true
  try {
    const response = await fetch(`/api/v1/admin/reports/${exportForm.value.reportType}/export`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        format: exportForm.value.format,
        startDate: exportForm.value.startDate,
        endDate: exportForm.value.endDate,
        requestedBy: 'admin' // TODO: Get from auth context
      })
    })

    if (!response.ok) throw new Error('Failed to request export')

    const result = await response.json()
    console.log('Export requested:', result)

    // Reload jobs list
    await loadJobs()

    // Reset form
    exportForm.value.startDate = ''
    exportForm.value.endDate = ''
  } catch (error) {
    console.error('Failed to request export:', error)
    alert('Failed to request export. Please try again.')
  } finally {
    requesting.value = false
  }
}

const refreshJobStatus = async (jobId: string) => {
  try {
    const response = await fetch(`/api/v1/admin/reports/jobs/${jobId}`)
    if (!response.ok) throw new Error('Failed to refresh job status')

    const updatedJob = hydrateJob((await response.json()) as ApiJob)

    // Update job in list
    const index = jobs.value.findIndex((j) => j.jobId === jobId)
    if (index !== -1) {
      jobs.value[index] = updatedJob
    }
  } catch (error) {
    console.error('Failed to refresh job status:', error)
  }
}

const cancelJob = async (jobId: string) => {
  if (!confirm('Are you sure you want to cancel this export job?')) return

  try {
    const response = await fetch(`/api/v1/admin/reports/jobs/${jobId}`, {
      method: 'DELETE'
    })

    if (!response.ok) throw new Error('Failed to cancel job')

    // Reload jobs list
    await loadJobs()
  } catch (error) {
    console.error('Failed to cancel job:', error)
    alert('Failed to cancel job. Please try again.')
  }
}

const downloadReport = (url: string) => {
  window.open(url, '_blank')
}

const prevPage = () => {
  if (page.value > 0) {
    page.value--
    loadJobs()
  }
}

const nextPage = () => {
  if ((page.value + 1) * pageSize.value < totalCount.value) {
    page.value++
    loadJobs()
  }
}

let pollInterval: number | null = null

onMounted(() => {
  loadJobs()

  pollInterval = window.setInterval(() => {
    if (jobs.value.some((j) => j.status === 'pending' || j.status === 'running')) {
      loadJobs()
    }
  }, 10000)
})

onUnmounted(() => {
  if (pollInterval) {
    window.clearInterval(pollInterval)
    pollInterval = null
  }
})
</script>

<style scoped>
.reports-view {
  @apply max-w-7xl mx-auto;
}
</style>
