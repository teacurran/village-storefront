import { apiClient } from '@/api/client'
import type { SSEReportJobEvent } from './types'

export interface SalesAggregate {
  id: string
  periodStart: string
  periodEnd: string
  totalAmount: number
  orderCount: number
  dataFreshnessTimestamp?: string
}

export interface ReportExportJob {
  jobId: string
  reportType: string
  status: string
  createdAt: string
  completedAt?: string
  downloadUrl?: string
}

export async function getSalesAggregates(params: { startDate?: string; endDate?: string } = {}) {
  const response = await apiClient.get<SalesAggregate[]>(
    '/admin/reports/aggregates/sales',
    Object.keys(params).length ? { params } : undefined
  )
  return response
}

export async function getInventoryAgingAggregates() {
  return apiClient.get<any[]>('/admin/reports/aggregates/inventory-aging')
}

export async function requestExport(
  reportType: string,
  payload: { format: 'csv' | 'pdf'; requestedBy?: string }
) {
  return apiClient.post(`/admin/reports/${reportType}/export`, payload)
}

export async function getExportJobs() {
  const response = await apiClient.get<{ jobs: ReportExportJob[] }>('/admin/reports/jobs')
  return response.jobs
}

/**
 * Connect to SSE stream for real-time report job updates
 */
export function connectReportsSSE(
  onEvent: (event: SSEReportJobEvent) => void,
  onError?: (error: Error) => void
): EventSource {
  const eventSource = new EventSource('/api/v1/admin/reports/jobs/events')

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data) as SSEReportJobEvent
      onEvent(data)
    } catch (error) {
      console.error('Failed to parse SSE event:', error)
      onError?.(error as Error)
    }
  }

  eventSource.onerror = (event) => {
    console.error('SSE connection error:', event)
    onError?.(new Error('SSE connection failed'))
  }

  return eventSource
}
