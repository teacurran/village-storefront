import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useReportingStore } from '../store'
import * as reportingApi from '../api'
import type { SSEReportJobEvent } from '../types'

// Mock the API module
vi.mock('../api', () => ({
  getSalesAggregates: vi.fn(),
  getInventoryAgingAggregates: vi.fn(),
  getExportJobs: vi.fn(),
  requestExport: vi.fn(),
  connectReportsSSE: vi.fn(),
}))

// Mock telemetry
vi.mock('@/telemetry', () => ({
  emitTelemetryEvent: vi.fn(),
}))

describe('Reporting Store', () => {
  let store: ReturnType<typeof useReportingStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    store = useReportingStore()

    // Reset mocks
    vi.clearAllMocks()

    // Setup default mock responses
    vi.mocked(reportingApi.getSalesAggregates).mockResolvedValue([
      {
        id: '1',
        periodStart: '2026-01-01T00:00:00Z',
        periodEnd: '2026-01-02T00:00:00Z',
        totalAmount: 10000,
        orderCount: 10,
        dataFreshnessTimestamp: '2026-01-10T10:00:00Z',
      },
    ])

    vi.mocked(reportingApi.getInventoryAgingAggregates).mockResolvedValue([
      { id: '1', variant: { sku: 'SKU-001' }, location: { name: 'Warehouse' }, quantity: 50, daysInStock: 120 },
    ])

    vi.mocked(reportingApi.getExportJobs).mockResolvedValue([
      {
        jobId: 'job-1',
        reportType: 'sales',
        status: 'pending',
        createdAt: '2026-01-10T10:00:00Z',
      },
    ])
  })

  describe('loadDashboard', () => {
    it('loads sales aggregates and computes metrics', async () => {
      await store.loadDashboard()

      expect(reportingApi.getSalesAggregates).toHaveBeenCalled()
      expect(store.salesSeries).toHaveLength(1)
      expect(store.metrics.totalRevenue).toBe(10000)
      expect(store.metrics.orderCount).toBe(10)
      expect(store.metrics.avgOrderValue).toBe(1000)
    })

    it('updates data freshness timestamp', async () => {
      await store.loadDashboard()

      expect(store.dataFreshnessTimestamp).toBe('2026-01-10T10:00:00Z')
    })

    it('loads inventory aging aggregates', async () => {
      await store.loadDashboard()

      expect(reportingApi.getInventoryAgingAggregates).toHaveBeenCalled()
      expect(store.slowMovers).toHaveLength(1)
      expect(store.slowMovers[0].daysInStock).toBe(120)
    })

    it('loads export jobs', async () => {
      await store.loadDashboard()

      expect(reportingApi.getExportJobs).toHaveBeenCalled()
      expect(store.exportJobs).toHaveLength(1)
      expect(store.exportJobs[0].jobId).toBe('job-1')
    })

    it('sets loading state correctly', async () => {
      const loadPromise = store.loadDashboard()
      expect(store.loading).toBe(true)
      await loadPromise
      expect(store.loading).toBe(false)
    })
  })

  describe('SSE Connection', () => {
    it('creates SSE connection when connectSSE is called', () => {
      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      vi.mocked(reportingApi.connectReportsSSE).mockReturnValue(mockEventSource)

      store.connectSSE()

      expect(reportingApi.connectReportsSSE).toHaveBeenCalled()
      expect(store.sseConnected).toBe(true)
    })

    it('does not create duplicate connections', () => {
      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      vi.mocked(reportingApi.connectReportsSSE).mockReturnValue(mockEventSource)

      store.connectSSE()
      store.connectSSE()

      expect(reportingApi.connectReportsSSE).toHaveBeenCalledTimes(1)
    })

    it('closes connection when disconnectSSE is called', () => {
      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      vi.mocked(reportingApi.connectReportsSSE).mockReturnValue(mockEventSource)

      store.connectSSE()
      store.disconnectSSE()

      expect(mockEventSource.close).toHaveBeenCalled()
      expect(store.sseConnected).toBe(false)
    })
  })

  describe('SSE Event Handling', () => {
    it('updates job status when SSE event is received', async () => {
      await store.loadDashboard()

      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      let eventHandler: (event: SSEReportJobEvent) => void = () => {}

      vi.mocked(reportingApi.connectReportsSSE).mockImplementation((onEvent) => {
        eventHandler = onEvent
        return mockEventSource
      })

      store.connectSSE()

      // Simulate SSE event
      const event: SSEReportJobEvent = {
        jobId: 'job-1',
        reportType: 'sales',
        status: 'completed',
        completedAt: '2026-01-10T10:15:00Z',
        downloadUrl: 'https://example.com/report.csv',
      }

      eventHandler(event)

      expect(store.exportJobs[0].status).toBe('completed')
      expect(store.exportJobs[0].downloadUrl).toBe('https://example.com/report.csv')
    })

    it('refreshes jobs when SSE event for unknown job is received', async () => {
      await store.loadDashboard()

      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      let eventHandler: (event: SSEReportJobEvent) => void = () => {}

      vi.mocked(reportingApi.connectReportsSSE).mockImplementation((onEvent) => {
        eventHandler = onEvent
        return mockEventSource
      })

      store.connectSSE()

      // Mock refreshExportJobs
      vi.mocked(reportingApi.getExportJobs).mockResolvedValue([
        {
          jobId: 'job-2',
          reportType: 'consignment',
          status: 'completed',
          createdAt: '2026-01-10T11:00:00Z',
        },
      ])

      // Simulate SSE event for unknown job
      const event: SSEReportJobEvent = {
        jobId: 'job-2',
        reportType: 'consignment',
        status: 'completed',
      }

      eventHandler(event)

      await new Promise((resolve) => setTimeout(resolve, 10))

      expect(reportingApi.getExportJobs).toHaveBeenCalledTimes(2) // Once on load, once on refresh
    })

    it('attempts reconnection after SSE error', async () => {
      vi.useFakeTimers()

      const mockEventSource = {
        onmessage: null,
        onerror: null,
        close: vi.fn(),
      } as unknown as EventSource

      let errorHandler: (error: Error) => void = () => {}

      vi.mocked(reportingApi.connectReportsSSE).mockImplementation((onEvent, onError) => {
        errorHandler = onError || (() => {})
        return mockEventSource
      })

      store.connectSSE()

      // Simulate error
      errorHandler(new Error('Connection lost'))

      expect(store.sseConnected).toBe(false)

      // Fast-forward time to trigger reconnection
      await vi.advanceTimersByTimeAsync(5000)

      expect(reportingApi.connectReportsSSE).toHaveBeenCalledTimes(2)

      vi.useRealTimers()
    })
  })

  describe('exportReport', () => {
    it('requests export and refreshes job list', async () => {
      await store.exportReport('csv')

      expect(reportingApi.requestExport).toHaveBeenCalledWith('sales', { format: 'csv' })
      expect(reportingApi.getExportJobs).toHaveBeenCalled()
    })
  })

  describe('trend calculation', () => {
    it('calculates positive trend correctly', async () => {
      vi.mocked(reportingApi.getSalesAggregates).mockResolvedValue([
        {
          id: '1',
          periodStart: '2026-01-01T00:00:00Z',
          periodEnd: '2026-01-02T00:00:00Z',
          totalAmount: 10000,
          orderCount: 10,
        },
        {
          id: '2',
          periodStart: '2026-01-02T00:00:00Z',
          periodEnd: '2026-01-03T00:00:00Z',
          totalAmount: 15000,
          orderCount: 15,
        },
      ])

      await store.loadDashboard()

      expect(store.trend).toBe(50) // 50% increase
    })

    it('returns 0 when less than 2 data points', async () => {
      vi.mocked(reportingApi.getSalesAggregates).mockResolvedValue([
        {
          id: '1',
          periodStart: '2026-01-01T00:00:00Z',
          periodEnd: '2026-01-02T00:00:00Z',
          totalAmount: 10000,
          orderCount: 10,
        },
      ])

      await store.loadDashboard()

      expect(store.trend).toBe(0)
    })
  })
})
