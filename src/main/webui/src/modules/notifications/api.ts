import { apiClient } from '@/api/client'
import type { Notification } from './store'

export async function listNotifications(): Promise<Notification[]> {
  try {
    return await apiClient.get('/admin/notifications')
  } catch (error) {
    console.warn('Notifications API unavailable, falling back to SSE only')
    return []
  }
}

export function connectNotificationsSSE(
  onEvent: (notification: Notification) => void,
  onError?: (err: Error) => void
) {
  const source = new EventSource('/api/v1/admin/notifications/events')

  source.onmessage = (event) => {
    try {
      const notification = JSON.parse(event.data) as Notification
      onEvent(notification)
    } catch (err) {
      onError?.(err as Error)
    }
  }

  source.onerror = () => {
    onError?.(new Error('notifications stream disconnected'))
  }

  return source
}
