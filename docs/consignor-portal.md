# Consignor Portal Documentation

## Overview

The Consignor Portal is a vendor-facing Vue.js module that allows consignors to manage their consignment inventory, view earnings, request payouts, and receive notifications. The portal is built with responsive design, localization support (English/Spanish), and full accessibility compliance.

**References:**
- Task I3.T7: Consignor Portal UI Implementation
- Architecture Section 2.4: Organisms & Complex Regions
- Architecture Section 3.1: Route Definitions

---

## Architecture

### Module Structure

```
src/main/webui/src/modules/consignor/
├── api.ts                    # API client wrapper
├── store.ts                  # Pinia state management
├── types.ts                  # TypeScript interfaces
├── components/
│   ├── BalanceChart.vue
│   ├── ConsignmentItemsTable.vue
│   ├── DashboardStatsCard.vue
│   ├── NotificationCenter.vue
│   └── PayoutRequestModal.vue
├── composables/
│   └── useI18n.ts           # Localization helper
└── views/
    └── ConsignorDashboard.vue
```

### Backend Integration

The portal consumes REST endpoints from `VendorPortalResource.java`:

- **GET** `/api/v1/vendor/portal/profile` - Get consignor profile
- **GET** `/api/v1/vendor/portal/items` - List consignment items (paginated)
- **GET** `/api/v1/vendor/portal/payouts` - List payout batches (paginated)

All endpoints require vendor JWT with `consignor_id` claim and `vendor` role.

---

## Features

### 1. Dashboard Overview

**Route:** `/admin/consignor/dashboard`

The dashboard displays:

- **Stats Cards**: Balance owed, active items, sold this month, lifetime earnings
- **Balance Chart**: Current balance with payout request CTA
- **Items Table**: Consignment items with filters (all, available, sold, returned)
- **Notification Center**: Recent notifications with read/unread tracking

### 2. Payout Requests

Consignors can request payouts when balance exceeds $50 minimum:

- **Methods**: Check (mail), ACH, PayPal, Store Credit
- **Validation**: Minimum amount, max available balance
- **Status Tracking**: Pending → Processing → Completed/Failed

### 3. Localization

Supports English (en) and Spanish (es) with locale files:

- `src/main/webui/src/locales/en.json`
- `src/main/webui/src/locales/es.json`

Use the `useI18n()` composable to access translations:

```typescript
import { useI18n } from '@/modules/consignor/composables/useI18n'

const { t, setLocale } = useI18n()
console.log(t('consignor.dashboard.title')) // "Consignor Dashboard"
setLocale('es')
console.log(t('consignor.dashboard.title')) // "Panel de Consignatario"
```

### 4. Responsive Design

Built with Tailwind CSS using mobile-first breakpoints:

- **Mobile (default)**: Single column layout
- **md (768px+)**: 2-column stats grid
- **lg (1024px+)**: 4-column stats grid, 2-column main grid

All components are tested for responsiveness and touch-friendly interactions.

### 5. Accessibility

- **Semantic HTML**: Proper heading hierarchy, landmarks
- **ARIA Labels**: Descriptive labels on all interactive elements
- **Keyboard Navigation**: Full keyboard support, focus management
- **Screen Reader**: Live regions for notifications, status updates
- **Color Contrast**: WCAG AA compliant contrast ratios

---

## Launch Instructions

### Development

1. **Install dependencies:**
   ```bash
   cd src/main/webui
   npm install
   ```

2. **Run dev server:**
   ```bash
   npm run dev
   ```

3. **Access portal:**
   - Navigate to `http://localhost:5173/admin/consignor/dashboard`
   - Mock vendor JWT in auth store (see `src/main/webui/src/stores/auth.ts`)

### Testing

1. **Run unit tests:**
   ```bash
   npm run test
   ```

2. **Run E2E tests:**
   ```bash
   npm run test -- tests/admin/ConsignorPortal.spec.ts
   ```

3. **Run Storybook:**
   ```bash
   npm run storybook
   ```
   - View stories at `http://localhost:6006`
   - Navigate to "Consignor" section

### Production Build

1. **Build SPA:**
   ```bash
   npm run build
   ```

2. **Verify bundle size:**
   ```bash
   ls -lh dist/assets/
   ```

