# Admin SPA Modules – Implementation Notes (I5.T1)

The admin SPA now includes feature-complete modules for orders, inventory, reporting, loyalty, and notifications. Each module follows the Pinia + PrimeVue patterns established by the consignor portal and satisfies the iteration acceptance criteria (RBAC, feature flags, SSE telemetries, Storybook coverage, and E2E hooks).

## Orders
- **Route:** `/admin/orders` (requires `ORDERS_VIEW`, feature flag `orders`).
- **Highlights:** KPI cards, table with filters/search, bulk actions, CSV export, SSE connection badge, and a new order detail drawer with inline status/cancel actions. Telemetry emits `view_orders`, `action_…`, and SSE health events.
- **RBAC/Flags:** Export and bulk actions respect `ORDERS_EDIT`/`ORDERS_EXPORT`. Feature flag hides module with upgrade messaging.

## Inventory
- **Route:** `/admin/inventory` (`INVENTORY_VIEW`, `inventory`).
- **Highlights:** Location/search filters, low-stock toggle, PrimeVue `DataTable`, drawer showing quantities + recent transfers, adjustment dialog wired to `/admin/inventory/adjustments`, SSE feed for live quantities, telemetry events (`view_inventory`, `action_inventory_adjustment`).
- **Flags:** Feature disabled tenants see InlineAlert per exposure guidance.

## Reporting
- **Route:** `/admin/reports` (`REPORTS_VIEW`).
- **Highlights:** Date range filters, KPI cards, slow-mover table sourced from `/admin/reports/aggregates/*`, export job panel, telemetry for view/export actions. Export button gated by `REPORTS_EXPORT`.

## Loyalty
- **Route:** `/admin/loyalty` (`LOYALTY_ADMIN`, `loyalty`).
- **Highlights:** Program summary, tier table parsed from backend config, member lookup/transactions, adjust-points dialog hitting `/admin/loyalty/adjust/{userId}`, SSE-badge refreshing lookups when loyalty events arrive.

## Notifications
- **Route:** `/admin/notifications` (`NOTIFICATIONS_VIEW`).
- **Highlights:** Severity/search filters, unread handling, action buttons for mark-read and action links, SSE-driven list with telemetry and graceful fallback when history endpoint is unavailable.

All modules share the `useI18n` helper for copy, PrimeVue for layout, and telemetry instrumentation (`@/telemetry`). Acceptance tests live in `src/main/webui/tests/admin/*` and cover dashboard loading, filters, and RBAC toggles.
