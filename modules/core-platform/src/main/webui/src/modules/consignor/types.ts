/**
 * Consignor Portal Type Definitions
 *
 * Defines interfaces for consignor-specific data structures used throughout
 * the vendor portal module.
 *
 * References:
 * - Task I3.T7: Consignor Portal UI Implementation
 * - Backend API: VendorPortalResource.java
 */

import type { Money } from '@/api/types'

export interface ConsignorProfile {
  id: string
  tenantId: string
  displayName: string
  email: string
  phone?: string
  businessName?: string
  taxId?: string
  commissionRate: number
  balanceOwed: Money
  lifetimeEarnings: Money
  activeItemCount: number
  soldItemCount: number
  status: 'ACTIVE' | 'SUSPENDED' | 'PENDING'
  createdAt: string
  updatedAt: string
}

export interface ConsignmentItem {
  id: string
  consignorId: string
  variantId: string
  productName: string
  variantSku: string
  variantAttributes?: Record<string, string>
  consignmentPrice: Money
  commissionRate: number
  status: 'AVAILABLE' | 'SOLD' | 'RETURNED' | 'WITHDRAWN'
  consignedAt: string
  soldAt?: string
  withdrawnAt?: string
}

export interface PayoutBatch {
  id: string
  consignorId: string
  tenantId: string
  amount: Money
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
  itemCount: number
  method: 'CHECK' | 'ACH' | 'PAYPAL' | 'STORE_CREDIT'
  requestedAt: string
  processedAt?: string
  completedAt?: string
  failureReason?: string
  notes?: string
}

export interface ConsignorNotification {
  id: string
  consignorId: string
  type: 'ITEM_SOLD' | 'PAYOUT_READY' | 'PAYOUT_COMPLETED' | 'ITEM_RETURNED' | 'ACCOUNT_UPDATE'
  title: string
  message: string
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  read: boolean
  actionUrl?: string
  createdAt: string
  readAt?: string
}

export interface ConsignorDashboardStats {
  balanceOwed: Money
  pendingPayoutCount: number
  activeItemCount: number
  soldThisMonth: number
  lifetimeEarnings: Money
  avgCommissionRate: number
  lastPayoutDate?: string
  nextPayoutEligible: boolean
}

export interface ConsignorDashboardSnapshot {
  profile: ConsignorProfile
  items: ConsignmentItem[]
  payouts: PayoutBatch[]
  stats: ConsignorDashboardStats
}

export interface PayoutRequest {
  amount: Money
  method: 'CHECK' | 'ACH' | 'PAYPAL' | 'STORE_CREDIT'
  notes?: string
}
