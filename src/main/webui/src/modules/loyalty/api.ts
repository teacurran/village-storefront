import { apiClient } from '@/api/client'

export interface LoyaltyProgramResponse {
  id: string
  name: string
  description?: string
  enabled: boolean
  pointsPerDollar: number
  redemptionValuePerPoint: number
  minRedemptionPoints: number
  maxRedemptionPoints?: number
  pointsExpirationDays?: number
  tierConfig?: string
  metadata?: Record<string, any>
}

export interface LoyaltyMemberResponse {
  id: string
  userId: string
  pointsBalance: number
  lifetimePointsEarned: number
  lifetimePointsRedeemed: number
  currentTier: string
  status: string
  enrolledAt: string
}

export interface LoyaltyTransactionResponse {
  id: string
  pointsDelta: number
  balanceAfter: number
  transactionType: string
  reason?: string
  createdAt: string
}

export async function getProgram(): Promise<LoyaltyProgramResponse> {
  return apiClient.get('/admin/loyalty/program')
}

export async function getMember(userId: string): Promise<LoyaltyMemberResponse> {
  return apiClient.get(`/loyalty/member/${userId}`)
}

export async function getTransactions(userId: string): Promise<LoyaltyTransactionResponse[]> {
  return apiClient.get(`/loyalty/transactions/${userId}`)
}

export async function adjustPoints(userId: string, points: number, reason: string) {
  return apiClient.post(`/admin/loyalty/adjust/${userId}`, { points, reason })
}

export function connectLoyaltySSE(onEvent: () => void, onError?: (err: Error) => void) {
  const source = new EventSource('/api/v1/loyalty/events')
  source.onmessage = () => onEvent()
  source.onerror = () => onError?.(new Error('loyalty sse error'))
  return source
}
