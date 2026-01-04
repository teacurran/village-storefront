/**
 * Loyalty Module Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const loyaltyRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/loyalty',
    name: 'loyalty',
    component: () => import('./views/LoyaltyDashboard.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'LOYALTY_ADMIN',
      featureFlag: 'loyalty',
      title: 'Loyalty Programs',
    },
  },
]
