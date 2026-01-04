/**
 * Platform Console Module Types
 *
 * Type definitions for platform admin console including store directory, impersonation,
 * audit logs, health dashboards, and feature flag governance.
 *
 * References:
 * - Task I5.T2: Platform admin console
 * - Task I5.T7: Feature flag governance
 * - Architecture: 01_Blueprint_Foundation.md Section 4.0
 */

export interface StoreDirectoryEntry {
  id: string
  subdomain: string
  name: string
  status: 'active' | 'suspended' | 'trial' | 'deleted'
  userCount: number
  activeUserCount: number
  createdAt: string
  lastActivityAt: string
  plan: 'free' | 'basic' | 'pro' | 'enterprise'
  customDomainConfigured: boolean
}

export interface ImpersonationContext {
  sessionId: string
  platformAdminId: string
  platformAdminEmail: string
  targetTenantId: string
  targetTenantName: string
  targetUserId?: string
  targetUserEmail?: string
  reason: string
  ticketNumber?: string
  startedAt: string
}

export interface ImpersonationRequest {
  targetTenantId: string
  targetUserId?: string
  reason: string
  ticketNumber: string
}

export interface AuditLogEntry {
  id: string
  actorType: 'platform_admin' | 'system' | 'automation'
  actorId?: string
  actorEmail?: string
  action: string
  targetType?: string
  targetId?: string
  reason?: string
  ticketNumber?: string
  occurredAt: string
  ipAddress?: string
}

export interface HealthMetricsSummary {
  timestamp: string
  tenantCount: number
  activeTenantCount: number
  totalUsers: number
  activeSessions: number
  jobQueueDepth: number
  failedJobs24h: number
  avgResponseTimeMs: number
  p95ResponseTimeMs: number
  errorRatePercent: number
  diskUsagePercent: number
  dbConnectionCount: number
  status: 'healthy' | 'degraded' | 'critical'
}

export interface PaginationState {
  page: number
  size: number
  total: number
}

export interface StoreFilters {
  status?: string
  search?: string
}

export interface AuditLogFilters {
  actorId?: string
  action?: string
  targetType?: string
  startDate?: string
  endDate?: string
}

export interface FeatureFlagDto {
  id: string
  tenantId?: string // null for global flags
  flagKey: string
  enabled: boolean
  config: string
  owner: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  reviewCadenceDays?: number
  expiryDate?: string
  lastReviewedAt?: string
  description?: string
  rollbackInstructions?: string
  stale?: boolean
  staleReason?: string
  createdAt: string
  updatedAt: string
}

export interface UpdateFeatureFlagRequest {
  enabled?: boolean
  owner?: string
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  reviewCadenceDays?: number
  expiryDate?: string
  description?: string
  rollbackInstructions?: string
  markReviewed?: boolean
  reason?: string
}

export interface StaleFlagReport {
  expiredCount: number
  expiredFlags: FeatureFlagDto[]
  needsReviewCount: number
  needsReviewFlags: FeatureFlagDto[]
  generatedAt: string
}
