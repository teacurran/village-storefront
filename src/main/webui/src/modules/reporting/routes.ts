/**
 * Reporting Module Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const reportingRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/reports',
    name: 'reports',
    component: () => import('./views/ReportingDashboard.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'REPORTS_VIEW',
      title: 'Reports & Analytics',
    },
  },
]
