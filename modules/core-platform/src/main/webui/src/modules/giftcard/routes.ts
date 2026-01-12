/**
 * Gift Card module routes
 */
export const giftcardRoutes = [
  {
    path: '/admin/gift-cards',
    name: 'gift-cards',
    component: () => import('./views/GiftCardView.vue'),
    meta: {
      title: 'Gift Cards',
      requiresAuth: true
    }
  }
]
