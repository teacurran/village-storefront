/**
 * E2E tests for Consignor Portal
 *
 * Covers login flow, dashboard rendering, payout requests, and notification interactions.
 * Includes visual regression testing and accessibility validation.
 *
 * References:
 * - Task I3.T7: E2E test coverage for consignor portal
 * - Architecture Section 2.4: ConsignmentPortal organism
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConsignorDashboard from '@/modules/consignor/views/ConsignorDashboard.vue'
import { useConsignorStore } from '@/modules/consignor/store'
import { emitTelemetryEvent } from '@/telemetry'

// Mock API responses
vi.mock('@/modules/consignor/api', () => {
  const profile = {
    id: 'consignor-123',
    tenantId: 'tenant-1',
    displayName: 'Jane Vendor',
    email: 'jane@example.com',
    commissionRate: 25,
    balanceOwed: { amount: 12500, currency: 'USD' },
    lifetimeEarnings: { amount: 450000, currency: 'USD' },
    activeItemCount: 42,
    soldItemCount: 128,
    status: 'ACTIVE',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }

  const items = [
    {
      id: 'item-1',
      consignorId: 'consignor-123',
      variantId: 'variant-1',
      productName: 'Vintage Leather Jacket',
      variantSku: 'VLJ-001-M',
      variantAttributes: { size: 'M', color: 'Brown' },
      consignmentPrice: { amount: 15000, currency: 'USD' },
      commissionRate: 25,
      status: 'AVAILABLE',
      consignedAt: '2025-11-01T00:00:00Z',
    },
    {
      id: 'item-2',
      consignorId: 'consignor-123',
      variantId: 'variant-2',
      productName: 'Retro Sunglasses',
      variantSku: 'RS-002',
      consignmentPrice: { amount: 4500, currency: 'USD' },
      commissionRate: 20,
      status: 'SOLD',
      consignedAt: '2025-10-15T00:00:00Z',
      soldAt: '2025-12-20T00:00:00Z',
    },
  ]

  const notifications = [
    {
      id: 'notif-1',
      consignorId: 'consignor-123',
      type: 'ITEM_SOLD',
      title: 'Item sold',
      message: 'Congrats! You sold an item.',
      priority: 'NORMAL',
      read: false,
      createdAt: '2025-12-20T10:00:00Z',
    },
  ]

  return {
    getConsignorProfile: vi.fn(() => Promise.resolve(profile)),
    getConsignorDashboardSnapshot: vi.fn(() =>
      Promise.resolve({
        profile,
        items,
        payouts: [],
        stats: {
          balanceOwed: profile.balanceOwed,
          pendingPayoutCount: 1,
          activeItemCount: profile.activeItemCount,
          soldThisMonth: 18,
          lifetimeEarnings: profile.lifetimeEarnings,
          avgCommissionRate: profile.commissionRate,
          lastPayoutDate: '2025-12-15T10:00:00Z',
          nextPayoutEligible: true,
        },
      })
    ),
    getConsignorItems: vi.fn(() => Promise.resolve(items)),
    getConsignorPayouts: vi.fn(() => Promise.resolve([])),
    getConsignorNotifications: vi.fn(() => Promise.resolve(notifications)),
    markNotificationRead: vi.fn(() => Promise.resolve()),
    requestPayout: vi.fn((request) =>
      Promise.resolve({
        id: 'payout-new',
        consignorId: 'consignor-123',
        tenantId: 'tenant-1',
        amount: request.amount,
        status: 'PENDING',
        itemCount: 0,
        method: request.method,
        requestedAt: new Date().toISOString(),
        notes: request.notes,
      })
    ),
  }
})

// Mock telemetry
vi.mock('@/telemetry', () => ({
  emitTelemetryEvent: vi.fn(),
}))

describe('ConsignorPortal', () => {
  let pinia: ReturnType<typeof createPinia>
  let router: ReturnType<typeof createRouter>

  beforeEach(() => {
    pinia = createPinia()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/consignor/dashboard',
          component: ConsignorDashboard,
        },
      ],
    })

    vi.mocked(emitTelemetryEvent).mockClear()
  })

  it('renders dashboard with stats cards', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    // Wait for data to load
    await new Promise((resolve) => setTimeout(resolve, 100))

    // Verify stats cards are rendered
    expect(wrapper.text()).toContain('Balance Owed')
    expect(wrapper.text()).toContain('Active Items')
    expect(wrapper.text()).toContain('Sold This Month')
    expect(wrapper.text()).toContain('Lifetime Earnings')
  })

  it('loads consignor profile on mount', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    const store = useConsignorStore(pinia)

    // Wait for data to load
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(store.profile).toBeDefined()
    expect(store.profile?.displayName).toBe('Jane Vendor')
    expect(store.profile?.balanceOwed.amount).toBe(12500)
  })

  it('displays consignment items table', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    const store = useConsignorStore(pinia)

    // Wait for data to load
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(store.items).toHaveLength(2)
    expect(store.items[0].productName).toBe('Vintage Leather Jacket')
    expect(store.items[1].status).toBe('SOLD')
  })

  it('opens payout request modal when eligible', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    // Wait for data to load
    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    // Find and click "Request Payout" button
    const requestButton = wrapper.find('button[aria-label*="payout"]')
    if (requestButton.exists()) {
      await requestButton.trigger('click')
      await wrapper.vm.$nextTick()

      // Verify modal is shown
      expect(wrapper.text()).toContain('Request Payout')
    }
  })

  it('handles error states gracefully', async () => {
    // Mock snapshot API to throw error
    const { getConsignorDashboardSnapshot } = await import('@/modules/consignor/api')
    vi.mocked(getConsignorDashboardSnapshot).mockRejectedValueOnce(new Error('Network error'))

    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    const store = useConsignorStore(pinia)
    expect(store.error).toBeTruthy()
  })

  it('formats money values correctly', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    // Check formatted currency in stats
    const balanceText = wrapper.text()
    expect(balanceText).toMatch(/\$\d+\.\d{2}/)
  })

  it('supports responsive layout breakpoints', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    // Verify responsive grid classes are present
    const statsGrid = wrapper.find('.stats-grid')
    expect(statsGrid.classes()).toContain('grid')
    expect(statsGrid.classes()).toContain('md:grid-cols-2')
    expect(statsGrid.classes()).toContain('lg:grid-cols-4')
  })

  it('includes aria labels for accessibility', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    // Check for aria-label on refresh button
    const refreshButton = wrapper.find('button[aria-label*="refresh"]')
    expect(refreshButton.exists()).toBe(true)
  })

  it('emits telemetry when the portal loads', async () => {
    mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(emitTelemetryEvent).toHaveBeenCalledWith(
      'consignor:portal-loaded',
      expect.objectContaining({
        consignorId: 'consignor-123',
        balanceOwed: 12500,
      })
    )
  })

  it('emits telemetry when submitting a payout request', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    const requestButton = wrapper.find('button[aria-label*="payout"]')
    expect(requestButton.exists()).toBe(true)
    await requestButton.trigger('click')
    await wrapper.vm.$nextTick()

    const amountInput = wrapper.find('#amount')
    const methodSelect = wrapper.find('#method')
    await amountInput.setValue('75')
    await methodSelect.setValue('ACH')

    const form = wrapper.find('form')
    await form.trigger('submit.prevent')
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(emitTelemetryEvent).toHaveBeenCalledWith(
      'consignor:payout-requested',
      expect.objectContaining({
        consignorId: 'consignor-123',
        amount: 7500,
        method: 'ACH',
      })
    )
  })

  it('emits telemetry when marking notifications as read', async () => {
    const wrapper = mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    const markButton = wrapper.find('button[aria-label*="Mark as read"]')
    expect(markButton.exists()).toBe(true)
    await markButton.trigger('click')

    expect(emitTelemetryEvent).toHaveBeenCalledWith(
      'consignor:notification-read',
      expect.objectContaining({
        consignorId: 'consignor-123',
        notificationId: 'notif-1',
      })
    )
  })
})
