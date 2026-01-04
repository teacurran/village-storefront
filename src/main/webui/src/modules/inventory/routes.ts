/**
 * Inventory Module Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const inventoryRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/inventory',
    name: 'inventory',
    component: () => import('./views/InventoryDashboard.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'INVENTORY_VIEW',
      featureFlag: 'inventory',
      title: 'Inventory',
    },
  },
]
