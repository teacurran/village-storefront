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

  // Set page title
  document.title = to.meta.title
    ? `${to.meta.title} - Village Storefront Admin`
    : 'Village Storefront Admin'

  // Check authentication requirement
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ name: 'login', query: { redirect: to.fullPath } })
  } else if (to.name === 'login' && authStore.isAuthenticated) {
    next({ name: 'dashboard' })
  } else {
    next()
  }
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
