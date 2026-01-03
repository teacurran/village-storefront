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
