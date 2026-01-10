/**
 * Platform Console Tests
 *
 * Integration tests for platform admin console functionality including:
 * - Store directory pagination and filtering
 * - Impersonation lifecycle
 * - Audit log querying
 * - RBAC-gated UI elements
 *
 * References:
 * - Task I5.T2: Platform admin console
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePlatformStore } from '../store'
import * as platformApi from '../api'

// Mock API module
vi.mock('../api', () => ({
  getStoreDirectory: vi.fn(),
  getStoreDetails: vi.fn(),
  suspendStore: vi.fn(),
  reactivateStore: vi.fn(),
  startImpersonation: vi.fn(),
  endImpersonation: vi.fn(),
  getCurrentImpersonation: vi.fn(),
  getAuditLogs: vi.fn(),
  getSystemHealth: vi.fn(),
}))

describe('Platform Console Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('Store Directory', () => {
    it('loads stores with pagination', async () => {
      const mockStores = [
        { id: '1', subdomain: 'store1', name: 'Store 1', status: 'active', userCount: 10 },
        { id: '2', subdomain: 'store2', name: 'Store 2', status: 'active', userCount: 5 },
      ]

      vi.mocked(platformApi.getStoreDirectory).mockResolvedValue({
        stores: mockStores,
        page: 0,
        size: 20,
        totalCount: 2,
      })

      const store = usePlatformStore()
      await store.loadStores()

      expect(platformApi.getStoreDirectory).toHaveBeenCalledWith({}, 0, 20)
      expect(store.stores).toHaveLength(2)
      expect(store.storeCount).toBe(2)
    })

    it('applies filters when loading stores', async () => {
      vi.mocked(platformApi.getStoreDirectory).mockResolvedValue({
        stores: [],
        page: 0,
        size: 20,
        totalCount: 0,
      })

      const store = usePlatformStore()
      store.updateStoreFilters({ status: 'active', search: 'test' })
      await store.loadStores()

      expect(platformApi.getStoreDirectory).toHaveBeenCalledWith(
        { status: 'active', search: 'test' },
        0,
        20,
      )
    })

    it('suspends store and refreshes list', async () => {
      vi.mocked(platformApi.suspendStore).mockResolvedValue()
      vi.mocked(platformApi.getStoreDirectory).mockResolvedValue({
        stores: [],
        page: 0,
        size: 20,
        totalCount: 0,
      })

      const store = usePlatformStore()
      await store.suspendStore('store-123', 'Violation of ToS')

      expect(platformApi.suspendStore).toHaveBeenCalledWith('store-123', 'Violation of ToS')
      expect(platformApi.getStoreDirectory).toHaveBeenCalled()
    })
  })

  describe('Impersonation', () => {
    it('starts impersonation session', async () => {
      const mockContext = {
        sessionId: 'session-123',
        platformAdminId: 'admin-1',
        platformAdminEmail: 'admin@test.com',
        targetTenantId: 'tenant-1',
        targetTenantName: 'Test Store',
        reason: 'Support ticket #12345',
        startedAt: new Date().toISOString(),
      }

      vi.mocked(platformApi.startImpersonation).mockResolvedValue(mockContext)

      const store = usePlatformStore()
      await store.startImpersonation({
        targetTenantId: 'tenant-1',
        reason: 'Support ticket #12345',
        ticketNumber: 'TICKET-1',
      })

      expect(store.impersonation).toEqual(mockContext)
      expect(store.isImpersonating).toBe(true)
    })

    it('ends impersonation session', async () => {
      const mockContext = {
        sessionId: 'session-123',
        platformAdminId: 'admin-1',
        platformAdminEmail: 'admin@test.com',
        targetTenantId: 'tenant-1',
        targetTenantName: 'Test Store',
        reason: 'Support ticket #12345',
        startedAt: new Date().toISOString(),
      }

      vi.mocked(platformApi.startImpersonation).mockResolvedValue(mockContext)
      vi.mocked(platformApi.endImpersonation).mockResolvedValue()

      const store = usePlatformStore()
      await store.startImpersonation({
        targetTenantId: 'tenant-1',
        reason: 'Support ticket #12345',
        ticketNumber: 'TICKET-1',
      })

      expect(store.isImpersonating).toBe(true)

      await store.endImpersonation()

      expect(platformApi.endImpersonation).toHaveBeenCalled()
      expect(store.impersonation).toBeNull()
      expect(store.isImpersonating).toBe(false)
    })

    it('checks impersonation status on mount', async () => {
      const mockContext = {
        sessionId: 'session-123',
        platformAdminId: 'admin-1',
        platformAdminEmail: 'admin@test.com',
        targetTenantId: 'tenant-1',
        targetTenantName: 'Test Store',
        reason: 'Support ticket #12345',
        startedAt: new Date().toISOString(),
      }

      vi.mocked(platformApi.getCurrentImpersonation).mockResolvedValue(mockContext)

      const store = usePlatformStore()
      await store.checkImpersonationStatus()

      expect(store.impersonation).toEqual(mockContext)
    })
  })

  describe('Audit Logs', () => {
    it('loads audit logs with filters', async () => {
      const mockEntries = [
        {
          id: '1',
          actorType: 'platform_admin' as const,
          actorEmail: 'admin@test.com',
          action: 'suspend_store',
          occurredAt: new Date().toISOString(),
        },
      ]

      vi.mocked(platformApi.getAuditLogs).mockResolvedValue({
        entries: mockEntries,
        page: 0,
        size: 50,
        totalCount: 1,
      })

      const store = usePlatformStore()
      store.updateAuditFilters({ action: 'suspend_store' })
      await store.loadAuditLogs()

      expect(platformApi.getAuditLogs).toHaveBeenCalledWith({ action: 'suspend_store' }, 0, 50)
      expect(store.auditLogs).toHaveLength(1)
    })
  })

  describe('Health Metrics', () => {
    it('loads system health metrics', async () => {
      const mockHealth = {
        timestamp: new Date().toISOString(),
        tenantCount: 100,
        activeTenantCount: 95,
        totalUsers: 1000,
        activeSessions: 50,
        jobQueueDepth: 5,
        failedJobs24h: 2,
        avgResponseTimeMs: 42.5,
        p95ResponseTimeMs: 125.3,
        errorRatePercent: 0.05,
        diskUsagePercent: 45.2,
        dbConnectionCount: 10,
        status: 'healthy' as const,
      }

      vi.mocked(platformApi.getSystemHealth).mockResolvedValue(mockHealth)

      const store = usePlatformStore()
      await store.loadHealthMetrics()

      expect(store.healthMetrics).toEqual(mockHealth)
      expect(store.healthMetrics?.status).toBe('healthy')
    })
  })
})
