/**
 * Platform Admin Console Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const platformRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/platform/stores',
    name: 'platform-stores',
    component: () => import('./views/StoreDirectoryView.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'PLATFORM_ADMIN',
      featureFlag: 'platformConsole',
      title: 'Platform Stores',
    },
  },
  {
    path: '/admin/platform/audit',
    name: 'platform-audit',
    component: () => import('./views/AuditLogView.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'PLATFORM_ADMIN',
      featureFlag: 'platformConsole',
      title: 'Platform Audit Logs',
    },
  },
  {
    path: '/admin/platform/health',
    name: 'platform-health',
    component: () => import('./views/HealthDashboardView.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'PLATFORM_ADMIN',
      featureFlag: 'platformConsole',
      title: 'Platform Health',
    },
  },
  {
    path: '/admin/platform/impersonation',
    name: 'platform-impersonation',
    component: () => import('./views/ImpersonationControlView.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'PLATFORM_ADMIN',
      featureFlag: 'platformConsole',
      title: 'Impersonation Control',
    },
  },
]
