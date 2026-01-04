import { apiClient } from '@/api/client'
import type { Money } from '@/api/types'

export interface SalesAggregate {
  id: string
  periodStart: string
  periodEnd: string
  totalAmount: number
  orderCount: number
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

export async function requestExport(reportType: string, payload: { format: 'csv' | 'pdf'; requestedBy?: string }) {
  return apiClient.post(`/admin/reports/${reportType}/export`, payload)
}

export async function getExportJobs() {
  const response = await apiClient.get<{ jobs: ReportExportJob[] }>('/admin/reports/jobs')
  return response.jobs
}
