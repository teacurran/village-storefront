/**
 * Platform Console Store
 *
 * Manages state for platform admin console including store directory, impersonation,
 * audit logs, and health metrics.
 *
 * References:
 * - Task I5.T2: Platform admin console
 * - Pattern: modules/orders/store.ts
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  StoreDirectoryEntry,
  ImpersonationContext,
  ImpersonationRequest,
  AuditLogEntry,
  HealthMetricsSummary,
  PaginationState,
  StoreFilters,
  AuditLogFilters,
  FeatureFlagDto,
  UpdateFeatureFlagRequest,
  StaleFlagReport,
} from './types'
import * as platformApi from './api'
import { emitTelemetryEvent } from '@/telemetry'

export const usePlatformStore = defineStore('platform', () => {
  // --- State ---
  const stores = ref<StoreDirectoryEntry[]>([])
  const selectedStore = ref<StoreDirectoryEntry | null>(null)
  const storeFilters = ref<StoreFilters>({})
  const storePagination = ref<PaginationState>({ page: 0, size: 20, total: 0 })

  const auditLogs = ref<AuditLogEntry[]>([])
  const auditFilters = ref<AuditLogFilters>({})
  const auditPagination = ref<PaginationState>({ page: 0, size: 50, total: 0 })

  const impersonation = ref<ImpersonationContext | null>(null)
  const healthMetrics = ref<HealthMetricsSummary | null>(null)

  const featureFlags = ref<FeatureFlagDto[]>([])
  const selectedFlag = ref<FeatureFlagDto | null>(null)
  const staleFlagReport = ref<StaleFlagReport | null>(null)

  const loading = ref(false)
  const error = ref<string | null>(null)

  // --- Computed ---
  const isImpersonating = computed(() => impersonation.value !== null)
  const storeCount = computed(() => stores.value.length)
  const auditLogCount = computed(() => auditLogs.value.length)
  const flagCount = computed(() => featureFlags.value.length)
  const staleFlags = computed(() => featureFlags.value.filter((f) => f.stale))
  const staleFlagCount = computed(() => staleFlags.value.length)

  // --- Actions: Store Directory ---

  async function loadStores(page = 0, resetSelection = true): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const result = await platformApi.getStoreDirectory(storeFilters.value, page, storePagination.value.size)

      stores.value = result.stores
      storePagination.value.page = page
      storePagination.value.size = result.size
      storePagination.value.total = result.totalCount

      if (resetSelection) {
        selectedStore.value = null
      }

      emitTelemetryEvent('platform_view_stores', {
        count: result.stores.length,
        total: result.totalCount,
        filters: storeFilters.value,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load stores'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadStoreDetails(storeId: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const store = await platformApi.getStoreDetails(storeId)
      selectedStore.value = store

      emitTelemetryEvent('platform_view_store_details', { storeId })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load store details'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function suspendStore(storeId: string, reason: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      await platformApi.suspendStore(storeId, reason)

      // Refresh store list
      await loadStores(storePagination.value.page, false)

      emitTelemetryEvent('platform_suspend_store', { storeId, reason })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to suspend store'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function reactivateStore(storeId: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      await platformApi.reactivateStore(storeId)

      // Refresh store list
      await loadStores(storePagination.value.page, false)

      emitTelemetryEvent('platform_reactivate_store', { storeId })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to reactivate store'
      throw err
    } finally {
      loading.value = false
    }
  }

  function updateStoreFilters(filters: StoreFilters): void {
    storeFilters.value = filters
  }

  // --- Actions: Impersonation ---

  async function startImpersonation(request: ImpersonationRequest): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const payload: ImpersonationRequest = {
        targetTenantId: request.targetTenantId.trim(),
        targetUserId: request.targetUserId?.trim() || undefined,
        reason: request.reason.trim(),
        ticketNumber: request.ticketNumber.trim(),
      }

      const context = await platformApi.startImpersonation(payload)
      impersonation.value = context

      emitTelemetryEvent('platform_impersonate_start', {
        sessionId: context.sessionId,
        targetTenantId: context.targetTenantId,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to start impersonation'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function endImpersonation(): Promise<void> {
    loading.value = true
    error.value = null

    const sessionId = impersonation.value?.sessionId

    try {
      await platformApi.endImpersonation()
      impersonation.value = null

      emitTelemetryEvent('platform_impersonate_end', { sessionId })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to end impersonation'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function checkImpersonationStatus(): Promise<void> {
    try {
      const context = await platformApi.getCurrentImpersonation()
      impersonation.value = context
    } catch (err) {
      // Silent failure - impersonation status check is non-critical
      console.warn('Failed to check impersonation status:', err)
    }
  }

  // --- Actions: Audit Logs ---

  async function loadAuditLogs(page = 0): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const result = await platformApi.getAuditLogs(auditFilters.value, page, auditPagination.value.size)

      auditLogs.value = result.entries
      auditPagination.value.page = page
      auditPagination.value.size = result.size
      auditPagination.value.total = result.totalCount

      emitTelemetryEvent('platform_view_audit_logs', {
        count: result.entries.length,
        total: result.totalCount,
        filters: auditFilters.value,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load audit logs'
      throw err
    } finally {
      loading.value = false
    }
  }

  function updateAuditFilters(filters: AuditLogFilters): void {
    auditFilters.value = filters
  }

  // --- Actions: Health Metrics ---

  async function loadHealthMetrics(): Promise<void> {
    try {
      const metrics = await platformApi.getSystemHealth()
      healthMetrics.value = metrics

      emitTelemetryEvent('platform_view_health', { status: metrics.status })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load health metrics'
      throw err
    }
  }

  // --- Actions: Feature Flags ---

  async function loadFeatureFlags(staleOnly = false): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const flags = await platformApi.getFeatureFlags(staleOnly)
      featureFlags.value = flags

      emitTelemetryEvent('platform_view_feature_flags', {
        count: flags.length,
        staleOnly,
        staleCount: flags.filter((f) => f.stale).length,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load feature flags'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadFeatureFlag(flagId: string): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const flag = await platformApi.getFeatureFlag(flagId)
      selectedFlag.value = flag

      emitTelemetryEvent('platform_view_feature_flag', { flagId, flagKey: flag.flagKey })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load feature flag'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadStaleFlagReport(): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const report = await platformApi.getStaleFlagReport()
      staleFlagReport.value = report

      emitTelemetryEvent('platform_view_stale_flags', {
        expiredCount: report.expiredCount,
        needsReviewCount: report.needsReviewCount,
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load stale flag report'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function updateFeatureFlag(
    flagId: string,
    request: UpdateFeatureFlagRequest,
  ): Promise<void> {
    loading.value = true
    error.value = null

    try {
      const updatedFlag = await platformApi.updateFeatureFlag(flagId, request)

      // Update in list
      const index = featureFlags.value.findIndex((f) => f.id === flagId)
      if (index !== -1) {
        featureFlags.value[index] = updatedFlag
      }

      // Update selected if matches
      if (selectedFlag.value?.id === flagId) {
        selectedFlag.value = updatedFlag
      }

      emitTelemetryEvent('platform_update_feature_flag', {
        flagId,
        flagKey: updatedFlag.flagKey,
        changes: Object.keys(request),
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to update feature flag'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function toggleFeatureFlag(flagId: string, enabled: boolean, reason: string): Promise<void> {
    await updateFeatureFlag(flagId, { enabled, reason })
  }

  async function reviewFeatureFlag(flagId: string, reason: string): Promise<void> {
    await updateFeatureFlag(flagId, { markReviewed: true, reason })
  }

  // --- Helper: Clear State ---

  function clearError(): void {
    error.value = null
  }

  function reset(): void {
    stores.value = []
    selectedStore.value = null
    storeFilters.value = {}
    auditLogs.value = []
    auditFilters.value = {}
    impersonation.value = null
    healthMetrics.value = null
    featureFlags.value = []
    selectedFlag.value = null
    staleFlagReport.value = null
    error.value = null
  }

  return {
    // State
    stores,
    selectedStore,
    storeFilters,
    storePagination,
    auditLogs,
    auditFilters,
    auditPagination,
    impersonation,
    healthMetrics,
    featureFlags,
    selectedFlag,
    staleFlagReport,
    loading,
    error,

    // Computed
    isImpersonating,
    storeCount,
    auditLogCount,
    flagCount,
    staleFlags,
    staleFlagCount,

    // Actions
    loadStores,
    loadStoreDetails,
    suspendStore,
    reactivateStore,
    updateStoreFilters,
    startImpersonation,
    endImpersonation,
    checkImpersonationStatus,
    loadAuditLogs,
    updateAuditFilters,
    loadHealthMetrics,
    loadFeatureFlags,
    loadFeatureFlag,
    loadStaleFlagReport,
    updateFeatureFlag,
    toggleFeatureFlag,
    reviewFeatureFlag,
    clearError,
    reset,
  }
})
