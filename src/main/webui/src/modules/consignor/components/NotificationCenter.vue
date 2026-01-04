<template>
  <div class="notification-center">
    <div class="notification-header">
      <h3 class="text-lg font-semibold text-neutral-900">
        {{ t('consignor.notifications.title') }}
      </h3>
      <span v-if="unreadCount > 0" class="notification-badge">{{ unreadCount }}</span>
    </div>

    <div v-if="loading" class="notification-loading" role="status" aria-live="polite">
      <div class="spinner" />
      <p>{{ t('common.loading') }}</p>
    </div>

    <div v-else-if="notifications.length === 0" class="notification-empty">
      <p class="text-neutral-600">{{ t('consignor.notifications.empty') }}</p>
    </div>

    <div v-else class="notification-list" role="list" aria-live="polite">
      <div
        v-for="notification in notifications"
        :key="notification.id"
        class="notification-item"
        :class="{ unread: !notification.read }"
        role="listitem"
      >
        <div class="notification-icon" :class="`priority-${notification.priority.toLowerCase()}`">
          <span class="text-lg">{{ getNotificationIcon(notification.type) }}</span>
        </div>

        <div class="notification-content">
          <div class="notification-title-row">
            <h4 class="notification-title">{{ notification.title }}</h4>
            <time class="notification-time" :datetime="notification.createdAt">
              {{ formatRelativeTime(notification.createdAt) }}
            </time>
          </div>
          <p class="notification-message">{{ notification.message }}</p>
          <div v-if="notification.actionUrl" class="notification-actions">
            <a :href="notification.actionUrl" class="notification-action-link">
              {{ t('consignor.notifications.viewDetails') }} →
            </a>
          </div>
        </div>

        <button
          v-if="!notification.read"
          class="notification-mark-read"
          @click="handleMarkAsRead(notification.id)"
          :aria-label="t('consignor.notifications.markAsRead')"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M5 13l4 4L19 7"
            />
          </svg>
        </button>
      </div>
    </div>

    <div v-if="hasMore" class="notification-footer">
      <button class="btn-secondary" @click="emit('load-more')">
        {{ t('common.loadMore') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { ConsignorNotification } from '../types'

const props = defineProps<{
  notifications: ConsignorNotification[]
  loading?: boolean
  hasMore?: boolean
}>()

const emit = defineEmits<{
  'load-more': []
  'mark-read': [id: string]
}>()

const { t, formatRelativeTime } = useI18n()

const unreadCount = computed(() => {
  return props.notifications.filter((n) => !n.read).length
})

function getNotificationIcon(type: string): string {
  const icons: Record<string, string> = {
    ITEM_SOLD: '💰',
    PAYOUT_READY: '💵',
    PAYOUT_COMPLETED: '✅',
    ITEM_RETURNED: '↩️',
    ACCOUNT_UPDATE: '📝',
  }
  return icons[type] || '📬'
}

function handleMarkAsRead(id: string) {
  emit('mark-read', id)
}
</script>

<style scoped>
.notification-center {
  @apply bg-white rounded-lg shadow-soft overflow-hidden;
}

.notification-header {
  @apply p-6 border-b border-neutral-200 flex items-center justify-between;
}

.notification-badge {
  @apply inline-flex items-center justify-center w-6 h-6 text-xs font-bold text-white bg-error-600 rounded-full;
}

.notification-loading {
  @apply p-12 flex flex-col items-center justify-center gap-4;
}

.spinner {
  @apply w-8 h-8 border-4 border-primary-200 border-t-primary-600 rounded-full animate-spin;
}

.notification-empty {
  @apply p-12 text-center;
}

.notification-list {
  @apply divide-y divide-neutral-200;
}

.notification-item {
  @apply p-4 flex items-start gap-4 hover:bg-neutral-50 transition-colors;
}

.notification-item.unread {
  @apply bg-primary-50;
}

.notification-icon {
  @apply w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0;
}

.priority-low {
  @apply bg-neutral-100;
}

.priority-normal {
  @apply bg-primary-100;
}

.priority-high {
  @apply bg-warning-100;
}

.priority-urgent {
  @apply bg-error-100;
}

.notification-content {
  @apply flex-1 min-w-0;
}

.notification-title-row {
  @apply flex items-start justify-between gap-2 mb-1;
}

.notification-title {
  @apply text-sm font-semibold text-neutral-900;
}

.notification-time {
  @apply text-xs text-neutral-500 flex-shrink-0;
}

.notification-message {
  @apply text-sm text-neutral-700 mb-2;
}

.notification-actions {
  @apply mt-2;
}

.notification-action-link {
  @apply text-sm font-medium text-primary-600 hover:text-primary-700 transition-colors;
}

.notification-mark-read {
  @apply p-2 text-neutral-400 hover:text-success-600 rounded-md hover:bg-success-50 transition-colors flex-shrink-0;
}

.notification-footer {
  @apply p-6 border-t border-neutral-200 text-center;
}

.btn-secondary {
  @apply px-4 py-2 bg-neutral-100 text-neutral-700 rounded-md font-medium hover:bg-neutral-200 transition-colors;
}
</style>
