/**
 * Consignor Portal Store
 *
 * Manages state for the vendor portal including profile, items, payouts, and notifications.
 *
 * References:
 * - Task I3.T7: Consignor Portal UI
 * - Architecture Section 4.1: State Management
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  ConsignorProfile,
  ConsignmentItem,
  PayoutBatch,
  ConsignorNotification,
  ConsignorDashboardStats,
  VendorDashboard,
  StripeConnectInfo,
} from './types'
import * as consignorApi from './api'
import { emitTelemetryEvent } from '@/telemetry'
import { MIN_PAYOUT_CENTS } from './constants'

export const useConsignorStore = defineStore('consignor', () => {
  // State
  const profile = ref<ConsignorProfile | null>(null)
  const dashboardStats = ref<ConsignorDashboardStats | null>(null)
  const vendorDashboard = ref<VendorDashboard | null>(null)
  const stripeConnect = ref<StripeConnectInfo | null>(null)
  const items = ref<ConsignmentItem[]>([])
  const payouts = ref<PayoutBatch[]>([])
  const notifications = ref<ConsignorNotification[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Computed
  const unreadNotificationCount = computed(() => {
    return notifications.value.filter((n) => !n.read).length
  })

  const pendingPayouts = computed(() => {
    return payouts.value.filter((p) => p.status === 'PENDING' || p.status === 'PROCESSING')
  })

  const activeItems = computed(() => {
    return items.value.filter((item) => item.status === 'AVAILABLE')
  })

  const soldItems = computed(() => {
    return items.value.filter((item) => item.status === 'SOLD')
  })

  // Actions
  async function loadProfile(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      profile.value = await consignorApi.getConsignorProfile()
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load profile'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadDashboardStats(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const snapshot = await consignorApi.getConsignorDashboardSnapshot()
      profile.value = snapshot.profile
      items.value = snapshot.items
      payouts.value = snapshot.payouts
      dashboardStats.value = snapshot.stats
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load dashboard stats'
      throw err
    } finally {
      loading.value = false
    }
  }

  /**
   * Load enhanced vendor dashboard with Stripe Connect info (Task I4.T2)
   */
  async function loadVendorDashboard(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const dashboard = await consignorApi.getVendorDashboard()
      vendorDashboard.value = dashboard
      stripeConnect.value = dashboard.stripeConnect

      // Normalize profile for downstream consumers
      profile.value = {
        id: dashboard.consignorId,
        tenantId: profile.value?.tenantId || 'tenant',
        displayName: dashboard.consignorName,
        email: profile.value?.email || '',
        phone: profile.value?.phone,
        businessName: profile.value?.businessName,
        taxId: profile.value?.taxId,
        commissionRate: profile.value?.commissionRate ?? 0,
        balanceOwed: {
          amount: dashboard.balances.availableBalance.amount + dashboard.balances.pendingBalance.amount,
          currency: dashboard.balances.availableBalance.currency,
        },
        lifetimeEarnings: dashboard.balances.totalEarnings,
        activeItemCount: dashboard.itemSummary.activeCount,
        soldItemCount: dashboard.itemSummary.totalSold,
        status: (dashboard.consignorStatus?.toUpperCase() as ConsignorProfile['status']) || 'ACTIVE',
        createdAt: profile.value?.createdAt || dashboard.lastUpdated,
        updatedAt: dashboard.lastUpdated,
      }

      // Update legacy state for backward compatibility
      items.value = dashboard.itemSummary.recentItems
      payouts.value = dashboard.payoutSummary.recentPayouts

      // Build legacy dashboard stats from new structure
      dashboardStats.value = {
        balanceOwed: {
          amount: dashboard.balances.availableBalance.amount + dashboard.balances.pendingBalance.amount,
          currency: dashboard.balances.availableBalance.currency,
        },
        pendingPayoutCount: dashboard.payoutSummary.recentPayouts.filter(
          (p) => p.status === 'PENDING' || p.status === 'PROCESSING'
        ).length,
        activeItemCount: dashboard.itemSummary.activeCount,
        soldThisMonth: dashboard.itemSummary.soldThisMonth,
        lifetimeEarnings: dashboard.balances.totalEarnings,
        avgCommissionRate: profile.value?.commissionRate ?? 0,
        lastPayoutDate: dashboard.payoutSummary.lastPayoutDate,
        nextPayoutEligible: dashboard.balances.availableBalance.amount >= MIN_PAYOUT_CENTS,
      }

      emitTelemetryEvent('consignor:vendor-dashboard-loaded', {
        consignorId: dashboard.consignorId,
        availableBalanceCents: dashboard.balances.availableBalance.amount,
        pendingBalanceCents: dashboard.balances.pendingBalance.amount,
        currency: dashboard.balances.availableBalance.currency,
        stripeOnboardingRequired: dashboard.stripeConnect.requiresOnboarding,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load vendor dashboard'
      throw err
    } finally {
      loading.value = false
    }
  }

  /**
   * Initiate Stripe Express onboarding flow
   */
  async function startStripeOnboarding(): Promise<string | null> {
    try {
      if (stripeConnect.value?.onboardingUrl) {
        const consignorId = profile.value?.id || vendorDashboard.value?.consignorId
        if (consignorId) {
          emitTelemetryEvent('consignor:stripe-onboarding-started', {
            consignorId,
          })
        }
        return stripeConnect.value.onboardingUrl
      }
      return null
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to start Stripe onboarding'
      throw err
    }
  }

  async function loadItems(page = 0, size = 20): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const newItems = await consignorApi.getConsignorItems(page, size)
      if (page === 0) {
        items.value = newItems
      } else {
        items.value.push(...newItems)
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load items'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadPayouts(page = 0, size = 20): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const newPayouts = await consignorApi.getConsignorPayouts(page, size)
      if (page === 0) {
        payouts.value = newPayouts
      } else {
        payouts.value.push(...newPayouts)
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load payouts'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadNotifications(page = 0, size = 20): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const newNotifications = await consignorApi.getConsignorNotifications(page, size)
      if (page === 0) {
        notifications.value = newNotifications
      } else {
        notifications.value.push(...newNotifications)
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load notifications'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function markNotificationRead(notificationId: string): Promise<void> {
    try {
      await consignorApi.markNotificationRead(notificationId)

      const notification = notifications.value.find((n) => n.id === notificationId)
      if (notification) {
        notification.read = true
        notification.readAt = new Date().toISOString()

        if (profile.value) {
          emitTelemetryEvent('consignor:notification-read', {
            consignorId: profile.value.id,
            notificationId: notification.id,
            notificationType: notification.type,
          })
        }
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to mark notification as read'
      throw err
    }
  }

  async function requestPayout(
    amount: { amount: number; currency: string },
    method: 'CHECK' | 'ACH' | 'PAYPAL' | 'STORE_CREDIT',
    notes?: string
  ): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const newPayout = await consignorApi.requestPayout({
        amount,
        method,
        notes,
      })

      await loadVendorDashboard()

      const consignorId = profile.value?.id || vendorDashboard.value?.consignorId
      if (consignorId) {
        emitTelemetryEvent('consignor:payout-requested', {
          consignorId,
          amount: newPayout.amount.amount,
          method: newPayout.method,
        })
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to request payout'
      throw err
    } finally {
      loading.value = false
    }
  }

  function clearError(): void {
    error.value = null
  }

  return {
    // State
    profile,
    dashboardStats,
    vendorDashboard,
    stripeConnect,
    items,
    payouts,
    notifications,
    loading,
    error,

    // Computed
    unreadNotificationCount,
    pendingPayouts,
    activeItems,
    soldItems,

    // Actions
    loadProfile,
    loadDashboardStats,
    loadVendorDashboard,
    startStripeOnboarding,
    loadItems,
    loadPayouts,
    loadNotifications,
    markNotificationRead,
    requestPayout,
    clearError,
  }
})
