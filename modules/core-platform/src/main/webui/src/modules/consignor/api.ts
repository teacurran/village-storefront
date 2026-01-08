/**
 * Consignor Portal API Client
 *
 * Wraps vendor portal REST endpoints with typed interfaces.
 * All methods use the shared apiClient with automatic tenant context and auth.
 *
 * References:
 * - VendorPortalResource.java endpoints
 * - Task I3.T7: Consignor Portal UI
 */

import { apiClient } from '@/api/client'
import type {
  ConsignorProfile,
  ConsignmentItem,
  PayoutBatch,
  ConsignorNotification,
  ConsignorDashboardStats,
  ConsignorDashboardSnapshot,
  PayoutRequest,
} from './types'
import { MIN_PAYOUT_CENTS } from './constants'

/**
 * Get current consignor's profile
 */
export async function getConsignorProfile(): Promise<ConsignorProfile> {
  return apiClient.get<ConsignorProfile>('/vendor/portal/profile')
}

/**
 * List consignor's items with pagination
 */
export async function getConsignorItems(page = 0, size = 20): Promise<ConsignmentItem[]> {
  return apiClient.get<ConsignmentItem[]>('/vendor/portal/items', {
    params: { page, size },
  })
}

/**
 * List consignor's payout batches with pagination
 */
export async function getConsignorPayouts(page = 0, size = 20): Promise<PayoutBatch[]> {
  return apiClient.get<PayoutBatch[]>('/vendor/portal/payouts', {
    params: { page, size },
  })
}

/**
 * Aggregate consignor dashboard data (profile, items, payouts, stats)
 */
export async function getConsignorDashboardSnapshot(): Promise<ConsignorDashboardSnapshot> {
  const [profile, items, payouts] = await Promise.all([
    getConsignorProfile(),
    getConsignorItems(0, 100),
    getConsignorPayouts(0, 10),
  ])

  const now = new Date()
  const firstOfMonth = new Date(now.getFullYear(), now.getMonth(), 1)
  const soldThisMonth = items.filter(
    (item) => item.soldAt && new Date(item.soldAt) >= firstOfMonth
  ).length

  const stats: ConsignorDashboardStats = {
    balanceOwed: profile.balanceOwed,
    pendingPayoutCount: payouts.filter((p) => p.status === 'PENDING').length,
    activeItemCount: profile.activeItemCount,
    soldThisMonth,
    lifetimeEarnings: profile.lifetimeEarnings,
    avgCommissionRate: profile.commissionRate,
    lastPayoutDate: payouts.find((p) => p.status === 'COMPLETED')?.completedAt,
    nextPayoutEligible: profile.balanceOwed.amount >= MIN_PAYOUT_CENTS,
  }

  return {
    profile,
    items,
    payouts,
    stats,
  }
}

/**
 * Backwards compatibility helper for callers that only need stats.
 */
export async function getConsignorDashboardStats(): Promise<ConsignorDashboardStats> {
  const snapshot = await getConsignorDashboardSnapshot()
  return snapshot.stats
}

/**
 * Get consignor notifications (mock until backend implements)
 */
export async function getConsignorNotifications(
  _page = 0,
  _size = 20
): Promise<ConsignorNotification[]> {
  // TODO: Replace with actual API endpoint when available
  return []
}

/**
 * Mark notification as read (mock until backend implements)
 */
export async function markNotificationRead(notificationId: string): Promise<void> {
  // TODO: Replace with actual API endpoint when available
  console.log('Mark notification read:', notificationId)
}

/**
 * Request payout (mock until backend implements)
 */
export async function requestPayout(request: PayoutRequest): Promise<PayoutBatch> {
  // TODO: Replace with actual API endpoint when available
  const profile = await getConsignorProfile()

  const mockBatch: PayoutBatch = {
    id: crypto.randomUUID(),
    consignorId: profile.id,
    tenantId: profile.tenantId,
    amount: request.amount,
    status: 'PENDING',
    itemCount: 0,
    method: request.method,
    requestedAt: new Date().toISOString(),
    notes: request.notes,
  }

  return mockBatch
}
