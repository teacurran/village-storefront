/**
 * Orders Module Routes
 */

import type { RouteRecordRaw } from 'vue-router'

export const ordersRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/orders',
    name: 'orders',
    component: () => import('./views/OrdersDashboard.vue'),
    meta: {
      requiresAuth: true,
      requiredRole: 'ORDERS_VIEW',
      featureFlag: 'orders',
      title: 'Orders',
    },
  },
  {
    path: '/admin/orders/:id',
    name: 'order-detail',
    component: () => import('./views/OrdersDashboard.vue'), // Replace with OrderDetail.vue when implemented
    meta: {
      requiresAuth: true,
      requiredRole: 'ORDERS_VIEW',
      featureFlag: 'orders',
      title: 'Order Detail',
    },
  },
]
