import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { emitTelemetryEvent } from '@/telemetry'
import * as notificationsApi from './api'

export type NotificationSeverity = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'

export interface Notification {
  id: string
  title: string
  message: string
  severity: NotificationSeverity
  read: boolean
  createdAt: string
  actionUrl?: string
}

export const useNotificationsStore = defineStore('notifications', () => {
  const notifications = ref<Notification[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const sseConnected = ref(false)
  const sseSource = ref<EventSource | null>(null)
  const severityFilter = ref<NotificationSeverity | 'ALL'>('ALL')
  const searchTerm = ref('')

  const unreadCount = computed(() => notifications.value.filter((notification) => !notification.read).length)

  const filteredNotifications = computed(() => {
    return notifications.value.filter((notification) => {
      if (severityFilter.value !== 'ALL' && notification.severity !== severityFilter.value) {
        return false
      }
      if (!searchTerm.value) return true
      const query = searchTerm.value.toLowerCase()
      return (
        notification.title.toLowerCase().includes(query) ||
        notification.message.toLowerCase().includes(query)
      )
    })
  })

  async function loadNotifications() {
    loading.value = true
    error.value = null
    try {
      notifications.value = await notificationsApi.listNotifications()
      emitTelemetryEvent('view_notifications', { count: notifications.value.length })
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load notifications'
    } finally {
      loading.value = false
    }
  }

  async function markAsRead(notificationId: string) {
    const notification = notifications.value.find((n) => n.id === notificationId)
    if (!notification) return
    notification.read = true
    emitTelemetryEvent('action_mark_notification_read', { notificationId })
  }

  async function markAllAsRead() {
    notifications.value.forEach((n) => (n.read = true))
    emitTelemetryEvent('action_mark_all_notifications_read', { total: notifications.value.length })
  }

  function connectSSE() {
    if (sseSource.value) return
    try {
      sseSource.value = notificationsApi.connectNotificationsSSE(handleSseEvent, () => {
        sseConnected.value = false
        setTimeout(() => connectSSE(), 5000)
      })
      sseConnected.value = true
      emitTelemetryEvent('sse_notifications_connected', {})
    } catch (err) {
      console.error('Failed to connect notifications SSE', err)
      sseConnected.value = false
    }
  }

  function handleSseEvent(notification: Notification) {
    notifications.value = [notification, ...notifications.value].slice(0, 100)
    emitTelemetryEvent('sse_notification_received', { severity: notification.severity })
  }

  function disconnectSSE() {
    if (!sseSource.value) return
    sseSource.value.close()
    sseSource.value = null
    sseConnected.value = false
    emitTelemetryEvent('sse_notifications_disconnected', {})
  }

  function setSeverityFilter(severity: NotificationSeverity | 'ALL') {
    severityFilter.value = severity
  }

  function setSearch(term: string) {
    searchTerm.value = term
  }

  return {
    notifications,
    loading,
    error,
    sseConnected,
    unreadCount,
    filteredNotifications,
    severityFilter,
    searchTerm,
    loadNotifications,
    markAsRead,
    markAllAsRead,
    connectSSE,
    disconnectSSE,
    setSeverityFilter,
    setSearch,
  }
})