3. **Deploy:**
   - Quarkus Quinoa extension bundles SPA into JAR automatically
   - SPA assets served from `/admin/*` paths
   - Tenant resolution via Host header or `X-Tenant-ID`

---

## Configuration

### Feature Flags

Enable consignment portal per tenant:

```java
// In TenantContext
featureFlags.put("consignment", true);
```

### Design Tokens

Portal respects tenant design tokens for theming:

- Primary/secondary colors from `--color-primary-*` CSS variables
- Typography from `--font-*` CSS variables
- Loaded via `useTenantStore().loadDesignTokens()`

### API Client

All API calls use shared `apiClient` from `@/api/client.ts`:

- Automatic tenant header injection (`X-Tenant-ID`)
- JWT token refresh on 401
- Retry logic with exponential backoff
- Problem Details error handling

---

## Telemetry & Analytics

Portal emits telemetry events for observability:

```typescript
import { emitTelemetryEvent } from '@/telemetry'

// Portal loaded
emitTelemetryEvent('consignor:portal-loaded', {
  consignorId: 'consignor-123',
  balanceOwed: 12500,
  activeItemCount: 42,
})

// Payout requested
emitTelemetryEvent('consignor:payout-requested', {
  consignorId: 'consignor-123',
  amount: 10000,
  method: 'ACH',
})

// Notification read
emitTelemetryEvent('consignor:notification-read', {
  consignorId: 'consignor-123',
  notificationId: 'notif-456',
  notificationType: 'ITEM_SOLD',
})
```

Events are forwarded to:
- Browser console (dev mode)
- Custom `window` events (`vsf:telemetry`)
- Backend audit logs (via API interceptor)

---

## Security

### Authentication

- **Required Role**: `vendor` (JWT claim)
- **Consignor ID**: Resolved from JWT `consignor_id` claim or `SecurityIdentity` attribute
- **Route Guard**: `requiresVendorRole: true` in router meta

### Authorization

- Consignors can only access their own data (enforced by backend via `consignor_id` claim)
- Admin users cannot access vendor portal (role check in router guard)
- Impersonation logged and tracked via `X-Impersonation-Context` header

### Data Privacy

- Sensitive fields (tax ID, bank info) not exposed to frontend
- Balance/earnings calculated server-side, not client-side
- All monetary values stored as integer minor units (cents)

---

## Troubleshooting

### Portal not loading

1. Verify tenant has `consignment` feature flag enabled
2. Check JWT contains `consignor_id` claim
3. Ensure user has `vendor` role
4. Check browser console for API errors

### Translations not working

1. Verify locale files exist: `src/main/webui/src/locales/en.json`, `es.json`
2. Check `useI18n()` composable is loaded
3. Ensure translation keys match locale file structure

### Payout request fails

1. Verify balance exceeds $50 minimum
2. Check amount doesn't exceed available balance
3. Ensure payout method is valid: `CHECK`, `ACH`, `PAYPAL`, `STORE_CREDIT`
4. Review backend logs for validation errors

### Tests failing

1. Run `npm install` to ensure dependencies are current
2. Clear Vitest cache: `rm -rf node_modules/.vite`
3. Verify mock API responses in `ConsignorPortal.spec.ts`
4. Check `vi.mock()` imports match actual module paths

---

## Future Enhancements

Planned features for future iterations:

- **Charts & Graphs**: Sales trends, earnings over time (Chart.js or D3)
- **Batch Item Upload**: CSV import for bulk consignment intake
- **Contract Management**: View/sign consignment agreements digitally
- **Tax Reporting**: Generate 1099 forms, export transaction history
- **Notifications**: Real-time push via WebSocket or SSE
- **Multi-currency**: Display balances in consignor's preferred currency

---

## Support

For questions or issues:

- **Documentation**: `docs/java-project-standards.adoc`
- **Architecture**: `docs/architecture/06_UI_UX_Architecture.md`
- **API Spec**: `api/v1/openapi.yaml` (vendor portal endpoints)
- **Issue Tracker**: GitHub Issues (VillageCompute/village-storefront)

---

**Last Updated:** 2026-01-03
**Version:** 1.0.0
**Iteration:** I3
