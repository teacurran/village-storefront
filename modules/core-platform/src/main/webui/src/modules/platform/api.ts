/**
 * Platform Console API Client
 *
 * API helpers for platform admin endpoints.
 *
 * References:
 * - Task I5.T2: Platform admin console
 * - Task I5.T7: Feature flag governance
 */

import type {
  StoreDirectoryEntry,
  ImpersonationContext,
  ImpersonationRequest,
  AuditLogEntry,
  HealthMetricsSummary,
  StoreFilters,
  AuditLogFilters,
  FeatureFlagDto,
  UpdateFeatureFlagRequest,
  StaleFlagReport,
} from './types'

const BASE_PATH = '/api/v1/platform'

interface PaginatedResponse<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
}

interface StoreDirectoryResponse {
  stores: StoreDirectoryEntry[]
  page: number
  size: number
  totalCount: number
}

interface AuditLogResponse {
  entries: AuditLogEntry[]
  page: number
  size: number
  totalCount: number
}

/**
 * Get store directory with pagination and filters.
 */
export async function getStoreDirectory(
  filters: StoreFilters = {},
  page = 0,
  size = 20,
): Promise<StoreDirectoryResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.status) params.set('status', filters.status)
  if (filters.search) params.set('search', filters.search)

  const response = await fetch(`${BASE_PATH}/stores?${params}`)
  if (!response.ok) throw new Error(`Failed to fetch store directory: ${response.statusText}`)

  return response.json()
}

/**
 * Get detailed store information by ID.
 */
export async function getStoreDetails(storeId: string): Promise<StoreDirectoryEntry> {
  const response = await fetch(`${BASE_PATH}/stores/${storeId}`)
  if (!response.ok) throw new Error(`Failed to fetch store details: ${response.statusText}`)

  return response.json()
}

/**
 * Suspend a store (platform admin action).
 */
export async function suspendStore(storeId: string, reason: string): Promise<void> {
  const response = await fetch(`${BASE_PATH}/stores/${storeId}/suspend`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  })

  if (!response.ok) throw new Error(`Failed to suspend store: ${response.statusText}`)
}

/**
 * Reactivate a suspended store.
 */
export async function reactivateStore(storeId: string): Promise<void> {
  const response = await fetch(`${BASE_PATH}/stores/${storeId}/reactivate`, {
    method: 'POST',
  })

  if (!response.ok) throw new Error(`Failed to reactivate store: ${response.statusText}`)
}

/**
 * Start impersonation session.
 */
export async function startImpersonation(
  request: ImpersonationRequest,
): Promise<ImpersonationContext> {
  const response = await fetch(`${BASE_PATH}/impersonate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const error = await response.json()
    throw new Error(error.error || 'Failed to start impersonation')
  }

  return response.json()
}

/**
 * End current impersonation session.
 */
export async function endImpersonation(): Promise<void> {
  const response = await fetch(`${BASE_PATH}/impersonate`, {
    method: 'DELETE',
  })

  if (!response.ok) throw new Error(`Failed to end impersonation: ${response.statusText}`)
}

/**
 * Get current impersonation context.
 */
export async function getCurrentImpersonation(): Promise<ImpersonationContext | null> {
  const response = await fetch(`${BASE_PATH}/impersonate/current`)

  if (response.status === 404) {
    return null // No active session
  }

  if (!response.ok) throw new Error(`Failed to fetch impersonation context: ${response.statusText}`)

  return response.json()
}

/**
 * Query audit logs with filters and pagination.
 */
export async function getAuditLogs(
  filters: AuditLogFilters = {},
  page = 0,
  size = 50,
): Promise<AuditLogResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.actorId) params.set('actorId', filters.actorId)
  if (filters.action) params.set('action', filters.action)
  if (filters.targetType) params.set('targetType', filters.targetType)
  if (filters.startDate) params.set('startDate', filters.startDate)
  if (filters.endDate) params.set('endDate', filters.endDate)

  const response = await fetch(`${BASE_PATH}/audit?${params}`)
  if (!response.ok) throw new Error(`Failed to fetch audit logs: ${response.statusText}`)

  return response.json()
}

/**
 * Get audit log statistics.
 */
export async function getAuditStats(days = 7): Promise<any> {
  const response = await fetch(`${BASE_PATH}/audit/stats?days=${days}`)
  if (!response.ok) throw new Error(`Failed to fetch audit stats: ${response.statusText}`)

  return response.json()
}

/**
 * Get current system health metrics.
 */
export async function getSystemHealth(): Promise<HealthMetricsSummary> {
  const response = await fetch(`${BASE_PATH}/health`)
  if (!response.ok) throw new Error(`Failed to fetch system health: ${response.statusText}`)

  return response.json()
}

/**
 * Get all feature flags with optional filters.
 */
export async function getFeatureFlags(staleOnly = false): Promise<FeatureFlagDto[]> {
  const params = staleOnly ? '?stale_only=true' : ''
  const response = await fetch(`${BASE_PATH}/feature-flags${params}`)
  if (!response.ok) throw new Error(`Failed to fetch feature flags: ${response.statusText}`)

  return response.json()
}

/**
 * Get single feature flag by ID.
 */
export async function getFeatureFlag(flagId: string): Promise<FeatureFlagDto> {
  const response = await fetch(`${BASE_PATH}/feature-flags/${flagId}`)
  if (!response.ok) throw new Error(`Failed to fetch feature flag: ${response.statusText}`)

  return response.json()
}

/**
 * Get stale flag report.
 */
export async function getStaleFlagReport(): Promise<StaleFlagReport> {
  const response = await fetch(`${BASE_PATH}/feature-flags/stale-report`)
  if (!response.ok) throw new Error(`Failed to fetch stale flag report: ${response.statusText}`)

  return response.json()
}

/**
 * Update feature flag.
 */
export async function updateFeatureFlag(
  flagId: string,
  request: UpdateFeatureFlagRequest,
): Promise<FeatureFlagDto> {
  const response = await fetch(`${BASE_PATH}/feature-flags/${flagId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const error = await response.json()
    throw new Error(error.error || 'Failed to update feature flag')
  }

  return response.json()
}

/**
 * Get feature flag change history.
 */
export async function getFeatureFlagHistory(flagId: string): Promise<any[]> {
  const response = await fetch(`${BASE_PATH}/feature-flags/${flagId}/history`)
  if (!response.ok) throw new Error(`Failed to fetch flag history: ${response.statusText}`)

  return response.json()
}
