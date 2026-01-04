/**
 * Lightweight telemetry event bus for the Admin SPA.
 *
 * Emits events for app bootstrap, routing, command palette usage,
 * and impersonation banners so downstream observers (Quinoa, BI, etc.)
 * can hook into meaningful UX signals. References Section 14 requirements.
 */

export type TelemetryEventName =
  | 'app:hydrated'
  | 'route:change'
  | 'command-palette:toggle'
  | 'command-palette:execute'
  | 'impersonation:banner'
  | 'consignor:portal-loaded'
  | 'consignor:payout-requested'
  | 'consignor:notification-read'
  | 'view_orders'
  | 'view_order_detail'
  | 'action_filter_orders'
  | 'action_update_order_status'
  | 'action_cancel_order'
  | 'action_bulk_update_orders'
  | 'action_export_orders'
  | 'sse_orders_connected'
  | 'sse_orders_disconnected'
  | 'sse_order_event'
  | 'sse_orders_error'
  | 'view_inventory'
  | 'action_filter_inventory'
  | 'action_inventory_adjustment'
  | 'sse_inventory_connected'
  | 'sse_inventory_disconnected'
  | 'sse_inventory_event'
  | 'view_reports'
  | 'action_export_report'
  | 'view_loyalty_program'
  | 'view_loyalty_member'
  | 'action_loyalty_adjust'
  | 'view_notifications'
  | 'action_mark_notification_read'
  | 'action_mark_all_notifications_read'
  | 'sse_notifications_connected'
  | 'sse_notifications_disconnected'
  | 'sse_notification_received'

export interface TelemetryPayloads {
  'app:hydrated': {
    loadTimeMs: number
    version: string
    userAgent: string
  }
  'route:change': {
    from: string
    to: string
    tenantId: string | null
    userId: string | null
    impersonating: boolean
  }
  'command-palette:toggle': {
    isOpen: boolean
    route: string
    tenantId: string | null
    userId: string | null
    impersonating: boolean
  }
  'command-palette:execute': {
    commandId: string
    destination: string
    label: string
  }
  'impersonation:banner': {
    active: boolean
    tenantId: string | null
    adminEmail?: string
  }
  'consignor:portal-loaded': {
    consignorId: string
    balanceOwed: number
    activeItemCount: number
  }
  'consignor:payout-requested': {
    consignorId: string
    amount: number
    method: string
  }
  'consignor:notification-read': {
    consignorId: string
    notificationId: string
    notificationType: string
  }
  view_orders: Record<string, any>
  view_order_detail: Record<string, any>
  action_filter_orders: Record<string, any>
  action_update_order_status: Record<string, any>
  action_cancel_order: Record<string, any>
  action_bulk_update_orders: Record<string, any>
  action_export_orders: Record<string, any>
  sse_orders_connected: Record<string, any>
  sse_orders_disconnected: Record<string, any>
  sse_order_event: Record<string, any>
  sse_orders_error: Record<string, any>
  view_inventory: Record<string, any>
  action_filter_inventory: Record<string, any>
  action_inventory_adjustment: Record<string, any>
  sse_inventory_connected: Record<string, any>
  sse_inventory_disconnected: Record<string, any>
  sse_inventory_event: Record<string, any>
  view_reports: Record<string, any>
  action_export_report: Record<string, any>
  view_loyalty_program: Record<string, any>
  view_loyalty_member: Record<string, any>
  action_loyalty_adjust: Record<string, any>
  view_notifications: Record<string, any>
  action_mark_notification_read: Record<string, any>
  action_mark_all_notifications_read: Record<string, any>
  sse_notifications_connected: Record<string, any>
  sse_notifications_disconnected: Record<string, any>
  sse_notification_received: Record<string, any>
}

export interface TelemetryEvent<T extends TelemetryEventName = TelemetryEventName> {
  name: T
  payload: TelemetryPayloads[T]
  timestamp: string
}

type TelemetryListener = (event: TelemetryEvent) => void

const listeners = new Set<TelemetryListener>()

export function emitTelemetryEvent<T extends TelemetryEventName>(
  name: T,
  payload: TelemetryPayloads[T]
): TelemetryEvent<T> {
  const event: TelemetryEvent<T> = {
    name,
    payload,
    timestamp: new Date().toISOString(),
  }

  if (import.meta.env.DEV) {
    console.info('[telemetry]', name, payload)
  }

  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent('vsf:telemetry', { detail: event }))
  }

  listeners.forEach((listener) => listener(event))

  return event
}

export function onTelemetryEvent(listener: TelemetryListener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
