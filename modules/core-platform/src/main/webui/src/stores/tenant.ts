/**
 * Tenant Store
 *
 * Manages multi-tenant context, feature flags, and tenant-specific configuration.
 * Automatically resolves tenant from host header or explicit selection.
 *
 * References:
 * - Architecture Section 4.1: State Management
 * - ADR-001: Tenant Resolution & Context
 * - UI/UX Section 1.9: Design Token Delivery
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiClient } from '@/api/client'
import type {
  TenantContext,
  TenantMetadata,
  DesignTokens,
  ThemeConfig,
  ThemeTokenPayload,
  TokenScale,
} from '@/api/types'

export const useTenantStore = defineStore('tenant', () => {
  // State
  const currentTenant = ref<TenantContext | null>(null)
  const availableTenants = ref<TenantContext[]>([])
  const designTokens = ref<DesignTokens>(getDefaultDesignTokens())
  const tokenVersion = ref<string | null>(null)
  const isLoadingTenant = ref(false)
  const isLoadingTokens = ref(false)

  // Computed
  const tenantId = computed(() => currentTenant.value?.id || null)
  const tenantName = computed(() => currentTenant.value?.name || 'Unknown Store')
  const tenantPlan = computed(() => currentTenant.value?.plan || 'FREE')
  const featureFlags = computed(() => currentTenant.value?.featureFlags || {})

  const isFeatureEnabled = (flag: string): boolean => {
    return featureFlags.value[flag] === true
  }

  // Actions
  async function loadTenant(): Promise<void> {
    if (isLoadingTenant.value) {
      return
    }

    isLoadingTenant.value = true
    try {
      const metadata = await apiClient.get<TenantMetadata>('/tenants/resolve')
      currentTenant.value = mapTenantMetadata(metadata)
    } catch (error) {
      console.error('Failed to load tenant metadata, falling back to mock tenant:', error)
      currentTenant.value = getMockTenant()
    } finally {
      isLoadingTenant.value = false
      if (currentTenant.value) {
        availableTenants.value = [currentTenant.value]
      }
    }
  }

  async function loadDesignTokens(): Promise<void> {
    if (!currentTenant.value || isLoadingTokens.value) return

    isLoadingTokens.value = true
    try {
      const response = await apiClient.get<ThemeConfig>('/admin/theme')
      designTokens.value = normalizeThemeTokens(response.tokens)
      tokenVersion.value = response.version || response.updatedAt || response.themeId || null
    } catch (error) {
      console.warn('Failed to fetch theme tokens, using default palette:', error)
      designTokens.value = getDefaultDesignTokens()
      tokenVersion.value = tokenVersion.value ?? 'fallback'
    } finally {
      isLoadingTokens.value = false
      applyDesignTokens()
    }
  }

  function applyDesignTokens(): void {
    if (typeof document === 'undefined') {
      return
    }

    const root = document.documentElement

    // Apply color tokens
    if (designTokens.value.colors) {
      applyColorScale(root, designTokens.value.colors.primary, '--color-primary')
      applyColorScale(root, designTokens.value.colors.secondary, '--color-secondary')
      applyColorScale(root, designTokens.value.colors.success, '--color-success')
      applyColorScale(root, designTokens.value.colors.warning, '--color-warning')
      applyColorScale(root, designTokens.value.colors.error, '--color-error')
      applyColorScale(root, designTokens.value.colors.neutral, '--color-neutral')
    }

    // Apply typography tokens
    if (designTokens.value.typography?.fontFamily) {
      Object.entries(designTokens.value.typography.fontFamily).forEach(([key, value]) => {
        if (value) {
          root.style.setProperty(`--font-${key}`, value)
        }
      })
    }

    if (designTokens.value.typography?.fontSize) {
      Object.entries(designTokens.value.typography.fontSize).forEach(([key, value]) => {
        root.style.setProperty(`--text-${key}`, value)
      })
    }

    if (designTokens.value.typography?.lineHeight) {
      Object.entries(designTokens.value.typography.lineHeight).forEach(([key, value]) => {
        root.style.setProperty(`--leading-${key}`, value)
      })
    }

    if (designTokens.value.typography?.fontWeight) {
      Object.entries(designTokens.value.typography.fontWeight).forEach(([key, value]) => {
        root.style.setProperty(`--font-weight-${key}`, String(value))
      })
    }

    if (designTokens.value.spacing?.scale) {
      Object.entries(designTokens.value.spacing.scale).forEach(([key, value]) => {
        root.style.setProperty(`--spacing-${key}`, value)
      })
    }

    if (designTokens.value.shadows) {
      Object.entries(designTokens.value.shadows).forEach(([key, value]) => {
        root.style.setProperty(`--shadow-${key}`, value)
      })
    }

    if (designTokens.value.borderRadius) {
      Object.entries(designTokens.value.borderRadius).forEach(([key, value]) => {
        root.style.setProperty(`--radius-${key}`, value)
      })
    }
  }

  async function switchTenant(tenantId: string): Promise<void> {
    const tenant = availableTenants.value.find((t) => t.id === tenantId)
    if (!tenant) {
      throw new Error(`Tenant ${tenantId} not found`)
    }

    currentTenant.value = tenant
    await loadDesignTokens()
  }

  async function loadAvailableTenants(): Promise<void> {
    // For platform admins who can switch between tenants
    availableTenants.value = [currentTenant.value].filter(Boolean) as TenantContext[]
  }

  return {
    // State
    currentTenant,
    availableTenants,
    designTokens,
    tokenVersion,
    isLoadingTenant,
    isLoadingTokens,

    // Computed
    tenantId,
    tenantName,
    tenantPlan,
    featureFlags,

    // Actions
    loadTenant,
    loadDesignTokens,
    switchTenant,
    loadAvailableTenants,
    isFeatureEnabled,
    applyDesignTokens,
  }
})

function mapTenantMetadata(metadata: TenantMetadata): TenantContext {
  return {
    ...metadata,
    customDomain: metadata.customDomains?.[0],
    plan: metadata.settings?.plan ?? 'PRO',
    featureFlags: metadata.settings?.featureFlags ?? getDefaultFeatureFlags(),
  }
}

function getMockTenant(): TenantContext {
  return {
    id: 'tenant-demo',
    subdomain: 'demo-store',
    customDomain: undefined,
    name: 'Demo Store',
    plan: 'PRO',
    status: 'active',
    featureFlags: getDefaultFeatureFlags(),
    settings: {
      locale: 'en-US',
      currency: 'USD',
      featureFlags: getDefaultFeatureFlags(),
    },
  }
}

function getDefaultFeatureFlags(): Record<string, boolean> {
  return {
    loyalty: true,
    pos: true,
    subscriptions: false,
    consignment: true,
    multiLocation: false,
    theme: true,
  }
}

type ThemeColorMap = {
  [key: string]: string | TokenScale | undefined
  primary?: string | TokenScale
  secondary?: string | TokenScale
  success?: string | TokenScale
  warning?: string | TokenScale
  error?: string | TokenScale
  neutral?: string | TokenScale
}

function normalizeThemeTokens(payload: ThemeTokenPayload | undefined): DesignTokens {
  if (!payload) {
    return getDefaultDesignTokens()
  }

  const colors = (payload.colors ?? {}) as ThemeColorMap

  const normalized: DesignTokens = {
    colors: {
      primary: ensureScale(colors.primary),
      secondary: ensureScale(colors.secondary),
      success: ensureScale(colors.success),
      warning: ensureScale(colors.warning),
      error: ensureScale(colors.error),
      neutral: ensureScale(colors.neutral),
    },
    typography: normalizeTypography(payload.typography),
    spacing: normalizeSpacing(payload.spacing),
    shadows: payload.shadows ? { ...payload.shadows } : undefined,
    borderRadius: normalizeBorderRadius(payload.borderRadius),
  }

  return normalized
}

function ensureScale(input: string | TokenScale | undefined): TokenScale | undefined {
  if (!input) {
    return undefined
  }

  if (typeof input === 'string') {
    return { 500: input }
  }

  const output: TokenScale = {}
  Object.entries(input).forEach(([key, value]) => {
    if (value) {
      output[key] = value
    }
  })
  return output
}

function normalizeTypography(typography?: ThemeTokenPayload['typography']): DesignTokens['typography'] {
  if (!typography) {
    return undefined
  }

  const fontFamily =
    typeof typography.fontFamily === 'string'
      ? { sans: typography.fontFamily }
      : typography.fontFamily

  return {
    fontFamily,
    fontSize: typography.fontSize,
    fontWeight: typography.fontWeight,
    lineHeight: typography.lineHeight,
  }
}

function normalizeSpacing(
  spacing?: ThemeTokenPayload['spacing']
): DesignTokens['spacing'] | undefined {
  if (!spacing) {
    return undefined
  }
  const scale: Record<string, string> = {}
  Object.entries(spacing).forEach(([key, value]) => {
    if (value) {
      scale[key] = value
    }
  })
  return Object.keys(scale).length ? { scale } : undefined
}

function normalizeBorderRadius(
  borderRadius?: ThemeTokenPayload['borderRadius']
): Record<string, string> | undefined {
  if (!borderRadius) {
    return undefined
  }

  if (typeof borderRadius === 'string') {
    return { md: borderRadius }
  }
  const entries: Record<string, string> = {}
  Object.entries(borderRadius).forEach(([key, value]) => {
    if (value) {
      entries[key] = value
    }
  })
  return entries
}

function getDefaultDesignTokens(): DesignTokens {
  return {
    colors: {
      primary: {
        50: '#eff6ff',
        100: '#dbeafe',
        200: '#bfdbfe',
        300: '#93c5fd',
        400: '#60a5fa',
        500: '#3b82f6',
        600: '#2563eb',
        700: '#1d4ed8',
        800: '#1e40af',
        900: '#1e3a8a',
        950: '#172554',
      },
      secondary: {
        50: '#f5f3ff',
        100: '#ede9fe',
        200: '#ddd6fe',
        300: '#c4b5fd',
        400: '#a78bfa',
        500: '#8b5cf6',
        600: '#7c3aed',
        700: '#6d28d9',
        800: '#5b21b6',
        900: '#4c1d95',
        950: '#2e1065',
      },
      neutral: {
        50: '#fafafa',
        100: '#f4f4f5',
        200: '#e4e4e7',
        300: '#d4d4d8',
        400: '#a1a1aa',
        500: '#71717a',
        600: '#52525b',
        700: '#3f3f46',
        800: '#27272a',
        900: '#18181b',
        950: '#09090b',
      },
      success: {
        100: '#dcfce7',
        200: '#bbf7d0',
        300: '#86efac',
        400: '#4ade80',
        500: '#22c55e',
        600: '#16a34a',
        700: '#15803d',
      },
      warning: {
        100: '#fef3c7',
        200: '#fde68a',
        300: '#fcd34d',
        400: '#fbbf24',
        500: '#f59e0b',
        600: '#d97706',
      },
      error: {
        100: '#fee2e2',
        200: '#fecaca',
        300: '#fca5a5',
        400: '#f87171',
        500: '#ef4444',
        600: '#dc2626',
      },
    },
    typography: {
      fontFamily: {
        sans: 'Inter, ui-sans-serif, system-ui, sans-serif',
        serif: 'Georgia, ui-serif, serif',
        mono: 'Menlo, Monaco, Courier New, monospace',
      },
    },
  }
}

function applyColorScale(root: HTMLElement, scale: TokenScale | undefined, prefix: string): void {
  if (!scale) return
  Object.entries(scale).forEach(([shade, value]) => {
    root.style.setProperty(`${prefix}-${shade}`, value)
  })
}
