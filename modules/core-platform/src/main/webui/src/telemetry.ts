/**
 * Telemetry Module
 *
 * Provides lightweight event tracking for admin SPA usage analytics.
 * Events are logged locally for now; can be extended to send to backend.
 */

export interface TelemetryEvent {
  name: string
  timestamp: number
  data?: Record<string, any>
}

const events: TelemetryEvent[] = []

/**
 * Emit a telemetry event
 */
export function emitTelemetryEvent(name: string, data?: Record<string, any>): void {
  const event: TelemetryEvent = {
    name,
    timestamp: Date.now(),
    data,
  }

  events.push(event)

  // Log to console in development
  if (import.meta.env.DEV) {
    console.debug('[Telemetry]', name, data)
  }

  // TODO: Send to backend analytics endpoint in production
  // if (import.meta.env.PROD) {
  //   sendToBackend(event)
  // }
}

/**
 * Get all recorded events (for debugging)
 */
export function getTelemetryEvents(): TelemetryEvent[] {
  return [...events]
}

/**
 * Clear all recorded events
 */
export function clearTelemetryEvents(): void {
  events.length = 0
}
