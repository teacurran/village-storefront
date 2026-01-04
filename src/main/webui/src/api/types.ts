/**
 * Shared API types and interfaces
 *
 * Defines common data structures used across the admin SPA.
 * Generated types from OpenAPI spec will be in ./generated/
 */

export interface PaginationMetadata {
  page: number
  pageSize: number
  totalPages: number
  totalItems: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface PaginatedResponse<T> {
  data: T[]
  pagination: PaginationMetadata
}

export interface Money {
  amount: number
  currency: string
}

export interface Address {
  line1: string
  line2?: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface HealthResponse {
  status: 'UP' | 'DOWN'
  checks: HealthCheck[]
}

export interface HealthCheck {
  name: string
  status: 'UP' | 'DOWN'
  data?: Record<string, any>
}

export interface ProblemDetails {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  [key: string]: any
}

export interface TenantContext {
  id: string
  subdomain: string
  customDomain?: string
  name: string
  plan: string
  featureFlags: Record<string, boolean>
}

export interface UserProfile {
  id: string
  email: string
  firstName: string
  lastName: string
  roles: string[]
  tenantId: string
}

export interface ImpersonationContext {
  adminUserId: string
  adminEmail: string
  reason: string
  startedAt: string
}

export interface AuthTokens {
  accessToken: string
  refreshToken: string
  expiresIn: number
}
