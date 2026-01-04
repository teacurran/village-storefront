/**
 * Vue Router Configuration
 *
 * Defines routes for admin SPA with authentication guards and lazy-loaded views.
 *
 * References:
 * - UI/UX Architecture Section 2.13: Admin Component Inventory
 */

import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useTenantStore } from '@/stores/tenant'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { emitTelemetryEvent } from '@/telemetry'
import { ordersRoutes } from '@/modules/orders/routes'
import { inventoryRoutes } from '@/modules/inventory/routes'
import { reportingRoutes } from '@/modules/reporting/routes'
import { loyaltyRoutes } from '@/modules/loyalty/routes'
import { notificationsRoutes } from '@/modules/notifications/routes'
import { platformRoutes } from '@/modules/platform/routes'

const router = createRouter({
  history: createWebHistory('/admin'),
  routes: [
    {
      path: '/',
      component: DefaultLayout,
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: 'Dashboard' },
        },
        {
          path: 'catalog',
          name: 'catalog',
          component: () => import('@/views/CatalogView.vue'),
          meta: { title: 'Catalog' },
        },
        {
          path: 'pos',
          name: 'pos',
          component: () => import('@/views/POSView.vue'),
          meta: { title: 'Point of Sale' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: 'Settings' },
        },
        // Admin module routes
        ...ordersRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
        ...inventoryRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
        ...reportingRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
        ...loyaltyRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
        ...notificationsRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
        ...platformRoutes.map((r) => ({ ...r, path: r.path.replace('/admin/', '') })),
      ],
    },
    {
      path: '/consignor',
      component: DefaultLayout,
      meta: { requiresAuth: true, requiresVendorRole: true },
      children: [
        {
          path: '',
          redirect: '/consignor/dashboard',
        },
        {
          path: 'dashboard',
          name: 'consignor-dashboard',
          component: () => import('@/modules/consignor/views/ConsignorDashboard.vue'),
          meta: { title: 'Consignor Portal', requiresVendorRole: true },
        },
      ],
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { requiresAuth: false, title: 'Login' },
    },
  ],
})

// Navigation guard for authentication
router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()
  const tenantStore = useTenantStore()

  // Set page title
  document.title = to.meta.title
    ? `${to.meta.title} - Village Storefront Admin`
    : 'Village Storefront Admin'

  // Check authentication requirement
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ name: 'login', query: { redirect: to.fullPath } })
    return
  }

  if (to.name === 'login' && authStore.isAuthenticated) {
    next({ name: 'dashboard' })
    return
  }

  // Check vendor role requirement
  if (to.meta.requiresVendorRole && !authStore.hasRole('vendor')) {
    console.warn('Access denied: vendor role required')
    next({ name: 'dashboard' })
    return
  }

  // Check RBAC requirements
  if (to.meta.requiredRole && !authStore.hasRole(to.meta.requiredRole as string)) {
    console.warn('Access denied: required role not found')
    next({ name: 'dashboard' })
    return
  }

  // Check feature flag requirements
  if (to.meta.featureFlag && !tenantStore.isFeatureEnabled(to.meta.featureFlag as string)) {
    console.warn('Access denied: feature not enabled')
    next({ name: 'dashboard' })
    return
  }

  next()
})

router.afterEach((to, from) => {
  const authStore = useAuthStore()
  const tenantStore = useTenantStore()

  emitTelemetryEvent('route:change', {
    from: from.fullPath,
    to: to.fullPath,
    tenantId: tenantStore.tenantId?.value ?? null,
    userId: authStore.user?.id ?? null,
    impersonating: authStore.isImpersonating,
  })
})

export default router
