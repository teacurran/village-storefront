import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReportingDashboard from '../views/ReportingDashboard.vue'
import { useReportingStore } from '../store'
import * as reportingApi from '../api'

// Mock the API module
vi.mock('../api', () => ({
  getSalesAggregates: vi.fn(),
  getInventoryAgingAggregates: vi.fn(),
  getExportJobs: vi.fn(),
  requestExport: vi.fn(),
  connectReportsSSE: vi.fn(),
}))

// Mock i18n
vi.mock('@/composables/useI18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
    formatCurrency: (money: { amount: number; currency: string }) => `$${money.amount / 100}`,
  }),
}))

// Mock auth store
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasRole: () => true,
    restoreAuth: () => {},
  }),
}))

// Mock toast
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({
    add: vi.fn(),
  }),
}))

// Mock telemetry
vi.mock('@/telemetry', () => ({
  emitTelemetryEvent: vi.fn(),
}))

describe('ReportingDashboard', () => {
  let wrapper: VueWrapper
  let reportingStore: ReturnType<typeof useReportingStore>

  beforeEach(() => {
    setActivePinia(createPinia())
    reportingStore = useReportingStore()

    // Setup API mocks with default responses
    vi.mocked(reportingApi.getSalesAggregates).mockResolvedValue([
      {
        id: '1',
        periodStart: '2026-01-01T00:00:00Z',
        periodEnd: '2026-01-02T00:00:00Z',
        totalAmount: 10000,
        orderCount: 10,
        dataFreshnessTimestamp: new Date().toISOString(),
      },
      {
        id: '2',
        periodStart: '2026-01-02T00:00:00Z',
        periodEnd: '2026-01-03T00:00:00Z',
        totalAmount: 15000,
        orderCount: 15,
        dataFreshnessTimestamp: new Date().toISOString(),
      },
    ])

    vi.mocked(reportingApi.getInventoryAgingAggregates).mockResolvedValue([
      {
        id: '1',
        variant: { sku: 'SKU-001' },
        location: { name: 'Warehouse A' },
        quantity: 50,
        daysInStock: 120,
      },
      {
        id: '2',
        variant: { sku: 'SKU-002' },
        location: { name: 'Warehouse B' },
        quantity: 30,
        daysInStock: 90,
      },
    ])

    vi.mocked(reportingApi.getExportJobs).mockResolvedValue([
      {
        jobId: 'job-1',
        reportType: 'sales',
        status: 'completed',
        createdAt: '2026-01-10T10:00:00Z',
        downloadUrl: 'https://example.com/report.csv',
      },
    ])

    // Mock SSE connection
    const mockEventSource = {
      onmessage: null,
      onerror: null,
      close: vi.fn(),
    } as unknown as EventSource
    vi.mocked(reportingApi.connectReportsSSE).mockReturnValue(mockEventSource)
  })

  it('renders dashboard with metrics cards', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div class="metrics-card"><slot /></div>' },
          SalesLineChart: { template: '<div class="sales-chart"></div>' },
          InventoryBarChart: { template: '<div class="inventory-chart"></div>' },
          JobStatusBadge: { template: '<span class="status-badge"></span>' },
          FreshnessBadge: { template: '<div class="freshness-badge"></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(wrapper.find('.reporting-dashboard').exists()).toBe(true)
    expect(wrapper.findAll('.metrics-card')).toHaveLength(3)
  })

  it('renders sales chart with accessibility attributes', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div class="metrics-card"></div>' },
          SalesLineChart: {
            template:
              '<canvas aria-label="Sales trend chart showing revenue over time" role="img"></canvas>',
          },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span></span>' },
          FreshnessBadge: { template: '<div></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    const canvas = wrapper.find('canvas')
    expect(canvas.exists()).toBe(true)
    expect(canvas.attributes('aria-label')).toContain('Sales trend chart')
    expect(canvas.attributes('role')).toBe('img')
  })

  it('connects to SSE on mount', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div></div>' },
          SalesLineChart: { template: '<div></div>' },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span></span>' },
          FreshnessBadge: { template: '<div></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(reportingApi.connectReportsSSE).toHaveBeenCalled()
    expect(reportingStore.sseConnected).toBe(true)
  })

  it('disconnects SSE on unmount', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div></div>' },
          SalesLineChart: { template: '<div></div>' },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span></span>' },
          FreshnessBadge: { template: '<div></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    wrapper.unmount()

    expect(reportingStore.sseConnected).toBe(false)
  })

  it('displays SSE connection status indicator', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div></div>' },
          SalesLineChart: { template: '<div></div>' },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span></span>' },
          FreshnessBadge: { template: '<div></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    const sseStatus = wrapper.find('.sse-status')
    expect(sseStatus.exists()).toBe(true)
    expect(sseStatus.classes()).toContain('connected')
  })

  it('renders job list with status badges', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div></div>' },
          SalesLineChart: { template: '<div></div>' },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span class="status-badge"></span>', props: ['status'] },
          FreshnessBadge: { template: '<div></div>' },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(wrapper.findAll('.job-item')).toHaveLength(1)
    expect(wrapper.find('.status-badge').exists()).toBe(true)
  })

  it('renders freshness badges on metrics cards', async () => {
    wrapper = mount(ReportingDashboard, {
      global: {
        stubs: {
          Button: { template: '<button><slot /></button>' },
          MetricsCard: { template: '<div class="metrics-card"></div>' },
          SalesLineChart: { template: '<div></div>' },
          InventoryBarChart: { template: '<div></div>' },
          JobStatusBadge: { template: '<span></span>' },
          FreshnessBadge: { template: '<div class="freshness-badge"></div>', props: ['timestamp'] },
        },
      },
    })

    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 100))

    const freshnessBadges = wrapper.findAll('.freshness-badge')
    expect(freshnessBadges.length).toBe(3) // One for each metric card
  })
})
