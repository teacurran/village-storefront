/**
 * Reporting Store
 *
 * Handles KPI metrics, aggregates, and export jobs for the admin dashboard.
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { emitTelemetryEvent } from '@/telemetry'
import * as reportingApi from './api'

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

  return {
    salesSeries,
    metrics,
    slowMovers,
    exportJobs,
    dateRange,
    loading,
    error,
    trend,
    loadDashboard,
    exportReport,
    refreshExportJobs,
    setDateRange,
  }
})
