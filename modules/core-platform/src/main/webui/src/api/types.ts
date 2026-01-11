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
  value?: number
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

export interface TenantSettings {
  locale?: string
  currency?: string
  plan?: string
  featureFlags?: Record<string, boolean>
  [key: string]: any
}

export interface TenantMetadata {
  id: string
  subdomain: string
  name: string
  status: 'active' | 'suspended' | 'deleted'
  customDomains?: string[]
  settings?: TenantSettings
}

export interface TenantContext extends TenantMetadata {
  customDomain?: string
  plan?: string
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

export type TokenScale = Record<string | number, string>

export interface ThemeTokenPayload {
  colors?: Record<string, string | TokenScale | undefined>
  typography?: {
    fontFamily?: Record<string, string> | string
    fontSize?: Record<string, string>
    fontWeight?: Record<string, number>
    lineHeight?: Record<string, string>
  }
  spacing?: {
    base?: string
    [key: string]: string | undefined
  }
  shadows?: Record<string, string>
  borderRadius?: string | Record<string, string>
}

export interface ThemeConfig {
  themeId: string
  tenantId: string
  name: string
  tokens: ThemeTokenPayload
  createdAt?: string
  updatedAt?: string
  version?: string
}

export interface DesignTokens {
  colors?: {
    primary?: TokenScale
    secondary?: TokenScale
    success?: TokenScale
    warning?: TokenScale
    error?: TokenScale
    neutral?: TokenScale
  }
  typography?: {
    fontFamily?: {
      sans?: string
      serif?: string
      mono?: string
    }
    fontSize?: Record<string, string>
    fontWeight?: Record<string, number>
    lineHeight?: Record<string, string>
  }
  spacing?: {
    scale?: Record<string, string>
  }
  shadows?: Record<string, string>
  borderRadius?: Record<string, string>
}
