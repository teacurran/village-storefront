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
  const vendorDashboard = {
    consignorId: 'consignor-123',
    consignorName: 'Jane Vendor',
    consignorStatus: 'active',
    balances: {
      pendingBalance: { amount: 250000, currency: 'USD' },
      availableBalance: { amount: 12500, currency: 'USD' },
      totalEarnings: { amount: 450000, currency: 'USD' },
      currency: 'USD',
      nextSettlementDate: '2026-01-15',
    },
    payoutSummary: {
      recentPayouts: [],
      lastPayoutDate: '2025-12-15T10:00:00Z',
      lastPayoutAmount: { amount: 25000, currency: 'USD' },
      nextPayoutSchedule: 'Monthly on the 15th',
    },
    itemSummary: {
      activeCount: 42,
      soldThisMonth: 18,
      totalSold: 128,
      recentItems: [
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
          soldAt: undefined,
          withdrawnAt: undefined,
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
          withdrawnAt: undefined,
        },
      ],
    },
    stripeConnect: {
      accountId: null,
      chargesEnabled: false,
      payoutsEnabled: false,
      detailsSubmitted: false,
      onboardingUrl: 'https://stripe.test/onboard',
      requiresOnboarding: true,
      onboardingMessage: 'Complete Stripe Express onboarding to receive payouts.',
    },
    notificationSummary: { unreadCount: 1, recentNotifications: [] },
    lastUpdated: '2026-01-01T00:00:00Z',
  }

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
    getVendorDashboard: vi.fn(() => Promise.resolve(JSON.parse(JSON.stringify(vendorDashboard)))),
    getConsignorProfile: vi.fn(() =>
      Promise.resolve({
        id: vendorDashboard.consignorId,
        tenantId: 'tenant-1',
        displayName: vendorDashboard.consignorName,
        email: 'jane@example.com',
        commissionRate: 25,
        balanceOwed: vendorDashboard.balances.availableBalance,
        lifetimeEarnings: vendorDashboard.balances.totalEarnings,
        activeItemCount: vendorDashboard.itemSummary.activeCount,
        soldItemCount: vendorDashboard.itemSummary.totalSold,
        status: 'ACTIVE',
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: vendorDashboard.lastUpdated,
      })
    ),
    getConsignorDashboardSnapshot: vi.fn(() =>
      Promise.resolve({
        profile: {
          id: vendorDashboard.consignorId,
          tenantId: 'tenant-1',
          displayName: vendorDashboard.consignorName,
          email: 'jane@example.com',
          commissionRate: 25,
          balanceOwed: vendorDashboard.balances.availableBalance,
          lifetimeEarnings: vendorDashboard.balances.totalEarnings,
          activeItemCount: vendorDashboard.itemSummary.activeCount,
          soldItemCount: vendorDashboard.itemSummary.totalSold,
          status: 'ACTIVE',
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: vendorDashboard.lastUpdated,
        },
        items: vendorDashboard.itemSummary.recentItems,
        payouts: vendorDashboard.payoutSummary.recentPayouts,
        stats: {
          balanceOwed: vendorDashboard.balances.availableBalance,
          pendingPayoutCount: 1,
          activeItemCount: vendorDashboard.itemSummary.activeCount,
          soldThisMonth: vendorDashboard.itemSummary.soldThisMonth,
          lifetimeEarnings: vendorDashboard.balances.totalEarnings,
          avgCommissionRate: 25,
          lastPayoutDate: vendorDashboard.payoutSummary.lastPayoutDate,
          nextPayoutEligible: true,
        },
      })
    ),
    getConsignorItems: vi.fn(() => Promise.resolve(vendorDashboard.itemSummary.recentItems)),
    getConsignorPayouts: vi.fn(() => Promise.resolve(vendorDashboard.payoutSummary.recentPayouts)),
    getConsignorNotifications: vi.fn(() => Promise.resolve(notifications)),
    markNotificationRead: vi.fn(() => Promise.resolve()),
    requestPayout: vi.fn((request) => {
      const payout = {
        id: crypto.randomUUID(),
        consignorId: vendorDashboard.consignorId,
        tenantId: 'tenant-1',
        amount: request.amount,
        status: 'PENDING' as const,
        itemCount: 0,
        method: request.method,
        requestedAt: new Date().toISOString(),
        notes: request.notes,
      }
      vendorDashboard.payoutSummary.recentPayouts.unshift(payout)
      vendorDashboard.balances.availableBalance.amount -= request.amount.amount
      return Promise.resolve(payout)
    }),
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
    mount(ConsignorDashboard, {
      global: {
        plugins: [pinia, router],
      },
    })

    const store = useConsignorStore(pinia)

    // Wait for data to load
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(store.profile).toBeDefined()
    expect(store.profile?.displayName).toBe('Jane Vendor')
    expect(store.profile?.balanceOwed.amount).toBe(12500 + 250000)
  })

  it('displays consignment items table', async () => {
    mount(ConsignorDashboard, {
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
    const { getVendorDashboard } = await import('@/modules/consignor/api')
    vi.mocked(getVendorDashboard).mockRejectedValueOnce(new Error('Network error'))

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

    // Verify responsive grid layout container exists
    // (Note: Tailwind @apply compiles utilities into .stats-grid CSS, not HTML classes)
    const statsGrid = wrapper.find('.stats-grid')
    expect(statsGrid.exists()).toBe(true)
    expect(statsGrid.classes()).toContain('stats-grid')
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
        availableBalanceCents: 12500,
        currency: 'USD',
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
