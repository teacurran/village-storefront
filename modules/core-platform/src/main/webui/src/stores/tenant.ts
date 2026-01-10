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
import type { TenantContext } from '@/api/types'

export const useTenantStore = defineStore('tenant', () => {
  // State
  const currentTenant = ref<TenantContext | null>(null)
  const availableTenants = ref<TenantContext[]>([])
  const designTokens = ref<Record<string, any>>({})
  const tokenVersion = ref<string | null>(null)

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
    // Mock implementation - replace with actual API call
    // const response = await apiClient.get<TenantContext>('/tenant/current')

    const mockTenant: TenantContext = {
      id: 'tenant-1',
      subdomain: 'demo-store',
      customDomain: undefined,
      name: 'Demo Store',
      plan: 'PRO',
      featureFlags: {
        loyalty: true,
        pos: true,
        subscriptions: false,
        consignment: true,
        multiLocation: false,
      },
    }

    currentTenant.value = mockTenant

    // Load design tokens for this tenant
    await loadDesignTokens()
  }

  async function loadDesignTokens(): Promise<void> {
    if (!currentTenant.value) return

    // Mock implementation - replace with actual API call
    // const response = await apiClient.get<{tokens: any, version: string}>(
    //   `/tenant/${currentTenant.value.id}/design-tokens`
    // )

    const mockTokens = {
      colors: {
        primary: '#3b82f6',
        secondary: '#8b5cf6',
      },
      typography: {
        fontFamily: 'Inter',
      },
    }

    designTokens.value = mockTokens
    tokenVersion.value = 'v1.0.0'

    // Apply tokens as CSS variables
    applyDesignTokens()
  }

  function applyDesignTokens(): void {
    const root = document.documentElement

    // Apply color tokens
    if (designTokens.value.colors) {
      Object.entries(designTokens.value.colors).forEach(([key, value]) => {
        root.style.setProperty(`--color-${key}`, value as string)
      })
    }

    // Apply typography tokens
    if (designTokens.value.typography) {
      Object.entries(designTokens.value.typography).forEach(([key, value]) => {
        root.style.setProperty(`--font-${key}`, value as string)
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
    // Mock implementation - replace with actual API call
    availableTenants.value = [currentTenant.value].filter(Boolean) as TenantContext[]
  }

  return {
    // State
    currentTenant,
    availableTenants,
    designTokens,
    tokenVersion,

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
  }
})
