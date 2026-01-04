# Consignor Portal Module

A Vue.js module providing vendor-facing consignment management capabilities within the Village Storefront admin SPA.

## Quick Start

```bash
# Navigate to SPA directory
cd src/main/webui

# Install dependencies
npm install

# Run dev server
npm run dev

# Run tests
npm run test

# Run Storybook
npm run storybook
```

## Module Contents

### Components

- **DashboardStatsCard** - Reusable stat widget with icon, value, subtitle
- **BalanceChart** - Balance overview with payout eligibility check
- **ConsignmentItemsTable** - Paginated table with status filters
- **NotificationCenter** - Notification list with read/unread tracking
- **PayoutRequestModal** - Form modal for requesting payouts

### Views

- **ConsignorDashboard** - Main portal view with stats, balance, items, notifications

### State Management

- **store.ts** - Pinia store for consignor data (profile, items, payouts, notifications)
- **api.ts** - API client wrapper for backend endpoints
- **types.ts** - TypeScript interfaces for consignor domain

### Utilities

- **composables/useI18n.ts** - Localization helper (English/Spanish)

## API Integration

Connects to backend vendor portal endpoints:

```
GET /api/v1/vendor/portal/profile
GET /api/v1/vendor/portal/items?page=0&size=20
GET /api/v1/vendor/portal/payouts?page=0&size=20
```

Requires JWT with `vendor` role and `consignor_id` claim.

## Localization

Translation keys in `src/main/webui/src/locales/`:

- `en.json` - English
- `es.json` - Spanish (Español)

Use the `useI18n()` composable:

```vue
<script setup>
import { useI18n } from '@/modules/consignor/composables/useI18n'
const { t } = useI18n()
</script>

<template>
  <h1>{{ t('consignor.dashboard.title') }}</h1>
</template>
```

## Routing

Portal routes under `/admin/consignor/*`:

- `/admin/consignor/dashboard` - Main dashboard (vendor role required)

Navigation guard checks for `vendor` role in JWT.

## Testing

Unit tests with Vitest:

```bash
npm run test -- tests/admin/ConsignorPortal.spec.ts
```

Storybook stories:

```bash
npm run storybook
# Navigate to "Consignor" section
```

## Accessibility

Fully WCAG 2.1 Level AA compliant:

- Semantic HTML, ARIA labels, keyboard navigation
- Screen reader support, focus management
- Color contrast validation, responsive touch targets

See `docs/consignor-portal-accessibility.md` for details.

## Documentation

- **Portal Guide**: `docs/consignor-portal.md`
- **Accessibility**: `docs/consignor-portal-accessibility.md`
- **Architecture**: `.codemachine/artifacts/architecture/06_UI_UX_Architecture.md`

## References

- Task I3.T7: Consignor Portal UI Implementation
- Backend Resource: `src/main/java/villagecompute/storefront/api/vendor/VendorPortalResource.java`
- OpenAPI Spec: `api/v1/openapi.yaml` (vendor portal endpoints)
