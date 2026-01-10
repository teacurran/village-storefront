/**
 * Reporting Store
 *
 * Handles KPI metrics, aggregates, and export jobs for the admin dashboard.
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { emitTelemetryEvent } from '@/telemetry'
import * as reportingApi from './api'
import type { SSEReportJobEvent } from './types'

export interface ReportingMetrics {
  totalRevenue: number
  orderCount: number
  avgOrderValue: number
}

export const useReportingStore = defineStore('reporting', () => {
  const salesSeries = ref<reportingApi.SalesAggregate[]>([])
  const metrics = ref<ReportingMetrics>({ totalRevenue: 0, orderCount: 0, avgOrderValue: 0 })
  const slowMovers = ref<any[]>([])
  const exportJobs = ref<reportingApi.ReportExportJob[]>([])
  const dateRange = ref<{ start?: string; end?: string }>({})
  const loading = ref(false)
  const error = ref<string | null>(null)
  const sseConnected = ref(false)
  const sseEventSource = ref<EventSource | null>(null)
  const dataFreshnessTimestamp = ref<string>(new Date().toISOString())

  const trend = computed(() => {
    if (salesSeries.value.length < 2) return 0
    const last = salesSeries.value[salesSeries.value.length - 1].totalAmount
    const prev = salesSeries.value[salesSeries.value.length - 2].totalAmount
    if (prev === 0) return 0
    return ((last - prev) / prev) * 100
  })

  async function loadDashboard(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const [sales, aging, jobs] = await Promise.all([
        reportingApi.getSalesAggregates(dateRange.value),
        reportingApi.getInventoryAgingAggregates(),
        reportingApi.getExportJobs(),
      ])

      salesSeries.value = sales
      slowMovers.value = aging.slice(0, 10)
      exportJobs.value = jobs

      const totalRevenue = sales.reduce((sum, agg) => sum + agg.totalAmount, 0)
      const totalOrders = sales.reduce((sum, agg) => sum + agg.orderCount, 0)
      metrics.value = {
        totalRevenue,
        orderCount: totalOrders,
        avgOrderValue: totalOrders ? totalRevenue / totalOrders : 0,
      }

      // Update freshness timestamp if available
      if (sales.length > 0 && sales[0].dataFreshnessTimestamp) {
        dataFreshnessTimestamp.value = sales[0].dataFreshnessTimestamp
      }

      emitTelemetryEvent('view_reports', {
        range: dateRange.value,
        totalRevenue,
        orderCount: totalOrders,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load reporting data'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function exportReport(format: 'csv' | 'pdf') {
    await reportingApi.requestExport('sales', { format })
    emitTelemetryEvent('action_export_report', { format })
    await refreshExportJobs()
  }

  async function refreshExportJobs() {
    exportJobs.value = await reportingApi.getExportJobs()
  }

  function setDateRange(range: { start?: string; end?: string }) {
    dateRange.value = range
  }

  function connectSSE(): void {
    if (sseEventSource.value) {
      return // Already connected
    }

    try {
      sseEventSource.value = reportingApi.connectReportsSSE(handleSSEEvent, handleSSEError)
      sseConnected.value = true

      emitTelemetryEvent('sse_reports_connected', {
        timestamp: new Date().toISOString(),
      })
    } catch (err) {
      console.error('Failed to connect SSE:', err)
      sseConnected.value = false
    }
  }

  function disconnectSSE(): void {
    if (sseEventSource.value) {
      sseEventSource.value.close()
      sseEventSource.value = null
      sseConnected.value = false

      emitTelemetryEvent('sse_reports_disconnected', {
        timestamp: new Date().toISOString(),
      })
    }
  }

  function handleSSEEvent(event: SSEReportJobEvent): void {
    // Update job in list if present
    const index = exportJobs.value.findIndex((j) => j.jobId === event.jobId)
    if (index !== -1) {
      exportJobs.value[index] = {
        ...exportJobs.value[index],
        status: event.status,
        completedAt: event.completedAt,
        downloadUrl: event.downloadUrl,
      }
    } else {
      // Job not in list, refresh to get it
      refreshExportJobs().catch(console.error)
    }

    emitTelemetryEvent('sse_report_job_event', {
      jobId: event.jobId,
      reportType: event.reportType,
      status: event.status,
    })
  }

  function handleSSEError(error: Error): void {
    console.error('SSE error:', error)
    sseConnected.value = false

    // Close existing connection
    if (sseEventSource.value) {
      sseEventSource.value.close()
      sseEventSource.value = null
    }

    emitTelemetryEvent('sse_reports_error', {
      error: error.message,
      timestamp: new Date().toISOString(),
    })

    // Attempt reconnection after delay
    setTimeout(() => {
      if (!sseConnected.value) {
        connectSSE()
      }
    }, 5000)
  }

  return {
    salesSeries,
    metrics,
    slowMovers,
    exportJobs,
    dateRange,
    loading,
    error,
    trend,
    sseConnected,
    dataFreshnessTimestamp,
    loadDashboard,
    exportReport,
    refreshExportJobs,
    setDateRange,
    connectSSE,
    disconnectSSE,
  }
})
