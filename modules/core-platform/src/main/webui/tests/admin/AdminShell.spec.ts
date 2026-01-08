/**
 * Admin Shell Integration Tests
 *
 * Tests routing guards, command palette toggling, and Pinia store hydration
 * per acceptance criteria.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import App from '@/App.vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import LoginView from '@/views/LoginView.vue'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'

describe('AdminShell', () => {
  beforeEach(() => {
    // Create fresh Pinia instance for each test
    setActivePinia(createPinia())

    // Clear localStorage
    localStorage.clear()
  })

  describe('Routing Guards', () => {
    it('redirects to login when accessing protected routes while unauthenticated', async () => {
      const router = createRouter({
        history: createWebHistory('/admin'),
        routes: [
          {
            path: '/',
            component: DefaultLayout,
            meta: { requiresAuth: true },
          },
          {
            path: '/login',
            component: LoginView,
            meta: { requiresAuth: false },
          },
        ],
      })

      const wrapper = mount(App, {
        global: {
          plugins: [router, createPinia()],
        },
      })

      await router.push('/')
      await router.isReady()

      // Should redirect to login
      expect(router.currentRoute.value.path).toBe('/login')
    })

    it('allows access to protected routes when authenticated', async () => {
      const authStore = useAuthStore()

      // Mock authentication
      authStore.accessToken = 'mock-token'
      authStore.user = {
        id: '1',
        email: 'test@example.com',
        firstName: 'Test',
        lastName: 'User',
        roles: ['ADMIN'],
        tenantId: 'tenant-1',
      }

      const router = createRouter({
        history: createWebHistory('/admin'),
        routes: [
          {
            path: '/',
            component: DefaultLayout,
            meta: { requiresAuth: true },
          },
          {
            path: '/login',
            component: LoginView,
            meta: { requiresAuth: false },
          },
        ],
      })

      await router.push('/')
      await router.isReady()

      // Should allow access to dashboard
      expect(router.currentRoute.value.path).toBe('/')
    })
  })

  describe('Command Palette', () => {
    it('toggles command palette on event', async () => {
      const wrapper = mount(App, {
        global: {
          plugins: [
            createRouter({
              history: createWebHistory('/admin'),
              routes: [{ path: '/', component: { template: '<div>Test</div>' } }],
            }),
            createPinia(),
          ],
        },
      })

      // Command palette should be closed initially
      expect(wrapper.vm.commandPaletteOpen).toBe(false)

      // Toggle command palette
      wrapper.vm.toggleCommandPalette()
      await wrapper.vm.$nextTick()

      // Should now be open
      expect(wrapper.vm.commandPaletteOpen).toBe(true)

      // Toggle again
      wrapper.vm.toggleCommandPalette()
      await wrapper.vm.$nextTick()

      // Should be closed
      expect(wrapper.vm.commandPaletteOpen).toBe(false)
    })
  })

  describe('Pinia Store Hydration', () => {
    it('holds auth context in auth store', () => {
      const authStore = useAuthStore()

      // Set auth tokens
      authStore.setTokens({
        accessToken: 'test-access-token',
        refreshToken: 'test-refresh-token',
        expiresIn: 900,
      })

      authStore.user = {
        id: '1',
        email: 'test@example.com',
        firstName: 'Test',
        lastName: 'User',
        roles: ['ADMIN', 'STORE_OWNER'],
        tenantId: 'tenant-1',
      }

      // Verify auth state
      expect(authStore.isAuthenticated).toBe(true)
      expect(authStore.accessToken).toBe('test-access-token')
      expect(authStore.user?.email).toBe('test@example.com')
      expect(authStore.userRoles).toContain('ADMIN')
    })

    it('holds tenant context in tenant store', async () => {
      const tenantStore = useTenantStore()

      // Load tenant (will use mock data)
      await tenantStore.loadTenant()

      // Verify tenant state
      expect(tenantStore.currentTenant).toBeTruthy()
      expect(tenantStore.tenantName).toBe('Demo Store')
      expect(tenantStore.tenantPlan).toBe('PRO')
    })

    it('checks feature flags via tenant store', async () => {
      const tenantStore = useTenantStore()

      await tenantStore.loadTenant()

      // Test feature flag checking
      expect(tenantStore.isFeatureEnabled('loyalty')).toBe(true)
      expect(tenantStore.isFeatureEnabled('subscriptions')).toBe(false)
    })

    it('handles impersonation context', () => {
      const authStore = useAuthStore()

      // Not impersonating initially
      expect(authStore.isImpersonating).toBe(false)

      // Set impersonation
      authStore.setImpersonation({
        adminUserId: 'admin-1',
        adminEmail: 'admin@platform.com',
        reason: 'Customer support',
        startedAt: new Date().toISOString(),
      })

      // Should be impersonating
      expect(authStore.isImpersonating).toBe(true)
      expect(authStore.impersonationContext?.adminEmail).toBe('admin@platform.com')

      // Clear impersonation
      authStore.clearImpersonation()
      expect(authStore.isImpersonating).toBe(false)
    })
  })

  describe('Auth Persistence', () => {
    it('persists auth state to localStorage', () => {
      const authStore = useAuthStore()

      authStore.setTokens({
        accessToken: 'test-token',
        refreshToken: 'test-refresh',
        expiresIn: 900,
      })

      authStore.user = {
        id: '1',
        email: 'test@example.com',
        firstName: 'Test',
        lastName: 'User',
        roles: ['ADMIN'],
        tenantId: 'tenant-1',
      }

      authStore.persistAuth()

      // Verify localStorage
      const stored = localStorage.getItem('auth')
      expect(stored).toBeTruthy()

      const parsed = JSON.parse(stored!)
      expect(parsed.accessToken).toBe('test-token')
      expect(parsed.user.email).toBe('test@example.com')
    })

    it('restores auth state from localStorage', () => {
      // Pre-populate localStorage
      localStorage.setItem(
        'auth',
        JSON.stringify({
          accessToken: 'stored-token',
          refreshToken: 'stored-refresh',
          tokenExpiresAt: Date.now() + 60000,
          user: {
            id: '1',
            email: 'stored@example.com',
            firstName: 'Stored',
            lastName: 'User',
            roles: ['ADMIN'],
            tenantId: 'tenant-1',
          },
          impersonationContext: null,
        })
      )

      const authStore = useAuthStore()
      authStore.restoreAuth()

      // Verify restoration
      expect(authStore.accessToken).toBe('stored-token')
      expect(authStore.user?.email).toBe('stored@example.com')
      expect(authStore.isAuthenticated).toBe(true)
    })
  })
})
