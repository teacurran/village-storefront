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
import type { Money } from '@/api/types'
import type {
  ConsignorProfile,
  ConsignmentItem,
  PayoutBatch,
  ConsignorNotification,
  ConsignorDashboardStats,
  ConsignorDashboardSnapshot,
  PayoutRequest,
  VendorDashboard,
} from './types'
import { MIN_PAYOUT_CENTS } from './constants'

/**
 * Get aggregated vendor dashboard (Task I4.T2)
 *
 * Fetches comprehensive dashboard data including balances, payouts, items, and Stripe status
 * in a single request to minimize API round-trips.
 */
export async function getVendorDashboard(): Promise<VendorDashboard> {
  const response = await apiClient.get<VendorDashboardResponse>('/vendor/portal/dashboard')
  return normalizeVendorDashboard(response)
}

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

type NumericValue = number | string | null | undefined

interface RawMoney {
  amount: NumericValue
  currency: string
}

interface VendorPayoutBatchResponse {
  id: string
  consignorId: string
  periodStart?: string
  periodEnd?: string
  totalAmount: RawMoney
  status?: string
  processedAt?: string
  paymentReference?: string
  createdAt: string
  updatedAt: string
}

interface VendorConsignmentItemResponse {
  id: string
  consignorId: string
  productName: string
  variantId?: string
  variantSku?: string
  commissionRate?: number
  status?: string
  soldAt?: string
  createdAt: string
  updatedAt: string
  costBasis?: NumericValue
}

interface VendorDashboardResponse {
  consignorId: string
  consignorName: string
  consignorStatus: string
  balances: {
    pendingBalance: NumericValue
    availableBalance: NumericValue
    totalEarnings: NumericValue
    currency: string
    nextSettlementDate: string
  }
  payoutSummary: {
    recentPayouts: VendorPayoutBatchResponse[]
    lastPayoutDate?: string
    lastPayoutAmount?: NumericValue
    nextPayoutSchedule: string
  }
  itemSummary: {
    activeCount: number
    soldThisMonth: number
    totalSold: number
    recentItems: VendorConsignmentItemResponse[]
  }
  stripeConnect: StripeConnectInfo
  notificationSummary: NotificationSummary
  lastUpdated: string
}

function normalizeVendorDashboard(response: VendorDashboardResponse): VendorDashboard {
  const currency = response.balances.currency || 'USD'
  const balances: BalanceInfo = {
    pendingBalance: decimalToMoney(response.balances.pendingBalance, currency),
    availableBalance: decimalToMoney(response.balances.availableBalance, currency),
    totalEarnings: decimalToMoney(response.balances.totalEarnings, currency),
    currency,
    nextSettlementDate: response.balances.nextSettlementDate,
  }

  const payoutSummary: PayoutSummary = {
    recentPayouts: response.payoutSummary.recentPayouts.map((batch) =>
      normalizePayoutBatch(batch, currency)
    ),
    lastPayoutDate: response.payoutSummary.lastPayoutDate,
    lastPayoutAmount: response.payoutSummary.lastPayoutAmount
      ? decimalToMoney(response.payoutSummary.lastPayoutAmount, currency)
      : undefined,
    nextPayoutSchedule: response.payoutSummary.nextPayoutSchedule,
  }

  const itemSummary: ItemSummary = {
    activeCount: response.itemSummary.activeCount,
    soldThisMonth: response.itemSummary.soldThisMonth,
    totalSold: response.itemSummary.totalSold,
    recentItems: response.itemSummary.recentItems.map((item) =>
      normalizeConsignmentItem(item, currency)
    ),
  }

  return {
    consignorId: response.consignorId,
    consignorName: response.consignorName,
    consignorStatus: response.consignorStatus,
    balances,
    payoutSummary,
    itemSummary,
    stripeConnect: response.stripeConnect,
    notificationSummary: response.notificationSummary,
    lastUpdated: response.lastUpdated,
  }
}

function decimalToMoney(value: NumericValue, currency: string): Money {
  const numeric =
    typeof value === 'string'
      ? parseFloat(value)
      : typeof value === 'number'
      ? value
      : 0
  const safeValue = Number.isFinite(numeric) ? numeric : 0

  return {
    amount: Math.round(safeValue * 100),
    currency: currency || 'USD',
  }
}

function normalizeMoney(money?: RawMoney | null, fallbackCurrency = 'USD'): Money {
  if (!money) {
    return {
      amount: 0,
      currency: fallbackCurrency,
    }
  }
  return decimalToMoney(money.amount, money.currency || fallbackCurrency)
}

function normalizePayoutBatch(batch: VendorPayoutBatchResponse, currency: string): PayoutBatch {
  return {
    id: batch.id,
    consignorId: batch.consignorId,
    tenantId: 'tenant',
    amount: normalizeMoney(batch.totalAmount, currency),
    status: mapPayoutStatus(batch.status),
    itemCount: 0,
    method: 'ACH',
    requestedAt: batch.createdAt,
    processedAt: batch.processedAt,
    completedAt: batch.processedAt,
    failureReason: undefined,
    notes: batch.paymentReference,
  }
}

function mapPayoutStatus(status?: string): PayoutBatch['status'] {
  switch ((status || '').toLowerCase()) {
    case 'processing':
      return 'PROCESSING'
    case 'completed':
      return 'COMPLETED'
    case 'failed':
      return 'FAILED'
    default:
      return 'PENDING'
  }
}

function normalizeConsignmentItem(
  item: VendorConsignmentItemResponse,
  currency: string
): ConsignmentItem {
  return {
    id: item.id,
    consignorId: item.consignorId,
    variantId: item.variantId || '',
    productName: item.productName,
    variantSku: item.variantSku || 'N/A',
    variantAttributes: {},
    consignmentPrice: decimalToMoney(item.costBasis, currency),
    commissionRate: item.commissionRate ?? 0,
    status: mapItemStatus(item.status),
    consignedAt: item.createdAt,
    soldAt: item.soldAt,
    withdrawnAt: undefined,
  }
}

function mapItemStatus(status?: string): ConsignmentItem['status'] {
  switch ((status || '').toLowerCase()) {
    case 'sold':
      return 'SOLD'
    case 'returned':
      return 'RETURNED'
    case 'withdrawn':
      return 'WITHDRAWN'
    default:
      return 'AVAILABLE'
  }
}
