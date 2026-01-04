/**
 * Notifications Module Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const notificationsRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/notifications',
    name: 'notifications',
    component: () => import('./views/NotificationsDashboard.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'NOTIFICATIONS_VIEW',
      title: 'Notifications',
    },
  },
]
